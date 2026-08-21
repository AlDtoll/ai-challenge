# RAG Anti-Hallucination — 3 слоя защиты от выдумывания

## What it improves

LLM с RAG-контекстом всё равно может выдумывать: использовать знания не из предоставленных документов, придумывать цитаты, уверенно врать. Три слоя защиты: (1) **ALLOWED_QUOTES** — программная нарезка чанков на фразы 40-240 символов, LLM выбирает цитаты только из этого списка (детерминированная проверка без второго LLM-вызова); (2) **structured output** — LLM возвращает JSON `{answer, sources, citations, confidence, abstained}`; (3) **retry-loop** — если цитата не из ALLOWED_QUOTES → retry с явным фидбеком. Плюс **soft-abstain**: при отсутствии данных отвечает из общих знаний с пометкой, не молчит.

## When to use

- Продуктовый RAG где ошибочная информация критична (медицина, право, техподдержка)
- Нужна трассируемость ответа: пользователь должен видеть откуда взята информация
- Compliance-требование: «ответ должен быть из документов, не из головы модели»
- Агент ведёт переговоры или даёт рекомендации — выдумка = юридический риск

**Когда НЕ надо:** creative tasks (там hallucination = feature), внутренние dev-инструменты без compliance, быстрый прототип — 3 слоя дают 2-3× overhead на токены.

## How to integrate

1. После retrieval: нарежь все чанки на ALLOWED_QUOTES (Regex по предложениям/фразам 40-240 симв).
2. В промпт: передай ALLOWED_QUOTES списком + инструкция «используй ТОЛЬКО эти фразы как цитаты».
3. Потребуй от LLM JSON-ответ: `{answer: str, sources: [int], citations: [str], confidence: float, abstained: bool}`.
4. Post-check: каждую citation проверь программно через `allowedQuotes.contains(citation)` (без LLM).
5. Если citation не прошёл проверку → retry с фидбеком «цитата не из документов, перегенерируй» (до 2 повторов).

## Working example (Kotlin)

```kotlin
@Serializable
data class GroundedAnswer(
    val answer: String,
    val sources: List<Int>,     // индексы чанков
    val citations: List<String>, // точные фрагменты из ALLOWED_QUOTES
    val confidence: Double,      // 0.0..1.0
    val abstained: Boolean       // true если данных нет
)

class AntiHallucinationRag(private val llmClient: DeepSeekClient) {
    fun buildAllowedQuotes(chunks: List<Chunk>): Set<String> {
        val pattern = Regex("""[^.!?]{40,240}""")
        return chunks.flatMap { chunk ->
            pattern.findAll(chunk.content).map { it.value.trim() }
        }.toSet()
    }

    suspend fun answer(
        query: String,
        chunks: List<Chunk>,
        maxRetries: Int = 2
    ): GroundedAnswer {
        val allowedQuotes = buildAllowedQuotes(chunks)
        val chunksText = chunks.mapIndexed { i, c -> "[$i] ${c.content}" }.joinToString("\n\n")
        val quotesText = allowedQuotes.take(50).joinToString("\n") { "- \"$it\"" }

        val prompt = """
            Вопрос: "$query"
            
            Документы:
            $chunksText
            
            РАЗРЕШЁННЫЕ ЦИТАТЫ (использовать ТОЛЬКО их, без изменений):
            $quotesText
            
            Ответь СТРОГО в формате JSON:
            {"answer": "...", "sources": [0, 1], "citations": ["цитата из списка"], "confidence": 0.9, "abstained": false}
            
            Если ответа нет в документах: {"answer": "По общим знаниям: ...", "sources": [], "citations": [], "confidence": 0.4, "abstained": true}
        """.trimIndent()

        repeat(maxRetries + 1) { attempt ->
            val raw = llmClient.chat(listOf(Message("user", prompt)))
            return try {
                val result = Json { ignoreUnknownKeys = true }.decodeFromString<GroundedAnswer>(
                    raw.substringAfter("{").let { "{$it" }.substringBeforeLast("}").let { "$it}" }
                )
                val invalidCitations = result.citations.filter { it !in allowedQuotes }
                if (invalidCitations.isEmpty() || attempt == maxRetries) {
                    result
                } else {
                    // retry — недостижим через return, используем continue
                    throw IllegalStateException("Invalid citations: $invalidCitations")
                }
            } catch (e: Exception) {
                if (attempt == maxRetries) {
                    GroundedAnswer("Не удалось получить верифицированный ответ.", emptyList(), emptyList(), 0.0, true)
                } else {
                    GroundedAnswer("retry", emptyList(), emptyList(), 0.0, false)  // placeholder для retry
                }
            }
        }

        return GroundedAnswer("Превышены попытки верификации.", emptyList(), emptyList(), 0.0, true)
    }
}
```

## Metrics

- **Citation verification pass rate** — % ответов где все citations прошли post-check с первого раза; < 80% = ALLOWED_QUOTES слишком строгий или LLM игнорирует инструкцию
- **Retry trigger rate** — % вызовов требующих повтора; при > 20% — упростить ограничение или улучшить промпт
- **Abstain rate** — % вопросов где `abstained=true`; при > 30% — KB неполная или вопросы не по теме
- **Confidence calibration** — correlation между confidence и реальной точностью ответа (проверить на 50 вопросах с правильными ответами)

## Source

- **AI Challenge:** week5/day4 — Цитаты, источники, анти-галлюцинации
- **Артефакты:** https://github.com/AlDtoll/ai-challenge/tree/week5/day4/week5/day4 — `Main.kt`
- **Связано:** [`rag_reranker_and_rewrite.md`](rag_reranker_and_rewrite.md) — улучшение retrieval до anti-hallucination; [`rag_task_state_enrichment.md`](rag_task_state_enrichment.md) — обогащение retrieval сессионным контекстом; [`../security/llm_gateway.md`](../security/llm_gateway.md) — gateway с output-guard как дополнительный слой защиты
