# RAG Chunking — fixed-size vs structural (по Markdown-заголовкам)

## What it improves

Качество RAG напрямую зависит от того как разбиты документы. Fixed-size chunker режет на равные куски по символам — просто, но разрезает смысловые блоки посреди предложения. Structural chunker разбивает по Markdown-заголовкам (`#`, `##`, `###`) — каждый чанк = одна смысловая секция. Для технической документации, wiki, руководств structural даёт Recall@3 и MRR выше на 15-30% без усложнения retrieval.

## When to use

- Документация в Markdown/HTML с явными заголовками → structural chunking
- Книги, статьи, plain text без структуры → fixed-size с overlap
- Сравнение baseline vs улучшенного chunker перед выбором (запусти метрики Recall@3, MRR)
- Документы смешанного типа → гибрид: structural по заголовкам, fixed внутри длинных секций

**Когда НЕ надо:** структурный chunking на неструктурированных документах — нет заголовков, получится один большой чанк; очень короткие документы (< 300 симв) — нет смысла дробить.

## How to integrate

1. Реализуй `FixedSizeChunker(chunkSize: Int, overlap: Int)` — режет строку на куски через `substring` с перекрытием.
2. Реализуй `StructuralChunker` — ищет строки начинающиеся с `#`, каждая секция = чанк с заголовком как metadata.
3. Сохраняй чанки в SQLite: `id, document_id, content, heading, chunk_index, char_start`.
4. При retrieval — вместе с контентом возвращай `heading` и `document_id` как источник для цитирования.
5. Измерь Recall@3 и MRR на тестовом наборе вопросов (20+ вопросов с known answers).

## Working example (Kotlin)

```kotlin
data class Chunk(
    val id: String,
    val documentId: String,
    val content: String,
    val heading: String = "",
    val chunkIndex: Int = 0
)

class FixedSizeChunker(
    private val chunkSize: Int = 600,
    private val overlap: Int = 100
) {
    fun chunk(documentId: String, text: String): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        var start = 0
        var index = 0
        while (start < text.length) {
            val end = minOf(start + chunkSize, text.length)
            chunks.add(Chunk(
                id = "${documentId}_$index",
                documentId = documentId,
                content = text.substring(start, end),
                chunkIndex = index
            ))
            start += chunkSize - overlap
            index++
        }
        return chunks
    }
}

class StructuralChunker {
    fun chunk(documentId: String, text: String): List<Chunk> {
        val lines = text.lines()
        val chunks = mutableListOf<Chunk>()
        var currentHeading = ""
        val buffer = StringBuilder()
        var index = 0

        fun flush() {
            val content = buffer.toString().trim()
            if (content.isNotEmpty()) {
                chunks.add(Chunk(
                    id = "${documentId}_$index",
                    documentId = documentId,
                    content = content,
                    heading = currentHeading,
                    chunkIndex = index
                ))
                index++
                buffer.clear()
            }
        }

        for (line in lines) {
            if (line.startsWith("#")) {
                flush()
                currentHeading = line.trimStart('#').trim()
                buffer.appendLine(line)
            } else {
                buffer.appendLine(line)
            }
        }
        flush()
        return chunks
    }
}

// Метрика Recall@3:
// Для каждого тест-вопроса: retrieve top-3 chunks, проверь что правильный ответ в них
// recall@3 = hit_count / total_questions
// MRR = sum(1/rank) / total_questions где rank = позиция правильного чанка (1..3)
```

## Metrics

- **Recall@3** — % тест-вопросов где правильный чанк в top-3; structural обычно > fixed на структурированных docs
- **MRR (Mean Reciprocal Rank)** — среднее 1/rank; 1.0 = всегда первый, 0.33 = всегда третий
- **Avg chunks per document** — если structural = 1 чанк на весь doc → документ без заголовков → использовать fixed
- **Chunk size distribution** — P50/P95 длины чанков; слишком большие (> 2000 симв) плохо для контекстного окна LLM; слишком маленькие (< 100 симв) теряют контекст

## Source

- **AI Challenge:** week5/day1 — RAG индексация: fixed vs structural chunking
- **Артефакты:** https://github.com/AlDtoll/ai-challenge/tree/week5/day1/week5/day1 — `Main.kt`, `src/main/resources/data/` (25 .md файлов)
- **Связано:** [`rag_reranker_and_rewrite.md`](rag_reranker_and_rewrite.md) — следующий шаг после хорошего chunking: улучшить retrieval через реранкинг; [`bm25_offline_index.md`](bm25_offline_index.md) — альтернативный retrieval без эмбеддингов, работает поверх тех же чанков
