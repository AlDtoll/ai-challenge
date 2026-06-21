import com.google.gson.Gson
import com.google.gson.JsonObject

// Двухслойный guard:
//   1) preCheck()  — детерминистический matcher по regex-патернам ИЗ инварианта.
//                    Дёшево (никаких API-вызовов), ловит явные нарушения.
//   2) postCheck() — LLM-judge (отдельный мини-вызов). Анализирует ответ агента
//                    и говорит: «нарушает ли он какой-то инвариант? который?»
//                    Семантическая проверка — ловит то, что pre-check пропустил.
//   3) retry()     — если LLM нарушил, дать ему фидбек и попросить переделать (1 попытка).
//
// Молчаливый rollback: если ответ оказался плохим, мы НЕ добавляем его в историю —
// чтобы агент в следующем шаге не считал нарушение «уже сказанным».

data class PreCheckResult(val violation: Invariant?, val rationale: String? = null)
data class PostCheckResult(val violated: Boolean, val invariantId: String?, val rationale: String?)
data class GuardOutcome(val reply: String, val blocked: Boolean, val violationOf: Invariant? = null, val explanation: String? = null)

class InvariantGuard(
    private val store: InvariantStore,
    private val llm: LlmClient,
    private val audit: AuditLog
) {
    private val gson = Gson()

    // ── Pre-check: regex-матчинг запросов юзера по denyPatterns каждого инварианта ──
    fun preCheck(userMessage: String): PreCheckResult {
        for (inv in store.all()) {
            if (inv.matchesAnyPattern(userMessage)) {
                return PreCheckResult(inv, "Pattern match: запрос содержит триггер из инварианта.")
            }
        }
        return PreCheckResult(null)
    }

    // ── Post-check: LLM-judge оценивает ответ ──
    fun postCheck(userMessage: String, assistantReply: String): PostCheckResult {
        val invariants = store.all().filter { it.strictness == Strictness.HARD }
        if (invariants.isEmpty()) return PostCheckResult(false, null, null)

        val invList = invariants.joinToString("\n") { "[${it.id}] (${it.category}) ${it.text}" }
        val judgePrompt = """
            Ты — независимый аудитор. Проверь, нарушает ли ОТВЕТ ассистента хотя бы один из инвариантов.
            Инварианты:
            $invList

            Запрос пользователя: ${'"'}${userMessage.take(500)}${'"'}
            Ответ ассистента: ${'"'}${assistantReply.take(2000)}${'"'}

            Верни СТРОГО JSON одной строкой:
            {"violated": true|false, "invariantId": "<id>"|null, "rationale": "<краткая причина>"}
            Если нарушений нет — "violated": false. Если несколько — назови первое.
            Только JSON, без markdown, без пояснений вне JSON.
        """.trimIndent()

        return try {
            val (raw, _) = llm.chat(judgePrompt, emptyList(), maxTokens = 200)
            val clean = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val obj = gson.fromJson(clean, JsonObject::class.java)
            PostCheckResult(
                violated = obj.get("violated")?.asBoolean ?: false,
                invariantId = obj.get("invariantId")?.takeIf { !it.isJsonNull }?.asString,
                rationale = obj.get("rationale")?.takeIf { !it.isJsonNull }?.asString
            )
        } catch (_: Exception) {
            PostCheckResult(false, null, null)
        }
    }

    // ── Retry: переделать ответ с явным фидбеком ──
    fun retry(systemPrompt: String, history: List<Message>, violation: Invariant, rationale: String): String {
        val retryPrompt = systemPrompt + "\n\n!!! ПРЕДЫДУЩИЙ ОТВЕТ БЫЛ ОТКЛОНЁН — он нарушает инвариант:\n" +
            "  • ${violation.shortLabel()}\n" +
            "  • Причина: $rationale\n" +
            "Переделай ответ так, чтобы не нарушать этот инвариант. Либо вежливо откажись с объяснением."
        val (reply, _) = llm.chat(retryPrompt, history)
        return reply
    }

    // ── Главный метод: один turn чата под защитой инвариантов ──
    fun processTurn(systemPrompt: String, history: List<Message>, userMessage: String): GuardOutcome {
        // 1) Pre-check
        val pre = preCheck(userMessage)
        if (pre.violation != null) {
            val explanation = "Запрос напрямую нарушает инвариант ${pre.violation.shortLabel()}. ${pre.rationale}"
            audit.append(audit.event(AuditEventType.PRE_BLOCK, userMessage, pre.violation, explanation = explanation))
            return GuardOutcome(
                reply = "Не могу выполнить запрос: он нарушает инвариант проекта.\n  ${pre.violation.shortLabel()}\n\nПереформулируй или сними инвариант через /inv del ${pre.violation.id}.",
                blocked = true,
                violationOf = pre.violation,
                explanation = explanation
            )
        }

        // 2) Обычный вызов LLM
        val (initialReply, _) = llm.chat(systemPrompt, history + Message("user", userMessage))

        // 3) Post-check
        val post = postCheck(userMessage, initialReply)
        if (!post.violated) {
            audit.append(audit.event(AuditEventType.OK, userMessage, reply = initialReply))
            return GuardOutcome(initialReply, blocked = false)
        }

        val violated = post.invariantId?.let { store.byId(it) } ?: store.all().firstOrNull()
        audit.append(audit.event(AuditEventType.POST_VIOLATION, userMessage, violated, initialReply, post.rationale))

        if (violated == null) return GuardOutcome(initialReply, blocked = false)

        // 4) Retry — попросим LLM переделать
        audit.append(audit.event(AuditEventType.RETRY, userMessage, violated))
        val retried = retry(systemPrompt, history, violated, post.rationale ?: "(без объяснений)")
        // Проверим ещё раз — если опять нарушил, выдаём отказ
        val recheck = postCheck(userMessage, retried)
        if (recheck.violated) {
            val explanation = "Дважды нарушен инвариант ${violated.shortLabel()}. Причина: ${recheck.rationale ?: post.rationale}"
            audit.append(audit.event(AuditEventType.POST_VIOLATION, userMessage, violated, retried, explanation))
            return GuardOutcome(
                reply = "Отказ: ${violated.shortLabel()}\n${recheck.rationale ?: post.rationale ?: ""}",
                blocked = true,
                violationOf = violated,
                explanation = explanation
            )
        }

        audit.append(audit.event(AuditEventType.OK, userMessage, reply = retried, explanation = "Принят после retry."))
        return GuardOutcome(retried, blocked = false, violationOf = violated, explanation = "Переделано после нарушения.")
    }
}
