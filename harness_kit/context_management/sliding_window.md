# Sliding Window — скользящее окно истории

## What it improves

Самый простой способ удержать историю в лимите контекста: хранить только последние N сообщений, остальные отбрасывать. В отличие от compression, не тратит дополнительные токены на суммаризацию — просто обрезает хвост. Для FAQ-ботов и коротких сессий (поддержка, quick Q&A) этого достаточно, и стоимость вызова остаётся предсказуемой.

## When to use

- FAQ-бот или служба поддержки: каждый тикет независим, старый контекст бесполезен
- Агент с жёстким бюджетом токенов (cost-sensitive production)
- Пользователи редко ссылаются на сообщения старше 5-10 реплик
- Прототипирование — нужен рабочий агент без сложной логики памяти

**Когда НЕ надо:** многошаговые задачи (агент должен помнить решение из шага 1 на шаге 12), долгосрочные сессии с профилем пользователя — там нужен sticky facts или compression.

## How to integrate

1. Определи `WINDOW_SIZE` — количество **пар** сообщений (user+assistant) которые сохранять. Рекомендуемый старт: 10-15 пар.
2. После каждого ответа агента добавляй оба сообщения в `history`.
3. Перед каждым новым LLM-вызовом обрезай `history` до `WINDOW_SIZE * 2` последних элементов.
4. System prompt (профиль, правила, роль) — **никогда** не включай в sliding window; он всегда идёт отдельно первым элементом.
5. Опционально: логируй «выброшенные» сообщения в отдельный cold-storage файл для аудита.

## Working example (Kotlin)

```kotlin
class SlidingWindowAgent(
    private val llmClient: DeepSeekClient,
    private val systemPrompt: String,
    private val windowSize: Int = 10  // пар сообщений
) {
    private val history = mutableListOf<Message>()

    suspend fun chat(userMessage: String): String {
        history.add(Message(role = "user", content = userMessage))

        // Обрезаем: оставляем windowSize пар (= windowSize * 2 сообщений)
        val maxMessages = windowSize * 2
        if (history.size > maxMessages) {
            val removed = history.size - maxMessages
            history.subList(0, removed).clear()
        }

        val contextMessages = buildList {
            add(Message(role = "system", content = systemPrompt))
            addAll(history)
        }

        val response = llmClient.chat(contextMessages)
        history.add(Message(role = "assistant", content = response))
        return response
    }

    fun historySize(): Int = history.size
    fun clearHistory() = history.clear()
}

// Использование с Telegram:
// При /start → agent.clearHistory() — новая сессия без старого контекста
// При каждом сообщении → agent.chat(text)
```

## Metrics

- **Avg context size per call** (tokens) — должен оставаться стабильным, не расти со временем
- **% сессий превысивших window** — если > 20%, возможно window слишком мал или задачи длинные → рассмотреть compression
- **«Агент забыл» жалобы** — если пользователь жалуется «ты только что говорил...» → увеличить WINDOW_SIZE или добавить sticky facts
- **Cost per session** — с sliding window предсказуем; сравни с compression, выбери что дешевле для твоих сессий

## Source

- **AI Challenge:** week2/day5 — Sliding Window + Sticky Facts + Branching
- **Артефакты:** https://github.com/AlDtoll/ai-challenge/tree/week2/day5/week2/day5 — `Agent.kt`, `Main.kt`
- **Связано:** [`context_compression.md`](context_compression.md) — альтернатива с сохранением старого контекста через суммаризацию; [`sticky_facts.md`](sticky_facts.md) — дополнение для ключевых фактов
