# BM25 Offline Index — retrieval без Ollama (CI/CD и graceful degradation)

## What it improves

Стандартный RAG требует Ollama для генерации эмбеддингов — в CI-раннере (GitHub Actions) Ollama нет. BM25 — классический алгоритм поиска на TF-IDF с нормализацией по длине документа — работает полностью офлайн без GPU, без зависимостей кроме базовой JVM. Детерминированный: один и тот же запрос даёт один и тот же результат — важно для воспроизводимых тестов. Паттерн dual-fallback: агент пробует Ollama → при недоступности переключается на BM25 без деградации сервиса.

## When to use

- CI/CD: PR-ревью, автоматические проверки — Ollama недоступна на раннере
- Production graceful degradation: Ollama упала → обслуживай запросы через BM25 пока не починили
- Детерминированный baseline для тестирования RAG-пайплайна (BM25 reproducible, эмбеддинги — нет)
- Небольшие KB (< 5000 документов) где скорость Ollama не даёт преимущества

**Когда НЕ надо:** семантический поиск критичен и нет инфраструктурных ограничений — BM25 хуже на semantic queries (синонимы, перифраз); языки с богатой морфологией (русский) без стеммера дают сниженное качество.

## How to integrate

1. Реализуй `Bm25Index` — строится за один проход по корпусу: для каждого чанка посчитай TF, веса IDF по всему корпусу.
2. Сохраняй индекс как `index.json` — при рестарте загружается, не переиндексируешь.
3. `search(query, topK)` — токенизируй запрос (lowercase + split), для каждого чанка посчитай BM25-score, верни top-K.
4. `LlmBackend` абстракция с методом `retrieve()` — реализации `OllamaRetriever` и `Bm25Retriever`; агент работает через интерфейс.
5. Health-check: `OllamaRetriever` пробует подключиться → таймаут 2 сек → при failure переключается на `Bm25Retriever`.

## Working example (Kotlin)

```kotlin
import kotlin.math.ln
import kotlin.math.sqrt

class Bm25Index(
    private val k1: Double = 1.5,  // контроль насыщения TF
    private val b: Double = 0.75   // нормализация по длине документа
) {
    private val documents = mutableListOf<Chunk>()
    private val idf = mutableMapOf<String, Double>()
    private var avgDocLength = 0.0

    fun build(chunks: List<Chunk>) {
        documents.clear()
        documents.addAll(chunks)

        val docCount = chunks.size.toDouble()
        avgDocLength = chunks.map { tokenize(it.content).size }.average()

        // IDF для каждого токена
        val df = mutableMapOf<String, Int>()
        for (chunk in chunks) {
            tokenize(chunk.content).toSet().forEach { term ->
                df[term] = (df[term] ?: 0) + 1
            }
        }
        for ((term, count) in df) {
            idf[term] = ln((docCount - count + 0.5) / (count + 0.5) + 1)
        }
    }

    fun search(query: String, topK: Int = 5): List<Chunk> {
        val queryTerms = tokenize(query)
        if (queryTerms.isEmpty()) return emptyList()

        return documents
            .map { chunk -> chunk to score(chunk, queryTerms) }
            .filter { (_, score) -> score > 0 }
            .sortedByDescending { (_, score) -> score }
            .take(topK)
            .map { (chunk, _) -> chunk }
    }

    private fun score(chunk: Chunk, queryTerms: List<String>): Double {
        val tokens = tokenize(chunk.content)
        val docLength = tokens.size.toDouble()
        val tf = tokens.groupingBy { it }.eachCount()

        return queryTerms.sumOf { term ->
            val termFreq = tf[term]?.toDouble() ?: 0.0
            val idfScore = idf[term] ?: 0.0
            val numerator = termFreq * (k1 + 1)
            val denominator = termFreq + k1 * (1 - b + b * docLength / avgDocLength)
            idfScore * (numerator / denominator)
        }
    }

    private fun tokenize(text: String): List<String> =
        text.lowercase()
            .replace(Regex("[^a-zа-яё0-9 ]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 2 }

    fun saveToJson(file: java.io.File) = file.writeText(/* serialization */ "")
    fun loadFromJson(file: java.io.File) { /* deserialization */ }
}

// Dual-fallback retriever:
class DualFallbackRetriever(
    private val ollamaRetriever: OllamaRetriever,
    private val bm25Index: Bm25Index
) {
    suspend fun retrieve(query: String, topK: Int = 5): List<Chunk> {
        return try {
            withTimeout(2000L) { ollamaRetriever.retrieve(query, topK) }
        } catch (e: Exception) {
            println("Ollama недоступна, переключаюсь на BM25: ${e.message}")
            bm25Index.search(query, topK)
        }
    }
}
```

## Metrics

- **BM25 Recall@3 vs Ollama Recall@3** — разрыв в качестве; для технических KB обычно 5-15% в пользу Ollama; при > 25% разрыв — BM25 как fallback ещё ок, но основным не делать
- **Fallback trigger rate** — % запросов где сработал BM25 вместо Ollama; при > 10% — Ollama инфраструктура нестабильна
- **Index build time** (ms) — при 5000+ чанков; должен быть < 5 сек; если выше — добавить инкрементальное обновление
- **Search latency** (ms) — BM25 должен быть быстрее Ollama (нет HTTP-вызова); при задержке > 100 ms на 10000 чанков — оптимизировать токенизацию

## Source

- **AI Challenge:** week7/day2 — Автоматизация ревью PR (BM25 для CI/CD); week7/day3 — dual fallback Ollama→BM25 в support assistant
- **Артефакты:** https://github.com/AlDtoll/ai-challenge/tree/week7/day2/week7/day2 — `Bm25Index.kt`, `Main.kt`; https://github.com/AlDtoll/ai-challenge/tree/week7/day3/week7/day3 — `Rag.kt`, `SupportMcp.kt`
- **Связано:** [`../local_llm/ollama_local_setup.md`](../local_llm/ollama_local_setup.md) — основной retriever от которого BM25 — fallback; [`rag_chunking.md`](rag_chunking.md) — чанки которые BM25 индексирует; [`rag_reranker_and_rewrite.md`](rag_reranker_and_rewrite.md) — реранкинг поверх BM25 для улучшения качества
