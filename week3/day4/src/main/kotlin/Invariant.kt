// ─────────────────────────────────────────────────────────────────
// День 14. Инварианты — правила, которые ассистент не имеет права нарушать.
//
// Каждый инвариант — отдельный документ с категорией, текстом, степенью
// строгости и списком ключевых слов для быстрого guard'а БЕЗ LLM-вызова.
// ─────────────────────────────────────────────────────────────────

enum class Category {
    ARCHITECTURE,   // "только MVVM", "не использовать MVI"
    STACK,          // "Kotlin Compose, не XML"
    BUSINESS,       // "платежи всегда требуют подтверждения"
    SECURITY,       // "не выводить токены в логи"
    PROFANITY       // "никаких матов в ответах"
}

enum class Strictness {
    HARD,           // нарушать категорически нельзя — отказ
    SOFT            // предупреждение, но можно с пометкой
}

data class Invariant(
    val id: String,
    val category: Category,
    val text: String,
    val strictness: Strictness = Strictness.HARD,
    // Regex-патерны (case-insensitive) для дешёвой pre-проверки запроса юзера
    // ДО вызова LLM. Если хоть один матчится — можно отказать сразу.
    val denyPatterns: List<String> = emptyList()
) {
    fun matchesAnyPattern(text: String): Boolean {
        if (denyPatterns.isEmpty()) return false
        val lower = text.lowercase()
        return denyPatterns.any { p ->
            runCatching { Regex(p, RegexOption.IGNORE_CASE).containsMatchIn(lower) }.getOrDefault(false)
        }
    }

    fun shortLabel(): String = "[$category${if (strictness == Strictness.SOFT) "·soft" else ""}] $text"
}
