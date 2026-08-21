# Context Compression — incremental LLM summary

## What it improves

Когда история диалога с агентом превышает лимит контекста (обычно 128k-200k токенов), обычно есть два плохих варианта: (а) обрубить старое (агент забывает контекст), (б) переполнение → API ошибка. **Incremental summary** — третий вариант: когда история переходит порог, отдельный LLM-вызов сжимает старые сообщения в одну «суммарную» запись, которая заменяет их в контексте. Агент помнит суть без raw-текста.

## When to use

- Долгие рабочие сессии (>50 сообщений в одном диалоге)
- Агент решает многошаговую задачу и не должен «забыть» ранние решения
- Пользователь возвращается через часы/дни к тому же диалогу
- Контекст растёт быстро (много tool_calls, каждый с длинным output'ом)

**Когда НЕ надо:** одноразовые запросы, короткие Q&A (< 20 сообщений) — оверинжиниринг.

## How to integrate

1. Определи порог `MAX_HISTORY_TOKENS` (например 60% от лимита модели: `0.6 * 128_000 = 76_800`)
2. После каждого добавления сообщения посчитай текущий размер истории (в токенах, не в сообщениях)
3. Если превысил — вызови LLM с промптом «суммаризуй эти сообщения в одном абзаце, сохрани ключевые факты и решения»
4. Замени старые сообщения (например все кроме последних 5) на **одно** system-сообщение с суммаризацией
5. Логи сохраняй отдельно (audit / debug), не в active-контексте

## Working example (Kotlin)

```kotlin
class ContextCompressor(
    private val llmClient: DeepSeekClient,
    private val maxTokens: Int = 76_800,
    private val keepRecent: Int = 5
) {
    suspend fun compressIfNeeded(history: MutableList<Message>): Boolean {
        val currentTokens = estimateTokens(history)
        if (currentTokens < maxTokens) return false

        val toCompress = history.dropLast(keepRecent)
        val recent = history.takeLast(keepRecent)

        val summaryPrompt = """
            Ниже история диалога агента с пользователем. Суммаризуй в одном абзаце
            (максимум 300 слов), сохраняя все принятые решения, установленные факты
            и открытые вопросы. Пиши в третьем лице.

            История:
            ${toCompress.joinToString("\n") { "${it.role}: ${it.content}" }}
        """.trimIndent()

        val summary = llmClient.chat(listOf(
            Message(role = "user", content = summaryPrompt)
        ))

        history.clear()
        history.add(Message(role = "system", content = "Сжатая история: $summary"))
        history.addAll(recent)
        return true
    }

    private fun estimateTokens(history: List<Message>): Int =
        history.sumOf { it.content.length / 4 }  // ~4 симв на токен для латиницы
}
```

## Metrics

- **Cost per session** — до/после введения compression (compression сам стоит токенов, но экономит на длинных сессиях)
- **Context refill rate** — сколько раз за сессию срабатывает compression (если каждое сообщение — порог слишком низкий, оверхед)
- **User satisfaction (proxy)** — retention после compression: пользователь жалуется что «агент забыл» → сумма слишком агрессивная, увеличить `keepRecent`
- **Summary quality** — периодически ручная проверка: остались ли важные решения в summary

## Source

- **AI Challenge:** week2/day4 — Context Compression (incremental LLM summary)
- **Артефакты:** https://github.com/AlDtoll/ai-challenge/tree/week2/day4/week2/day4 — `Agent.kt`, `Main.kt`
- **Связано:** week2/day3 (Context Window & overflow handling), week2/day5 (Sticky Facts как альтернатива)
