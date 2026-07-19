import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.math.sqrt

/* ============================================================
 * Day 28 — RAG-CLI + сравнение local vs cloud.
 *
 * Retrieval — ВСЕГДА локальный (nomic-embed-text).
 * Генерация — на выбор:
 *   Local  = qwen2.5:7b через Ollama /api/chat
 *   Cloud  = deepseek-chat через OpenRouter или прямой DeepSeek API
 *
 * Ключевая идея (по эталону sergio): для сравнения используем ОДИН
 * retrieval → одинаковый контекст → две генерации на разных бэкендах.
 * Так изолируется вклад LLM, retrieval из уравнения выпадает.
 *
 * Команды:
 *   ingest <папка>           построить индекс
 *   <вопрос>                 задать локальной (по умолчанию, если не указан backend)
 *   :backend local|cloud     переключить дефолтный бэкенд
 *   :compare <вопрос>        задать обоим, side-by-side + метрики
 *   :eval [<путь>]           регресс-тест текущего бэкенда
 *   :eval --both [<путь>]    регресс-тест обоих (Cmp-таблица)
 *   :stability <вопрос> [N]  прогнать один вопрос N раз (5 по умолчанию)
 *   :sources / :stats / :save / :history / :clear / :help / :quit
 *
 * Формально «RAG полностью локальный» (retrieval, embed, index) — cloud
 * используется только на этапе финальной генерации для сравнения.
 * ============================================================ */

/* ---------- Метрики + Backend ---------- */

data class LlmMetrics(
    val prompt: Int,
    val out: Int,
    val evalMs: Long,     // время генерации из ответа (без сети). Для cloud = wall (нет отдельно)
    val wallMs: Long,
    val isLocal: Boolean,
) {
    val tps: Double = if (evalMs > 0) out * 1000.0 / evalMs else 0.0
}

sealed interface LlmBackend {
    val name: String
    val isLocal: Boolean
    fun chat(messages: List<Map<String, String>>): Pair<String, LlmMetrics>
}

class OllamaLocal(
    private val baseUrl: String = "http://localhost:11434",
    val chatModel: String = "qwen2.5:7b",
    val embedModel: String = "nomic-embed-text",
    private val temperature: Double = 0.2,
) : LlmBackend {
    override val name = "local:$chatModel"
    override val isLocal = true

    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val gson = Gson()

    fun healthCheck(): List<String> {
        val tags = JsonParser.parseString(get("/api/tags")).asJsonObject
        val models = tags.getAsJsonArray("models").map { it.asJsonObject["name"].asString }
        fun isInstalled(name: String) = models.any { it == name || it == "$name:latest" || it.startsWith("$name:") }
        require(isInstalled(chatModel)) { "модель '$chatModel' не установлена. ollama pull $chatModel. Установлено: $models" }
        require(isInstalled(embedModel)) { "модель '$embedModel' не установлена. ollama pull $embedModel. Установлено: $models" }
        return models
    }

    fun embed(text: String): FloatArray {
        val body = gson.toJson(mapOf("model" to embedModel, "input" to text))
        val resp = post("/api/embed", body)
        val vec = JsonParser.parseString(resp).asJsonObject.getAsJsonArray("embeddings").first().asJsonArray
        return FloatArray(vec.size()) { vec[it].asFloat }
    }

    override fun chat(messages: List<Map<String, String>>): Pair<String, LlmMetrics> {
        val body = gson.toJson(mapOf(
            "model" to chatModel,
            "messages" to messages,
            "stream" to false,
            "options" to mapOf("temperature" to temperature),
        ))
        val wallStart = System.currentTimeMillis()
        val resp = post("/api/chat", body)
        val wall = System.currentTimeMillis() - wallStart
        val json = JsonParser.parseString(resp).asJsonObject
        val answer = json.getAsJsonObject("message")["content"].asString
        fun ns(f: String): Long = json[f]?.asLong ?: 0L
        return answer to LlmMetrics(
            prompt = json["prompt_eval_count"]?.asInt ?: 0,
            out = json["eval_count"]?.asInt ?: 0,
            evalMs = ns("eval_duration") / 1_000_000,
            wallMs = wall,
            isLocal = true,
        )
    }

    private fun get(path: String): String {
        val req = HttpRequest.newBuilder(URI.create("$baseUrl$path")).timeout(Duration.ofSeconds(10)).GET().build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        require(resp.statusCode() == 200) { "GET $path → ${resp.statusCode()}: ${resp.body()}" }
        return resp.body()
    }
    private fun post(path: String, body: String): String {
        val req = HttpRequest.newBuilder(URI.create("$baseUrl$path"))
            .timeout(Duration.ofMinutes(5)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        require(resp.statusCode() == 200) { "POST $path → ${resp.statusCode()}: ${resp.body()}" }
        return resp.body()
    }
}

// Cloud-бэкенд: DeepSeek напрямую (endpoint OpenAI-compatible).
class DeepSeekCloud(
    private val apiKey: String,
    private val baseUrl: String = "https://api.deepseek.com",
    private val model: String = "deepseek-chat",
    private val temperature: Double = 0.2,
) : LlmBackend {
    override val name = "cloud:$model"
    override val isLocal = false

    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val gson = Gson()

    override fun chat(messages: List<Map<String, String>>): Pair<String, LlmMetrics> {
        val body = gson.toJson(mapOf(
            "model" to model,
            "messages" to messages,
            "stream" to false,
            "temperature" to temperature,
        ))
        val wallStart = System.currentTimeMillis()
        val req = HttpRequest.newBuilder(URI.create("$baseUrl/chat/completions"))
            .timeout(Duration.ofMinutes(5))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        val wall = System.currentTimeMillis() - wallStart
        require(resp.statusCode() == 200) { "POST /chat/completions → ${resp.statusCode()}: ${resp.body()}" }
        val json = JsonParser.parseString(resp.body()).asJsonObject
        val answer = json.getAsJsonArray("choices").first().asJsonObject
            .getAsJsonObject("message")["content"].asString
        val usage = json.getAsJsonObject("usage")
        return answer to LlmMetrics(
            prompt = usage["prompt_tokens"]?.asInt ?: 0,
            out = usage["completion_tokens"]?.asInt ?: 0,
            evalMs = wall,  // у OpenAI-compat нет отдельно eval time, берём wall
            wallMs = wall,
            isLocal = false,
        )
    }
}

/* ---------- Chunk + Index (без изменений с day 27) ---------- */

data class Chunk(
    val source: String, val idx: Int, val text: String, val vec: FloatArray,
) {
    val id: String get() = "$source#$idx"
}

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

fun cosine(a: FloatArray, b: FloatArray): Double {
    var dot = 0.0; var na = 0.0; var nb = 0.0
    for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
    val denom = sqrt(na) * sqrt(nb)
    return if (denom == 0.0) 0.0 else dot / denom
}

class Index(val chunks: MutableList<Chunk> = mutableListOf()) {
    fun size() = chunks.size
    fun sources() = chunks.map { it.source }.distinct()
    fun search(query: FloatArray, k: Int = 3): List<Pair<Chunk, Double>> =
        chunks.map { it to cosine(query, it.vec) }
            .sortedByDescending { it.second }.take(k)

    fun saveTo(path: String) {
        val gson = Gson()
        val jsonList = chunks.map { mapOf(
            "source" to it.source, "idx" to it.idx, "text" to it.text, "vec" to it.vec.toList()) }
        File(path).writeText(gson.toJson(jsonList), Charsets.UTF_8)
    }

    companion object {
        fun loadFrom(path: String): Index {
            val f = File(path)
            require(f.exists()) { "индекс не найден: $path (сначала сделай ingest)" }
            val list = JsonParser.parseString(f.readText(Charsets.UTF_8)).asJsonArray
            val ch = list.map { e ->
                val o = e.asJsonObject
                Chunk(
                    source = o["source"].asString, idx = o["idx"].asInt,
                    text = o["text"].asString,
                    vec = FloatArray(o.getAsJsonArray("vec").size()) { i -> o.getAsJsonArray("vec")[i].asFloat },
                )
            }
            return Index(ch.toMutableList())
        }
    }
}

fun ingest(folder: String, indexPath: String, local: OllamaLocal) {
    val root = File(folder)
    require(root.exists() && root.isDirectory) { "папка не найдена: $folder" }
    val files = root.walkTopDown()
        .filter { it.isFile && (it.extension.equals("md", true) || it.extension.equals("txt", true)) }
        .toList()
    require(files.isNotEmpty()) { "нет .md/.txt файлов в '$folder'" }
    println("Ingest: '$folder' → ${files.size} файлов")
    val index = Index(); val t0 = System.currentTimeMillis()
    files.forEachIndexed { fi, file ->
        val text = file.readText(Charsets.UTF_8)
        val rel = file.relativeTo(root).path.replace('\\', '/')
        val pieces = chunkMarkdown(text)
        pieces.forEachIndexed { ci, piece ->
            val vec = local.embed("search_document: $piece")
            index.chunks.add(Chunk(source = rel, idx = ci, text = piece, vec = vec))
        }
        println("  [${fi + 1}/${files.size}] $rel — ${pieces.size} чанков")
    }
    index.saveTo(indexPath)
    val dt = (System.currentTimeMillis() - t0) / 1000.0
    println("Готово: ${index.size()} чанков из ${index.sources().size} файлов за ${"%.1f".format(dt)} с.")
    println("Индекс: $indexPath")
}

/* ---------- Prompt + verbatim ---------- */

fun buildPromptWithContext(question: String, hits: List<Pair<Chunk, Double>>): String {
    val sb = StringBuilder()
    sb.appendLine("Контекст (фрагменты локальных документов):").appendLine()
    hits.forEachIndexed { i, (chunk, score) ->
        sb.appendLine("[S${i + 1}] источник: ${chunk.source} (score=${"%.3f".format(score)})")
        sb.appendLine(chunk.text.trim()).appendLine()
    }
    sb.appendLine("---").appendLine("Вопрос: $question").appendLine()
    sb.appendLine("Отвечай кратко на русском. Ссылайся на источники маркерами [S1]/[S2]/[S3]. Если контекста не хватает — так и скажи.")
    return sb.toString()
}

// verbatimRate: доля 8-грамм ответа, встречающихся дословно в контексте.
// Высокая = модель цитирует источник, а не сочиняет. Низкая = креатив/галлюцинация.
// Идея из эталона sergio.
fun verbatimRate(answer: String, contextChunks: List<Chunk>): Double {
    val normalize = { s: String -> s.replace(Regex("\\s+"), " ").trim().lowercase() }
    val ans = normalize(answer)
    val ctx = normalize(contextChunks.joinToString(" ") { it.text })
    if (ans.length < 8) return 0.0
    val ngramSize = 8
    val words = ans.split(" ").filter { it.isNotBlank() }
    if (words.size < ngramSize) return 0.0
    var matched = 0; var total = 0
    for (i in 0..(words.size - ngramSize)) {
        val ng = words.subList(i, i + ngramSize).joinToString(" ")
        total++
        if (ctx.contains(ng)) matched++
    }
    return if (total == 0) 0.0 else matched.toDouble() / total
}

/* ---------- Turn ---------- */

data class Turn(
    val question: String,
    val answer: String,
    val sources: List<String>,
    val hits: List<Pair<Chunk, Double>>,
    val metrics: LlmMetrics,
    val backendName: String,
    val verbatim: Double,
) {
    fun toJsonLine(gson: Gson, ts: Long): String = gson.toJson(mapOf(
        "ts" to ts, "backend" to backendName, "is_local" to metrics.isLocal,
        "question" to question, "answer" to answer, "sources" to sources,
        "prompt_tokens" to metrics.prompt, "out_tokens" to metrics.out,
        "eval_ms" to metrics.evalMs, "wall_ms" to metrics.wallMs,
        "tokens_per_sec" to metrics.tps, "verbatim_rate" to verbatim,
    ))
}

const val TRACE_PATH = "chat.log.jsonl"
fun appendTrace(turn: Turn) {
    File(TRACE_PATH).appendText(turn.toJsonLine(Gson(), System.currentTimeMillis()) + "\n", Charsets.UTF_8)
}

/* ---------- Session с двумя бэкендами ---------- */

class Session(
    val local: OllamaLocal,
    val cloud: LlmBackend?,   // null если нет DEEPSEEK_API_KEY
    val index: Index,
    var defaultBackend: LlmBackend,
    val history: MutableList<Map<String, String>> = mutableListOf(
        mapOf("role" to "system", "content" to "Ты помощник, отвечающий по локальным документам. Отвечай кратко, только на основе контекста. Если контекста не хватает — так и скажи."),
    ),
    val turns: MutableList<Turn> = mutableListOf(),
) {
    fun retrieve(question: String, k: Int = 3): List<Pair<Chunk, Double>> {
        val qvec = local.embed("search_query: $question")
        return index.search(qvec, k)
    }

    fun askOn(backend: LlmBackend, question: String, hits: List<Pair<Chunk, Double>>, updateHistory: Boolean = true): Turn {
        val userMsg = buildPromptWithContext(question, hits)
        val msgs = if (updateHistory) {
            history.add(mapOf("role" to "user", "content" to userMsg))
            history.toList()
        } else {
            history + mapOf("role" to "user", "content" to userMsg)
        }
        val (answer, metrics) = backend.chat(msgs)
        if (updateHistory) history.add(mapOf("role" to "assistant", "content" to answer))
        val verbatim = verbatimRate(answer, hits.map { it.first })
        val turn = Turn(question, answer, hits.map { it.first.source }, hits, metrics, backend.name, verbatim)
        appendTrace(turn)
        return turn
    }

    fun ask(question: String): Turn {
        val hits = retrieve(question)
        val turn = askOn(defaultBackend, question, hits, updateHistory = true)
        turns.add(turn)
        return turn
    }

    fun compare(question: String): Pair<Turn, Turn?> {
        require(cloud != null) { "cloud-бэкенд не инициализирован (нужен DEEPSEEK_API_KEY)" }
        val hits = retrieve(question)
        // ОБА бэкенда на ОДНОМ контексте, без апдейта общей истории (чтобы не мешать чат)
        val tLocal = askOn(local, question, hits, updateHistory = false)
        val tCloud = askOn(cloud, question, hits, updateHistory = false)
        return tLocal to tCloud
    }

    // Прогон списка вопросов на чистой истории каждый.
    fun evalOn(backend: LlmBackend, questions: List<String>): List<Turn> {
        val savedHistory = history.toList(); val savedTurns = turns.toList()
        val results = mutableListOf<Turn>()
        for (q in questions) {
            clear()
            val hits = retrieve(q)
            results.add(askOn(backend, q, hits, updateHistory = false))
        }
        history.clear(); history.addAll(savedHistory)
        turns.clear(); turns.addAll(savedTurns)
        return results
    }

    // Стабильность: один и тот же вопрос N раз, чистая история каждый.
    fun stability(question: String, n: Int = 5): List<Turn> {
        val results = mutableListOf<Turn>()
        for (i in 0 until n) {
            val hits = retrieve(question)  // retrieval стабилен (temperature=0 для embed нет, но detereministic)
            results.add(askOn(defaultBackend, question, hits, updateHistory = false))
        }
        return results
    }

    fun clear() {
        val system = history.first()
        history.clear(); history.add(system); turns.clear()
    }
}

/* ---------- REPL ---------- */

const val DEFAULT_INDEX = "index.json"

fun printHelp(cloudAvailable: Boolean) {
    println("""
        Команды:
          ingest <папка>                построить индекс
          <вопрос>                      спросить (использует текущий backend)
          :backend local|cloud          переключить дефолтный backend
          :compare <вопрос>             задать обоим (local + cloud), side-by-side
          :eval [<файл>]                прогнать вопросы через ТЕКУЩИЙ backend
          :eval --both [<файл>]         прогнать вопросы через оба, сравнительная таблица
          :stability <вопрос> [N]       прогнать один вопрос N раз (по умолчанию 5)
          :history / :sources / :stats  диагностика
          :save <путь>                  сохранить историю в markdown
          :clear                        очистить историю (индекс не трогает)
          :help / :quit
    """.trimIndent())
    if (!cloudAvailable) {
        println()
        println("⚠️ DEEPSEEK_API_KEY не найден в окружении. :compare и :eval --both недоступны.")
    }
}

fun loadEvalQuestions(path: String): List<String> {
    val f = File(path)
    require(f.exists()) { "нет файла с eval-вопросами: $path" }
    return f.readLines(Charsets.UTF_8).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
}

fun printCompareTable(local: Turn, cloud: Turn) {
    println()
    println("┌─ LOCAL (${local.backendName}) ─────────────────")
    local.answer.trim().lines().forEach { println("│ $it") }
    println("│ ")
    println("│ [sources: ${local.sources.joinToString(", ")}]")
    println("│ [${local.metrics.out} tok, eval=${local.metrics.evalMs}ms, ${"%.1f".format(local.metrics.tps)} tok/s, wall=${local.metrics.wallMs}ms, verbatim=${"%.2f".format(local.verbatim)}]")
    println("├─ CLOUD (${cloud.backendName}) ─────────────────")
    cloud.answer.trim().lines().forEach { println("│ $it") }
    println("│ ")
    println("│ [sources: ${cloud.sources.joinToString(", ")}]")
    println("│ [${cloud.metrics.out} tok, wall=${cloud.metrics.wallMs}ms, verbatim=${"%.2f".format(cloud.verbatim)}]")
    println("└─────────────────────────────────────────────")
    val sameSources = local.sources.toSet() == cloud.sources.toSet()
    println("Одинаковые источники: ${if (sameSources) "✓" else "✗ (retrieval стабилен, но local ${local.sources} vs cloud ${cloud.sources})"}")
    println("Cloud быстрее по wall: ${if (cloud.metrics.wallMs < local.metrics.wallMs) "✓ (${local.metrics.wallMs - cloud.metrics.wallMs} ms разница)" else "✗ (local быстрее на ${cloud.metrics.wallMs - local.metrics.wallMs} ms)"}")
}

fun printEvalBothTable(pairs: List<Pair<Turn, Turn>>) {
    println()
    println("| # | Вопрос | Local out | Local wall | Local vRate | Cloud out | Cloud wall | Cloud vRate | Same src |")
    println("|---|---|---|---|---|---|---|---|---|")
    pairs.forEachIndexed { i, (l, c) ->
        val q = l.question.take(50).replace("|", "\\|")
        val same = if (l.sources.toSet() == c.sources.toSet()) "✓" else "✗"
        println("| ${i + 1} | $q | ${l.metrics.out} | ${l.metrics.wallMs} | ${"%.2f".format(l.verbatim)} | ${c.metrics.out} | ${c.metrics.wallMs} | ${"%.2f".format(c.verbatim)} | $same |")
    }
    val locAvgWall = pairs.map { it.first.metrics.wallMs }.average()
    val cldAvgWall = pairs.map { it.second.metrics.wallMs }.average()
    val locAvgV = pairs.map { it.first.verbatim }.average()
    val cldAvgV = pairs.map { it.second.verbatim }.average()
    val sameSourcesShare = pairs.count { it.first.sources.toSet() == it.second.sources.toSet() }.toDouble() / pairs.size
    println()
    println("Local  средний wall: ${"%.0f".format(locAvgWall)} ms, средний verbatim: ${"%.2f".format(locAvgV)}")
    println("Cloud  средний wall: ${"%.0f".format(cldAvgWall)} ms, средний verbatim: ${"%.2f".format(cldAvgV)}")
    println("Одинаковые источники (доля): ${"%.0f".format(sameSourcesShare * 100)}%")
}

fun repl(s0: Session?, cloudAvailable: Boolean) {
    var s = s0
    println("RAG-CLI (day28). backend=${s?.defaultBackend?.name ?: "?"} (cloud ${if (cloudAvailable) "✓" else "✗"}). Индекс: ${s?.index?.size() ?: 0} чанков.")
    printHelp(cloudAvailable)

    val input = System.`in`.bufferedReader()
    while (true) {
        print("\n>>> "); System.out.flush()
        val raw = input.readLine() ?: break
        val line = raw.trim()
        if (line.isEmpty()) continue

        try {
            when {
                line == ":quit" || line == ":exit" -> return
                line == ":help" -> printHelp(cloudAvailable)
                line == ":history" -> {
                    if (s == null || s.turns.isEmpty()) { println("(пусто)"); continue }
                    s.turns.takeLast(5).forEachIndexed { i, t ->
                        println("[${i + 1}] ${t.backendName} Q: ${t.question}")
                        println("    A: ${t.answer.replace("\n", " ").take(120)}${if (t.answer.length > 120) "…" else ""}")
                    }
                }
                line == ":sources" -> { if (s == null) println("нет индекса") else s.index.sources().forEach { println("  $it") } }
                line == ":stats" -> {
                    val t = s?.turns?.lastOrNull()
                    if (t == null) println("(нет ответов)")
                    else println("Последний: ${t.backendName}, prompt=${t.metrics.prompt} → out=${t.metrics.out}, eval=${t.metrics.evalMs}ms, ${"%.1f".format(t.metrics.tps)} tok/s, wall=${t.metrics.wallMs}ms, verbatim=${"%.2f".format(t.verbatim)}\nИсточники: ${t.sources.joinToString(", ")}")
                }
                line == ":clear" -> { s?.clear(); println("история очищена") }
                line.startsWith(":backend") -> {
                    val which = line.removePrefix(":backend").trim()
                    if (s == null) { println("сначала ingest"); continue }
                    s.defaultBackend = when (which) {
                        "local" -> s.local
                        "cloud" -> s.cloud ?: run { println("cloud недоступен"); continue }
                        else -> { println("используй :backend local или :backend cloud"); continue }
                    }
                    println("Backend: ${s.defaultBackend.name}")
                }
                line.startsWith(":save") -> {
                    val path = line.removePrefix(":save").trim().ifEmpty { "chat.md" }
                    saveChatMarkdown(s, path); println("Сохранено: $path")
                }
                line.startsWith("ingest ") -> {
                    val folder = line.removePrefix("ingest ").trim()
                    ingest(folder, DEFAULT_INDEX, s?.local ?: OllamaLocal())
                    val local = s?.local ?: OllamaLocal()
                    val cloud = s?.cloud ?: buildCloudFromEnv()
                    val ix = Index.loadFrom(DEFAULT_INDEX)
                    s = Session(local, cloud, ix, defaultBackend = local)
                    println("Индекс перезагружен: ${s.index.size()} чанков")
                }
                line.startsWith(":compare ") -> {
                    val q = line.removePrefix(":compare ").trim()
                    if (s == null) { println("сначала ingest"); continue }
                    if (s.cloud == null) { println("cloud недоступен — установи DEEPSEEK_API_KEY"); continue }
                    val (loc, cld) = s.compare(q)
                    printCompareTable(loc, cld!!)
                }
                line.startsWith(":stability") -> {
                    val rest = line.removePrefix(":stability").trim()
                    val parts = rest.split(Regex("\\s+"))
                    val n = parts.last().toIntOrNull() ?: 5
                    val q = if (parts.last().toIntOrNull() != null) parts.dropLast(1).joinToString(" ") else rest
                    if (q.isBlank()) { println(":stability <вопрос> [N]"); continue }
                    if (s == null) { println("сначала ingest"); continue }
                    val runs = s.stability(q, n)
                    println("\nСтабильность (${s.defaultBackend.name}, N=$n): $q")
                    runs.forEachIndexed { i, t ->
                        println("  [${i + 1}] ${t.metrics.out} tok, wall=${t.metrics.wallMs}ms, verbatim=${"%.2f".format(t.verbatim)}, sources=${t.sources.joinToString(",")}")
                    }
                    val outsRange = runs.map { it.metrics.out }.let { "${it.min()}-${it.max()}" }
                    val wallRange = runs.map { it.metrics.wallMs }.let { "${it.min()}-${it.max()}" }
                    val sourcesSame = runs.map { it.sources.toSet() }.distinct().size == 1
                    println("Разброс out: $outsRange | wall: $wallRange ms | одинаковые источники: ${if (sourcesSame) "✓" else "✗"}")
                }
                line.startsWith(":eval") -> {
                    if (s == null) { println("сначала ingest"); continue }
                    val rest = line.removePrefix(":eval").trim()
                    val both = rest.contains("--both")
                    val path = rest.replace("--both", "").trim().ifEmpty { "eval-questions.txt" }
                    val questions = try { loadEvalQuestions(path) } catch (t: Throwable) { println("Ошибка: ${t.message}"); continue }
                    if (both) {
                        if (s.cloud == null) { println("cloud недоступен"); continue }
                        println("Eval --both: ${questions.size} вопросов")
                        val pairs = mutableListOf<Pair<Turn, Turn>>()
                        questions.forEach { q ->
                            val hits = s.retrieve(q)
                            val l = s.askOn(s.local, q, hits, updateHistory = false)
                            val c = s.askOn(s.cloud, q, hits, updateHistory = false)
                            pairs.add(l to c)
                        }
                        printEvalBothTable(pairs)
                    } else {
                        val results = s.evalOn(s.defaultBackend, questions)
                        println("\nEval (${s.defaultBackend.name}): ${results.size} вопросов")
                        println("| # | Вопрос | Sources | out | tok/s | wall | verbatim |")
                        println("|---|---|---|---|---|---|---|")
                        results.forEachIndexed { i, t ->
                            val q = t.question.take(50).replace("|", "\\|")
                            println("| ${i + 1} | $q | ${t.sources.joinToString(",")} | ${t.metrics.out} | ${"%.1f".format(t.metrics.tps)} | ${t.metrics.wallMs} | ${"%.2f".format(t.verbatim)} |")
                        }
                    }
                }
                else -> {
                    // просто вопрос
                    if (s == null) { println("сначала ingest <папка>"); continue }
                    val turn = s.ask(line)
                    s.turns.add(turn)
                    println("\n${turn.answer.trim()}")
                    println("\n[${turn.backendName} | sources: ${turn.sources.joinToString(", ")} | ${turn.metrics.out} tok, ${"%.1f".format(turn.metrics.tps)} tok/s, wall=${turn.metrics.wallMs}ms, verbatim=${"%.2f".format(turn.verbatim)}]")
                }
            }
        } catch (t: Throwable) {
            println("Ошибка: ${t.message}")
        }
    }
}

fun saveChatMarkdown(s: Session?, path: String) {
    val sb = StringBuilder()
    sb.appendLine("# RAG-CLI chat (day 28)")
    sb.appendLine("**fully_local_rag: true** (retrieval + embed локально; cloud только для сравнения генерации).")
    sb.appendLine()
    if (s == null || s.turns.isEmpty()) { sb.appendLine("_(пусто)_"); File(path).writeText(sb.toString(), Charsets.UTF_8); return }
    sb.appendLine("Local backend: ${s.local.name}, cloud: ${s.cloud?.name ?: "нет"}. Индекс: ${s.index.size()} чанков.")
    sb.appendLine()
    s.turns.forEachIndexed { i, t ->
        sb.appendLine("## Turn ${i + 1} (${t.backendName})")
        sb.appendLine("**Вопрос:** ${t.question}")
        sb.appendLine("**Источники:** ${t.sources.joinToString(", ")}")
        sb.appendLine("**Ответ:**")
        sb.appendLine(t.answer.trim())
        sb.appendLine("_Метрики: prompt=${t.metrics.prompt} → out=${t.metrics.out}, ${"%.1f".format(t.metrics.tps)} tok/s, wall=${t.metrics.wallMs}ms, verbatim=${"%.2f".format(t.verbatim)}._")
        sb.appendLine()
    }
    File(path).writeText(sb.toString(), Charsets.UTF_8)
}

/* ---------- main ---------- */

// Ключ ищем в env → .env → нету → cloud disabled.
fun buildCloudFromEnv(): LlmBackend? {
    val fromEnv = System.getenv("DEEPSEEK_API_KEY")
    val fromDotenv = try {
        File(".env").takeIf { it.exists() }?.readLines()?.firstOrNull { it.startsWith("DEEPSEEK_API_KEY=") }?.substringAfter("=")?.trim()
    } catch (t: Throwable) { null }
    // Пробуем ещё родительские папки (типично .env в корне репо, а cwd = week6/day3)
    val fromParentDotenv = try {
        listOf("../.env", "../../.env").firstNotNullOfOrNull { p ->
            File(p).takeIf { it.exists() }?.readLines()?.firstOrNull { it.startsWith("DEEPSEEK_API_KEY=") }?.substringAfter("=")?.trim()
        }
    } catch (t: Throwable) { null }
    val key = fromEnv ?: fromDotenv ?: fromParentDotenv
    return if (!key.isNullOrBlank()) DeepSeekCloud(apiKey = key) else null
}

fun main(args: Array<String>) {
    val local = OllamaLocal()

    println("Health-check…")
    val models = local.healthCheck()
    println("  local: chat=${local.chatModel}, embed=${local.embedModel}. Всего моделей: ${models.size}")

    val cloud = buildCloudFromEnv()
    if (cloud != null) println("  cloud: ${cloud.name} (DEEPSEEK_API_KEY найден)")
    else println("  cloud: НЕТ (установи DEEPSEEK_API_KEY в env или .env для :compare/:eval --both)")

    val mode = args.getOrNull(0)
    when (mode) {
        "ingest" -> {
            val folder = args.getOrNull(1) ?: error("ingest <папка>")
            ingest(folder, DEFAULT_INDEX, local); return
        }
        null, "chat" -> {
            val path = if (mode == "chat") args.getOrNull(1) ?: DEFAULT_INDEX else DEFAULT_INDEX
            val session = if (File(path).exists()) {
                Session(local, cloud, Index.loadFrom(path), defaultBackend = local)
            } else null
            repl(session, cloud != null)
        }
        else -> println("Неизвестный режим: '$mode'. Ожидаю: ingest <папка> | chat [<index>] | (пусто)")
    }
}
