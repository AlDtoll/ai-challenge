import java.io.File
import java.nio.ByteBuffer
import java.sql.DriverManager
import kotlin.math.sqrt

// ============ Модели ============

data class Metadata(
    val source: String,       // "python.md"
    val title: String,        // "Python"
    val section: String?,     // "История" (structural) или null (fixed)
    val chunkId: String,      // "python.md#f-0" | "python.md#s-2"
    val strategy: String,     // "fixed" | "structural"
)

data class Chunk(
    val text: String,
    val embedding: FloatArray,
    val metadata: Metadata,
)

data class Document(val source: String, val title: String, val text: String)

// ============ Chunker ============

interface Chunker {
    val name: String
    fun chunk(source: String, title: String, text: String): List<Chunk>
}

class FixedSizeChunker(
    private val chunkSize: Int,
    private val overlap: Int,
    private val embedder: Embedder,
) : Chunker {
    override val name = "fixed"

    override fun chunk(source: String, title: String, text: String): List<Chunk> {
        if (text.isBlank()) return emptyList()
        val step = chunkSize - overlap
        val chunks = mutableListOf<Chunk>()
        var i = 0
        var idx = 0
        while (i < text.length) {
            val end = minOf(i + chunkSize, text.length)
            val slice = text.substring(i, end)
            val meta = Metadata(source, title, null, "$source#f-$idx", "fixed")
            chunks.add(Chunk(slice, embedder.embed(slice), meta))
            if (end >= text.length) break
            i += step
            idx++
        }
        return chunks
    }
}

class StructuralChunker(private val embedder: Embedder) : Chunker {
    override val name = "structural"

    override fun chunk(source: String, title: String, text: String): List<Chunk> {
        val lines = text.lines()
        val chunks = mutableListOf<Chunk>()
        var currentSection: String? = null
        var buf = StringBuilder()
        var idx = 0

        fun flush() {
            val body = buf.toString().trim()
            if (body.isNotEmpty()) {
                val meta = Metadata(source, title, currentSection, "$source#s-$idx", "structural")
                chunks.add(Chunk(body, embedder.embed(body), meta))
                idx++
            }
            buf = StringBuilder()
        }

        for (line in lines) {
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("## ") || trimmed.startsWith("### ") -> {
                    flush()
                    currentSection = trimmed.trimStart('#', ' ').trim()
                    buf.append(line).append('\n')
                }
                trimmed.startsWith("# ") -> {
                    flush()
                    currentSection = trimmed.trimStart('#', ' ').trim()
                    buf.append(line).append('\n')
                }
                else -> buf.append(line).append('\n')
            }
        }
        flush()
        return chunks
    }
}

// ============ Embedder (fake bag-of-words) ============

interface Embedder {
    val dim: Int
    fun embed(text: String): FloatArray
}

class BagOfWordsEmbedder(override val dim: Int = 256) : Embedder {
    override fun embed(text: String): FloatArray {
        val v = FloatArray(dim)
        val words = text.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 2 }
        for (w in words) {
            val i = Math.floorMod(w.hashCode(), dim)
            v[i] += 1f
        }
        val norm = sqrt(v.map { it * it }.sum().toDouble()).toFloat()
        if (norm > 0f) for (i in v.indices) v[i] /= norm
        return v
    }
}

// ============ Хранилище (SQLite) ============

class SqliteVectorStore(dbPath: String) {
    private val conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")

    init {
        conn.createStatement().use { st ->
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS chunks (
                    chunk_id TEXT PRIMARY KEY,
                    source TEXT NOT NULL,
                    title TEXT NOT NULL,
                    section TEXT,
                    text TEXT NOT NULL,
                    embedding BLOB NOT NULL,
                    strategy TEXT NOT NULL
                )
                """.trimIndent()
            )
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_strategy ON chunks(strategy)")
        }
    }

    fun clear(strategy: String) {
        conn.prepareStatement("DELETE FROM chunks WHERE strategy = ?").use { ps ->
            ps.setString(1, strategy)
            ps.executeUpdate()
        }
    }

    fun save(chunks: List<Chunk>) {
        val sql = "INSERT OR REPLACE INTO chunks " +
            "(chunk_id, source, title, section, text, embedding, strategy) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)"
        conn.autoCommit = false
        try {
            conn.prepareStatement(sql).use { ps ->
                for (c in chunks) {
                    ps.setString(1, c.metadata.chunkId)
                    ps.setString(2, c.metadata.source)
                    ps.setString(3, c.metadata.title)
                    ps.setString(4, c.metadata.section)
                    ps.setString(5, c.text)
                    ps.setBytes(6, floatArrayToBytes(c.embedding))
                    ps.setString(7, c.metadata.strategy)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            conn.commit()
        } finally {
            conn.autoCommit = true
        }
    }

    fun close() = conn.close()

    private fun floatArrayToBytes(a: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(a.size * 4)
        for (v in a) buf.putFloat(v)
        return buf.array()
    }
}

// ============ Поиск (косинус) + метрики ============

fun cosine(a: FloatArray, b: FloatArray): Float {
    var dot = 0f
    val n = minOf(a.size, b.size)
    for (i in 0 until n) dot += a[i] * b[i]
    return dot // векторы уже нормализованы
}

data class SearchHit(val chunk: Chunk, val score: Float)

fun topK(chunks: List<Chunk>, queryEmb: FloatArray, k: Int): List<SearchHit> =
    chunks
        .map { SearchHit(it, cosine(queryEmb, it.embedding)) }
        .sortedByDescending { it.score }
        .take(k)

data class TestQuestion(val question: String, val expectedSource: String)

fun parseTestQuestions(text: String): List<TestQuestion> {
    val out = mutableListOf<TestQuestion>()
    val re = Regex("""^"(.+?)"\s*->\s*(\S+)\s*$""")
    for (line in text.lines()) {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
        val m = re.matchEntire(trimmed)
        if (m != null) out.add(TestQuestion(m.groupValues[1], m.groupValues[2]))
    }
    return out
}

data class StrategyMetrics(
    val strategy: String,
    val chunkCount: Int,
    val avgChunkLen: Double,
    val recallAt3: Double,
    val mrr: Double,
    val perQuestionTop1: List<Triple<TestQuestion, String?, Float>>,
)

fun evaluate(
    strategy: String,
    chunks: List<Chunk>,
    embedder: Embedder,
    questions: List<TestQuestion>,
): StrategyMetrics {
    var recallHits = 0
    var mrrSum = 0.0
    val perQ = mutableListOf<Triple<TestQuestion, String?, Float>>()

    for (q in questions) {
        val qEmb = embedder.embed(q.question)
        val hits = topK(chunks, qEmb, 3)
        val rank = hits.indexOfFirst { it.chunk.metadata.source == q.expectedSource }
        if (rank >= 0) {
            recallHits++
            mrrSum += 1.0 / (rank + 1)
        }
        val top1 = hits.firstOrNull()
        perQ.add(Triple(q, top1?.chunk?.metadata?.source, top1?.score ?: 0f))
    }

    val avgLen = if (chunks.isEmpty()) 0.0 else chunks.map { it.text.length }.average()
    return StrategyMetrics(strategy, chunks.size, avgLen, recallHits.toDouble() / questions.size, mrrSum / questions.size, perQ)
}

// ============ Отчёт (comparison.md) ============

fun writeComparisonReport(
    outFile: File,
    fixed: StrategyMetrics,
    structural: StrategyMetrics,
    corpusStats: String,
) {
    val sb = StringBuilder()
    sb.appendLine("# День 21 — Сравнение стратегий чанкинга")
    sb.appendLine()
    sb.appendLine(corpusStats)
    sb.appendLine()
    sb.appendLine("## Метрики")
    sb.appendLine()
    sb.appendLine("| Метрика                | Fixed      | Structural |")
    sb.appendLine("|------------------------|------------|------------|")
    sb.appendLine("| Количество чанков      | ${fixed.chunkCount}          | ${structural.chunkCount}          |")
    sb.appendLine("| Средняя длина (симв.)  | ${"%.0f".format(fixed.avgChunkLen)}         | ${"%.0f".format(structural.avgChunkLen)}         |")
    sb.appendLine("| Recall@3               | ${"%.2f".format(fixed.recallAt3)}       | ${"%.2f".format(structural.recallAt3)}       |")
    sb.appendLine("| MRR                    | ${"%.3f".format(fixed.mrr)}      | ${"%.3f".format(structural.mrr)}      |")
    sb.appendLine()
    sb.appendLine("## Результаты по вопросам (top-1)")
    sb.appendLine()
    sb.appendLine("| # | Вопрос | Ожидалось | Fixed → top-1 | Structural → top-1 |")
    sb.appendLine("|---|--------|-----------|---------------|--------------------|")
    for ((i, entry) in fixed.perQuestionTop1.withIndex()) {
        val (q, fTop, _) = entry
        val sTop = structural.perQuestionTop1[i].second
        val fMark = if (fTop == q.expectedSource) "✅" else "❌"
        val sMark = if (sTop == q.expectedSource) "✅" else "❌"
        sb.appendLine("| ${i + 1} | ${q.question} | `${q.expectedSource}` | $fMark `${fTop ?: "—"}` | $sMark `${sTop ?: "—"}` |")
    }
    sb.appendLine()
    sb.appendLine("## Как читать")
    sb.appendLine()
    sb.appendLine("- **Recall@3** — доля вопросов, где правильный источник попал в топ-3 результатов поиска.")
    sb.appendLine("- **MRR** — Mean Reciprocal Rank, среднее значение `1 / позиция первого правильного результата`. Ближе к 1 = правильный источник чаще выходит в самый топ.")
    sb.appendLine("- **Ожидалось** — файл, из которого «должен» был бы прийти ответ (эталон).")
    sb.appendLine()
    outFile.writeText(sb.toString())
}

// ============ Загрузка corpus и вопросов ============

fun loadCorpus(dir: File): List<Document> {
    require(dir.isDirectory) { "Corpus каталог не найден: ${dir.absolutePath}" }
    return dir.listFiles { f -> f.isFile && f.name.endsWith(".md") }
        ?.sortedBy { it.name }
        ?.map { f ->
            val text = f.readText()
            val h1 = Regex("^#\\s+(.+)$", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)?.trim()
            Document(f.name, h1 ?: f.nameWithoutExtension, text)
        }
        ?: emptyList()
}

// ============ Main ============

fun main(args: Array<String>) {
    var strategy = "both"
    var outDb = "day21_index.db"
    var outReport = "day21_comparison.md"
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--strategy" -> { strategy = args[i + 1]; i++ }
            "--out" -> { outDb = args[i + 1]; i++ }
            "--report" -> { outReport = args[i + 1]; i++ }
        }
        i++
    }

    println("=========================================")
    println(" День 21 — RAG индексация: fixed vs structural")
    println("=========================================")
    println("Стратегия:       $strategy")
    println("Хранилище (БД):  $outDb")
    println("Отчёт сравнения: $outReport")
    println()

    // 1. Загрузка corpus
    val corpusDir = File("src/main/resources/data")
    val docs = loadCorpus(corpusDir)
    val totalChars = docs.sumOf { it.text.length }
    val approxPages = totalChars / 2500.0
    println("--- Corpus ---")
    println("Каталог:     ${corpusDir.absolutePath}")
    println("Документов:  ${docs.size}")
    println("Символов:    $totalChars")
    println("Страниц ≈:   ${"%.1f".format(approxPages)}  (по 2500 симв./стр.)")
    println()

    // 2. Тестовые вопросы
    val qFile = File("src/main/resources/test-questions.md")
    val questions = parseTestQuestions(qFile.readText())
    println("--- Тестовые вопросы ---")
    println("Всего:       ${questions.size}")
    questions.forEachIndexed { idx, q -> println("  ${idx + 1}. \"${q.question}\"  →  ${q.expectedSource}") }
    println()

    // 3. Индексация
    val embedder = BagOfWordsEmbedder(dim = 256)
    val store = SqliteVectorStore(outDb)
    val chunkers = mutableListOf<Chunker>()
    if (strategy == "fixed" || strategy == "both") chunkers.add(FixedSizeChunker(600, 100, embedder))
    if (strategy == "structural" || strategy == "both") chunkers.add(StructuralChunker(embedder))

    val results = mutableMapOf<String, StrategyMetrics>()
    for (chunker in chunkers) {
        println("--- Стратегия: ${chunker.name} ---")
        val t0 = System.currentTimeMillis()
        store.clear(chunker.name)
        val all = mutableListOf<Chunk>()
        for (doc in docs) all.addAll(chunker.chunk(doc.source, doc.title, doc.text))
        store.save(all)
        val elapsed = System.currentTimeMillis() - t0
        println("  Чанков создано: ${all.size}")
        println("  Средняя длина:  ${"%.0f".format(all.map { it.text.length }.average())} симв.")
        println("  Время индекс.:  $elapsed мс")
        val m = evaluate(chunker.name, all, embedder, questions)
        results[chunker.name] = m
        println("  Recall@3:       ${"%.2f".format(m.recallAt3)}")
        println("  MRR:            ${"%.3f".format(m.mrr)}")
        println()
    }

    // 4. Сравнение
    if (strategy == "both") {
        val fixed = results["fixed"]!!
        val structural = results["structural"]!!
        val corpusStats = "Corpus: ${docs.size} документов, $totalChars символов ≈ ${"%.1f".format(approxPages)} страниц."
        writeComparisonReport(File(outReport), fixed, structural, corpusStats)
        println("--- Итог ---")
        println("Отчёт: ${File(outReport).absolutePath}")
        println()
        println("По вопросам (top-1):")
        for ((idx, entry) in fixed.perQuestionTop1.withIndex()) {
            val (q, fTop, fScore) = entry
            val sTriple = structural.perQuestionTop1[idx]
            val fMark = if (fTop == q.expectedSource) "OK" else "MISS"
            val sMark = if (sTriple.second == q.expectedSource) "OK" else "MISS"
            println("  ${idx + 1}. \"${q.question}\"")
            println("       fixed:      [$fMark] ${fTop ?: "—"}      (score ${"%.3f".format(fScore)})")
            println("       structural: [$sMark] ${sTriple.second ?: "—"}      (score ${"%.3f".format(sTriple.third)})")
        }
    }

    store.close()
    println()
    println("Готово.")
}
