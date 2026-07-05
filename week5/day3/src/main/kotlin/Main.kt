/*
 * Day 23 (week5/day3). RAG + реранкинг + query rewrite + фильтрация.
 *
 * Строим поверх дня 22 (naive top-3) два этапа:
 *   - QUERY REWRITE  — DeepSeek генерирует 3 переформулировки, эмбеддим каждую,
 *                     объединяем результаты (union по chunkId, максимальный cosine).
 *   - LLM-RERANK     — DeepSeek оценивает релевантность top-10 кандидатов
 *                     плоским JSON-массивом [0.9, 0.4, ...], фильтруем по порогу.
 *   - SIMILARITY-ПОРОГ — быстрый дешёвый фильтр перед реранкером.
 *
 * Флаги --compare прогоняют 5 режимов (naive / +threshold / +rerank / +rewrite / full)
 * и печатают матрицу вкладов каждого шага: Retrieval@3, Retrieval@10, MRR@3, Grounded,
 * LLM calls (rewrite/rerank/final), latency.
 *
 * Эмбеддинги: локальная Ollama (nomic-embed-text, 768-мерка, task-префиксы).
 * LLM: DeepSeek chat.
 */
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
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
    val source: String,
    val chunkId: Int,
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

/** Пять режимов работы — от naive (день 22) до full. */
enum class Mode(val cli: String, val label: String) {
    NAIVE("naive", "naive (эмбед top-3)"),
    THRESHOLD("threshold", "+threshold (эмбед top-10 → cos ≥ TH → top-3)"),
    RERANK("rerank", "+rerank (эмбед top-10 → LLM-rerank → top-3)"),
    REWRITE("rewrite", "+rewrite (3 rewrite → union top-10 → top-3)"),
    FULL("full", "full (rewrite + threshold + rerank)");

    companion object {
        fun parse(s: String): Mode? = entries.firstOrNull { it.cli.equals(s, ignoreCase = true) }
    }
}

/** Метрики за один запрос: сколько DeepSeek-вызовов, сколько мс потрачено, кандидаты до/после. */
data class RagResult(
    val question: String,
    val mode: Mode,
    val answer: String,
    val finalHits: List<Hit>,           // топ-3, использованные в контексте LLM
    val candidatesBefore: List<Hit>,    // топ-10 до фильтра/реранка (для видимости)
    val rewrites: List<String>,         // если было — какие переформулировки использовались
    val llmCallsRewrite: Int,
    val llmCallsRerank: Int,
    val llmCallsFinal: Int,
    val latencyMs: Long,
) {
    val llmCallsTotal: Int get() = llmCallsRewrite + llmCallsRerank + llmCallsFinal
}

// ==================== Константы стратегии ====================

/** Сколько кандидатов забираем с эмбед-этапа перед фильтром/реранком. */
private const val TOP_K_INITIAL = 10

/** Финальный размер контекста для LLM (сколько top-K в подсказке). */
private const val TOP_K_FINAL = 3

/** Similarity-порог: cosine ниже — отбрасываем ещё до реранкера. */
private const val SIM_THRESHOLD = 0.28f

/** Rerank-порог: score от LLM ниже — отбрасываем. Шкала 0.0..1.0. */
private const val RERANK_THRESHOLD = 0.45f

/** Сколько переформулировок запрашиваем у DeepSeek в rewrite. */
private const val REWRITE_N = 3

/** Обрезка чанка при подаче в rerank-промпт (стоимость + шум). */
private const val RERANK_CHUNK_LIMIT = 800

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
 *  ошибка ранних RAG-имплементаций. */
fun embedDocument(text: String): FloatArray =
    ollamaEmbedRaw(if (OLLAMA_MODEL.contains("nomic")) "search_document: $text" else text)

fun embedQuery(text: String): FloatArray =
    ollamaEmbedRaw(if (OLLAMA_MODEL.contains("nomic")) "search_query: $text" else text)

// ==================== Чанкер (по абзацам, с укрупнением) ====================

fun chunkParagraphs(text: String): List<String> {
    val paras = text.split(Regex("\\n\\s*\\n"))
        .map { it.trim() }
        .filter { it.isNotBlank() }
    val chunks = mutableListOf<String>()
    var buf = StringBuilder()
    val hardMax = 700
    for (p in paras) {
        if (buf.isEmpty()) buf.append(p)
        else if (buf.length + p.length + 2 <= hardMax) buf.append("\n\n").append(p)
        else {
            chunks += buf.toString(); buf = StringBuilder(p)
        }
    }
    if (buf.isNotEmpty()) chunks += buf.toString()
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

// ==================== Индекс (JSON) ====================

private val indexFile: File by lazy {
    File(System.getProperty("user.home"), ".ai-challenge/day23_index.json")
}

fun saveIndex(idx: Index) {
    indexFile.parentFile.mkdirs()
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

/** Multi-query retrieval: эмбеддим каждый запрос из списка, объединяем результаты по chunkId
 *  (берём максимальный cosine из вариантов) → сортируем → top-K. Дедупликация обязательна:
 *  один и тот же чанк часто находят все три переформулировки. */
fun topKMulti(queries: List<String>, idx: Index, k: Int): List<Hit> {
    if (queries.isEmpty()) return emptyList()
    if (queries.size == 1) {
        val emb = embedQuery(queries[0])
        return topK(emb, idx.chunks, k)
    }
    val best = HashMap<Chunk, Float>()
    for (q in queries) {
        val emb = embedQuery(q)
        val hits = topK(emb, idx.chunks, k)
        for (h in hits) {
            val prev = best[h.chunk]
            if (prev == null || h.score > prev) best[h.chunk] = h.score
        }
    }
    return best.map { (c, s) -> Hit(c, s) }
        .sortedByDescending { it.score }
        .take(k)
}

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

fun deepseek(system: String, user: String, temperature: Double = 0.2): String {
    val body = gson.toJson(
        mapOf(
            "model" to DEEPSEEK_MODEL,
            "temperature" to temperature,
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

// ==================== Query rewrite ====================

private const val REWRITE_SYSTEM = """Ты эксперт по переформулированию поисковых запросов
для базы знаний AI-челленджа Данила (Kotlin, RAG, MCP, DeepSeek, Ollama, Windows).
Дан оригинальный вопрос. Верни РОВНО 3 переформулировки:
1) Rephrasing — тот же смысл другими словами;
2) Conceptual Expansion — переформулировка с добавлением ключевых терминов/синонимов;
3) Search Engine Style — короткая формулировка как для поисковика (2-6 ключевых слов).

Формат ответа СТРОГО JSON без markdown-блоков:
{"rewritten_queries":["...","...","..."]}"""

/** Возвращает список из [ORIGINAL, rewrite1, rewrite2, rewrite3]. При ошибке — только оригинал. */
fun rewriteQuery(question: String): List<String> {
    val raw = try { deepseek(REWRITE_SYSTEM, question, temperature = 0.3) }
    catch (e: Exception) { return listOf(question) }
    return try {
        val json = extractJsonObject(raw)
        val arr = JsonParser.parseString(json).asJsonObject.getAsJsonArray("rewritten_queries")
        val list = arr.map { it.asString.trim() }.filter { it.isNotBlank() }.take(REWRITE_N)
        if (list.isEmpty()) listOf(question) else listOf(question) + list
    } catch (e: Exception) {
        listOf(question)
    }
}

/** Достаём JSON-объект из ответа модели: срезаем markdown-fences, оставляем от первой `{` до последней `}`. */
private fun extractJsonObject(raw: String): String {
    val cleaned = raw
        .replace(Regex("^```json\\s*", RegexOption.MULTILINE), "")
        .replace(Regex("^```\\s*", RegexOption.MULTILINE), "")
        .replace(Regex("\\s*```$", RegexOption.MULTILINE), "")
        .trim()
    val start = cleaned.indexOf('{')
    val end = cleaned.lastIndexOf('}')
    return if (start >= 0 && end > start) cleaned.substring(start, end + 1) else cleaned
}

/** Достаём JSON-массив: от первой `[` до последней `]`. */
private fun extractJsonArray(raw: String): String {
    val cleaned = raw
        .replace(Regex("^```json\\s*", RegexOption.MULTILINE), "")
        .replace(Regex("^```\\s*", RegexOption.MULTILINE), "")
        .replace(Regex("\\s*```$", RegexOption.MULTILINE), "")
        .trim()
    val start = cleaned.indexOf('[')
    val end = cleaned.lastIndexOf(']')
    return if (start >= 0 && end > start) cleaned.substring(start, end + 1) else cleaned
}

// ==================== LLM Reranker ====================

private const val RERANK_SYSTEM = """Ты эксперт-ассистент по оценке релевантности.
Дан ВОПРОС и список ФРАГМЕНТОВ базы знаний (пронумерованных). Оцени, насколько каждый фрагмент
полезен для ответа на вопрос. Верни СТРОГО JSON — плоский массив чисел 0.0..1.0 в том же порядке,
что и фрагменты. Длина массива обязана совпадать с количеством фрагментов.

Пример вывода: [0.9, 0.35, 0.1, 0.7, 0.0]
БЕЗ markdown, без комментариев, без пояснений — только массив."""

/** Возвращает пары (Hit, rerank-score в 0..1). Порядок как в исходных candidates.
 *  При сломанном JSON — паддит недостающие значения 0.5 (по dpmn). */
fun rerankLLM(question: String, candidates: List<Hit>): List<Pair<Hit, Float>> {
    if (candidates.isEmpty()) return emptyList()
    val prompt = buildString {
        append("ВОПРОС:\n").append(question).append("\n\nФРАГМЕНТЫ:\n")
        for ((i, h) in candidates.withIndex()) {
            append('[').append(i).append("] ")
            val t = h.chunk.text
            append(if (t.length > RERANK_CHUNK_LIMIT) t.substring(0, RERANK_CHUNK_LIMIT) + "…" else t)
            append("\n\n")
        }
    }
    val raw = try { deepseek(RERANK_SYSTEM, prompt, temperature = 0.1) }
    catch (e: Exception) {
        // сеть отвалилась — возвращаем нейтральный скор, порядок как есть
        return candidates.map { it to 0.5f }
    }
    val scores = parseRerankScores(raw, candidates.size)
    return candidates.zip(scores) { h, s -> h to s }
}

/** Парсит плоский массив чисел из ответа модели. Regex-fallback + padding до `expected`
 *  значением 0.5. Клэмпит в [0, 1] (LLM изредка выдаёт «1.2» или «-0.1»). */
fun parseRerankScores(raw: String, expected: Int): FloatArray {
    val out = FloatArray(expected) { 0.5f }
    val jsonStr = extractJsonArray(raw)
    val parsed = try {
        val arr = JsonParser.parseString(jsonStr).asJsonArray
        arr.map { it.asFloat }
    } catch (e: Exception) {
        // Regex-fallback: находим все числа в исходной строке
        Regex("[-+]?[0-9]*\\.?[0-9]+").findAll(raw).mapNotNull { it.value.toFloatOrNull() }.toList()
    }
    for (i in 0 until minOf(parsed.size, expected)) {
        out[i] = parsed[i].coerceIn(0f, 1f)
    }
    return out
}

// ==================== Основной агент ====================

private const val SYSTEM_NORAG =
    "Ты помощник по AI-челленджу Данила. Отвечай кратко и по делу на русском."

private fun systemWithRag(context: String) =
    """Ты помощник по AI-челленджу Данила. Отвечай на вопрос СТРОГО опираясь на КОНТЕКСТ ниже,
своих знаний не выдумывай. Если ответа в контексте нет — так и скажи: «в моей базе нет данных».
Отвечай кратко и по делу на русском. В конце ответа перечисли использованные источники в
виде «Источники: <файл1>, <файл2>».

КОНТЕКСТ:
$context""".trim()

/** Отдельно чистый LLM-ответ БЕЗ RAG — для сравнения в REPL и в `--compare`. */
fun askNoRag(question: String): String = deepseek(SYSTEM_NORAG, question)

/** Основная точка входа: собирает контекст согласно режиму и вызывает DeepSeek. */
fun runRag(question: String, idx: Index, mode: Mode): RagResult {
    val t0 = System.currentTimeMillis()
    var callsRewrite = 0
    var callsRerank = 0
    var callsFinal = 0

    // 1) Формируем запросы (rewrite или только оригинал).
    val queries: List<String>
    if (mode == Mode.REWRITE || mode == Mode.FULL) {
        queries = rewriteQuery(question).also { callsRewrite = if (it.size > 1) 1 else 0 }
    } else {
        queries = listOf(question)
    }

    // 2) Ретрив: naive → top-3 сразу; остальные — top-10 → обработка.
    val initialK = if (mode == Mode.NAIVE) TOP_K_FINAL else TOP_K_INITIAL
    val candidates = topKMulti(queries, idx, initialK)

    // 3) Similarity-порог (threshold, full).
    val afterThreshold = if (mode == Mode.THRESHOLD || mode == Mode.FULL) {
        candidates.filter { it.score >= SIM_THRESHOLD }.ifEmpty { candidates.take(TOP_K_FINAL) }
    } else candidates

    // 4) LLM-реранк (rerank, full).
    val afterRerank: List<Hit> = if (mode == Mode.RERANK || mode == Mode.FULL) {
        val scored = rerankLLM(question, afterThreshold).also { callsRerank = 1 }
        scored.filter { it.second >= RERANK_THRESHOLD }
            .sortedByDescending { it.second }
            .map { it.first }
            .ifEmpty {
                // если порог всё срезал — берём лучших по rerank-скору, чтобы не остаться пустыми
                scored.sortedByDescending { it.second }.take(TOP_K_FINAL).map { it.first }
            }
    } else {
        afterThreshold
    }

    val finalHits = afterRerank.take(TOP_K_FINAL)

    // 5) Финальный LLM-ответ по контексту.
    val ctx = finalHits.joinToString("\n\n---\n\n") {
        "[источник: ${it.chunk.source}, cos=${"%.3f".format(it.score)}]\n${it.chunk.text}"
    }
    val answer = if (finalHits.isEmpty()) "В моей базе нет данных (ни один кандидат не прошёл фильтр)."
    else deepseek(systemWithRag(ctx), question).also { callsFinal = 1 }

    return RagResult(
        question = question,
        mode = mode,
        answer = answer,
        finalHits = finalHits,
        candidatesBefore = candidates,
        rewrites = queries.drop(1),
        llmCallsRewrite = callsRewrite,
        llmCallsRerank = callsRerank,
        llmCallsFinal = callsFinal,
        latencyMs = System.currentTimeMillis() - t0,
    )
}

// ==================== Метрики и compare ====================

fun loadQuestions(): List<Question> {
    val text = File("src/main/resources/questions.json").readText(Charsets.UTF_8)
    val qs = gson.fromJson(text, QuestionSet::class.java)
    return qs.questions
}

fun keywordsHit(answer: String, expected: List<String>): Int {
    val lo = answer.lowercase()
    return expected.count { lo.contains(it.lowercase()) }
}

/** Reciprocal Rank ожидаемого источника в списке hits. Возвращает 0.0 если не найден. */
fun reciprocalRank(hits: List<Hit>, expectedSource: String): Double {
    val idx = hits.indexOfFirst { it.chunk.source == expectedSource }
    return if (idx < 0) 0.0 else 1.0 / (idx + 1)
}

data class ModeStats(
    val mode: Mode,
    var retrievalAt3: Int = 0,
    var retrievalAt10: Int = 0,
    var mrrSum: Double = 0.0,
    var groundedHits: Int = 0,
    var llmCallsSum: Int = 0,
    var latencySumMs: Long = 0,
    var errors: Int = 0,
) {
    fun mrrAvg(n: Int) = if (n > 0) mrrSum / n else 0.0
    fun avgLatency(n: Int) = if (n > 0) latencySumMs / n else 0
}

fun runCompare(idx: Index, modes: List<Mode> = Mode.entries.toList()) {
    val questions = loadQuestions()
    val stats = modes.associateWith { ModeStats(it) }.toMutableMap()

    println("== Прогон ${questions.size} контрольных вопросов × ${modes.size} режимов ==\n")

    for ((qi, q) in questions.withIndex()) {
        println("Q${qi + 1}: ${q.q}")
        println("  ожидаемый источник: ${q.expected_source}")
        println("  ключевые слова: ${q.expected_keywords}")

        for (m in modes) {
            val res = try { runRag(q.q, idx, m) }
            catch (e: Exception) {
                stats[m]!!.errors++
                println("  [${m.cli}] ERR: ${e.message}")
                continue
            }

            val topSources3 = res.finalHits.map { it.chunk.source }
            val topSources10 = res.candidatesBefore.map { it.chunk.source }
            val ok3 = q.expected_source in topSources3
            val ok10 = q.expected_source in topSources10
            val rr = reciprocalRank(res.finalHits, q.expected_source)
            val kHits = keywordsHit(res.answer, q.expected_keywords)
            val grounded = kHits >= (q.expected_keywords.size + 1) / 2

            val s = stats[m]!!
            if (ok3) s.retrievalAt3++
            if (ok10) s.retrievalAt10++
            s.mrrSum += rr
            if (grounded) s.groundedHits++
            s.llmCallsSum += res.llmCallsTotal
            s.latencySumMs += res.latencyMs

            println("  [${m.cli.padEnd(9)}] R@3=${if (ok3) "✓" else "✗"} R@10=${if (ok10) "✓" else "✗"} " +
                "MRR=${"%.2f".format(rr)} grnd=$kHits/${q.expected_keywords.size} " +
                "calls=${res.llmCallsTotal} lat=${res.latencyMs}ms")
        }
        println()
    }

    // Финальная таблица
    val n = questions.size
    println("== Итог по режимам (n=$n) ==")
    println("Режим       | R@3    | R@10   | MRR@3 | Grounded | LLM/q | avg lat")
    println("------------|--------|--------|-------|----------|-------|--------")
    for (m in modes) {
        val s = stats[m]!!
        val r3 = "${s.retrievalAt3}/$n"
        val r10 = "${s.retrievalAt10}/$n"
        val mrr = "%.2f".format(s.mrrAvg(n))
        val g = "${s.groundedHits}/$n"
        val calls = "%.1f".format(s.llmCallsSum.toDouble() / n)
        val lat = "${s.avgLatency(n)}ms"
        println("${m.cli.padEnd(11)} | ${r3.padEnd(6)} | ${r10.padEnd(6)} | $mrr  | ${g.padEnd(8)} | $calls   | $lat")
    }
    val naiveMrr = stats[Mode.NAIVE]?.mrrAvg(n) ?: 0.0
    println("\nΔMRR относительно naive:")
    for (m in modes) {
        if (m == Mode.NAIVE) continue
        val d = stats[m]!!.mrrAvg(n) - naiveMrr
        println("  ${m.cli.padEnd(11)}  ${if (d >= 0) "+" else ""}${"%.3f".format(d)}")
    }
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
  (без флагов)                             — интерактивный REPL, режим по умолчанию `full`,
                                             сменить: `:mode naive|threshold|rerank|rewrite|full`
  --build                                  — построить индекс (эмбеддинги через Ollama)
  --ask "вопрос"                           — one-shot запрос, дефолтный режим `full`
  --ask "вопрос" --mode <режим>            — one-shot с выбором режима
  --compare                                — прогон 10 контрольных вопросов × 5 режимов + метрики
  --help                                   — эта справка

Режимы (--mode):
  naive       — эмбед top-3 (baseline, как день 22)
  threshold   — эмбед top-10 → cos ≥ $SIM_THRESHOLD → top-3
  rerank      — эмбед top-10 → LLM-rerank ≥ $RERANK_THRESHOLD → top-3
  rewrite     — 3 переформулировки → union top-10 → top-3
  full        — rewrite + threshold + rerank
""")
}

fun runRepl() {
    val idx = ensureIndex()
    var mode = Mode.FULL
    println("\nИнтерактивный RAG (день 23). Режим по умолчанию: ${mode.label}")
    println("Смена режима:  :mode naive|threshold|rerank|rewrite|full")
    println("Показать топ-10 кандидатов: :show (после каждого ответа автоматически)")
    println("Пустая строка / Ctrl+C — выход.\n")
    while (true) {
        print("[${mode.cli}] > ")
        System.out.flush()
        val line = readlnOrNull()?.trim() ?: break
        if (line.isEmpty()) break
        if (line.startsWith(":mode")) {
            val parts = line.split(Regex("\\s+"))
            val newMode = if (parts.size >= 2) Mode.parse(parts[1]) else null
            if (newMode == null) println("Неизвестный режим. Доступно: ${Mode.entries.joinToString("/") { it.cli }}")
            else { mode = newMode; println("→ ${mode.label}") }
            continue
        }
        val q = line
        println("\n--- БЕЗ RAG (для сравнения) ---")
        val noRag = try { askNoRag(q) } catch (e: Exception) { "ERR: ${e.message}" }
        println(noRag)

        println("\n--- С RAG [${mode.label}] ---")
        val res = try { runRag(q, idx, mode) } catch (e: Exception) {
            println("ERR: ${e.message}"); continue
        }
        if (res.rewrites.isNotEmpty()) {
            println("Rewrite-варианты:")
            for ((i, r) in res.rewrites.withIndex()) println("  ${i + 1}. $r")
        }
        println(res.answer)
        println("\nКандидаты top-${res.candidatesBefore.size} до финального отбора:")
        for (h in res.candidatesBefore) {
            val used = if (h in res.finalHits) "★" else " "
            println("  $used ${h.chunk.source}#${h.chunk.chunkId}  cos=${"%.3f".format(h.score)}")
        }
        println("★ — попал в финальный контекст.")
        println("LLM calls: rewrite=${res.llmCallsRewrite} rerank=${res.llmCallsRerank} final=${res.llmCallsFinal} " +
            "(всего ${res.llmCallsTotal}), latency ${res.latencyMs}ms")
        println()
    }
    println("Пока.")
}

fun main(args: Array<String>) {
    val list = args.toList()
    when {
        "--help" in list -> printHelp()
        "--build" in list -> buildIndex()
        "--compare" in list -> {
            val idx = ensureIndex()
            val modeArg = list.getOrNull(list.indexOf("--modes") + 1)?.takeIf { list.contains("--modes") }
            val modes = modeArg?.split(',')?.mapNotNull { Mode.parse(it.trim()) }?.ifEmpty { null }
                ?: Mode.entries
            runCompare(idx, modes)
        }
        "--ask" in list -> {
            val ai = list.indexOf("--ask")
            val q = list.getOrNull(ai + 1) ?: run { println("Нет текста после --ask"); exitProcess(1) }
            val modeArg = list.getOrNull(list.indexOf("--mode") + 1)?.takeIf { list.contains("--mode") }
            val mode = modeArg?.let { Mode.parse(it) } ?: Mode.FULL
            val idx = ensureIndex()
            val res = runRag(q, idx, mode)
            println("\n=== ОТВЕТ [${mode.label}] ===")
            println(res.answer)
            println("\nКандидаты top-${res.candidatesBefore.size}:")
            for (h in res.candidatesBefore) {
                val used = if (h in res.finalHits) "★" else " "
                println("  $used ${h.chunk.source}#${h.chunk.chunkId}  cos=${"%.3f".format(h.score)}")
            }
            println("LLM calls: ${res.llmCallsTotal} (rewrite=${res.llmCallsRewrite}, rerank=${res.llmCallsRerank}, final=${res.llmCallsFinal}), latency ${res.latencyMs}ms")
        }
        else -> runRepl()
    }
}
