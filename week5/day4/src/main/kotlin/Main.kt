/*
 * Day 24 (week5/day4). Цитаты, источники, анти-галлюцинации.
 *
 * Строим поверх дня 23 (rewrite + rerank + threshold = "full"-режим) три анти-галл. слоя:
 *   1) ALLOWED_QUOTES — программа детерминированно режет каждый чанк на кандидат-фразы 40-240
 *      символов и передаёт LLM списком. Модель ОБЯЗАНА выбирать `quote` из этого списка
 *      дословно — иначе она физически не может «сочинить» цитату.
 *   2) STRUCTURED OUTPUT — финальный ответ строгим JSON с sources[S1..SN] и citations[]:
 *      `{"answer":"...", "sources":[{"id":"S1","file":"...","chunk_id":2}], "citations":[{"src":"S1","quote":"..."}], "confidence":0.85, "abstained":false}`.
 *      Валидация: source_id уникален, каждая цитата ∈ ALLOWED_QUOTES + substring чанка.
 *   3) RETRY-LOOP — если валидация упала, шлём тот же вопрос ещё раз с обратной связью
 *      «эти цитаты — не из ALLOWED_QUOTES: …», до 2 повторов.
 *
 * Плюс два опциональных этапа (флаг --anti full):
 *   4) SOFT-ABSTAIN — при max rerank-score < ABSTAIN_THRESHOLD НЕ говорим «не знаю»,
 *      а честно помечаем: «В моей базе нет данных, отвечу из общего знания:» + обычный LLM-ответ.
 *      Это лучше жёсткого abstain (по dpmn): пользователь получает пользу, но помечено как менее надёжное.
 *   5) GROUNDED-JUDGE — второй LLM-вызов «проверь, каждое утверждение answer подкреплено какой-то
 *      цитатой из sources?». Дорогой, поэтому вызываем только при confidence != "high".
 *
 * --compare гоняет 3 anti-режима (off / strict / full-anti) на 10 вопросах и печатает матрицу:
 *   has_sources, has_quotes, valid_quotes, grounded (LLM-judge), abstain_rate, false_abstain,
 *   R@3 / MRR / Grounded (keywords) / LLM calls / latency.
 *
 * Retrieve-стек фиксирован = day23-full (rewrite + rerank + threshold).
 * Эмбеддинги: локальная Ollama (nomic-embed-text, 768d, task-префиксы). LLM: DeepSeek chat.
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

/** Anti-hallucination режимы для дня 24. */
enum class AntiMode(val cli: String, val label: String) {
    OFF("off", "off (как день 23 full — свободный ответ без цитат)"),
    STRICT("strict", "strict (structured JSON + ALLOWED_QUOTES + retry-loop, без judge/abstain)"),
    FULL("full-anti", "full-anti (strict + grounded-judge + soft-abstain)");

    companion object {
        fun parse(s: String): AntiMode? = entries.firstOrNull { it.cli.equals(s, ignoreCase = true) }
    }
}

/** Отдельный источник, на который ссылается ответ. */
data class Source(
    val id: String,           // "S1", "S2", ...
    val file: String,         // "rag-week5.md"
    val chunkId: Int,         // порядковый номер в файле
)

/** Цитата: ссылка на source_id + текст цитаты. */
data class Citation(
    val srcId: String,        // "S1"
    val quote: String,        // точный фрагмент из чанка
    val supports: String? = null, // какое утверждение подкрепляет (опционально)
    val validQuote: Boolean = false, // подтверждена ли substring-валидацией
    val inAllowed: Boolean = false,  // была ли в ALLOWED_QUOTES
)

/** Структурированный ответ от финального LLM-вызова. */
data class StructuredAnswer(
    val answer: String,
    val sources: List<Source>,
    val citations: List<Citation>,
    val confidence: String,   // "high" / "medium" / "low"
    val abstained: Boolean,
    val raw: String,          // сырой JSON от модели (для диагностики)
)

/** Результат grounded-судьи (второй LLM-проход). */
data class GroundedVerdict(
    val grounded: Float,      // 0..1
    val unsupportedClaims: List<String>,
)

/** Всё, что делает runRag за один вопрос. Богатая телеметрия для видимости и метрик. */
data class RagResult(
    val question: String,
    val antiMode: AntiMode,
    val answer: String,
    val structured: StructuredAnswer?,   // null в режиме OFF
    val finalHits: List<Hit>,
    val candidatesBefore: List<Hit>,
    val rewrites: List<String>,
    val allowedQuotesCount: Int,
    val validQuotesCount: Int,
    val totalCitations: Int,
    val abstained: Boolean,              // soft-abstain сработал
    val abstainReason: String?,
    val grounded: GroundedVerdict?,      // null если judge не вызывался
    val llmCallsRewrite: Int,
    val llmCallsRerank: Int,
    val llmCallsFinal: Int,              // включая retry
    val llmCallsJudge: Int,
    val llmCallsAbstain: Int,            // fallback + возможно предложение наводящих вопросов
    val latencyMs: Long,
    val retryCount: Int,
) {
    val llmCallsTotal: Int get() = llmCallsRewrite + llmCallsRerank + llmCallsFinal + llmCallsJudge + llmCallsAbstain
}

// ==================== Константы стратегии ====================

/** Ретрив (день 23). */
private const val TOP_K_INITIAL = 10
private const val TOP_K_FINAL = 3
private const val SIM_THRESHOLD = 0.28f
private const val RERANK_THRESHOLD = 0.45f
private const val REWRITE_N = 3
private const val RERANK_CHUNK_LIMIT = 800

/** Anti-hallucination. */
private const val ABSTAIN_THRESHOLD = 0.5f          // max rerank-score ниже → soft-abstain
private const val QUOTE_MIN_LEN = 40
private const val QUOTE_MAX_LEN = 240
private const val MAX_RETRIES = 2                   // повторов финального вызова при invalid quotes
private const val ALLOWED_PER_CHUNK = 4             // сколько цитат-кандидатов режем из каждого чанка

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
    File(System.getProperty("user.home"), ".ai-challenge/day24_index.json")
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
        ?: error("DEEPSEEK_API_KEY не найден. Проверь .env в корне проекта.")
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

// ==================== JSON парсеры/экстракторы ====================

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

// ==================== Query rewrite (день 23) ====================

private const val REWRITE_SYSTEM = """Ты эксперт по переформулированию поисковых запросов
для базы знаний AI-челленджа Данила (Kotlin, RAG, MCP, DeepSeek, Ollama, Windows).
Дан оригинальный вопрос. Верни РОВНО 3 переформулировки:
1) Rephrasing — тот же смысл другими словами;
2) Conceptual Expansion — переформулировка с добавлением ключевых терминов/синонимов;
3) Search Engine Style — короткая формулировка как для поисковика (2-6 ключевых слов).

Формат ответа СТРОГО JSON без markdown-блоков:
{"rewritten_queries":["...","...","..."]}"""

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

// ==================== LLM-реранкер (день 23) ====================

private const val RERANK_SYSTEM = """Ты эксперт-ассистент по оценке релевантности.
Дан ВОПРОС и список ФРАГМЕНТОВ базы знаний (пронумерованных). Оцени, насколько каждый фрагмент
полезен для ответа на вопрос. Верни СТРОГО JSON — плоский массив чисел 0.0..1.0 в том же порядке,
что и фрагменты. Длина массива обязана совпадать с количеством фрагментов.

Пример вывода: [0.9, 0.35, 0.1, 0.7, 0.0]
БЕЗ markdown, без комментариев, без пояснений — только массив."""

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
    val jsonStr = extractJsonArray(raw)
    val parsed = try {
        val arr = JsonParser.parseString(jsonStr).asJsonArray
        arr.map { it.asFloat }
    } catch (e: Exception) {
        Regex("[-+]?[0-9]*\\.?[0-9]+").findAll(raw).mapNotNull { it.value.toFloatOrNull() }.toList()
    }
    for (i in 0 until minOf(parsed.size, expected)) {
        out[i] = parsed[i].coerceIn(0f, 1f)
    }
    return out
}

// ==================== ALLOWED_QUOTES — детерминированная нарезка чанков ====================

/** Режем чанк на кандидат-цитаты 40-240 символов по предложениям.
 *  Возвращаем до N лучших (по длине — предпочитаем в середине диапазона). */
fun sliceCandidateQuotes(text: String, maxCandidates: Int = ALLOWED_PER_CHUNK): List<String> {
    // Разбиение по предложениям (грубое, по [.!?] с сохранением пунктуации)
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

    // Склеиваем короткие соседние предложения в кандидаты 40..240 символов
    val candidates = mutableListOf<String>()
    var buf = StringBuilder()
    for (s in sentences) {
        if (buf.isEmpty()) {
            if (s.length in QUOTE_MIN_LEN..QUOTE_MAX_LEN) {
                candidates += s
            } else if (s.length < QUOTE_MIN_LEN) {
                buf.append(s)
            } else {
                // слишком длинное — режем окном 200 символов на границе слова
                candidates += cutToLen(s, QUOTE_MAX_LEN)
            }
        } else {
            val next = if (buf.last() == ' ') "$buf$s" else "$buf $s"
            if (next.length in QUOTE_MIN_LEN..QUOTE_MAX_LEN) {
                candidates += next
                buf = StringBuilder()
            } else if (next.length < QUOTE_MIN_LEN) {
                buf = StringBuilder(next)
            } else {
                candidates += cutToLen(buf.toString(), QUOTE_MAX_LEN)
                buf = StringBuilder(s.take(QUOTE_MAX_LEN))
            }
        }
    }
    if (buf.length >= QUOTE_MIN_LEN) candidates += buf.toString()

    // Дедупликация + сортировка (предпочтение середине диапазона)
    val seen = HashSet<String>()
    return candidates
        .distinctBy { it.take(50) }  // грубая дедупликация
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

/** Собираем «ALLOWED_QUOTES» — map: (S1, q0..qN, quote). */
data class AllowedQuote(val srcId: String, val idx: Int, val text: String)

fun buildAllowedQuotes(hits: List<Hit>): Pair<List<Source>, List<AllowedQuote>> {
    val sources = hits.mapIndexed { i, h -> Source("S${i + 1}", h.chunk.source, h.chunk.chunkId) }
    val allowed = mutableListOf<AllowedQuote>()
    for ((i, h) in hits.withIndex()) {
        val srcId = "S${i + 1}"
        val cands = sliceCandidateQuotes(h.chunk.text)
        for ((qi, q) in cands.withIndex()) {
            allowed += AllowedQuote(srcId, qi, q)
        }
    }
    return sources to allowed
}

// ==================== Основной агент ====================

private const val SYSTEM_NORAG =
    "Ты помощник по AI-челленджу Данила. Отвечай кратко и по делу на русском."

/** Промпт для режима OFF (свободный ответ, как в дне 23). */
private fun systemFreeAnswer(context: String) =
    """Ты помощник по AI-челленджу Данила. Отвечай на вопрос СТРОГО опираясь на КОНТЕКСТ ниже,
своих знаний не выдумывай. Если ответа в контексте нет — так и скажи. Отвечай кратко, на русском.

КОНТЕКСТ:
$context""".trim()

/** Промпт для строгого JSON-режима с обязательным цитированием (STRICT / FULL). */
private fun systemStrictAnswer(sources: List<Source>, allowed: List<AllowedQuote>): String {
    val srcLines = sources.joinToString("\n") { "- ${it.id} → ${it.file} (chunk #${it.chunkId})" }
    val allowedLines = allowed.joinToString("\n") { "- [${it.srcId}#q${it.idx}] \"${it.text}\"" }
    return """Ты помощник по AI-челленджу Данила. Отвечай на вопрос СТРОГО опираясь на ИСТОЧНИКИ и
разрешённые цитаты (ALLOWED_QUOTES) ниже. Верни ответ в формате JSON и обязательно опирайся на
цитаты, ссылаясь по source_id.

ИСТОЧНИКИ (source_id → файл):
$srcLines

ALLOWED_QUOTES — единственный список, из которого можно брать цитаты (выбирай ДОСЛОВНО):
$allowedLines

Правила:
1. Каждое ключевое утверждение в ответе подкрепи как минимум одной цитатой.
2. Цитата — ТОЛЬКО дословный текст из ALLOWED_QUOTES, никаких новых цитат не сочинять.
3. В теле ответа расставь inline-маркеры [CITATION:S1], [CITATION:S2] и т.п. напротив утверждений.
4. Если контекста для ответа недостаточно — верни `"abstained": true` и пустые sources/citations.
5. `confidence` — твоя субъективная оценка ответа: "high" / "medium" / "low".

Формат ответа СТРОГО JSON, без markdown-блоков:
{
  "answer": "...текст с [CITATION:Sx] маркерами...",
  "sources": [{"id":"S1","file":"...","chunk_id":0}, ...],
  "citations": [{"src":"S1","quote":"дословно из ALLOWED_QUOTES","supports":"какое утверждение"}, ...],
  "confidence": "high|medium|low",
  "abstained": false
}"""
}

/** LLM-судья: проверяет, каждое ли утверждение answer подкреплено какой-либо цитатой. */
private const val JUDGE_SYSTEM = """Ты эксперт-ревизор ответов RAG-агента. Дан ОТВЕТ модели и
список CITATIONS. Проверь: каждое ли фактическое утверждение в ответе подкреплено какой-нибудь
цитатой из списка? Верни JSON:
{"grounded": 0.0..1.0, "unsupported_claims": ["утверждение1", ...]}
0.0 = ни одно, 1.0 = все. Без markdown."""

fun askNoRag(question: String): String = deepseek(SYSTEM_NORAG, question)

/** Ретрив-стек дня 23 в режиме "full" — вход для всех anti-режимов. */
fun retrieveFull(question: String, idx: Index): Triple<List<Hit>, List<Hit>, List<String>> {
    // rewrite → union → threshold → rerank → top-3
    val rewrites = rewriteQuery(question)
    val candidates = topKMulti(rewrites, idx, TOP_K_INITIAL)
    val afterThreshold = candidates.filter { it.score >= SIM_THRESHOLD }
        .ifEmpty { candidates.take(TOP_K_FINAL) }
    val reranked = rerankLLM(question, afterThreshold)
    val filtered = reranked.filter { it.second >= RERANK_THRESHOLD }
        .sortedByDescending { it.second }
        .ifEmpty { reranked.sortedByDescending { it.second }.take(TOP_K_FINAL) }
    val finalHits = filtered.take(TOP_K_FINAL).map { it.first }
    return Triple(finalHits, candidates, rewrites.drop(1))
}

/** Основная точка входа. */
fun runRag(question: String, idx: Index, antiMode: AntiMode): RagResult {
    val t0 = System.currentTimeMillis()
    var callsRewrite = 1
    var callsRerank = 1
    var callsFinal = 0
    var callsJudge = 0
    var callsAbstain = 0

    val (finalHits, candidates, rewrites) = retrieveFull(question, idx)
    val maxScore = finalHits.maxOfOrNull { it.score } ?: 0f

    // Soft-abstain: только в FULL-anti режиме, при слабом контексте.
    if (antiMode == AntiMode.FULL && (finalHits.isEmpty() || maxScore < ABSTAIN_THRESHOLD)) {
        val fallback = try {
            callsAbstain = 1
            deepseek(SYSTEM_NORAG, question)
        } catch (e: Exception) { "ERR: ${e.message}" }
        val text = "В моей базе нет данных по этому вопросу (max score=${"%.2f".format(maxScore)} < ${ABSTAIN_THRESHOLD}). " +
            "Отвечу из общего знания (может быть неточно):\n\n$fallback"
        return RagResult(
            question = question, antiMode = antiMode, answer = text, structured = null,
            finalHits = finalHits, candidatesBefore = candidates, rewrites = rewrites,
            allowedQuotesCount = 0, validQuotesCount = 0, totalCitations = 0,
            abstained = true, abstainReason = "max score $maxScore < $ABSTAIN_THRESHOLD",
            grounded = null,
            llmCallsRewrite = callsRewrite, llmCallsRerank = callsRerank, llmCallsFinal = 0,
            llmCallsJudge = 0, llmCallsAbstain = callsAbstain,
            latencyMs = System.currentTimeMillis() - t0,
            retryCount = 0,
        )
    }

    when (antiMode) {
        AntiMode.OFF -> {
            // День 23 full-стиль: свободный ответ без обязательных цитат.
            val ctx = finalHits.joinToString("\n\n---\n\n") {
                "[источник: ${it.chunk.source}, cos=${"%.3f".format(it.score)}]\n${it.chunk.text}"
            }
            val answer = if (finalHits.isEmpty()) "В моей базе нет данных."
            else deepseek(systemFreeAnswer(ctx), question).also { callsFinal = 1 }
            return RagResult(
                question = question, antiMode = antiMode, answer = answer, structured = null,
                finalHits = finalHits, candidatesBefore = candidates, rewrites = rewrites,
                allowedQuotesCount = 0, validQuotesCount = 0, totalCitations = 0,
                abstained = finalHits.isEmpty(), abstainReason = null, grounded = null,
                llmCallsRewrite = callsRewrite, llmCallsRerank = callsRerank, llmCallsFinal = callsFinal,
                llmCallsJudge = 0, llmCallsAbstain = 0,
                latencyMs = System.currentTimeMillis() - t0,
                retryCount = 0,
            )
        }
        AntiMode.STRICT, AntiMode.FULL -> {
            // Структурированный JSON + ALLOWED_QUOTES + retry-loop.
            val (sources, allowed) = buildAllowedQuotes(finalHits)
            val sys = systemStrictAnswer(sources, allowed)

            var structured: StructuredAnswer? = null
            var lastError: String? = null
            var retries = 0
            for (attempt in 0..MAX_RETRIES) {
                val userMsg = if (attempt == 0) question
                else "$question\n\n[FEEDBACK] Прошлый ответ невалиден: $lastError. Исправь и верни JSON заново."
                val raw = try {
                    callsFinal++
                    deepseek(sys, userMsg, temperature = 0.15)
                } catch (e: Exception) {
                    lastError = "LLM error: ${e.message}"; break
                }
                val parsed = parseStructuredAnswer(raw, allowed, finalHits, sources)
                val invalidCit = parsed.citations.count { !it.inAllowed }
                if (invalidCit == 0 && parsed.sources.isNotEmpty()) {
                    structured = parsed; break
                } else {
                    lastError = "$invalidCit цитат не из ALLOWED_QUOTES, ${parsed.sources.size} источников"
                    retries++
                    if (attempt == MAX_RETRIES) { structured = parsed; break }
                }
            }

            // Grounded-judge только в FULL и только если confidence != high.
            var grounded: GroundedVerdict? = null
            if (antiMode == AntiMode.FULL && structured != null && structured.confidence != "high") {
                grounded = runGroundedJudge(structured).also { callsJudge = 1 }
            }

            val validCount = structured?.citations?.count { it.validQuote } ?: 0
            val totalCit = structured?.citations?.size ?: 0
            val displayText = renderStructured(structured)
            return RagResult(
                question = question, antiMode = antiMode, answer = displayText, structured = structured,
                finalHits = finalHits, candidatesBefore = candidates, rewrites = rewrites,
                allowedQuotesCount = allowed.size, validQuotesCount = validCount, totalCitations = totalCit,
                abstained = structured?.abstained ?: false, abstainReason = null, grounded = grounded,
                llmCallsRewrite = callsRewrite, llmCallsRerank = callsRerank, llmCallsFinal = callsFinal,
                llmCallsJudge = callsJudge, llmCallsAbstain = 0,
                latencyMs = System.currentTimeMillis() - t0,
                retryCount = retries,
            )
        }
    }
}

/** Парсит JSON-ответ модели в StructuredAnswer, валидирует цитаты. */
fun parseStructuredAnswer(
    raw: String,
    allowed: List<AllowedQuote>,
    hits: List<Hit>,
    sources: List<Source>,
): StructuredAnswer {
    val allowedTexts = allowed.map { it.text.trim() }.toSet()
    val allowedNorm = allowed.map { normalizeForCompare(it.text) }.toSet()
    val hitByChunkId = hits.associate { "${it.chunk.source}#${it.chunk.chunkId}" to it.chunk.text }

    return try {
        val obj = JsonParser.parseString(extractJsonObject(raw)).asJsonObject
        val answer = obj.get("answer")?.asString ?: ""
        val srcsArr = obj.getAsJsonArray("sources") ?: com.google.gson.JsonArray()
        val citArr = obj.getAsJsonArray("citations") ?: com.google.gson.JsonArray()
        val conf = obj.get("confidence")?.asString ?: "low"
        val abstained = obj.get("abstained")?.asBoolean ?: false

        // Sources — предпочитаем те, что мы предоставили; ужимаем модельные до наших.
        val parsedSources = srcsArr.mapNotNull { el ->
            val o = el.asJsonObject
            val id = o.get("id")?.asString ?: return@mapNotNull null
            val file = o.get("file")?.asString ?: return@mapNotNull null
            val chunkId = o.get("chunk_id")?.asInt ?: return@mapNotNull null
            Source(id, file, chunkId)
        }.ifEmpty { sources }

        // Citations — валидируем каждую.
        val parsedCitations = citArr.mapNotNull { el ->
            val o = el.asJsonObject
            val srcId = o.get("src")?.asString ?: return@mapNotNull null
            val quote = o.get("quote")?.asString?.trim() ?: return@mapNotNull null
            val supports = o.get("supports")?.asString?.trim()
            val inAllowed = quote in allowedTexts || normalizeForCompare(quote) in allowedNorm
            val srcObj = parsedSources.firstOrNull { it.id == srcId }
            val srcText = srcObj?.let { hitByChunkId["${it.file}#${it.chunkId}"] }
            val substrOk = srcText != null && normalizeForCompare(srcText).contains(normalizeForCompare(quote))
            Citation(srcId, quote, supports, validQuote = substrOk, inAllowed = inAllowed)
        }

        StructuredAnswer(answer, parsedSources, parsedCitations, conf, abstained, raw)
    } catch (e: Exception) {
        StructuredAnswer(
            answer = "⚠️ Модель вернула невалидный JSON: ${e.message}\n\nСырой ответ:\n$raw",
            sources = sources,
            citations = emptyList(),
            confidence = "low",
            abstained = false,
            raw = raw,
        )
    }
}

/** Нормализуем пробелы + приводим к нижнему регистру для устойчивого сравнения цитат. */
fun normalizeForCompare(s: String): String =
    s.lowercase().replace(Regex("\\s+"), " ").trim()

/** Второй проход: LLM-судья, подкреплены ли утверждения ответа. */
fun runGroundedJudge(sa: StructuredAnswer): GroundedVerdict {
    val user = buildString {
        append("ОТВЕТ:\n").append(sa.answer).append("\n\nCITATIONS:\n")
        for (c in sa.citations) append("- [${c.srcId}] \"${c.quote}\"\n")
    }
    val raw = try { deepseek(JUDGE_SYSTEM, user, temperature = 0.1) }
    catch (e: Exception) { return GroundedVerdict(0.5f, listOf("judge error: ${e.message}")) }
    return try {
        val obj = JsonParser.parseString(extractJsonObject(raw)).asJsonObject
        val g = obj.get("grounded")?.asFloat ?: 0.5f
        val unsupported = obj.getAsJsonArray("unsupported_claims")
            ?.map { it.asString } ?: emptyList()
        GroundedVerdict(g.coerceIn(0f, 1f), unsupported)
    } catch (e: Exception) {
        GroundedVerdict(0.5f, listOf("parse error: ${e.message}"))
    }
}

/** Красивое отображение StructuredAnswer в консоли/REPL. */
fun renderStructured(sa: StructuredAnswer?): String {
    if (sa == null) return "(empty)"
    val out = StringBuilder()
    out.append(sa.answer).append("\n\n")
    out.append("Источники:\n")
    for (s in sa.sources) out.append("  ").append(s.id).append(" → ").append(s.file)
        .append(" (chunk #").append(s.chunkId).append(")\n")
    if (sa.citations.isNotEmpty()) {
        out.append("\nЦитаты:\n")
        for (c in sa.citations) {
            val icon = when {
                c.inAllowed && c.validQuote -> "✓"
                c.validQuote -> "≈"  // substring есть, но не из ALLOWED
                else -> "⚠"
            }
            out.append("  ").append(icon).append(" [").append(c.srcId).append("] \"")
                .append(c.quote.take(120)).append(if (c.quote.length > 120) "…" else "").append("\"")
            if (c.supports != null) out.append(" — ").append(c.supports.take(80))
            out.append("\n")
        }
    }
    out.append("\nConfidence: ").append(sa.confidence)
    if (sa.abstained) out.append(" (abstained)")
    return out.toString()
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

fun reciprocalRank(hits: List<Hit>, expectedSource: String): Double {
    val idx = hits.indexOfFirst { it.chunk.source == expectedSource }
    return if (idx < 0) 0.0 else 1.0 / (idx + 1)
}

data class AntiStats(
    val mode: AntiMode,
    var retrievalAt3: Int = 0,
    var mrrSum: Double = 0.0,
    var hasSourcesN: Int = 0,
    var hasQuotesN: Int = 0,
    var validQuoteSum: Int = 0,
    var totalQuoteSum: Int = 0,
    var groundedSum: Double = 0.0,
    var groundedCount: Int = 0,
    var abstainN: Int = 0,
    var falseAbstainN: Int = 0,
    var keywordsGoodN: Int = 0,
    var retriesSum: Int = 0,
    var llmCallsSum: Int = 0,
    var latencySumMs: Long = 0,
    var errors: Int = 0,
)

fun runCompare(idx: Index, modes: List<AntiMode> = AntiMode.entries.toList()) {
    val questions = loadQuestions()
    val stats = modes.associateWith { AntiStats(it) }.toMutableMap()

    println("== Прогон ${questions.size} контрольных вопросов × ${modes.size} anti-режимов ==\n")

    for ((qi, q) in questions.withIndex()) {
        println("Q${qi + 1}: ${q.q}")
        println("  ожидаемый источник: ${q.expected_source}")

        for (m in modes) {
            val res = try { runRag(q.q, idx, m) }
            catch (e: Exception) {
                stats[m]!!.errors++
                println("  [${m.cli}] ERR: ${e.message}")
                continue
            }
            val topSources3 = res.finalHits.map { it.chunk.source }
            val ok3 = q.expected_source in topSources3
            val rr = reciprocalRank(res.finalHits, q.expected_source)
            val kHits = keywordsHit(res.answer, q.expected_keywords)
            val kGood = kHits >= (q.expected_keywords.size + 1) / 2
            val hasSources = res.structured?.sources?.isNotEmpty() == true
            val hasQuotes = res.structured?.citations?.isNotEmpty() == true
            val validQ = res.validQuotesCount
            val totalQ = res.totalCitations
            val abstained = res.abstained
            // false-abstain: ожидаемый источник ЕСТЬ в кандидатах, а мы всё равно abstain'нули
            val expectedInHits = ok3
            val falseAbstain = abstained && expectedInHits

            val s = stats[m]!!
            if (ok3) s.retrievalAt3++
            s.mrrSum += rr
            if (hasSources) s.hasSourcesN++
            if (hasQuotes) s.hasQuotesN++
            s.validQuoteSum += validQ
            s.totalQuoteSum += totalQ
            if (res.grounded != null) {
                s.groundedSum += res.grounded.grounded
                s.groundedCount++
            }
            if (abstained) s.abstainN++
            if (falseAbstain) s.falseAbstainN++
            if (kGood) s.keywordsGoodN++
            s.retriesSum += res.retryCount
            s.llmCallsSum += res.llmCallsTotal
            s.latencySumMs += res.latencyMs

            println("  [${m.cli.padEnd(9)}] R@3=${if (ok3) "✓" else "✗"} MRR=${"%.2f".format(rr)} " +
                "sources=${if (hasSources) "✓" else "✗"} quotes=${if (hasQuotes) "✓" else "✗"} " +
                "valid=$validQ/$totalQ grnd=${res.grounded?.let { "%.2f".format(it.grounded) } ?: "-"} " +
                "abst=${if (abstained) "✓" else "✗"} kw=$kHits/${q.expected_keywords.size} " +
                "calls=${res.llmCallsTotal} lat=${res.latencyMs}ms retries=${res.retryCount}")
        }
        println()
    }

    val n = questions.size
    println("== Итог по anti-режимам (n=$n) ==")
    println("Режим       | R@3    | MRR   | has_src | has_q | valid_q       | grounded | abstain | false_ab | kw       | LLM/q | avg lat")
    println("------------|--------|-------|---------|-------|---------------|----------|---------|----------|----------|-------|--------")
    for (m in modes) {
        val s = stats[m]!!
        val r3 = "${s.retrievalAt3}/$n"
        val mrr = "%.2f".format(s.mrrSum / n)
        val hs = if (m == AntiMode.OFF) "-      " else "${s.hasSourcesN}/$n".padEnd(7)
        val hq = if (m == AntiMode.OFF) "-    " else "${s.hasQuotesN}/$n".padEnd(5)
        val vq = if (m == AntiMode.OFF) "-            " else "${s.validQuoteSum}/${s.totalQuoteSum}".padEnd(13)
        val grn = if (s.groundedCount > 0) "%.2f".format(s.groundedSum / s.groundedCount).padEnd(8)
                  else "-       "
        val ab = "${s.abstainN}/$n".padEnd(7)
        val fab = "${s.falseAbstainN}/$n".padEnd(8)
        val kw = "${s.keywordsGoodN}/$n".padEnd(8)
        val calls = "%.1f".format(s.llmCallsSum.toDouble() / n).padEnd(5)
        val lat = "${s.latencySumMs / n}ms"
        println("${m.cli.padEnd(11)} | ${r3.padEnd(6)} | $mrr  | $hs | $hq | $vq | $grn | $ab | $fab | $kw | $calls | $lat")
    }
    println("\nЛегенда:")
    println("  R@3 — попал ли ожидаемый источник в финальный топ-3 контекста")
    println("  MRR — средний Reciprocal Rank (1/позиция)")
    println("  has_src / has_q — доля ответов, где есть хоть один источник / цитата")
    println("  valid_q — цитат прошло substring-валидацию / всего")
    println("  grounded — среднее по LLM-судье (0..1), только в FULL при confidence!=high")
    println("  abstain — сколько раз soft-abstain (только FULL, при слабом контексте)")
    println("  false_ab — abstain при том что нужный источник в KB реально был (плохо)")
    println("  kw — доля ответов, где ≥ половины ожидаемых ключевых слов")
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
  (без флагов)                             — интерактивный REPL, режим по умолчанию `full-anti`
  --build                                  — построить индекс (эмбеддинги через Ollama)
  --ask "вопрос"                           — one-shot, режим `full-anti`
  --ask "вопрос" --anti <режим>            — one-shot с выбором anti-режима
  --compare                                — прогон 10 контрольных × 3 anti-режима + метрики
  --help                                   — эта справка

Anti-режимы (--anti):
  off        — свободный ответ (как день 23 full), без обязательных цитат
  strict     — обязательный JSON + ALLOWED_QUOTES + retry-loop, без judge/abstain
  full-anti  — strict + grounded-judge + soft-abstain
""")
}

fun runRepl() {
    val idx = ensureIndex()
    var mode = AntiMode.FULL
    println("\nИнтерактивный RAG день 24 (anti-hallucination).")
    println("Режим по умолчанию: ${mode.label}")
    println("Смена режима:  :anti off|strict|full-anti")
    println("Пустая строка / Ctrl+C — выход.\n")
    while (true) {
        print("[${mode.cli}] > ")
        System.out.flush()
        val line = readlnOrNull()?.trim() ?: break
        if (line.isEmpty()) break
        if (line.startsWith(":anti")) {
            val parts = line.split(Regex("\\s+"))
            val newMode = if (parts.size >= 2) AntiMode.parse(parts[1]) else null
            if (newMode == null) println("Неизвестный режим. Доступно: ${AntiMode.entries.joinToString("/") { it.cli }}")
            else { mode = newMode; println("→ ${mode.label}") }
            continue
        }
        val res = try { runRag(line, idx, mode) } catch (e: Exception) {
            println("ERR: ${e.message}"); continue
        }
        if (res.rewrites.isNotEmpty()) {
            println("\nRewrite-варианты (retrieve stack):")
            for ((i, r) in res.rewrites.withIndex()) println("  ${i + 1}. $r")
        }
        println("\n--- ОТВЕТ ---")
        println(res.answer)
        println()
        if (res.grounded != null) {
            println("Grounded-judge: ${"%.2f".format(res.grounded.grounded)}")
            if (res.grounded.unsupportedClaims.isNotEmpty()) {
                println("Утверждения без цитаты:")
                for (u in res.grounded.unsupportedClaims) println("  ⚠ $u")
            }
        }
        println("ALLOWED_QUOTES=${res.allowedQuotesCount}, valid=${res.validQuotesCount}/${res.totalCitations}, retries=${res.retryCount}")
        println("LLM calls: rewrite=${res.llmCallsRewrite} rerank=${res.llmCallsRerank} " +
            "final=${res.llmCallsFinal} judge=${res.llmCallsJudge} abstain=${res.llmCallsAbstain} " +
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
            val modes = modeArg?.split(',')?.mapNotNull { AntiMode.parse(it.trim()) }?.ifEmpty { null }
                ?: AntiMode.entries
            runCompare(idx, modes)
        }
        "--ask" in list -> {
            val ai = list.indexOf("--ask")
            val q = list.getOrNull(ai + 1) ?: run { println("Нет текста после --ask"); exitProcess(1) }
            val modeArg = list.getOrNull(list.indexOf("--anti") + 1)?.takeIf { list.contains("--anti") }
            val mode = modeArg?.let { AntiMode.parse(it) } ?: AntiMode.FULL
            val idx = ensureIndex()
            val res = runRag(q, idx, mode)
            println("\n=== ОТВЕТ [${mode.label}] ===")
            println(res.answer)
            if (res.grounded != null) {
                println("\nGrounded-judge: ${"%.2f".format(res.grounded.grounded)}")
            }
            println("LLM calls total: ${res.llmCallsTotal}, retries: ${res.retryCount}, latency: ${res.latencyMs}ms")
        }
        else -> runRepl()
    }
}
