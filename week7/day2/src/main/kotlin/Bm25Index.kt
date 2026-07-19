import java.io.File
import kotlin.math.ln

/**
 * Простой BM25 индекс над коротким корпусом текстовых чанков.
 *
 * Почему не Ollama-эмбеддинги как в day31: GitHub Actions runner не имеет Ollama,
 * а прокидывать эмбеддинги через DeepSeek — дорого (много токенов) и медленно.
 * BM25 полностью offline, детерминирован, работает за миллисекунды.
 *
 * Токенизация — простейшая: lowercase → split по НЕ буквенно-цифровым →
 * фильтр коротких (<3). Достаточно для запросов вида «код + путь + класс».
 */
class Bm25Index(chunks: List<Chunk>, k1: Double = 1.5, b: Double = 0.75) {

    data class Chunk(val source: String, val text: String)
    data class ScoredChunk(val chunk: Chunk, val score: Double)

    private val docs: List<List<String>> = chunks.map { tokenize(it.text) }
    private val chunks: List<Chunk> = chunks
    private val avgDocLen: Double = if (docs.isEmpty()) 1.0 else docs.sumOf { it.size } / docs.size.toDouble()
    private val df: Map<String, Int> = buildDf(docs)
    private val n: Int = docs.size
    private val k1: Double = k1
    private val b: Double = b

    fun topK(query: String, k: Int): List<ScoredChunk> {
        if (chunks.isEmpty()) return emptyList()
        val qTokens = tokenize(query).distinct()
        return docs.mapIndexed { i, doc ->
            val tf = doc.groupingBy { it }.eachCount()
            val dl = doc.size.toDouble()
            val score = qTokens.sumOf { term ->
                val f = tf[term] ?: return@sumOf 0.0
                val dfi = df[term] ?: 0
                val idf = ln(((n - dfi + 0.5) / (dfi + 0.5)) + 1.0)
                val norm = f * (k1 + 1) / (f + k1 * (1 - b + b * dl / avgDocLen))
                idf * norm
            }
            ScoredChunk(chunks[i], score)
        }.filter { it.score > 0.0 }
            .sortedByDescending { it.score }
            .take(k)
    }

    companion object {
        private val NON_WORD = Regex("[^\\p{L}\\p{Nd}]+")

        fun tokenize(text: String): List<String> =
            text.lowercase().split(NON_WORD).filter { it.length >= 3 }

        private fun buildDf(docs: List<List<String>>): Map<String, Int> {
            val df = HashMap<String, Int>()
            for (doc in docs) {
                for (term in doc.distinct()) df[term] = (df[term] ?: 0) + 1
            }
            return df
        }
    }
}

/**
 * Сбор чанков из документации проекта: README + docs/*.md + верхнеуровневые
 * *.md (CLAUDE.md/MEMORY.md, если есть). Каждый файл нарезается по абзацам с cap 1200 символов.
 */
fun collectDocChunks(repoRoot: File, chunkSize: Int = 1200, overlap: Int = 150): List<Bm25Index.Chunk> {
    val allowedNames = setOf("README.md", "CLAUDE.md", "MEMORY.md")
    val ignored = setOf(".git", "node_modules", "build", ".gradle", ".idea", "tmp", "target")
    val files = mutableListOf<File>()

    fun visit(f: File, depth: Int) {
        if (depth > 8) return
        if (f.name in ignored) return
        if (f.isFile) {
            val rel = try { f.relativeTo(repoRoot).path } catch (_: Exception) { f.name }
            when {
                f.name in allowedNames -> files.add(f)
                f.name.endsWith(".md") && rel.contains("docs" + File.separator) -> files.add(f)
            }
            return
        }
        f.listFiles()?.forEach { visit(it, depth + 1) }
    }
    visit(repoRoot, 0)

    val chunks = mutableListOf<Bm25Index.Chunk>()
    for (file in files.sortedBy { it.absolutePath }) {
        val rel = try { file.relativeTo(repoRoot).path } catch (_: Exception) { file.name }
        val text = file.readText(Charsets.UTF_8)
        for (piece in chunkText(text, chunkSize, overlap)) {
            chunks.add(Bm25Index.Chunk(source = rel, text = piece))
        }
    }
    return chunks
}

private fun chunkText(text: String, size: Int, overlap: Int): List<String> {
    val cleaned = text.trim()
    if (cleaned.length <= size) return listOf(cleaned)
    val step = (size - overlap).coerceAtLeast(1)
    val out = mutableListOf<String>()
    var i = 0
    while (i < cleaned.length) {
        val end = (i + size).coerceAtMost(cleaned.length)
        var piece = cleaned.substring(i, end)
        if (end < cleaned.length) {
            val lastNL = piece.lastIndexOf("\n\n")
            if (lastNL > size - 200) piece = piece.substring(0, lastNL)
        }
        out.add(piece.trim())
        if (end >= cleaned.length) break
        i += step
    }
    return out.filter { it.isNotBlank() }
}
