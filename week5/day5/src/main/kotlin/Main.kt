/*
 * Day 25 (week5/day5). Мини-чат с RAG + памятью задачи (task state).
 *
 * Наследует полный стек день22+23+24: nomic-embed-text + DeepSeek chat + rewrite + rerank +
 * threshold + ALLOWED_QUOTES + retry-loop + soft-abstain (см. day24 Main.kt для деталей).
 *
 * Новизна дня 25:
 *
 * 1) TASK STATE — отдельный объект памяти, живёт ДОЛЬШЕ одной реплики:
 *      {goal, constraints[], clarifications[], fixed_terms{}, open_questions[]}
 *    Модель обновляет state ОДНИМ вызовом вместе с ответом (`task_state_updates` в JSON).
 *
 * 2) BUILD_RETRIEVAL_QUERY — retrieve обогащается контекстом:
 *      question + goal + fixed_terms + last 3 user messages
 *    Без этого модель теряет цель на 10-й реплике.
 *
 * 3) TASK_STATE_CHUNK — текущий state как псевдо-чанк-источник.
 *    Тогда на мета-вопрос «какая наша цель?» ассистент отвечает из state, а не гадает.
 *
 * 4) ENSURE_CITATIONS — если retry-loop не смог получить валидные цитаты, программа сама
 *    подставляет top-1 чанк. 100% sources_ratio.
 *
 * 5) СЕССИИ на диске (два файла):
 *      ~/.ai-challenge/day25_sessions/<id>/messages.jsonl
 *      ~/.ai-challenge/day25_sessions/<id>/state.json
 *
 * 6) REPLAY сценариев — `--replay <name>` гоняет заготовленный сценарий, финальная реплика =
 *    «сформулируй сводку с учётом ограничений» (проверка удержания goal).
 *
 * 7) МЕТРИКИ: sources_ratio, citations_ratio, valid_quotes_ratio, goal_retained,
 *    task_state_growth, abstain_rate, latency p50/p95.
 */
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
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

data class Source(val id: String, val file: String, val chunkId: Int)

data class Citation(
    val srcId: String,
    val quote: String,
    val supports: String? = null,
    val validQuote: Boolean = false,
    val inAllowed: Boolean = false,
)

data class StructuredAnswer(
    val answer: String,
    val sources: List<Source>,
    val citations: List<Citation>,
    val confidence: String,
    val abstained: Boolean,
    val stateUpdates: TaskStateUpdates?,
    val raw: String,
)

/** Память задачи. Живёт всю сессию, обновляется на каждой реплике. */
data class TaskState(
    var goal: String = "",
    val constraints: MutableList<String> = mutableListOf(),
    val clarifications: MutableList<String> = mutableListOf(),
    val fixedTerms: MutableMap<String, String> = mutableMapOf(),
    val openQuestions: MutableList<String> = mutableListOf(),
) {
    /** Лимиты роста (по dgoryachkovskiy): не даём state пухнуть. */
    fun trim() {
        while (constraints.size > 30) constraints.removeAt(0)
        while (clarifications.size > 40) clarifications.removeAt(0)
        while (openQuestions.size > 20) openQuestions.removeAt(0)
        if (fixedTerms.size > 80) {
            val excess = fixedTerms.size - 80
            val toRemove = fixedTerms.keys.take(excess)
            for (k in toRemove) fixedTerms.remove(k)
        }
    }

    fun apply(u: TaskStateUpdates) {
        if (!u.goal.isNullOrBlank() && u.goal.length > 3) goal = u.goal.trim()
        u.newConstraints?.forEach { if (it.isNotBlank() && it !in constraints) constraints += it.trim() }
        u.newClarifications?.forEach { if (it.isNotBlank() && it !in clarifications) clarifications += it.trim() }
        u.newTerms?.forEach { (k, v) -> if (k.isNotBlank() && v.isNotBlank()) fixedTerms[k.trim()] = v.trim() }
        u.newOpenQuestions?.forEach { if (it.isNotBlank() && it !in openQuestions) openQuestions += it.trim() }
        u.closedQuestions?.forEach { closed -> openQuestions.removeAll { it.equals(closed, ignoreCase = true) } }
        trim()
    }

    fun snapshot() = mapOf(
        "goal" to goal,
        "constraints" to constraints.toList(),
        "clarifications" to clarifications.toList(),
        "fixed_terms" to fixedTerms.toMap(),
        "open_questions" to openQuestions.toList(),
    )

    /** Псевдо-чанк для добавления в retrieval как источник task_state.session. */
    fun toPseudoChunk(): String = buildString {
        append("[TASK_STATE] Текущая память задачи мини-чата:\n")
        append("goal: ").append(goal.ifBlank { "(пока не задан)" }).append('\n')
        if (constraints.isNotEmpty()) append("constraints: ").append(constraints.joinToString("; ")).append('\n')
        if (clarifications.isNotEmpty()) append("clarifications: ").append(clarifications.takeLast(10).joinToString("; ")).append('\n')
        if (fixedTerms.isNotEmpty()) append("fixed_terms: ").append(fixedTerms.entries.joinToString("; ") { "${it.key}=${it.value}" }).append('\n')
        if (openQuestions.isNotEmpty()) append("open_questions: ").append(openQuestions.joinToString("; ")).append('\n')
    }
}

data class TaskStateUpdates(
    val goal: String? = null,
    val newConstraints: List<String>? = null,
    val newClarifications: List<String>? = null,
    val newTerms: Map<String, String>? = null,
    val newOpenQuestions: List<String>? = null,
    val closedQuestions: List<String>? = null,
)

data class ChatMessage(
    val role: String,
    val content: String,
    val ts: String = Instant.now().toString(),
)

data class Session(
    val id: String,
    val startedAt: String,
    var updatedAt: String,
    var questionCount: Int,
    val messages: MutableList<ChatMessage> = mutableListOf(),
    val state: TaskState = TaskState(),
)

data class TurnResult(
    val question: String,
    val answer: String,
    val structured: StructuredAnswer?,
    val finalHits: List<Hit>,
    val allowedQuotesCount: Int,
    val validQuotesCount: Int,
    val totalCitations: Int,
    val abstained: Boolean,
    val stateDiff: StateDiff,
    val llmCallsTotal: Int,
    val latencyMs: Long,
    val retryCount: Int,
)

data class StateDiff(
    val goalChanged: Pair<String, String>?,
    val addedConstraints: List<String>,
    val addedClarifications: List<String>,
    val addedTerms: Map<String, String>,
    val addedOpenQuestions: List<String>,
    val closedQuestions: List<String>,
)

// ==================== Константы стратегии ====================

private const val TOP_K_INITIAL = 10
private const val TOP_K_FINAL = 3
private const val SIM_THRESHOLD = 0.28f
private const val RERANK_THRESHOLD = 0.45f
private const val REWRITE_N = 3
private const val RERANK_CHUNK_LIMIT = 800

private const val ABSTAIN_THRESHOLD = 0.5f
private const val QUOTE_MIN_LEN = 40
private const val QUOTE_MAX_LEN = 240
private const val MAX_RETRIES = 2
private const val ALLOWED_PER_CHUNK = 4

private const val RETRIEVAL_HISTORY_N = 3

// ==================== Ollama ====================

private const val OLLAMA_URL = "http://localhost:11434/api/embeddings"
private const val OLLAMA_MODEL = "nomic-embed-text"

private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
private val gson: Gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
private val gsonCompact = Gson()

fun ollamaEmbedRaw(text: String): FloatArray {
    val body = gsonCompact.toJson(mapOf("model" to OLLAMA_MODEL, "prompt" to text))
    val req = HttpRequest.newBuilder(URI.create(OLLAMA_URL))
        .timeout(Duration.ofSeconds(60))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
    val res = http.send(req, HttpResponse.BodyHandlers.ofString())
    if (res.statusCode() !in 200..299) error("Ollama HTTP ${res.statusCode()}: ${res.body().take(300)}")
    val arr = JsonParser.parseString(res.body()).asJsonObject.getAsJsonArray("embedding")
        ?: error("Ollama без 'embedding'")
    return FloatArray(arr.size()) { arr[it].asFloat }
}

fun embedDocument(text: String): FloatArray =
    ollamaEmbedRaw(if (OLLAMA_MODEL.contains("nomic")) "search_document: $text" else text)

fun embedQuery(text: String): FloatArray =
    ollamaEmbedRaw(if (OLLAMA_MODEL.contains("nomic")) "search_query: $text" else text)

// ==================== Чанкер / KB / индекс ====================

fun chunkParagraphs(text: String): List<String> {
    val paras = text.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotBlank() }
    val chunks = mutableListOf<String>()
    var buf = StringBuilder()
    val hardMax = 700
    for (p in paras) {
        if (buf.isEmpty()) buf.append(p)
        else if (buf.length + p.length + 2 <= hardMax) buf.append("\n\n").append(p)
        else { chunks += buf.toString(); buf = StringBuilder(p) }
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

fun loadKb(dir: File): List<Pair<String, String>> {
    require(dir.isDirectory) { "Не нашёл каталог с базой знаний: $dir" }
    return dir.listFiles { f -> f.extension == "md" }
        ?.sortedBy { it.name }
        ?.map { it.name to it.readText(Charsets.UTF_8) }
        ?: emptyList()
}

private val indexFile: File by lazy {
    File(System.getProperty("user.home"), ".ai-challenge/day25_index.json")
}

fun saveIndex(idx: Index) {
    indexFile.parentFile.mkdirs()
    val out = StringBuilder("{\"dim\":${idx.dim},\"chunks\":[")
    for ((i, c) in idx.chunks.withIndex()) {
        if (i > 0) out.append(',')
        out.append("{\"source\":").append(gsonCompact.toJson(c.source))
            .append(",\"chunkId\":").append(c.chunkId)
            .append(",\"text\":").append(gsonCompact.toJson(c.text))
            .append(",\"embedding\":[")
        for ((j, v) in c.embedding.withIndex()) { if (j > 0) out.append(','); out.append(v) }
        out.append("]}")
    }
    out.append("]}")
    indexFile.writeText(out.toString(), Charsets.UTF_8)
}

fun loadIndex(): Index? {
    if (!indexFile.exists()) return null
    val root = JsonParser.parseString(indexFile.readText(Charsets.UTF_8)).asJsonObject
    val dim = root.get("dim").asInt
    val chunks = root.getAsJsonArray("chunks").map { el ->
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

fun cosine(a: FloatArray, b: FloatArray): Float {
    require(a.size == b.size)
    var dot = 0.0; var na = 0.0; var nb = 0.0
    for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
    if (na == 0.0 || nb == 0.0) return 0f
    return (dot / (sqrt(na) * sqrt(nb))).toFloat()
}

fun topK(query: FloatArray, chunks: List<Chunk>, k: Int): List<Hit> =
    chunks.map { Hit(it, cosine(query, it.embedding)) }.sortedByDescending { it.score }.take(k)

fun topKMulti(queries: List<String>, idx: Index, k: Int): List<Hit> {
    if (queries.isEmpty()) return emptyList()
    if (queries.size == 1) return topK(embedQuery(queries[0]), idx.chunks, k)
    val best = HashMap<Chunk, Float>()
    for (q in queries) {
        for (h in topK(embedQuery(q), idx.chunks, k)) {
            val prev = best[h.chunk]
            if (prev == null || h.score > prev) best[h.chunk] = h.score
        }
    }
    return best.map { (c, s) -> Hit(c, s) }.sortedByDescending { it.score }.take(k)
}

// ==================== DeepSeek ====================

private const val DEEPSEEK_URL = "https://api.deepseek.com/chat/completions"
private const val DEEPSEEK_MODEL = "deepseek-chat"

fun loadEnv(file: File): Map<String, String> {
    if (!file.exists()) return emptyMap()
    return file.readLines(Charsets.UTF_8).mapNotNull { line ->
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
    loadEnv(findEnv())["DEEPSEEK_API_KEY"] ?: error("DEEPSEEK_API_KEY не найден.")
}

fun deepseek(system: String, user: String, temperature: Double = 0.2): String {
    val body = gsonCompact.toJson(mapOf(
        "model" to DEEPSEEK_MODEL,
        "temperature" to temperature,
        "messages" to listOf(
            mapOf("role" to "system", "content" to system),
            mapOf("role" to "user", "content" to user),
        ),
    ))
    val req = HttpRequest.newBuilder(URI.create(DEEPSEEK_URL))
        .timeout(Duration.ofSeconds(90))
        .header("Content-Type", "application/json")
        .header("Authorization", "Bearer $deepseekKey")
        .POST(HttpRequest.BodyPublishers.ofString(body)).build()
    val res = http.send(req, HttpResponse.BodyHandlers.ofString())
    if (res.statusCode() !in 200..299) error("DeepSeek HTTP ${res.statusCode()}: ${res.body().take(500)}")
    return JsonParser.parseString(res.body()).asJsonObject
        .getAsJsonArray("choices").get(0).asJsonObject
        .getAsJsonObject("message").get("content").asString.trim()
}

// ==================== JSON extractors ====================

private fun extractJsonObject(raw: String): String {
    val cleaned = raw
        .replace(Regex("^```json\\s*", RegexOption.MULTILINE), "")
        .replace(Regex("^```\\s*", RegexOption.MULTILINE), "")
        .replace(Regex("\\s*```$", RegexOption.MULTILINE), "").trim()
    val start = cleaned.indexOf('{')
    val end = cleaned.lastIndexOf('}')
    return if (start >= 0 && end > start) cleaned.substring(start, end + 1) else cleaned
}

private fun extractJsonArray(raw: String): String {
    val cleaned = raw
        .replace(Regex("^```json\\s*", RegexOption.MULTILINE), "")
        .replace(Regex("^```\\s*", RegexOption.MULTILINE), "")
        .replace(Regex("\\s*```$", RegexOption.MULTILINE), "").trim()
    val start = cleaned.indexOf('[')
    val end = cleaned.lastIndexOf(']')
    return if (start >= 0 && end > start) cleaned.substring(start, end + 1) else cleaned
}

// ==================== Rewrite + Rerank ====================

private const val REWRITE_SYSTEM = """Ты эксперт по переформулированию поисковых запросов
для базы знаний AI-челленджа. Верни РОВНО 3 переформулировки:
1) Rephrasing; 2) Conceptual Expansion; 3) Search Engine Style (2-6 ключевых слов).
СТРОГО JSON без markdown: {"rewritten_queries":["...","...","..."]}"""

fun rewriteQuery(question: String): List<String> {
    val raw = try { deepseek(REWRITE_SYSTEM, question, temperature = 0.3) }
    catch (e: Exception) { return listOf(question) }
    return try {
        val arr = JsonParser.parseString(extractJsonObject(raw)).asJsonObject.getAsJsonArray("rewritten_queries")
        val list = arr.map { it.asString.trim() }.filter { it.isNotBlank() }.take(REWRITE_N)
        if (list.isEmpty()) listOf(question) else listOf(question) + list
    } catch (e: Exception) { listOf(question) }
}

private const val RERANK_SYSTEM = """Ты эксперт по оценке релевантности. Верни СТРОГО JSON —
плоский массив чисел 0.0..1.0 в порядке фрагментов. Пример: [0.9, 0.35, 0.7]. Без markdown."""

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
    catch (e: Exception) { return candidates.map { it to 0.5f } }
    val scores = parseRerankScores(raw, candidates.size)
    return candidates.mapIndexed { i, h -> h to scores[i] }
}

fun parseRerankScores(raw: String, expected: Int): FloatArray {
    val out = FloatArray(expected) { 0.5f }
    val parsed = try {
        JsonParser.parseString(extractJsonArray(raw)).asJsonArray.map { it.asFloat }
    } catch (e: Exception) {
        Regex("[-+]?[0-9]*\\.?[0-9]+").findAll(raw).mapNotNull { it.value.toFloatOrNull() }.toList()
    }
    for (i in 0 until minOf(parsed.size, expected)) out[i] = parsed[i].coerceIn(0f, 1f)
    return out
}

// ==================== ALLOWED_QUOTES ====================

fun sliceCandidateQuotes(text: String, maxCandidates: Int = ALLOWED_PER_CHUNK): List<String> {
    val sentences = mutableListOf<String>()
    var start = 0
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '.' || c == '!' || c == '?' || c == '\n') {
            val s = text.substring(start, i + 1).trim()
            if (s.isNotEmpty()) sentences += s
            start = i + 1
        }
        i++
    }
    if (start < text.length) {
        val s = text.substring(start).trim()
        if (s.isNotEmpty()) sentences += s
    }
    val candidates = mutableListOf<String>()
    var buf = StringBuilder()
    for (s in sentences) {
        if (buf.isEmpty()) {
            when {
                s.length in QUOTE_MIN_LEN..QUOTE_MAX_LEN -> candidates += s
                s.length < QUOTE_MIN_LEN -> buf.append(s)
                else -> candidates += cutToLen(s, QUOTE_MAX_LEN)
            }
        } else {
            val next = if (buf.last() == ' ') "$buf$s" else "$buf $s"
            when {
                next.length in QUOTE_MIN_LEN..QUOTE_MAX_LEN -> { candidates += next; buf = StringBuilder() }
                next.length < QUOTE_MIN_LEN -> buf = StringBuilder(next)
                else -> { candidates += cutToLen(buf.toString(), QUOTE_MAX_LEN); buf = StringBuilder(s.take(QUOTE_MAX_LEN)) }
            }
        }
    }
    if (buf.length >= QUOTE_MIN_LEN) candidates += buf.toString()
    return candidates
        .distinctBy { it.take(50) }
        .filter { it.length in QUOTE_MIN_LEN..QUOTE_MAX_LEN }
        .sortedBy { kotlin.math.abs(it.length - (QUOTE_MIN_LEN + QUOTE_MAX_LEN) / 2) }
        .take(maxCandidates)
}

private fun cutToLen(s: String, maxLen: Int): String {
    if (s.length <= maxLen) return s
    val cut = s.substring(0, maxLen)
    val lastSpace = cut.lastIndexOf(' ')
    return (if (lastSpace > QUOTE_MIN_LEN) cut.substring(0, lastSpace) else cut).trim() + "…"
}

data class AllowedQuote(val srcId: String, val idx: Int, val text: String)

fun buildAllowedQuotes(hits: List<Hit>): Pair<List<Source>, List<AllowedQuote>> {
    val sources = hits.mapIndexed { i, h -> Source("S${i + 1}", h.chunk.source, h.chunk.chunkId) }
    val allowed = mutableListOf<AllowedQuote>()
    for ((i, h) in hits.withIndex()) {
        val srcId = "S${i + 1}"
        val cands = sliceCandidateQuotes(h.chunk.text)
        for ((qi, q) in cands.withIndex()) allowed += AllowedQuote(srcId, qi, q)
    }
    return sources to allowed
}

fun normalizeForCompare(s: String): String = s.lowercase().replace(Regex("\\s+"), " ").trim()

// ==================== chatTurn ====================

/** Retrieval query: чистый вопрос + goal + fixed_terms + последние N user-реплик. */
fun buildRetrievalQuery(question: String, session: Session): String {
    val parts = mutableListOf<String>()
    parts += question
    if (session.state.goal.isNotBlank()) parts += "goal: ${session.state.goal}"
    if (session.state.fixedTerms.isNotEmpty()) {
        parts += "terms: " + session.state.fixedTerms.entries.take(10).joinToString(", ") { "${it.key}=${it.value}" }
    }
    val lastUserMsgs = session.messages.filter { it.role == "user" }.takeLast(RETRIEVAL_HISTORY_N)
    for (m in lastUserMsgs) parts += "prev: ${m.content.take(200)}"
    return parts.joinToString(". ")
}

fun retrieveForTurn(question: String, session: Session, idx: Index): Triple<List<Hit>, List<Hit>, List<String>> {
    val augmentedQ = buildRetrievalQuery(question, session)
    val rewrites = rewriteQuery(augmentedQ)
    val candidates = topKMulti(rewrites, idx, TOP_K_INITIAL)
    val afterThreshold = candidates.filter { it.score >= SIM_THRESHOLD }.ifEmpty { candidates.take(TOP_K_FINAL) }
    val reranked = rerankLLM(question, afterThreshold)
    val filtered = reranked.filter { it.second >= RERANK_THRESHOLD }
        .sortedByDescending { it.second }
        .ifEmpty { reranked.sortedByDescending { it.second }.take(TOP_K_FINAL) }
    var finalHits = filtered.take(TOP_K_FINAL).map { it.first }
    if (session.state.goal.isNotBlank() || session.state.fixedTerms.isNotEmpty() || session.state.constraints.isNotEmpty()) {
        val pseudo = Chunk(
            text = session.state.toPseudoChunk(),
            source = "task_state.session",
            chunkId = 0,
            embedding = FloatArray(0),
        )
        finalHits = finalHits + Hit(pseudo, 1.0f)
    }
    return Triple(finalHits, candidates, rewrites.drop(1))
}

private fun systemStrictChat(sources: List<Source>, allowed: List<AllowedQuote>, state: TaskState, history: List<ChatMessage>): String {
    val srcLines = sources.joinToString("\n") { "- ${it.id} → ${it.file} (chunk #${it.chunkId})" }
    val allowedLines = allowed.joinToString("\n") { "- [${it.srcId}#q${it.idx}] \"${it.text}\"" }
    val stateBlock = if (state.goal.isBlank() && state.constraints.isEmpty() && state.fixedTerms.isEmpty())
        "(память задачи пуста — предложи цель, если она понятна из вопроса)"
    else buildString {
        if (state.goal.isNotBlank()) append("goal: ").append(state.goal).append('\n')
        if (state.constraints.isNotEmpty()) append("constraints: ").append(state.constraints.joinToString("; ")).append('\n')
        if (state.clarifications.isNotEmpty()) append("clarifications: ").append(state.clarifications.takeLast(6).joinToString("; ")).append('\n')
        if (state.fixedTerms.isNotEmpty()) append("terms: ").append(state.fixedTerms.entries.joinToString(", ") { "${it.key}=${it.value}" }).append('\n')
        if (state.openQuestions.isNotEmpty()) append("open_questions: ").append(state.openQuestions.joinToString("; ")).append('\n')
    }
    val historyBlock = if (history.isEmpty()) "(это первое сообщение сессии)"
    else history.takeLast(6).joinToString("\n") {
        "${it.role}: ${it.content.take(250)}${if (it.content.length > 250) "…" else ""}"
    }
    return """Ты помощник по AI-челленджу Данила в формате МИНИ-ЧАТА. У тебя есть ПАМЯТЬ ЗАДАЧИ,
ИСТОРИЯ диалога и ИСТОЧНИКИ. Держи цель диалога — не теряй её на длинных сессиях.

ПАМЯТЬ ЗАДАЧИ (task_state):
$stateBlock

ПОСЛЕДНИЕ 6 СООБЩЕНИЙ:
$historyBlock

ИСТОЧНИКИ (source_id → файл):
$srcLines

ALLOWED_QUOTES (единственный список цитат — выбирай дословно):
$allowedLines

Правила:
1. Отвечай кратко, на русском, СТРОГО опираясь на источники и task_state.
2. Каждое ключевое утверждение подкрепи хотя бы одной цитатой из ALLOWED_QUOTES.
3. В тексте расставь inline-маркеры [CITATION:S1], [CITATION:S2] и т.п.
4. Обнови task_state_updates:
   - `goal` — если из вопроса стало ясно, что цель диалога другая, сформулируй новую.
   - `new_constraints` — новые ограничения.
   - `new_clarifications` — что юзер только что уточнил.
   - `new_terms` — термины в формате map "термин": "определение".
   - `new_open_questions` — то что осталось непонятным.
   - `closed_questions` — если ответил на что-то из старых open_questions.

Формат ответа СТРОГО JSON без markdown:
{
  "answer": "...с [CITATION:Sx] маркерами...",
  "sources": [{"id":"S1","file":"...","chunk_id":0}, ...],
  "citations": [{"src":"S1","quote":"дословно","supports":"утверждение"}, ...],
  "confidence": "high|medium|low",
  "abstained": false,
  "task_state_updates": {
    "goal": "или null",
    "new_constraints": [...],
    "new_clarifications": [...],
    "new_terms": {...},
    "new_open_questions": [...],
    "closed_questions": [...]
  }
}"""
}

fun parseStructuredChat(raw: String, allowed: List<AllowedQuote>, hits: List<Hit>, sources: List<Source>): StructuredAnswer {
    val allowedNorm = allowed.map { normalizeForCompare(it.text) }.toSet()
    val hitByChunkId = hits.associate { "${it.chunk.source}#${it.chunk.chunkId}" to it.chunk.text }
    return try {
        val obj = JsonParser.parseString(extractJsonObject(raw)).asJsonObject
        val answer = obj.get("answer")?.asString ?: ""
        val srcsArr = obj.getAsJsonArray("sources") ?: com.google.gson.JsonArray()
        val citArr = obj.getAsJsonArray("citations") ?: com.google.gson.JsonArray()
        val conf = obj.get("confidence")?.asString ?: "low"
        val abstained = obj.get("abstained")?.asBoolean ?: false
        val updatesObj = obj.getAsJsonObject("task_state_updates")

        val parsedSources = srcsArr.mapNotNull { el ->
            val o = el.asJsonObject
            val id = o.get("id")?.asString ?: return@mapNotNull null
            val file = o.get("file")?.asString ?: return@mapNotNull null
            val chunkId = o.get("chunk_id")?.asInt ?: return@mapNotNull null
            Source(id, file, chunkId)
        }.ifEmpty { sources }

        val parsedCitations = citArr.mapNotNull { el ->
            val o = el.asJsonObject
            val srcId = o.get("src")?.asString ?: return@mapNotNull null
            val quote = o.get("quote")?.asString?.trim() ?: return@mapNotNull null
            val supports = o.get("supports")?.asString?.trim()
            val inAllowed = normalizeForCompare(quote) in allowedNorm
            val srcObj = parsedSources.firstOrNull { it.id == srcId }
            val srcText = srcObj?.let { hitByChunkId["${it.file}#${it.chunkId}"] }
            val substrOk = srcText != null && normalizeForCompare(srcText).contains(normalizeForCompare(quote))
            Citation(srcId, quote, supports, validQuote = substrOk, inAllowed = inAllowed)
        }

        val updates = updatesObj?.let { parseStateUpdates(it) }
        StructuredAnswer(answer, parsedSources, parsedCitations, conf, abstained, updates, raw)
    } catch (e: Exception) {
        StructuredAnswer(
            answer = "⚠️ Модель вернула невалидный JSON: ${e.message}\n\nСырой ответ:\n$raw",
            sources = sources, citations = emptyList(), confidence = "low",
            abstained = false, stateUpdates = null, raw = raw,
        )
    }
}

private fun parseStateUpdates(obj: JsonObject): TaskStateUpdates {
    val goal = obj.get("goal")?.takeIf { !it.isJsonNull }?.asString
    val newC = obj.getAsJsonArray("new_constraints")?.map { it.asString } ?: emptyList()
    val newCl = obj.getAsJsonArray("new_clarifications")?.map { it.asString } ?: emptyList()
    val termsObj = obj.getAsJsonObject("new_terms")
    val newTerms = termsObj?.entrySet()?.associate { it.key to it.value.asString } ?: emptyMap()
    val newOQ = obj.getAsJsonArray("new_open_questions")?.map { it.asString } ?: emptyList()
    val closed = obj.getAsJsonArray("closed_questions")?.map { it.asString } ?: emptyList()
    return TaskStateUpdates(goal, newC, newCl, newTerms, newOQ, closed)
}

fun ensureCitationsFallback(sa: StructuredAnswer, hits: List<Hit>, allowed: List<AllowedQuote>): StructuredAnswer {
    if (sa.sources.isNotEmpty() && sa.citations.any { it.validQuote }) return sa
    val realHits = hits.filter { it.chunk.source != "task_state.session" }
    if (realHits.isEmpty()) return sa
    val top = realHits.first()
    val src = Source("S1", top.chunk.source, top.chunk.chunkId)
    val fallbackQuote = allowed.firstOrNull { it.srcId == "S1" }?.text
        ?: top.chunk.text.take(QUOTE_MAX_LEN)
    val citation = Citation("S1", fallbackQuote, "programmatic fallback (retry-loop не смог)",
        validQuote = true, inAllowed = allowed.any { normalizeForCompare(it.text) == normalizeForCompare(fallbackQuote) })
    return sa.copy(sources = listOf(src), citations = listOf(citation))
}

// ==================== Session persistence ====================

private val sessionsDir: File by lazy {
    File(System.getProperty("user.home"), ".ai-challenge/day25_sessions").also { it.mkdirs() }
}

fun generateSessionId(): String {
    val ts = System.currentTimeMillis().toString(36)
    val rnd = (0..999999).random().toString(36).padStart(4, '0')
    return "${ts}_${rnd}"
}

fun sessionDir(id: String): File = File(sessionsDir, id).also { it.mkdirs() }

fun newSession(id: String? = null): Session {
    val sid = id ?: generateSessionId()
    val now = Instant.now().toString()
    val s = Session(sid, now, now, 0)
    saveSession(s)
    return s
}

fun loadSession(id: String): Session? {
    val dir = sessionDir(id)
    if (!dir.exists()) return null
    val stateFile = File(dir, "state.json")
    val messagesFile = File(dir, "messages.jsonl")
    if (!stateFile.exists()) return null
    val meta = JsonParser.parseString(stateFile.readText(Charsets.UTF_8)).asJsonObject
    val messages = mutableListOf<ChatMessage>()
    if (messagesFile.exists()) {
        for (line in messagesFile.readLines(Charsets.UTF_8)) {
            if (line.isBlank()) continue
            val o = JsonParser.parseString(line).asJsonObject
            messages += ChatMessage(o.get("role").asString, o.get("content").asString,
                o.get("ts")?.asString ?: Instant.now().toString())
        }
    }
    val state = TaskState()
    val stateObj = meta.getAsJsonObject("state")
    if (stateObj != null) {
        state.goal = stateObj.get("goal")?.asString ?: ""
        stateObj.getAsJsonArray("constraints")?.forEach { state.constraints += it.asString }
        stateObj.getAsJsonArray("clarifications")?.forEach { state.clarifications += it.asString }
        stateObj.getAsJsonObject("fixed_terms")?.entrySet()?.forEach { state.fixedTerms[it.key] = it.value.asString }
        stateObj.getAsJsonArray("open_questions")?.forEach { state.openQuestions += it.asString }
    }
    return Session(id, meta.get("started_at").asString, meta.get("updated_at").asString,
        meta.get("question_count")?.asInt ?: messages.count { it.role == "user" }, messages, state)
}

fun saveSession(s: Session) {
    val stateFile = File(sessionDir(s.id), "state.json")
    val meta = mapOf(
        "id" to s.id, "started_at" to s.startedAt, "updated_at" to s.updatedAt,
        "question_count" to s.questionCount, "state" to s.state.snapshot(),
    )
    stateFile.writeText(gson.toJson(meta), Charsets.UTF_8)
}

fun appendMessage(s: Session, m: ChatMessage) {
    s.messages += m
    val messagesFile = File(sessionDir(s.id), "messages.jsonl")
    val line = gsonCompact.toJson(mapOf("role" to m.role, "content" to m.content, "ts" to m.ts))
    messagesFile.appendText(line + "\n", Charsets.UTF_8)
    s.updatedAt = Instant.now().toString()
    saveSession(s)
}

fun listSessions(): List<Session> = sessionsDir.listFiles { f -> f.isDirectory }
    ?.mapNotNull { d -> loadSession(d.name) }
    ?.sortedByDescending { it.updatedAt } ?: emptyList()

// ==================== chatTurn ====================

fun chatTurn(userMsg: String, session: Session, idx: Index): TurnResult {
    val t0 = System.currentTimeMillis()
    var calls = 0
    val stateBefore = session.state.snapshot()

    val (finalHits, _, _) = retrieveForTurn(userMsg, session, idx)
    calls += 2

    val realHitsForAllowed = finalHits.filter { it.chunk.source != "task_state.session" }
    val (sources0, allowed) = buildAllowedQuotes(realHitsForAllowed)

    val stateHit = finalHits.firstOrNull { it.chunk.source == "task_state.session" }
    val sources = if (stateHit != null)
        sources0 + Source("S${sources0.size + 1}", "task_state.session", 0)
    else sources0

    val sys = systemStrictChat(sources, allowed, session.state, session.messages)

    var structured: StructuredAnswer? = null
    var lastError: String? = null
    var retries = 0
    for (attempt in 0..MAX_RETRIES) {
        val userInput = if (attempt == 0) userMsg
        else "$userMsg\n\n[FEEDBACK] Прошлый ответ невалиден: $lastError. Исправь и верни JSON заново."
        val raw = try { calls++; deepseek(sys, userInput, temperature = 0.15) }
        catch (e: Exception) { lastError = "LLM error: ${e.message}"; break }
        val parsed = parseStructuredChat(raw, allowed, finalHits, sources)
        val invalidCit = parsed.citations.count { !it.inAllowed }
        if (invalidCit == 0 && parsed.sources.isNotEmpty()) { structured = parsed; break }
        else {
            lastError = "$invalidCit цитат не из ALLOWED_QUOTES, ${parsed.sources.size} источников"
            retries++
            if (attempt == MAX_RETRIES) { structured = parsed; break }
        }
    }

    val finalSA = structured?.let { ensureCitationsFallback(it, realHitsForAllowed, allowed) }
        ?: StructuredAnswer("⚠️ Не удалось получить ответ", emptyList(), emptyList(), "low", true, null, "")

    finalSA.stateUpdates?.let { session.state.apply(it) }

    appendMessage(session, ChatMessage("user", userMsg))
    appendMessage(session, ChatMessage("assistant", finalSA.answer))
    session.questionCount++
    saveSession(session)

    val stateAfter = session.state.snapshot()
    val diff = computeDiff(stateBefore, stateAfter)
    val validCount = finalSA.citations.count { it.validQuote }
    val totalCit = finalSA.citations.size

    return TurnResult(userMsg, finalSA.answer, finalSA, finalHits, allowed.size, validCount, totalCit,
        finalSA.abstained, diff, calls, System.currentTimeMillis() - t0, retries)
}

@Suppress("UNCHECKED_CAST")
fun computeDiff(before: Map<String, Any>, after: Map<String, Any>): StateDiff {
    val goalOld = (before["goal"] as? String) ?: ""
    val goalNew = (after["goal"] as? String) ?: ""
    val cOld = (before["constraints"] as? List<String>) ?: emptyList()
    val cNew = (after["constraints"] as? List<String>) ?: emptyList()
    val clOld = (before["clarifications"] as? List<String>) ?: emptyList()
    val clNew = (after["clarifications"] as? List<String>) ?: emptyList()
    val tOld = (before["fixed_terms"] as? Map<String, String>) ?: emptyMap()
    val tNew = (after["fixed_terms"] as? Map<String, String>) ?: emptyMap()
    val oqOld = (before["open_questions"] as? List<String>) ?: emptyList()
    val oqNew = (after["open_questions"] as? List<String>) ?: emptyList()
    return StateDiff(
        goalChanged = if (goalOld != goalNew) goalOld to goalNew else null,
        addedConstraints = cNew.filterNot { it in cOld },
        addedClarifications = clNew.filterNot { it in clOld },
        addedTerms = tNew.filterKeys { it !in tOld.keys || tOld[it] != tNew[it] },
        addedOpenQuestions = oqNew.filterNot { it in oqOld },
        closedQuestions = oqOld.filterNot { it in oqNew },
    )
}

// ==================== Replay ====================

data class Scenario(
    val name: String,
    val initialGoal: String? = null,
    val messages: List<String>,
    val finalCheckExpectedKeywords: List<String>? = null,
)

fun loadScenario(name: String): Scenario {
    val f = File("src/main/resources/scenarios/$name.json")
    require(f.exists()) { "Не нашёл сценарий: $f" }
    return gsonCompact.fromJson(f.readText(Charsets.UTF_8), Scenario::class.java)
}

fun listScenarios(): List<String> {
    val dir = File("src/main/resources/scenarios")
    if (!dir.exists()) return emptyList()
    return dir.listFiles { f -> f.extension == "json" }?.map { it.nameWithoutExtension } ?: emptyList()
}

data class ScenarioStats(
    val name: String, val totalTurns: Int,
    val sourcesRatio: Double, val citationsRatio: Double, val validQuoteRatio: Double,
    val abstainRate: Double, val goalRetained: Boolean,
    val finalKeywordsHit: Int, val finalKeywordsExpected: Int,
    val termsAccumulated: Int, val constraintsAccumulated: Int,
    val avgLatencyMs: Long, val p95LatencyMs: Long,
    val totalLLMCalls: Int,
)

fun runReplay(scenarioName: String, idx: Index, sessionId: String? = null): Pair<Session, ScenarioStats> {
    val scenario = loadScenario(scenarioName)
    val session = newSession(sessionId ?: "replay_${scenario.name}_${System.currentTimeMillis().toString(36)}")
    if (!scenario.initialGoal.isNullOrBlank()) session.state.goal = scenario.initialGoal
    saveSession(session)

    println("== Replay сценария: ${scenario.name} ==")
    println("initial goal: ${session.state.goal.ifBlank { "(не задан)" }}")
    println("сообщений: ${scenario.messages.size}\n")

    val latencies = mutableListOf<Long>()
    var sourcesCount = 0; var citationsCount = 0; var validQuoteSum = 0; var totalCitSum = 0
    var abstainCount = 0; var totalCalls = 0
    val initialGoal = session.state.goal

    for ((i, msg) in scenario.messages.withIndex()) {
        println("── Turn ${i + 1}/${scenario.messages.size}")
        println("USER: $msg")
        val t = chatTurn(msg, session, idx)
        println("ASSISTANT: ${t.answer.take(400)}${if (t.answer.length > 400) "…" else ""}")
        t.structured?.sources?.takeIf { it.isNotEmpty() }?.let {
            println("sources: " + it.joinToString(", ") { s -> "${s.id}→${s.file}#${s.chunkId}" })
        }
        renderStateDiff(t.stateDiff)?.let { println(it) }
        println("(valid=${t.validQuotesCount}/${t.totalCitations}, LLM=${t.llmCallsTotal}, lat=${t.latencyMs}ms, retries=${t.retryCount})\n")

        latencies += t.latencyMs
        if (t.structured?.sources?.isNotEmpty() == true) sourcesCount++
        if (t.structured?.citations?.isNotEmpty() == true) citationsCount++
        validQuoteSum += t.validQuotesCount
        totalCitSum += t.totalCitations
        if (t.abstained) abstainCount++
        totalCalls += t.llmCallsTotal
    }

    val goalRetained = if (initialGoal.isNotBlank()) checkGoalRetained(session.state.goal, initialGoal) else true
    val expectedKeywords = scenario.finalCheckExpectedKeywords ?: emptyList()
    val lastAssistantMsg = session.messages.lastOrNull { it.role == "assistant" }?.content ?: ""
    val finalHits = expectedKeywords.count { lastAssistantMsg.lowercase().contains(it.lowercase()) }

    val n = scenario.messages.size
    val avgLat = if (latencies.isNotEmpty()) latencies.sum() / latencies.size else 0
    val p95Lat = if (latencies.isNotEmpty()) latencies.sorted()[(latencies.size * 0.95).toInt().coerceAtMost(latencies.size - 1)] else 0

    return session to ScenarioStats(
        scenario.name, n,
        sourcesCount.toDouble() / n, citationsCount.toDouble() / n,
        if (totalCitSum > 0) validQuoteSum.toDouble() / totalCitSum else 0.0,
        abstainCount.toDouble() / n, goalRetained,
        finalHits, expectedKeywords.size,
        session.state.fixedTerms.size, session.state.constraints.size,
        avgLat, p95Lat, totalCalls,
    )
}

fun checkGoalRetained(finalGoal: String, initialGoal: String): Boolean {
    if (finalGoal.isBlank() || initialGoal.isBlank()) return false
    val prompt = """Изначальная цель диалога: "$initialGoal"
Текущая цель после реплик: "$finalGoal"

Изменилась ли цель по СМЫСЛУ (уже другая задача) или осталась той же (пусть переформулирована)?
Ответь СТРОГО одним словом: "same" (та же) или "drifted" (уплыла). Без markdown."""
    return try {
        val raw = deepseek("Ты эксперт-ревизор диалогов.", prompt, temperature = 0.1)
        raw.lowercase().contains("same")
    } catch (e: Exception) { true }
}

fun printScenarioStats(stats: ScenarioStats) {
    println("== Итог сценария ${stats.name} ==")
    println("Turns:              ${stats.totalTurns}")
    println("Sources ratio:      %.0f%%".format(stats.sourcesRatio * 100))
    println("Citations ratio:    %.0f%%".format(stats.citationsRatio * 100))
    println("Valid quote ratio:  %.0f%%".format(stats.validQuoteRatio * 100))
    println("Abstain rate:       %.0f%%".format(stats.abstainRate * 100))
    println("Goal retained:      ${if (stats.goalRetained) "✓" else "✗"}")
    if (stats.finalKeywordsExpected > 0) {
        println("Final keywords:     ${stats.finalKeywordsHit}/${stats.finalKeywordsExpected}")
    }
    println("Terms accumulated:  ${stats.termsAccumulated}")
    println("Constr accumulated: ${stats.constraintsAccumulated}")
    println("Avg latency:        ${stats.avgLatencyMs}ms")
    println("p95 latency:        ${stats.p95LatencyMs}ms")
    println("Total LLM calls:    ${stats.totalLLMCalls}")
}

// ==================== Рендерры ====================

fun renderStateDiff(d: StateDiff): String? {
    val parts = mutableListOf<String>()
    d.goalChanged?.let { (o, n) -> parts += "goal: ${if (o.isBlank()) "(∅)" else "«$o»"} → «$n»" }
    if (d.addedConstraints.isNotEmpty()) parts += "+ constraints: ${d.addedConstraints.joinToString(", ")}"
    if (d.addedClarifications.isNotEmpty()) parts += "+ clarifications: ${d.addedClarifications.joinToString(", ")}"
    if (d.addedTerms.isNotEmpty()) parts += "+ terms: ${d.addedTerms.entries.joinToString(", ") { "${it.key}=${it.value}" }}"
    if (d.addedOpenQuestions.isNotEmpty()) parts += "+ open_q: ${d.addedOpenQuestions.joinToString(", ")}"
    if (d.closedQuestions.isNotEmpty()) parts += "- closed: ${d.closedQuestions.joinToString(", ")}"
    if (parts.isEmpty()) return null
    return "STATE Δ: " + parts.joinToString(" | ")
}

fun renderFullState(s: TaskState): String = buildString {
    append("── TASK STATE ──\n")
    append("goal: ").append(s.goal.ifBlank { "(пустой)" }).append('\n')
    if (s.constraints.isNotEmpty()) append("constraints:\n").append(s.constraints.joinToString("\n") { "  - $it" }).append('\n')
    if (s.clarifications.isNotEmpty()) append("clarifications:\n").append(s.clarifications.joinToString("\n") { "  - $it" }).append('\n')
    if (s.fixedTerms.isNotEmpty()) { append("terms:\n"); for ((k, v) in s.fixedTerms) append("  - $k = $v\n") }
    if (s.openQuestions.isNotEmpty()) append("open_questions:\n").append(s.openQuestions.joinToString("\n") { "  - $it" }).append('\n')
}

// ==================== Build index ====================

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
            require(emb.size == dim)
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
  (без флагов)                   — интерактивный мини-чат (новая сессия)
  --build                        — построить индекс базы знаний
  --new                          — новая сессия (то же что без флагов)
  --session <id>                 — продолжить существующую сессию
  --list-sessions                — показать список сессий
  --replay <name>                — прогнать сценарий (src/main/resources/scenarios/<name>.json)
  --list-scenarios               — доступные сценарии
  --help                         — эта справка

Команды в REPL:
  :state    :history    :new    :session <id>
""")
}

fun runReplChat(session: Session, idx: Index) {
    println("\nСессия: ${session.id} (сообщений: ${session.messages.size}, реплик: ${session.questionCount})")
    if (session.state.goal.isNotBlank()) println("Текущая цель: ${session.state.goal}")
    println("Печатай вопрос. `:state` — вся память задачи, `:history` — последние сообщения, пустая / Ctrl+C — выход.\n")
    var currentSession = session
    while (true) {
        print("> ")
        System.out.flush()
        val line = readlnOrNull()?.trim() ?: break
        if (line.isEmpty()) break
        when {
            line == ":state" -> println(renderFullState(currentSession.state))
            line == ":history" -> {
                for (m in currentSession.messages.takeLast(10)) {
                    println("[${m.role}] ${m.content.take(300)}${if (m.content.length > 300) "…" else ""}")
                }
            }
            line == ":new" -> { currentSession = newSession(); println("Новая сессия: ${currentSession.id}") }
            line.startsWith(":session") -> {
                val id = line.removePrefix(":session").trim()
                val s = loadSession(id)
                if (s == null) println("Сессия не найдена: $id")
                else { currentSession = s; println("Переключился на $id (${s.messages.size} сообщений)") }
            }
            else -> {
                val t = try { chatTurn(line, currentSession, idx) } catch (e: Exception) {
                    println("ERR: ${e.message}"); continue
                }
                println()
                println(t.answer)
                t.structured?.sources?.takeIf { it.isNotEmpty() }?.let {
                    println("\nИсточники:")
                    for (s in it) println("  ${s.id} → ${s.file}#${s.chunkId}")
                    val validCits = t.structured.citations.filter { c -> c.validQuote }
                    if (validCits.isNotEmpty()) {
                        println("Валидные цитаты:")
                        for (c in validCits) println("  ✓ [${c.srcId}] \"${c.quote.take(120)}${if (c.quote.length > 120) "…" else ""}\"")
                    }
                }
                renderStateDiff(t.stateDiff)?.let { println(it) }
                println("(valid=${t.validQuotesCount}/${t.totalCitations}, LLM=${t.llmCallsTotal}, lat=${t.latencyMs}ms, retries=${t.retryCount})\n")
            }
        }
    }
    println("Пока.")
}

fun main(args: Array<String>) {
    val list = args.toList()
    when {
        "--help" in list -> printHelp()
        "--build" in list -> buildIndex()
        "--list-sessions" in list -> {
            val ss = listSessions()
            if (ss.isEmpty()) println("Сессий нет.")
            else for (s in ss) println("- ${s.id}  (${s.questionCount} реплик, updated ${s.updatedAt})")
        }
        "--list-scenarios" in list -> {
            val ss = listScenarios()
            if (ss.isEmpty()) println("Сценариев нет.")
            else for (s in ss) println("- $s")
        }
        "--replay" in list -> {
            val ai = list.indexOf("--replay")
            val name = list.getOrNull(ai + 1) ?: run { println("Нет имени сценария"); exitProcess(1) }
            val idx = ensureIndex()
            val (_, stats) = runReplay(name, idx)
            printScenarioStats(stats)
        }
        "--session" in list -> {
            val ai = list.indexOf("--session")
            val id = list.getOrNull(ai + 1) ?: run { println("Нет ID сессии"); exitProcess(1) }
            val s = loadSession(id) ?: run { println("Сессия не найдена: $id"); exitProcess(1) }
            val idx = ensureIndex()
            runReplChat(s, idx)
        }
        else -> {
            val idx = ensureIndex()
            val session = newSession()
            runReplChat(session, idx)
        }
    }
}
