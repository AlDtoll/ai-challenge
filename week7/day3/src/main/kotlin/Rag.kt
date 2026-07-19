import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * RAG над корпусом FAQ (md-файлы в data/faq).
 *
 * Два режима, выбирается автоматически:
 *  • Ollama nomic-embed-text + cosine (как в day31). Если Ollama доступна — используем.
 *  • BM25 offline fallback (как в day32) — если Ollama не поднята или USE_OLLAMA=false.
 *
 * Идея: демо должно работать в любой среде — на VPS без Ollama, на Windows Данила с Ollama.
 */

data class Chunk(val id: Int, val source: String, val text: String, val embedding: DoubleArray?)
data class IndexFile(val mode: String, val chunks: List<Chunk>)
data class ScoredChunk(val chunk: Chunk, val score: Double)

private val gson = Gson()
private val http: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(3)).build()

fun ollamaAvailable(host: String): Boolean = try {
    val req = HttpRequest.newBuilder(URI.create("$host/api/tags"))
        .timeout(Duration.ofSeconds(2))
        .GET()
        .build()
    val res = http.send(req, HttpResponse.BodyHandlers.discarding())
    res.statusCode() == 200
} catch (_: Exception) { false }

fun ollamaEmbed(host: String, model: String, text: String): DoubleArray {
    val body = mapOf("model" to model, "input" to text)
    val req = HttpRequest.newBuilder(URI.create("$host/api/embed"))
        .timeout(Duration.ofSeconds(60))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body), StandardCharsets.UTF_8))
        .build()
    val res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
    if (res.statusCode() != 200) error("Ollama embed HTTP ${res.statusCode()}")
    val j = gson.fromJson(res.body(), JsonObject::class.java)
    val arr = j.getAsJsonArray("embeddings").get(0).asJsonArray
    return DoubleArray(arr.size()) { arr.get(it).asDouble }
}

fun cosine(a: DoubleArray, b: DoubleArray): Double {
    var dot = 0.0; var na = 0.0; var nb = 0.0
    for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
    return if (na == 0.0 || nb == 0.0) 0.0 else dot / (sqrt(na) * sqrt(nb))
}

fun chunkMarkdown(text: String, size: Int, overlap: Int): List<String> {
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

fun collectFaqFiles(dataDir: File): List<File> {
    val faqDir = File(dataDir, "faq")
    if (!faqDir.isDirectory) return emptyList()
    return faqDir.walk().filter { it.isFile && it.name.endsWith(".md") }.sortedBy { it.absolutePath }.toList()
}

fun ingest(cfg: Config, mode: String): List<Chunk> {
    val files = collectFaqFiles(cfg.dataDir)
    System.err.println("FAQ: нашёл ${files.size} md-файлов в ${File(cfg.dataDir, "faq").absolutePath}")
    val out = mutableListOf<Chunk>()
    var id = 0
    for (f in files) {
        val rel = "faq/${f.name}"
        val text = f.readText(Charsets.UTF_8)
        for (piece in chunkMarkdown(text, cfg.chunkSize, cfg.chunkOverlap)) {
            val emb = if (mode == "ollama") ollamaEmbed(cfg.ollamaHost, cfg.embedModel, piece) else null
            out.add(Chunk(id++, rel, piece, emb))
        }
    }
    return out
}

fun saveIndex(path: File, mode: String, chunks: List<Chunk>) {
    path.writeText(gson.toJson(IndexFile(mode, chunks)), Charsets.UTF_8)
}

fun loadIndex(path: File): IndexFile? {
    if (!path.exists()) return null
    val type = object : TypeToken<IndexFile>() {}.type
    return gson.fromJson(path.readText(Charsets.UTF_8), type)
}

/** Ретрив top-K с автовыбором режима. */
fun ragSearch(cfg: Config, index: IndexFile, query: String): List<ScoredChunk> {
    if (index.chunks.isEmpty()) return emptyList()
    return when (index.mode) {
        "ollama" -> {
            val q = ollamaEmbed(cfg.ollamaHost, cfg.embedModel, query)
            index.chunks.map {
                ScoredChunk(it, if (it.embedding != null) cosine(q, it.embedding) else 0.0)
            }.sortedByDescending { it.score }.take(cfg.topK)
        }
        else -> bm25TopK(index.chunks, query, cfg.topK)
    }
}

/** Одна и та же BM25-реализация что в day32. */
private fun bm25TopK(chunks: List<Chunk>, query: String, k: Int, k1: Double = 1.5, b: Double = 0.75): List<ScoredChunk> {
    val docs = chunks.map { tokenize(it.text) }
    val avgLen = if (docs.isEmpty()) 1.0 else docs.sumOf { it.size } / docs.size.toDouble()
    val df = HashMap<String, Int>()
    for (d in docs) for (t in d.distinct()) df[t] = (df[t] ?: 0) + 1
    val n = docs.size
    val qTokens = tokenize(query).distinct()
    return docs.mapIndexed { i, doc ->
        val tf = doc.groupingBy { it }.eachCount()
        val dl = doc.size.toDouble()
        val score = qTokens.sumOf { term ->
            val f = tf[term] ?: return@sumOf 0.0
            val dfi = df[term] ?: 0
            val idf = ln(((n - dfi + 0.5) / (dfi + 0.5)) + 1.0)
            val norm = f * (k1 + 1) / (f + k1 * (1 - b + b * dl / avgLen))
            idf * norm
        }
        ScoredChunk(chunks[i], score)
    }.filter { it.score > 0 }.sortedByDescending { it.score }.take(k)
}

private val NON_WORD = Regex("[^\\p{L}\\p{Nd}]+")
private fun tokenize(text: String): List<String> =
    text.lowercase().split(NON_WORD).filter { it.length >= 3 }
