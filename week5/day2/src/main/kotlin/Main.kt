/*
 * Day 22 (week5/day2). Первый RAG-запрос.
 *
 * Вопрос → эмбеддинг → cosine top-K по нашей вики → LLM с контекстом → ответ.
 * Плюс режим без RAG для сравнения + прогон по 10 контрольным вопросам.
 *
 * Эмбеддинги: локальная Ollama (nomic-embed-text, 768-мерка).
 * LLM: DeepSeek chat.
 */
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.math.sqrt
import kotlin.system.exitProcess

// ==================== Модели данных ====================

data class Chunk(
    val text: String,
    val source: String,           // "rules.md"
    val chunkId: Int,             // порядковый номер внутри файла
    val embedding: FloatArray,
) {
    override fun equals(other: Any?) = other is Chunk && other.chunkId == chunkId && other.source == source
    override fun hashCode() = source.hashCode() * 31 + chunkId
}

data class Index(val chunks: List<Chunk>, val dim: Int)

data class Hit(val chunk: Chunk, val score: Float)

data class Question(
    val q: String,
    val expected_keywords: List<String>,
    val expected_source: String,
)
data class QuestionSet(val questions: List<Question>)

// ==================== Ollama эмбеддинги ====================

private const val OLLAMA_URL = "http://localhost:11434/api/embeddings"
private const val OLLAMA_MODEL = "nomic-embed-text"

private val http: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .build()

private val gson = Gson()

fun ollamaEmbedRaw(text: String): FloatArray {
    val body = gson.toJson(mapOf("model" to OLLAMA_MODEL, "prompt" to text))
    val req = HttpRequest.newBuilder(URI.create(OLLAMA_URL))
        .timeout(Duration.ofSeconds(60))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
    val res = http.send(req, HttpResponse.BodyHandlers.ofString())
    if (res.statusCode() !in 200..299) {
        error("Ollama /embeddings HTTP ${res.statusCode()}: ${res.body().take(300)}")
    }
    val obj = JsonParser.parseString(res.body()).asJsonObject
    val arr = obj.getAsJsonArray("embedding")
        ?: error("Ollama ответ без поля 'embedding': ${res.body().take(300)}")
    return FloatArray(arr.size()) { arr[it].asFloat }
}

/** nomic-embed-text ожидает task-префиксы: `search_document:` для чанков, `search_query:` для вопросов.
 *  Официальный best practice: даёт ~5-15% прирост recall@k. Пропущенный префикс — самая частая
 *  ошибка ранних RAG-имплементаций. Если модель не nomic-семейства — префикс не добавляем. */
fun embedDocument(text: String): FloatArray =
    ollamaEmbedRaw(if (OLLAMA_MODEL.contains("nomic")) "search_document: $text" else text)

fun embedQuery(text: String): FloatArray =
    ollamaEmbedRaw(if (OLLAMA_MODEL.contains("nomic")) "search_query: $text" else text)

// ==================== Чанкер (по абзацам, с укрупнением) ====================

/** Разбиваем текст на чанки: сначала по пустой строке (абзац), потом склеиваем
 *  соседние если получается слишком короткий, и режем слишком длинные по 700 символам. */
fun chunkParagraphs(text: String): List<String> {
    val paras = text.split(Regex("\\n\\s*\\n"))
        .map { it.trim() }
        .filter { it.isNotBlank() }
    val chunks = mutableListOf<String>()
    var buf = StringBuilder()
    val minLen = 200
    val hardMax = 700
    for (p in paras) {
        if (buf.isEmpty()) buf.append(p)
        else if (buf.length + p.length + 2 <= hardMax) buf.append("\n\n").append(p)
        else {
            chunks += buf.toString(); buf = StringBuilder(p)
        }
    }
    if (buf.isNotEmpty()) chunks += buf.toString()
    // «Слишком короткие» одиночные абзацы уже склеены; «слишком длинные» режем окном 700/overlap 100.
    val out = mutableListOf<String>()
    for (c in chunks) {
        if (c.length <= hardMax) { out += c; continue }
        var i = 0
        while (i < c.length) {
            out += c.substring(i, minOf(i + hardMax, c.length))
            if (i + hardMax >= c.length) break
            i += hardMax - 100
        }
    }
    return out.filter { it.length >= 40 }
}

// ==================== Загрузка корпуса ====================

fun loadKb(dir: File): List<Pair<String, String>> {
    require(dir.isDirectory) { "Не нашёл каталог с базой знаний: $dir" }
    return dir.listFiles { f -> f.extension == "md" }
        ?.sortedBy { it.name }
        ?.map { it.name to it.readText(Charsets.UTF_8) }
        ?: emptyList()
}

// ==================== Индекс (сохранение/загрузка JSON) ====================

private val indexFile: File by lazy {
    File(System.getProperty("user.home"), ".ai-challenge/day22_index.json")
}

fun saveIndex(idx: Index) {
    indexFile.parentFile.mkdirs()
    // Пишем компактно: не через Gson по всему графу (FloatArray был бы неудобным JSON),
    // а свой JSON — {dim, chunks:[{text,source,chunkId,embedding:[..]}]}
    val out = StringBuilder("{\"dim\":${idx.dim},\"chunks\":[")
    for ((i, c) in idx.chunks.withIndex()) {
        if (i > 0) out.append(',')
        out.append("{\"source\":").append(gson.toJson(c.source))
            .append(",\"chunkId\":").append(c.chunkId)
            .append(",\"text\":").append(gson.toJson(c.text))
            .append(",\"embedding\":[")
        for ((j, v) in c.embedding.withIndex()) {
            if (j > 0) out.append(',')
            out.append(v)
        }
        out.append("]}")
    }
    out.append("]}")
    indexFile.writeText(out.toString(), Charsets.UTF_8)
}

fun loadIndex(): Index? {
    if (!indexFile.exists()) return null
    val root = JsonParser.parseString(indexFile.readText(Charsets.UTF_8)).asJsonObject
    val dim = root.get("dim").asInt
    val arr = root.getAsJsonArray("chunks")
    val chunks = arr.map { el ->
        val o = el.asJsonObject
        val embArr = o.getAsJsonArray("embedding")
        Chunk(
            text = o.get("text").asString,
            source = o.get("source").asString,
            chunkId = o.get("chunkId").asInt,
            embedding = FloatArray(embArr.size()) { embArr[it].asFloat },
        )
    }
    return Index(chunks, dim)
}

// ==================== Ретривер ====================

fun cosine(a: FloatArray, b: FloatArray): Float {
    require(a.size == b.size) { "Разная размерность векторов: ${a.size} vs ${b.size}" }
    var dot = 0.0; var na = 0.0; var nb = 0.0
    for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
    if (na == 0.0 || nb == 0.0) return 0f
    return (dot / (sqrt(na) * sqrt(nb))).toFloat()
}

fun topK(query: FloatArray, chunks: List<Chunk>, k: Int): List<Hit> =
    chunks.map { Hit(it, cosine(query, it.embedding)) }
        .sortedByDescending { it.score }
        .take(k)

// ==================== DeepSeek LLM ====================

private const val DEEPSEEK_URL = "https://api.deepseek.com/chat/completions"
private const val DEEPSEEK_MODEL = "deepseek-chat"

fun loadEnv(file: File): Map<String, String> {
    if (!file.exists()) return emptyMap()
    return file.readLines(Charsets.UTF_8)
        .mapNotNull { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith('#')) null
            else t.substringBefore('=', "").let { k ->
                if (k.isEmpty()) null else k to t.substringAfter('=').trim().trim('"').trim('\'')
            }
        }.toMap()
}

fun findEnv(startDir: File = File(".").absoluteFile): File {
    var d: File? = startDir
    while (d != null) {
        val f = File(d, ".env"); if (f.exists()) return f
        d = d.parentFile
    }
    return File(System.getProperty("user.home"), ".env")
}

private val deepseekKey: String by lazy {
    loadEnv(findEnv())["DEEPSEEK_API_KEY"]
        ?: error("DEEPSEEK_API_KEY не найден. Проверь .env в корне проекта (`DEEPSEEK_API_KEY=...`).")
}

fun deepseek(system: String, user: String): String {
    val body = gson.toJson(
        mapOf(
            "model" to DEEPSEEK_MODEL,
            "temperature" to 0.2,
            "messages" to listOf(
                mapOf("role" to "system", "content" to system),
                mapOf("role" to "user", "content" to user),
            ),
        )
    )
    val req = HttpRequest.newBuilder(URI.create(DEEPSEEK_URL))
        .timeout(Duration.ofSeconds(90))
        .header("Content-Type", "application/json")
        .header("Authorization", "Bearer $deepseekKey")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
    val res = http.send(req, HttpResponse.BodyHandlers.ofString())
    if (res.statusCode() !in 200..299) {
        error("DeepSeek HTTP ${res.statusCode()}: ${res.body().take(500)}")
    }
    val obj = JsonParser.parseString(res.body()).asJsonObject
    return obj.getAsJsonArray("choices")
        .get(0).asJsonObject
        .getAsJsonObject("message")
        .get("content").asString.trim()
}

// ==================== Агент: два режима ====================

/** Промпт-обёртка для режима «без RAG» — модель отвечает по своим знаниям. */
private const val SYSTEM_NORAG =
    "Ты помощник по AI-челленджу Данила. Отвечай кратко и по делу на русском."

/** Промпт-обёртка для режима «с RAG» — модель отвечает СТРОГО по контексту. */
private fun systemWithRag(context: String) =
    """Ты помощник по AI-челленджу Данила. Отвечай на вопрос СТРОГО опираясь на КОНТЕКСТ ниже,
своих знаний не выдумывай. Если ответа в контексте нет — так и скажи: «в моей базе нет данных».
Отвечай кратко и по делу на русском. В конце ответа перечисли использованные источники в
виде «Источники: <файл1>, <файл2>».

КОНТЕКСТ:
$context""".trim()

data class Answer(val text: String, val sourcesUsed: List<String>, val hits: List<Hit>)

fun askNoRag(question: String): Answer {
    val txt = deepseek(SYSTEM_NORAG, question)
    return Answer(txt, emptyList(), emptyList())
}

fun askWithRag(question: String, idx: Index, k: Int = 3): Answer {
    val qEmb = embedQuery(question)
    val hits = topK(qEmb, idx.chunks, k)
    val ctx = hits.joinToString("\n\n---\n\n") { "[источник: ${it.chunk.source}, cos=${"%.3f".format(it.score)}]\n${it.chunk.text}" }
    val txt = deepseek(systemWithRag(ctx), question)
    return Answer(txt, hits.map { it.chunk.source }.distinct(), hits)
}

// ==================== Оценка на 10 контрольных вопросах ====================

fun loadQuestions(): List<Question> {
    val text = File("src/main/resources/questions.json").readText(Charsets.UTF_8)
    val qs = gson.fromJson(text, QuestionSet::class.java)
    return qs.questions
}

fun keywordsHit(answer: String, expected: List<String>): Int {
    val lo = answer.lowercase()
    return expected.count { lo.contains(it.lowercase()) }
}

fun runCompare(idx: Index) {
    val questions = loadQuestions()
    var retrievalHit = 0
    var groundedNo = 0
    var groundedRag = 0
    println("== Прогон 10 контрольных вопросов ==\n")
    for ((i, q) in questions.withIndex()) {
        println("Q${i + 1}: ${q.q}")
        println("  ожидаемый источник: ${q.expected_source}")
        println("  ключевые слова: ${q.expected_keywords}")

        val noRag = try { askNoRag(q.q) } catch (e: Exception) { Answer("ERR: ${e.message}", emptyList(), emptyList()) }
        Thread.sleep(200)
        val withRag = try { askWithRag(q.q, idx) } catch (e: Exception) { Answer("ERR: ${e.message}", emptyList(), emptyList()) }

        val topSources = withRag.hits.map { it.chunk.source }
        val retrievalOk = q.expected_source in topSources
        val kNo = keywordsHit(noRag.text, q.expected_keywords)
        val kRag = keywordsHit(withRag.text, q.expected_keywords)
        if (retrievalOk) retrievalHit++
        if (kNo >= (q.expected_keywords.size + 1) / 2) groundedNo++
        if (kRag >= (q.expected_keywords.size + 1) / 2) groundedRag++

        println("  Retrieval@3: ${if (retrievalOk) "✓" else "✗"}  (top-src: $topSources)")
        println("  --- без RAG ---  keywords hit: $kNo/${q.expected_keywords.size}")
        println("  " + noRag.text.replace("\n", "\n  "))
        println("  --- с RAG ---    keywords hit: $kRag/${q.expected_keywords.size}")
        println("  " + withRag.text.replace("\n", "\n  "))
        println()
    }
    println("== Итог ==")
    println("Retrieval@3: $retrievalHit/${questions.size}")
    println("Grounded (без RAG): $groundedNo/${questions.size}")
    println("Grounded (с RAG):   $groundedRag/${questions.size}")
}

// ==================== Билд индекса ====================

fun buildIndex(): Index {
    val kbDir = File("src/main/resources/kb")
    val docs = loadKb(kbDir)
    require(docs.isNotEmpty()) { "База знаний пуста: $kbDir" }
    println("Читаю базу знаний из $kbDir — ${docs.size} MD-файлов.")

    val chunks = mutableListOf<Chunk>()
    var dim = -1
    for ((name, text) in docs) {
        val parts = chunkParagraphs(text)
        for ((i, p) in parts.withIndex()) {
            print("\rЭмбединг: ${chunks.size + 1}  (файл $name, чанк $i)          ")
            val emb = embedDocument(p)
            if (dim < 0) dim = emb.size
            require(emb.size == dim) { "Ollama вернул разную размерность: было $dim, стало ${emb.size}" }
            chunks += Chunk(text = p, source = name, chunkId = i, embedding = emb)
        }
    }
    println()
    println("Собрано ${chunks.size} чанков, размерность вектора = $dim.")
    val idx = Index(chunks, dim)
    saveIndex(idx)
    println("Индекс сохранён: ${indexFile.absolutePath}")
    return idx
}

fun ensureIndex(): Index {
    val loaded = loadIndex()
    if (loaded != null) {
        println("Загружен готовый индекс: ${loaded.chunks.size} чанков (dim=${loaded.dim}) из ${indexFile.name}")
        return loaded
    }
    println("Индекс не найден — собираю заново.")
    return buildIndex()
}

// ==================== CLI ====================

fun printHelp() {
    println("""Использование:
  --build                                  — построить индекс (эмбеддинги через Ollama)
  --ask "вопрос"                           — ответ БЕЗ RAG
  --ask "вопрос" --rag                     — ответ С RAG (загружает индекс или строит если нет)
  --compare                                — прогон по 10 контрольным вопросам, оба режима + метрики
  --help                                   — эта справка
""")
}

fun main(args: Array<String>) {
    val list = args.toList()
    when {
        list.isEmpty() || "--help" in list -> printHelp()
        "--build" in list -> buildIndex()
        "--compare" in list -> runCompare(ensureIndex())
        "--ask" in list -> {
            val ai = list.indexOf("--ask")
            val q = list.getOrNull(ai + 1) ?: run { println("Нет текста после --ask"); exitProcess(1) }
            val useRag = "--rag" in list
            if (useRag) {
                val idx = ensureIndex()
                val a = askWithRag(q, idx)
                println("\n=== ОТВЕТ (с RAG) ===")
                println(a.text)
                println("\nОткуда взял: топ-${a.hits.size} чанков ->")
                for (h in a.hits) println("  ${h.chunk.source}#${h.chunk.chunkId}  cos=${"%.3f".format(h.score)}")
            } else {
                val a = askNoRag(q)
                println("\n=== ОТВЕТ (без RAG) ===")
                println(a.text)
            }
        }
        else -> printHelp()
    }
}
