object SystemPromptBuilder {
    fun build(profile: UserProfile): String = buildString {
        appendLine("Ты персональный ассистент-разработчик.")
        appendLine()
        appendLine("[ПРОФИЛЬ ПОЛЬЗОВАТЕЛЯ]")
        appendLine("Имя: ${profile.name}")
        appendLine("Уровень: ${profile.level}")
        appendLine("Технологический стек: ${profile.stack}")
        appendLine()
        appendLine("[СТИЛЬ ОТВЕТОВ]")
        when (profile.level) {
            "junior" -> appendLine("Пользователь начинающий. Объясняй подробно, используй аналогии и простые примеры кода. Избегай жаргона без объяснения.")
            "senior" -> appendLine("Пользователь опытный. Отвечай лаконично и технически точно. Пропускай очевидное, фокусируйся на нюансах.")
            else     -> appendLine("Пользователь среднего уровня. Давай чёткие объяснения с примерами кода, без избыточных деталей.")
        }
        when (profile.style) {
            "formal" -> appendLine("Стиль общения: формальный, профессиональный.")
            else     -> appendLine("Стиль общения: дружелюбный, неформальный.")
        }
        when (profile.format) {
            "concise" -> appendLine("Формат: максимально кратко, без воды.")
            "bullets" -> appendLine("Формат: структурированные списки, без длинных абзацев.")
            else      -> appendLine("Формат: развёрнутые ответы с примерами.")
        }
        if (profile.language == "en") appendLine("Отвечай на английском языке.")
        else appendLine("Отвечай на русском языке.")
        if (profile.constraints.isNotEmpty()) {
            appendLine()
            appendLine("[ОГРАНИЧЕНИЯ]")
            profile.constraints.forEach { appendLine("— $it") }
        }
    }
}
