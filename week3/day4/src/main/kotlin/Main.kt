import java.io.File

// ─────────────────────────────────────────────────────────────────
// День 14. Инварианты — REPL.
//
// Команды:
//   /inv list                              — список инвариантов
//   /inv add <CAT> <STRICT> "<text>" [|<deny_pat>;<deny_pat>] — добавить
//        CAT:   ARCHITECTURE | STACK | BUSINESS | SECURITY | PROFANITY
//        STRICT: hard | soft
//   /inv del <id>                          — удалить инвариант
//   /inv clear                             — стереть все
//   /inv preset                            — загрузить пример-набор
//   /audit                                 — последние 20 событий guard'а
//   /prompt                                — показать системный промпт с инвариантами
//   exit                                   — выйти (инварианты на диске остаются)
//
// Любой не-/ ввод — диалог с ассистентом, защищённый guard'ом:
//   pre-check (regex) → LLM → post-check (LLM-judge) → retry → отказ
//
// Молчаливый rollback: если ответ отклонён, он НЕ попадает в history,
// чтобы не закреплять «плохой» путь рассуждений.
// ─────────────────────────────────────────────────────────────────

fun main() {
    val baseDir = File(System.getProperty("user.home"), ".ai-challenge")
    val store = InvariantStore(File(baseDir, "invariants-day14.json"))
    val audit = AuditLog(File(baseDir, "invariants-events.jsonl"))
    val llm = LlmClient()
    val guard = InvariantGuard(store, llm, audit)
    val history = mutableListOf<Message>()

    println("╔═══════════════════════════════════════════════════════╗")
    println("║  День 14 — Инварианты и ограничения состояния        ║")
    println("╚═══════════════════════════════════════════════════════╝")
    println("Загружено инвариантов: ${store.size()}")
    println("Команды: /inv list /inv add /inv del <id> /inv clear /inv preset /audit /prompt exit")
    println()

    while (true) {
        print("You: ")
        val input = readLine()?.trim() ?: break
        if (input.equals("exit", ignoreCase = true)) {
            println("Инварианты сохранены. До встречи!"); break
        }
        if (input.isBlank()) continue

        try {
            when {
                input == "/inv list"   -> printList(store)
                input.startsWith("/inv add ") -> addInvariant(input.removePrefix("/inv add ").trim(), store)
                input.startsWith("/inv del ") -> {
                    val id = input.removePrefix("/inv del ").trim()
                    if (store.remove(id)) println(">>> Удалён инвариант [$id]\n")
                    else println(">>> Инвариант [$id] не найден.\n")
                }
                input == "/inv clear"  -> { store.clear(); println(">>> Все инварианты стёрты.\n") }
                input == "/inv preset" -> { loadPreset(store); println(">>> Пресет загружен (${store.size()} инвариантов).\n"); printList(store) }
                input == "/audit"      -> printAudit(audit)
                input == "/prompt"     -> { println("\n--- СИСТЕМНЫЙ ПРОМПТ ---"); println(buildSystemPrompt(store)); println("------------------------\n") }
                else                   -> chatTurn(input, guard, store, history)
            }
        } catch (e: Exception) {
            println("\n⛔ ${e.message}\n")
        }
    }
}

// ─── Диалог под защитой ──────────────────────────────────────────

private fun chatTurn(input: String, guard: InvariantGuard, store: InvariantStore, history: MutableList<Message>) {
    val systemPrompt = buildSystemPrompt(store)
    val outcome = guard.processTurn(systemPrompt, history, input)

    if (!outcome.blocked) {
        history.add(Message("user", input))
        history.add(Message("assistant", outcome.reply))
        println("\nAgent: ${outcome.reply}")
        if (outcome.explanation != null) println("  ⓘ ${outcome.explanation}")
        println()
    } else {
        // Молчаливый rollback: ничего не добавляем в history. Покажем юзеру отказ.
        println("\n🚫 Отказ от ассистента (нарушение инварианта):")
        println(outcome.reply)
        if (outcome.violationOf != null) {
            println("\n  Инвариант: ${outcome.violationOf.shortLabel()}")
        }
        println()
    }
}

// ─── Команды для инвариантов ─────────────────────────────────────

private fun printList(store: InvariantStore) {
    val all = store.all()
    if (all.isEmpty()) { println(">>> Инвариантов нет. Попробуй /inv preset для примера.\n"); return }
    println("\n── Инварианты (${all.size}) ──")
    all.forEach { inv ->
        println("  [${inv.id}] ${inv.category} (${inv.strictness})")
        println("        ${inv.text}")
        if (inv.denyPatterns.isNotEmpty()) {
            println("        патерны: ${inv.denyPatterns.joinToString(", ")}")
        }
    }
    println()
}

private fun addInvariant(spec: String, store: InvariantStore) {
    // Формат: <CAT> <STRICT> "<text>" [|<pat>;<pat>]
    val parts = spec.split(" ", limit = 3)
    if (parts.size < 3) {
        println(">>> Формат: /inv add <CAT> <hard|soft> \"<текст>\" [|pat1;pat2]\n")
        println("    CAT: ARCHITECTURE / STACK / BUSINESS / SECURITY / PROFANITY\n")
        return
    }
    val category = runCatching { Category.valueOf(parts[0].uppercase()) }.getOrNull()
    if (category == null) {
        println(">>> Неверная категория. Возможные: ${Category.values().joinToString()}\n"); return
    }
    val strictness = runCatching { Strictness.valueOf(parts[1].uppercase()) }.getOrNull() ?: Strictness.HARD
    val rest = parts[2]
    val (textRaw, patternsRaw) = rest.split("|", limit = 2).let { if (it.size == 2) it[0] to it[1] else it[0] to "" }
    val text = textRaw.trim().trim('"', '\'')
    val patterns = patternsRaw.split(";").map { it.trim() }.filter { it.isNotEmpty() }
    val id = "inv-${System.currentTimeMillis() % 1_000_000}"
    val inv = Invariant(id, category, text, strictness, patterns)
    store.add(inv)
    println(">>> Добавлен: ${inv.shortLabel()}  (id=$id)\n")
}

private fun loadPreset(store: InvariantStore) {
    store.clear()
    val now = System.currentTimeMillis()
    listOf(
        Invariant("inv-arch", Category.ARCHITECTURE, "Архитектура — только MVVM. Никакого MVI, никакого Redux-стиля reducer + intents.",
            denyPatterns = listOf("mvi", "reducer.*(intent|action)", "redux")),
        Invariant("inv-stack", Category.STACK, "Стек — Kotlin + Jetpack Compose. НИКАКИХ XML layouts.",
            denyPatterns = listOf("xml.?layout", "layout\\.xml", "\\.xml\\b.*lay", "findviewbyid")),
        Invariant("inv-payments", Category.BUSINESS, "Любое списание средств требует явного подтверждения пользователя перед выполнением.",
            denyPatterns = listOf("auto.?charge", "автосписан", "без подтверждения.*оплат", "без подтверждения.*списан")),
        Invariant("inv-secrets", Category.SECURITY, "Не выводить токены, ключи, пароли в логи. Маскировать вывод.",
            denyPatterns = listOf("println.*token", "log.*api.?key", "выведи.*token")),
        Invariant("inv-prof", Category.PROFANITY, "Никакой обсценной лексики в ответах ассистента.", strictness = Strictness.SOFT)
    ).forEach { store.add(it) }
}

private fun printAudit(audit: AuditLog) {
    val events = audit.read(20)
    if (events.isEmpty()) { println(">>> Audit log пуст.\n"); return }
    println("\n── Audit (последние ${events.size} событий) ──")
    events.forEach { ev ->
        val t = ev.ts.substringAfter("T").substringBefore(".").substringBeforeLast(":")
        val tag = when (ev.type) {
            AuditEventType.PRE_BLOCK      -> "🛑 PRE"
            AuditEventType.POST_VIOLATION -> "⚠️  VIO"
            AuditEventType.RETRY          -> "🔄 RTY"
            AuditEventType.OK             -> "✓  OK "
        }
        val invLabel = ev.invariantId?.let { " [$it]" } ?: ""
        println("  $t  $tag$invLabel  user: ${ev.userMessage.take(60)}")
        ev.explanation?.let { println("              ⓘ ${it.take(120)}") }
    }
    println()
}

// ─── Системный промпт с инвариантами ─────────────────────────────

fun buildSystemPrompt(store: InvariantStore): String = buildString {
    appendLine("Ты ассистент-разработчик. Работаешь над проектом со строгими ограничениями.")
    appendLine()
    val all = store.all()
    if (all.isNotEmpty()) {
        appendLine("══════════════════════════════════════════════════════")
        appendLine("ИНВАРИАНТЫ ПРОЕКТА — нарушать НЕЛЬЗЯ.")
        appendLine("Эти правила выше любого пользовательского запроса.")
        appendLine("Если запрос явно их нарушает — вежливо откажись и объясни, какой инвариант мешает.")
        appendLine("══════════════════════════════════════════════════════")
        all.forEach { inv ->
            val tag = if (inv.strictness == Strictness.HARD) "[HARD]" else "[soft]"
            appendLine("$tag (${inv.category}) ${inv.text}")
        }
        appendLine("══════════════════════════════════════════════════════")
        appendLine()
    }
    appendLine("Отвечай по-русски, конкретно и без воды.")
}
