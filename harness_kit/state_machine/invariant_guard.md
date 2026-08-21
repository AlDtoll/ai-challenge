# Invariant Guard — двухслойная защита ответов с silent rollback

## What it improves

LLM может нарушить бизнес-правила даже с хорошим system prompt: предложить конкурента, использовать запрещённую архитектуру, ответить нецензурно. Invariant guard перехватывает ответ до попадания в историю и проверяет его в два слоя: быстрый regex-precheck и медленный LLM-судья. При нарушении применяется **silent rollback** — плохой ответ не попадает в историю диалога, не «отравляет» паттерны, агент генерирует другой ответ. Пользователь видит корректный ответ или вежливый отказ.

## When to use

- Продуктовый ассистент с запретами конкурентов, токсичного контента, конфиденциальных данных
- Корпоративный агент с compliance-требованиями (не давать юридических/медицинских советов)
- Security-чувствительные боты: нельзя выдавать IP, токены, внутренние пути
- Любой агент где нарушение одного ответа может испортить всю сессию через историю

**Когда НЕ надо:** внутренние инструменты разработчика где нет compliance-требований, одноразовые скрипты — двойная LLM-проверка дорога.

## How to integrate

1. Определи инварианты с типом (hard = блокировать+retry, soft = предупредить) и категорией (BUSINESS, SECURITY, PROFANITY, ARCHITECTURE).
2. Создай pre-check: быстрый regex по запрещённым словам/паттернам — фильтрует 80% нарушений без LLM-вызова.
3. Создай LLM-judge: промпт «нарушает ли этот ответ правило X? Верни JSON {violated: bool, reason: string}».
4. При `violated=true` для hard-инварианта — не добавляй ответ в историю, вызови LLM ещё раз с доп. инструкцией.
5. Логируй все нарушения в audit log.

## Working example (Kotlin)

```kotlin
enum class InvariantType { HARD, SOFT }
enum class InvariantCategory { BUSINESS, SECURITY, PROFANITY, ARCHITECTURE, STACK }

data class Invariant(
    val id: String,
    val description: String,
    val type: InvariantType,
    val category: InvariantCategory,
    val regexPatterns: List<Regex> = emptyList(),
    val llmCheckPrompt: String = ""
)

data class GuardResult(val violated: Boolean, val invariantId: String? = null, val reason: String = "")

class InvariantGuard(
    private val llmClient: DeepSeekClient,
    private val invariants: List<Invariant>
) {
    suspend fun check(response: String): GuardResult {
        // Слой 1: быстрый regex
        for (inv in invariants) {
            if (inv.regexPatterns.any { it.containsMatchIn(response.lowercase()) }) {
                return GuardResult(true, inv.id, "regex match: ${inv.category}")
            }
        }

        // Слой 2: LLM-судья (только если regex не поймал)
        for (inv in invariants.filter { it.llmCheckPrompt.isNotEmpty() }) {
            val judgePrompt = """
                ${inv.llmCheckPrompt}
                
                Ответ агента для проверки:
                ---
                $response
                ---
                
                Верни JSON: {"violated": true/false, "reason": "..."}
            """.trimIndent()

            val raw = llmClient.chat(listOf(Message("user", judgePrompt)))
            val violated = raw.contains("\"violated\": true") || raw.contains("\"violated\":true")
            if (violated) {
                val reason = Regex("\"reason\":\\s*\"([^\"]+)\"").find(raw)?.groupValues?.get(1) ?: "LLM judge"
                return GuardResult(true, inv.id, reason)
            }
        }

        return GuardResult(false)
    }

    // Пример инварианта — упоминание конкурентов
    companion object {
        fun buildDefaultInvariants() = listOf(
            Invariant(
                id = "no_competitors",
                description = "Не упоминать конкурирующие продукты",
                type = InvariantType.HARD,
                category = InvariantCategory.BUSINESS,
                regexPatterns = listOf(Regex("competitor_name_1|competitor_name_2")),
                llmCheckPrompt = "Нарушает ли ответ правило: не рекомендовать продукты конкурентов?"
            )
        )
    }
}

// В основном цикле агента (silent rollback):
// val response = llmClient.chat(messages)
// val guard = InvariantGuard(llmClient, invariants)
// val result = guard.check(response)
// if (result.violated) {
//     // НЕ добавляем в историю
//     val retryMessages = messages + Message("user", "Пожалуйста, ответь без нарушения правил: ${result.reason}")
//     return llmClient.chat(retryMessages)
// }
// history.add(Message("assistant", response))  // только если прошёл guard
```

## Metrics

- **Guard violation rate** — % ответов нарушающих хотя бы один инвариант; целевой < 1% для well-tuned system prompt
- **Pre-check vs LLM-judge ratio** — сколько нарушений поймано regex vs LLM; если LLM ловит больше 20% — добавить regex-паттерны
- **Retry success rate** — доля повторных вызовов после violation которые прошли guard; если < 80% — инвариант слишком жёсткий или system prompt неясен
- **Silent rollback count per day** — число «скрытых» откатов; если высокое — основной prompt нуждается в улучшении

## Source

- **AI Challenge:** week3/day4 — Инварианты с двухслойным guard'ом
- **Артефакты:** https://github.com/AlDtoll/ai-challenge/tree/week3/day4/week3/day4 — `InvariantGuard.kt`, `Invariant.kt`, `InvariantStore.kt`, `AuditLog.kt`
- **Связано:** [`task_state_machine.md`](task_state_machine.md) — guard можно применять на каждом state-переходе; [`gate_transitions.md`](gate_transitions.md) — gates как предусловия перехода (другой уровень контроля); [`../security/prompt_injection_defense.md`](../security/prompt_injection_defense.md) — security-специфичный guard для инъекций
