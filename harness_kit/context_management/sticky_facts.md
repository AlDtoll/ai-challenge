# Sticky Facts — ключевые факты вне скользящего окна

## What it improves

Sliding window выбрасывает старые сообщения целиком. Sticky facts решают главную проблему: пользователь сказал «я вегетарианец» в 3-м сообщении, но агент «забыл» на 50-м. Ключевые факты (предпочтения, профиль, ограничения, установленные договорённости) хранятся **отдельно** от rolling-истории и никогда не вытесняются. В system prompt они инъектятся автоматически при каждом вызове.

## When to use

- Персональный ассистент: пользователь упомянул диету, город, профессию, стек — нужно помнить
- Бизнес-бот с персонализацией: тариф пользователя, активные фичи, история заказов
- Агент-коуч или наставник: прогресс, уровень, согласованный стиль коммуникации
- Любой сценарий где есть «о пользователе» + «о текущей задаче» которые переживают рестарт

**Когда НЕ надо:** одноразовые анонимные запросы, агент где персонализация не нужна — лишний слой сложности без выгоды.

## How to integrate

1. Создай отдельную структуру `StickyFacts` (Map или data class) — хранится в памяти и персистируется в JSON.
2. Определи критерии «что sticky»: факты с явным тегом (`/remember ...`), или автоматически через LLM-экстрактор.
3. При каждом LLM-вызове формируй system prompt как: `baseSystemPrompt + "\n\n## Факты о пользователе:\n" + stickyFacts.format()`.
4. Sticky facts — не в rolling history, они идут только в system prompt.
5. Предусмотри команду `/forget <key>` для удаления и `/facts` для просмотра.

## Working example (Kotlin)

```kotlin
data class StickyFacts(
    val facts: MutableMap<String, String> = mutableMapOf()
) {
    fun add(key: String, value: String) {
        facts[key] = value
    }

    fun remove(key: String) = facts.remove(key)

    fun format(): String = if (facts.isEmpty()) "нет"
        else facts.entries.joinToString("\n") { (k, v) -> "- $k: $v" }

    fun toJson(): String = facts.entries
        .joinToString(",", "{", "}") { (k, v) -> "\"$k\":\"$v\"" }
}

class StickyFactsAgent(
    private val llmClient: DeepSeekClient,
    private val baseSystemPrompt: String,
    private val windowSize: Int = 10
) {
    val sticky = StickyFacts()
    private val history = mutableListOf<Message>()

    suspend fun chat(userMessage: String): String {
        // Проверяем команду /remember key: value
        if (userMessage.startsWith("/remember ")) {
            val parts = userMessage.removePrefix("/remember ").split(":", limit = 2)
            if (parts.size == 2) {
                sticky.add(parts[0].trim(), parts[1].trim())
                return "Запомнил: ${parts[0].trim()} = ${parts[1].trim()}"
            }
        }

        history.add(Message(role = "user", content = userMessage))
        val maxMessages = windowSize * 2
        if (history.size > maxMessages) {
            history.subList(0, history.size - maxMessages).clear()
        }

        val systemWithFacts = buildString {
            append(baseSystemPrompt)
            if (sticky.facts.isNotEmpty()) {
                append("\n\n## Известные факты о пользователе:\n")
                append(sticky.format())
            }
        }

        val messages = buildList {
            add(Message(role = "system", content = systemWithFacts))
            addAll(history)
        }

        val response = llmClient.chat(messages)
        history.add(Message(role = "assistant", content = response))
        return response
    }
}
```

## Metrics

- **Fact recall rate** — доля ответов где агент корректно применил sticky факт (проверять выборочно или через LLM-судью): целевой показатель > 95%
- **Facts count per user** — среднее число sticky facts; если > 20 — возможно раздувание, стоит ввести TTL или «важность»
- **«Ты забыл» жалобы после введения sticky facts** — должны упасть к нулю для предпочтений/профиля
- **System prompt overhead** — токены, которые sticky добавляет к каждому вызову; при > 500 токенов думать о компактном форматировании

## Source

- **AI Challenge:** week2/day5 — Sliding Window + Sticky Facts + Branching
- **Артефакты:** https://github.com/AlDtoll/ai-challenge/tree/week2/day5/week2/day5 — `Agent.kt`, `Main.kt`
- **Связано:** [`sliding_window.md`](sliding_window.md) — стратегия rolling-истории которую sticky дополняет; [`../memory_layers/profile_extractor.md`](../memory_layers/profile_extractor.md) — автоматическое извлечение фактов через LLM без `/remember`
