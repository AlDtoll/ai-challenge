# Profile Extractor — автоматическое извлечение профиля из диалога

## What it improves

Явный онбординг («назови свой стек») раздражает пользователей. Profile extractor работает пассивно: после каждого сообщения LLM анализирует диалог и извлекает факты о пользователе в структурированный JSON. Агент узнаёт имя, уровень, любимый стек из естественного разговора. System prompt строится динамически из профиля — чем больше узнал, тем точнее ответы. Implicit learning без явных вопросов.

## When to use

- Персональный ассистент или коуч: нужна персонализация, но онбординг неуместен
- Агент где пользователи органично упоминают контекст («я пишу на Kotlin под Android...»)
- Продукт где NPS важен — явный онбординг снижает первое впечатление
- Долгосрочные пользователи которые ценят что «бот помнит»

**Когда НЕ надо:** анонимные одноразовые запросы, агент без персонализации — LLM-экстракция стоит дополнительных токенов.

## How to integrate

1. Создай `ProfileExtractor` — один LLM-вызов после каждого user-сообщения с промптом «извлеки из диалога: имя, уровень, стек — верни JSON».
2. Запускай экстрактор **после** основного ответа агента (асинхронно или в следующем туре), чтобы не блокировать ответ.
3. Merge результат с существующим профилем: обновляй только поля которые LLM нашёл (не перетирай уже известное).
4. Строй `SystemPromptBuilder` — принимает профиль, возвращает отформатированный system prompt с секцией «о пользователе».
5. Добавь `/profile` для просмотра и `/switch junior|senior` для ручной корректировки.

## Working example (Kotlin)

```kotlin
@Serializable
data class UserProfile(
    val name: String? = null,
    val level: String? = null,  // junior / intermediate / senior
    val stack: List<String> = emptyList(),
    val interests: List<String> = emptyList()
)

class ProfileExtractor(private val llmClient: DeepSeekClient) {
    suspend fun extract(recentMessages: List<Message>): UserProfile? {
        if (recentMessages.isEmpty()) return null

        val prompt = """
            Проанализируй диалог и извлеки информацию о пользователе.
            Верни JSON строго в формате:
            {"name": "...", "level": "junior|intermediate|senior", "stack": [...], "interests": [...]}
            Поля которые не упоминались — null или [].
            Диалог:
            ${recentMessages.takeLast(6).joinToString("\n") { "${it.role}: ${it.content}" }}
        """.trimIndent()

        return try {
            val raw = llmClient.chat(listOf(Message("user", prompt)))
            Json { ignoreUnknownKeys = true }.decodeFromString<UserProfile>(
                raw.substringAfter("{").let { "{$it" }.substringBefore("}").let { "$it}" }
            )
        } catch (e: Exception) {
            null  // Тихая ошибка — профиль не обновится в этот раз
        }
    }

    fun merge(existing: UserProfile, extracted: UserProfile): UserProfile = UserProfile(
        name = extracted.name ?: existing.name,
        level = extracted.level ?: existing.level,
        stack = (existing.stack + extracted.stack).distinct(),
        interests = (existing.interests + extracted.interests).distinct()
    )
}

object SystemPromptBuilder {
    fun build(base: String, profile: UserProfile): String = buildString {
        append(base)
        if (profile.name != null || profile.stack.isNotEmpty()) {
            append("\n\n## Профиль пользователя\n")
            profile.name?.let { append("Имя: $it\n") }
            profile.level?.let { append("Уровень: $it\n") }
            if (profile.stack.isNotEmpty()) append("Стек: ${profile.stack.joinToString(", ")}\n")
        }
    }
}
```

## Metrics

- **Profile fill rate** — доля пользователей у которых заполнено ≥ 2 поля профиля после 5 сообщений; целевой > 60%
- **Extraction accuracy** — выборочная проверка: LLM верно определил уровень / стек? (ручной аудит 20 сессий)
- **False positive rate** — профиль обновился неверно (например «я читал про Kotlin» → в stack); мониторить жалобы «агент считает что я X, но это не так»
- **Prompt overhead** — дополнительные токены на экстракцию за сессию; при > 5% общего бюджета — запускать не каждый шаг, а каждые N сообщений

## Source

- **AI Challenge:** week3/day2 — Персонализация через автоматическое извлечение профиля
- **Артефакты:** https://github.com/AlDtoll/ai-challenge/tree/week3/day2/week3/day2 — `ProfileExtractor.kt`, `SystemPromptBuilder.kt`, `UserProfile.kt`
- **Связано:** [`three_layer_memory.md`](three_layer_memory.md) — L3 (LongTermMemory) — место где хранить извлечённый профиль; [`../context_management/sticky_facts.md`](../context_management/sticky_facts.md) — ручное добавление фактов как альтернатива; [`../state_machine/task_state_machine.md`](../state_machine/task_state_machine.md) — более строгое управление состоянием на уровне задачи
