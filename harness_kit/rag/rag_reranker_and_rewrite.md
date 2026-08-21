# RAG Reranker + Query Rewrite — улучшение retrieval поверх naive-RAG

## What it improves

Naive-RAG: cosine-similarity, top-K → передать в LLM. Проблемы: (1) косинус ловит лексическое сходство, не семантику; (2) «плохой» вопрос (с ошибкой, неполный) даёт плохой retrieval. Этот файл покрывает три улучшения поверх naive-RAG: **threshold фильтр** (отсекает нерелевантный шум ниже 0.28), **LLM-реранкер** (оценивает top-10 по семантике, возвращает JSON с float-оценками 0..1, выбирает top-3), **query rewrite** (3 переформулировки + union результатов). Включает паттерн LLM-as-judge как переиспользуемый building block.

## When to use

- Naive-RAG уже работает, но качество ответов недостаточно (recall < 70%)
- Пользователи задают «неточные» вопросы: сокращения, опечатки, другой стиль чем в документах
- База знаний > 1000 чанков — threshold нужен чтобы не тянуть нерелевантное
- Нужна оценка качества ответа без разметки → LLM-as-judge

**Когда НЕ надо:** маленькая KB (< 100 чанков) — reranker избыточен; бюджет токенов критичен — reranker стоит дополнительный вызов LLM.

## How to integrate

1. **Threshold**: после cosine-retrieval top-10 отфильтруй чанки с score < 0.28 (порог подбирается на тест-наборе).
2. **Reranker**: оставшиеся → LLM-промпт «оцени релевантность каждого чанка вопросу, верни JSON array с float scores 0..1» → sort by score → top-3.
3. **Query rewrite**: 3 переформулировки оригинального вопроса через LLM → retrieval для каждой → объединить (union) → дедуплицировать → reranker.
4. **LLM-as-judge** (переиспользуемый паттерн): LLM получает вопрос + ответ + критерии → возвращает `{"score": 0.8, "reasoning": "..."}` — применимо для реранкинга, оценки качества модели, автотестов.
5. Предусмотри 5 режимов: naive / threshold / rerank / rewrite / full — для A/B сравнения.

## Working example (Kotlin)

```kotlin
class RagReranker(private val llmClient: DeepSeekClient) {
    suspend fun rerank(query: String, candidates: List<Chunk>, topK: Int = 3): List<Chunk> {
        if (candidates.isEmpty()) return emptyList()

        val candidatesText = candidates.mapIndexed { i, c ->
            "[$i] ${c.heading.ifEmpty { "Чанк $i" }}: ${c.content.take(300)}..."
        }.joinToString("\n\n")

        val prompt = """
            Вопрос пользователя: "$query"
            
            Оцени релевантность каждого фрагмента документа для ответа на вопрос.
            Верни JSON массив с оценками от 0.0 до 1.0:
            [{"index": 0, "score": 0.9}, {"index": 1, "score": 0.3}, ...]
            
            Фрагменты:
            $candidatesText
        """.trimIndent()

        return try {
            val raw = llmClient.chat(listOf(Message("user", prompt)))
            val scores = parseScores(raw)
            candidates
                .mapIndexed { i, chunk -> chunk to (scores[i] ?: 0.0) }
                .filter { (_, score) -> score >= 0.45 }
                .sortedByDescending { (_, score) -> score }
                .take(topK)
                .map { (chunk, _) -> chunk }
        } catch (e: Exception) {
            candidates.take(topK)  // fallback к порядку cosine
        }
    }

    private fun parseScores(raw: String): Map<Int, Double> {
        val arrayContent = raw.substringAfter("[").substringBefore("]")
        return Regex("""\"index\":\s*(\d+),\s*\"score\":\s*([\d.]+)""")
            .findAll(arrayContent)
            .associate { it.groupValues[1].toInt() to it.groupValues[2].toDouble() }
    }
}

class QueryRewriter(private val llmClient: DeepSeekClient) {
    suspend fun rewrite(query: String, count: Int = 3): List<String> {
        val prompt = """
            Перефразируй вопрос $count разными способами для поиска в базе знаний.
            Верни JSON массив строк: ["вариант 1", "вариант 2", "вариант 3"]
            Вопрос: "$query"
        """.trimIndent()

        return try {
            val raw = llmClient.chat(listOf(Message("user", prompt)))
            Regex(""""([^"]+)"""").findAll(raw)
                .map { it.groupValues[1] }
                .filter { it.length > 5 }
                .take(count)
                .toList()
                .ifEmpty { listOf(query) }
        } catch (e: Exception) {
            listOf(query)
        }
    }
}

// LLM-as-judge (переиспользуемый паттерн из week1/day5):
// val judgePrompt = "Вопрос: $q\nОтвет: $a\nОцени качество 0..1 по критериям: точность, полнота.\nВерни JSON: {\"score\": 0.8, \"reasoning\": \"...\"}"
// Применимо для: реранкинг (выше), оценка моделей (day5), автотесты RAG-качества
```

## Metrics

- **Recall@3 naive vs reranked** — основная метрика; reranker должен давать +10-25% на structured docs
- **Rewriter contribution** — % вопросов где union(rewrites) нашёл чанк который original не нашёл; при < 5% query rewrite не окупается
- **Reranker latency** (ms) — дополнительное время на LLM-вызов; при > 2 сек — запускать async или кэшировать
- **Judge score calibration** — сравни auto-judge scores с ручной разметкой 20 примеров; расхождение > 0.3 = плохой judge-промпт

## Source

- **AI Challenge:** week5/day3 — Реранкинг, query rewrite, фильтрация; week1/day5 — LLM-as-judge (паттерн оценки моделей)
- **Артефакты:** https://github.com/AlDtoll/ai-challenge/tree/week5/day3/week5/day3 — `Main.kt`; https://github.com/AlDtoll/ai-challenge/tree/week1/day5/week1/day5 — `Main.kt` (LLM-судья)
- **Связано:** [`rag_chunking.md`](rag_chunking.md) — качество chunking определяет потолок reranker; [`rag_anti_hallucination.md`](rag_anti_hallucination.md) — что делать с результатом retrieval дальше; [`bm25_offline_index.md`](bm25_offline_index.md) — BM25 как дешёвая альтернатива без LLM-reranker
