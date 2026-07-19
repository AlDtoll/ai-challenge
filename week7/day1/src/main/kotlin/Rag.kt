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
import kotlin.math.sqrt

/**
 * RAG-индекс над README.md + docs + CLAUDE.md проекта-донора.
 * Формат чанков совместим с week5/day22 и week6/day5 (та же схема).
 */

data class Chunk(val id: Int, val source: String, val text: String, val embedding: DoubleArray)
data class IndexFile(val chunks: List<Chunk>)

private val gson = Gson()
private val http: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10)).build()

fun ollamaEmbed(host: String, model: String, text: String): DoubleArray {
    val body = mapOf("model" to model, "input" to text)
    val req = HttpRequest.newBuilder(URI.create("$host/api/embed"))
        .timeout(Duration.ofSeconds(60))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body), StandardCharsets.UTF_8))
        .build()
    val res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
    if (res.statusCode() != 200) error("Ollama embed HTTP ${res.statusCode()}: ${res.body().take(200)}")
    val j = gson.fromJson(res.body(), JsonObject::class.java)
    val arr = j.getAsJsonArray("embeddings").get(0).asJsonArray
    return DoubleArray(arr.size()) { arr.get(it).asDouble }
}

fun cosine(a: DoubleArray, b: DoubleArray): Double {
    var dot = 0.0; var na = 0.0; var nb = 0.0
    for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
    return if (na == 0.0 || nb == 0.0) 0.0 else dot / (sqrt(na) * sqrt(nb))
}

/** Простой markdown-aware chunker. Копия из week6/day5 — доказанно рабочая. */
fun chunkMarkdown(text: String, size: Int = 800, overlap: Int = 150): List<String> {
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

/**
 * Читает README.md, все .md в docs, root CLAUDE.md.
 * Игнорирует .git, node_modules, build, .gradle, .idea, tmp, /tmp.
 */
fun collectSourceFiles(root: File): List<File> {
    val allowedNames = setOf("README.md", "CLAUDE.md", "MEMORY.md")
    val ignored = setOf(".git", "node_modules", "build", ".gradle", ".idea", "tmp", "target")
    val out = mutableListOf<File>()

    fun visit(f: File, depth: Int) {
        if (depth > 8) return
        if (f.name in ignored) return
        if (f.isFile) {
            val rel = f.relativeTo(root).path
            when {
                f.name in allowedNames -> out.add(f)
                f.name.endsWith(".md") && (rel.startsWith("docs${File.separator}") || rel.contains("${File.separator}docs${File.separator}")) -> out.add(f)
            }
            return
        }
        f.listFiles()?.forEach { visit(it, depth + 1) }
    }
    visit(root, 0)
    return out.sortedBy { it.absolutePath }
}

fun ingest(project: File, cfg: Config): List<Chunk> {
    val files = collectSourceFiles(project)
    println("Ingest: нашёл ${files.size} md-файлов в ${project.absolutePath}")
    val chunks = mutableListOf<Chunk>()
    var id = 0
    for ((idx, f) in files.withIndex()) {
        val rel = try { f.relativeTo(project).path } catch (_: Exception) { f.name }
        val text = f.readText(Charsets.UTF_8)
        val pieces = chunkMarkdown(text, cfg.chunkSize, cfg.chunkOverlap)
        for (p in pieces) {
            val emb = ollamaEmbed(cfg.ollamaHost, cfg.embedModel, p)
            chunks.add(Chunk(id++, rel, p, emb))
        }
        if ((idx + 1) % 10 == 0) println("  … ${idx + 1}/${files.size} файлов, ${chunks.size} чанков")
    }
    return chunks
}

fun saveIndex(path: File, chunks: List<Chunk>) {
    path.writeText(gson.toJson(IndexFile(chunks)), Charsets.UTF_8)
}

fun loadIndex(path: File): List<Chunk> {
    if (!path.exists()) return emptyList()
    val type = object : TypeToken<IndexFile>() {}.type
    return gson.fromJson<IndexFile>(path.readText(Charsets.UTF_8), type).chunks
}

fun ragSearch(cfg: Config, question: String, chunks: List<Chunk>): List<Pair<Chunk, Double>> {
    if (chunks.isEmpty()) return emptyList()
    val qEmb = ollamaEmbed(cfg.ollamaHost, cfg.embedModel, question)
    return chunks.map { it to cosine(qEmb, it.embedding) }
        .sortedByDescending { it.second }
        .take(cfg.topK)
}
