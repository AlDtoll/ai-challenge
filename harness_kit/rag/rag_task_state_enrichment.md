# RAG TaskState Enrichment — обогащение retrieval сессионным контекстом

## What it improves

В обычном RAG каждый вопрос обрабатывается независимо — агент «забывает» что была за цель сессии. TaskState решает это: 5 полей (goal, constraints, clarifications, fixed_terms, open_questions) обновляются LLM **в одном вызове** вместе с генерацией ответа. Функция `build_retrieval_query` обогащает поисковый запрос сессионным контекстом — на 5-м шаге задачи retrieval находит более релевантные чанки чем на 1-м, потому что запрос содержит накопленные уточнения и зафиксированные термины.

## When to use

- Многошаговые RAG-диалоги: пользователь уточняет задачу в нескольких сообщениях
- Технические консультации: появляются fixed_terms («мы используем PostgreSQL», «ограничение — Java 11»)
- Agент-планировщик: goal и open_questions меняются по ходу сессии
- Нужно улучшить retrieval без дорогого query rewrite при каждом шаге

**Когда НЕ надо:** простой FAQ-бот с одношаговыми вопросами — TaskState overhead без выгоды; очень короткие сессии (< 3 сообщений).

## How to integrate

1. Определи `TaskState` с 5 полями: `goal`, `constraints: List`, `clarifications: List`, `fixed_terms: Map<String,String>`, `open_questions: List`.
2. В каждом LLM-вызове: включи текущий TaskState в промпт + попроси обновить его вместе с ответом (один JSON-объект).
3. Реализуй `build_retrieval_query(userMessage, taskState)` — конкатенирует оригинальный вопрос с релевантными полями state.
4. Парсь обновлённый state из ответа и сохраняй для следующего шага.
5. Сброс state: при `/new` команде или явном «начнём сначала» от пользователя.

## Working example (Kotlin)

```kotlin
@Serializable
data class TaskState(
    val goal: String = "",
    val constraints: List<String> = emptyList(),
    val clarifications: List<String> = emptyList(),
    val fixedTerms: Map<String, String> = emptyMap(),
    val openQuestions: List<String> = emptyList()
) {
    fun isNotEmpty() = goal.isNotEmpty() || constraints.isNotEmpty() || clarifications.isNotEmpty()

    fun format() = buildString {
        if (goal.isNotEmpty()) appendLine("Цель: $goal")
        if (constraints.isNotEmpty()) appendLine("Ограничения: ${constraints.joinToString("; ")}")
        if (clarifications.isNotEmpty()) appendLine("Уточнения: ${clarifications.joinToString("; ")}")
        if (fixedTerms.isNotEmpty()) appendLine("Термины: ${fixedTerms.entries.joinToString("; ") { "${it.key}=${it.value}" }}")
        if (openQuestions.isNotEmpty()) appendLine("Открытые вопросы: ${openQuestions.joinToString("; ")}")
    }
}

fun buildRetrievalQuery(userMessage: String, state: TaskState): String = buildString {
    append(userMessage)
    if (state.goal.isNotEmpty()) append(" | цель: ${state.goal}")
    if (state.constraints.isNotEmpty()) append(" | ${state.constraints.take(2).joinToString(" ")}")
    if (state.fixedTerms.isNotEmpty()) append(" | ${state.fixedTerms.values.take(3).joinToString(" ")}")
}

class RagWithTaskState(
    private val llmClient: DeepSeekClient,
    private val retriever: (String) -> List<Chunk>
) {
    private var state = TaskState()

    suspend fun chat(userMessage: String): String {
        val enrichedQuery = buildRetrievalQuery(userMessage, state)
        val chunks = retriever(enrichedQuery)
        val context = chunks.joinToString("\n---\n") { it.content }

        val prompt = """
            Контекст из базы знаний:
            $context
            
            Текущее состояние задачи:
            ${state.format().ifEmpty { "Начало сессии" }}
            
            Вопрос: $userMessage
            
            Ответь на вопрос используя контекст, затем обнови TaskState.
            Верни JSON: {"answer": "...", "updated_state": {"goal": "...", "constraints": [...], "clarifications": [...], "fixedTerms": {...}, "openQuestions": [...]}}
        """.trimIndent()

        val raw = llmClient.chat(listOf(Message("user", prompt)))

        // Парсим ответ и новый state
        return try {
            val jsonStr = raw.substringAfter("{").let { "{$it" }.substringBeforeLast("}").let { "$it}" }
            val obj = Json { ignoreUnknownKeys = true }.parseToJsonElement(jsonStr).jsonObject
            val answer = obj["answer"]?.jsonPrimitive?.content ?: raw
            obj["updated_state"]?.jsonObject?.let { stateJson ->
                state = Json { ignoreUnknownKeys = true }.decodeFromJsonElement(stateJson)
            }
            answer
        } catch (e: Exception) {
            raw  // fallback: вернуть сырой ответ если JSON не распарсился
        }
    }

    fun resetState() { state = TaskState() }
}
```

## Metrics

- **Retrieval relevance improvement** — Recall@3 на шаге 1 vs шаге 5 той же сессии; с TaskState enrichment должен расти
- **State update accuracy** — % шагов где `updated_state` содержит корректную информацию (ручной аудит 10 сессий)
- **open_questions closure rate** — доля вопросов из `open_questions` закрытых за сессию (цель > 70%)
- **Fixed_terms utilization** — % retrieval queries где `fixed_terms` из state реально улучшили результат vs без них

## Source

- **AI Challenge:** week5/day5 — Мини-чат с RAG + TaskState
- **Артефакты:** https://github.com/AlDtoll/ai-challenge/tree/week5/day5/week5/day5 — `Main.kt`, `scenarios/overview.json`, `scenarios/planning.json`
- **Связано:** [`../state_machine/task_state_machine.md`](../state_machine/task_state_machine.md) — более строгий state с переходами и gates; [`rag_anti_hallucination.md`](rag_anti_hallucination.md) — что делать с найденными чанками после enriched retrieval; [`rag_reranker_and_rewrite.md`](rag_reranker_and_rewrite.md) — query rewrite как альтернативный способ улучшить retrieval запрос
