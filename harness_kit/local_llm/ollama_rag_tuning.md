# Ollama RAG Tuning — критические параметры для качественного RAG

## What it improves

Ollama по умолчанию запускается с `num_ctx=2048` — 2048 токенов контекстного окна. Для RAG с 3 чанками по ~600 символов каждый это **критический silent bug**: LLM физически не видит весь контекст и отвечает на вопросы «из головы» игнорируя документы. Правильный тюнинг: `num_ctx=8192`, `temperature=0`, правильные `stop`-последовательности, `repeat_penalty`, prompt-шаблон под RAG. После тюнинга качество RAG растёт на 20-40% без замены модели.

## When to use

- Любое использование Ollama для RAG — тюнинг обязателен, дефолты не подходят
- Качество ответов низкое хотя retrieval правильный (silent bug с num_ctx)
- Нужны детерминированные ответы (temperature=0 для factual tasks)
- Модель «галлюцинирует» или «болтает» в RAG-контексте (repeat_penalty, stop-sequences)

**Когда НЕ надо:** creative generation где variability нужна — там `temperature=0.7`, `repeat_penalty=1.0`; очень большой контекст (> 16K токенов) — Ollama на CPU будет медленной.

## How to integrate

1. Передавай параметры в теле запроса через поле `options` при каждом `/api/chat` вызове.
2. Установи `num_ctx=8192` (минимум для 3 чанков по 600 симв + системный промпт + ответ).
3. Установи `temperature=0` для factual RAG (детерминированный ответ), `num_predict=256` чтобы не болтало.
4. Добавь `stop=["\n\nHuman:", "\n\nUser:", "###"]` — предотвращает имитацию диалога.
5. Оформи prompt-шаблон: контекст → разделитель → вопрос → инструкция «только из контекста».

## Working example (Kotlin)

```kotlin
fun buildRagRequest(
    model: String,
    systemPrompt: String,
    userMessage: String,
    chunks: List<Chunk>
): String {
    val context = chunks.joinToString("\n---\n") { c ->
        "[${c.heading.ifEmpty { "Документ" }}]\n${c.content}"
    }

    val ragSystemPrompt = """
        $systemPrompt
        
        Отвечай ТОЛЬКО на основе предоставленного контекста.
        Если ответа нет в контексте — скажи "Информации нет в базе знаний".
        Не додумывай и не используй знания не из контекста.
    """.trimIndent()

    val fullMessage = """
        Контекст:
        $context
        
        Вопрос: $userMessage
    """.trimIndent()

    return buildJsonObject {
        put("model", model)
        put("messages", buildJsonArray {
            add(buildJsonObject {
                put("role", "system")
                put("content", ragSystemPrompt)
            })
            add(buildJsonObject {
                put("role", "user")
                put("content", fullMessage)
            })
        })
        put("stream", false)
        put("options", buildJsonObject {
            // Критический параметр: дефолт 2048 режет контекст!
            put("num_ctx", 8192)
            // Factual RAG: детерминированный ответ
            put("temperature", 0.0)
            // Ограничиваем длину ответа
            put("num_predict", 256)
            // Нормализация: уменьшает повторы
            put("top_p", 0.9)
            // Штраф за повторение
            put("repeat_penalty", 1.1)
            // Стоп-последовательности: модель не имитирует следующего пользователя
            put("stop", buildJsonArray {
                add("\n\nHuman:")
                add("\n\nUser:")
                add("\n\n###")
                add("<|endoftext|>")
            })
        })
    }.toString()
}

// Пример сравнения до/после тюнинга:
// До: num_ctx=2048, temp=0.7 → модель игнорирует контекст, выдаёт "Сидней" для "столица Австралии"
// После: num_ctx=8192, temp=0.0 → "Канберра (см. раздел 'Австралия' в базе знаний)"
//
// Для Python/TypeScript: те же поля в "options" объекте POST /api/chat или через openai-compat /api/options
```

## Metrics

- **Context utilization check** — тест: вопрос чей ответ ТОЛЬКО в документах, не в train данных модели; при `num_ctx=2048` провалится, при `8192` пройдёт
- **Answer determinism** — запусти один и тот же запрос 5 раз с `temperature=0`; все ответы должны совпадать
- **Response length distribution** — P50/P95 длины ответа; при `num_predict=256` P95 < 300 токенов; если нужно длиннее — увеличить
- **Repeat penalty effect** — % ответов с повторяющимися фразами до/после `repeat_penalty=1.1`; должно снизиться на 80%+

## Source

- **AI Challenge:** week6/day4 — Оптимизация локальной LLM под RAG
- **Артефакты:** https://github.com/AlDtoll/ai-challenge/tree/week6/day4/week6/day4 — `Main.kt`, `eval-questions.txt`
- **Связано:** [`ollama_local_setup.md`](ollama_local_setup.md) — базовый клиент Ollama который использует эти параметры; [`local_llm_http_service.md`](local_llm_http_service.md) — сервис где эти настройки зашиты как defaults; [`../rag/rag_chunking.md`](../rag/rag_chunking.md) — размер чанков определяет нужный num_ctx
