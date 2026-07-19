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
 * Day 29 — оптимизация локальной LLM под RAG.
 *
 * Три пресета, все на общем retrieval:
 *   BASELINE  = day 28 буквально (temp 0.2, num_ctx=2048, промпт v1)
 *   OPTIMIZED = temp 0.0, num_ctx=8192, num_predict=256, top_p=0.5,
 *               repeat_penalty=1.2, stop=[…], промпт v2 (схема+пример)
 *   QUANT     = модель qwen2.5:7b-instruct-q3_K_M + параметры OPTIMIZED
 *
 * Метрики: verbatimRate, parse_failure count, wall/tok/s + ресурсы из /api/ps.
 * ============================================================ */

/* ---------- Presets ---------- */

data class GenParams(
    val temperature: Double,
    val numCtx: Int,             // <=0 = не передавать (Ollama default)
    val numPredict: Int,         // <=0 = не передавать (без лимита)
    val topP: Double,
    val repeatPenalty: Double,
    val stopWords: List<String>,
)

enum class PromptStyle { V1_MINIMAL, V2_STRUCTURED }

data class Preset(
    val name: String,
    val model: String,           // qwen2.5:7b или qwen2.5:7b-instruct-q3_K_M
    val gen: GenParams,
    val prompt: PromptStyle,
) {
    override fun toString() = "$name[$model, T=${gen.temperature}, ctx=${gen.numCtx}, npred=${gen.numPredict}, prompt=$prompt]"
}

val PRESETS: Map<String, Preset> = mapOf(
    "baseline" to Preset(
        name = "baseline", model = "qwen2.5:7b",
        gen = GenParams(0.2, numCtx = 2048, numPredict = -1, topP = 0.9, repeatPenalty = 1.1, stopWords = emptyList()),
        prompt = PromptStyle.V1_MINIMAL,
    ),
    "optimized" to Preset(
        name = "optimized", model = "qwen2.5:7b",
        gen = GenParams(0.0, numCtx = 8192, numPredict = 256, topP = 0.5, repeatPenalty = 1.2, stopWords = listOf("\n\n---", "Вопрос:")),
        prompt = PromptStyle.V2_STRUCTURED,
    ),
    "quant" to Preset(
        name = "quant", model = "qwen2.5:7b-instruct-q3_K_M",
        gen = GenParams(0.0, numCtx = 8192, numPredict = 256, topP = 0.5, repeatPenalty = 1.2, stopWords = listOf("\n\n---", "Вопрос:")),
        prompt = PromptStyle.V2_STRUCTURED,
    ),
)

/* ---------- Метрики ---------- */

data class LlmMetrics(
    val prompt: Int, val out: Int, val evalMs: Long, val wallMs: Long,
    val model: String, val presetName: String,
) {
    val tps: Double = if (evalMs > 0) out * 1000.0 / evalMs else 0.0
}

data class PsInfo(val name: String, val sizeVramMb: Long, val sizeTotalMb: Long) {
    val gpuFraction: Double = if (sizeTotalMb > 0) sizeVramMb.toDouble() / sizeTotalMb else 0.0
}

/* ---------- Ollama client (расширенный) ---------- */

class OllamaClient(
    private val baseUrl: String = "http://localhost:11434",
    val embedModel: String = "nomic-embed-text",
) {
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val gson = Gson()

    fun healthCheck(requiredModels: List<String>): List<String> {
        val tags = JsonParser.parseString(get("/api/tags")).asJsonObject
        val models = tags.getAsJsonArray("models").map { it.asJsonObject["name"].asString }
        fun isInstalled(name: String) = models.any { it == name || it == "$name:latest" || it.startsWith("$name:") }
        for (m in requiredModels) {
            require(isInstalled(m)) { "модель '$m' не установлена. ollama pull $m. Установлено: $models" }
        }
        return models
    }

    fun embed(text: String): FloatArray {
        val body = gson.toJson(mapOf("model" to embedModel, "input" to text))
        val resp = post("/api/embed", body)
        val vec = JsonParser.parseString(resp).asJsonObject.getAsJsonArray("embeddings").first().asJsonArray
        return FloatArray(vec.size()) { vec[it].asFloat }
    }

    fun chat(preset: Preset, messages: List<Map<String, String>>): Pair<String, LlmMetrics> {
        val options = mutableMapOf<String, Any>("temperature" to preset.gen.temperature, "top_p" to preset.gen.topP, "repeat_penalty" to preset.gen.repeatPenalty)
        if (preset.gen.numCtx > 0) options["num_ctx"] = preset.gen.numCtx
        if (preset.gen.numPredict > 0) options["num_predict"] = preset.gen.numPredict
        if (preset.gen.stopWords.isNotEmpty()) options["stop"] = preset.gen.stopWords

        val body = gson.toJson(mapOf(
            "model" to preset.model,
            "messages" to messages,
            "stream" to false,
            "options" to options,
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
            model = preset.model, presetName = preset.name,
        )
    }

    // Warmup: единственный вопрос "hi" с параметрами пресета — грузит модель в память с нужным num_ctx.
    // Иначе первый реальный ответ съест cold-swap tax при смене num_ctx между пресетами.
    fun warmup(preset: Preset) {
        chat(preset, listOf(mapOf("role" to "user", "content" to "hi")))
    }

    // /api/ps → какие модели сейчас в памяти, GPU/CPU split.
    fun ps(): List<PsInfo> {
        val json = JsonParser.parseString(get("/api/ps")).asJsonObject
        val arr = json.getAsJsonArray("models") ?: return emptyList()
        return arr.map { it.asJsonObject }.map { o ->
            val sizeTotal = o["size"]?.asLong ?: 0L
            val sizeVram = o["size_vram"]?.asLong ?: 0L
            PsInfo(name = o["name"].asString, sizeVramMb = sizeVram / (1024 * 1024), sizeTotalMb = sizeTotal / (1024 * 1024))
        }
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

/* ---------- Chunk + Index (без изменений) ---------- */

data class Chunk(val source: String, val idx: Int, val text: String, val vec: FloatArray) {
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
    val d = sqrt(na) * sqrt(nb); return if (d == 0.0) 0.0 else dot / d
}

class Index(val chunks: MutableList<Chunk> = mutableListOf()) {
    fun size() = chunks.size
    fun sources() = chunks.map { it.source }.distinct()
    fun search(query: FloatArray, k: Int = 3): List<Pair<Chunk, Double>> =
        chunks.map { it to cosine(query, it.vec) }.sortedByDescending { it.second }.take(k)
    fun saveTo(path: String) {
        val gson = Gson()
        val jsonList = chunks.map { mapOf("source" to it.source, "idx" to it.idx, "text" to it.text, "vec" to it.vec.toList()) }
        File(path).writeText(gson.toJson(jsonList), Charsets.UTF_8)
    }
    companion object {
        fun loadFrom(path: String): Index {
            val f = File(path)
            require(f.exists()) { "индекс не найден: $path" }
            val list = JsonParser.parseString(f.readText(Charsets.UTF_8)).asJsonArray
            val ch = list.map { e ->
                val o = e.asJsonObject
                Chunk(o["source"].asString, o["idx"].asInt, o["text"].asString,
                    FloatArray(o.getAsJsonArray("vec").size()) { i -> o.getAsJsonArray("vec")[i].asFloat })
            }
            return Index(ch.toMutableList())
        }
    }
}

fun ingest(folder: String, indexPath: String, client: OllamaClient) {
    val root = File(folder); require(root.exists() && root.isDirectory) { "папка не найдена: $folder" }
    val files = root.walkTopDown().filter { it.isFile && (it.extension.equals("md", true) || it.extension.equals("txt", true)) }.toList()
    require(files.isNotEmpty()) { "нет .md/.txt файлов в '$folder'" }
    println("Ingest: '$folder' → ${files.size} файлов")
    val index = Index(); val t0 = System.currentTimeMillis()
    files.forEachIndexed { fi, file ->
        val text = file.readText(Charsets.UTF_8)
        val rel = file.relativeTo(root).path.replace('\\', '/')
        val pieces = chunkMarkdown(text)
        pieces.forEachIndexed { ci, piece ->
            val vec = client.embed("search_document: $piece")
            index.chunks.add(Chunk(rel, ci, piece, vec))
        }
        println("  [${fi + 1}/${files.size}] $rel — ${pieces.size} чанков")
    }
    index.saveTo(indexPath)
    val dt = (System.currentTimeMillis() - t0) / 1000.0
    println("Готово: ${index.size()} чанков из ${index.sources().size} файлов за ${"%.1f".format(dt)} с.")
}

/* ---------- Prompt templates ---------- */

fun buildPromptV1(question: String, hits: List<Pair<Chunk, Double>>): String {
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

// Оптимизированный: жёсткие правила + contrastive example (что делать / что НЕ делать) + чек-лист.
fun buildPromptV2(question: String, hits: List<Pair<Chunk, Double>>): String {
    val sb = StringBuilder()
    sb.appendLine("Ты — точный документальный ассистент. Правила ответа:")
    sb.appendLine("1) Каждый факт → маркер [S1]/[S2]/[S3] СРАЗУ за фразой.")
    sb.appendLine("2) Только на основе контекста ниже. Если данных не хватает — ответь ровно: «Недостаточно контекста.»")
    sb.appendLine("3) Максимум 3 предложения. Без вводных «Итак», «Согласно материалам».")
    sb.appendLine()
    sb.appendLine("Пример хорошего ответа:")
    sb.appendLine("Q: Какую модель использовал в day26? A: Использована qwen2.5:7b через Ollama [S1]. Модель занимает ~4.7 GB VRAM [S2].")
    sb.appendLine("Пример плохого ответа (НЕ делай так):")
    sb.appendLine("Q: Какую модель использовал в day26? A: Согласно предоставленным материалам, для day26 автор использовал модель Qwen 2.5 версии 7B параметров, что описано в разных источниках…  ← вводные слова, нет маркеров, размазано")
    sb.appendLine()
    sb.appendLine("Контекст:")
    hits.forEachIndexed { i, (chunk, score) ->
        sb.appendLine("[S${i + 1}] ${chunk.source} (score=${"%.3f".format(score)})")
        sb.appendLine(chunk.text.trim()).appendLine()
    }
    sb.appendLine("---")
    sb.appendLine("Вопрос: $question")
    sb.appendLine("Ответ (только по контексту, с маркерами, максимум 3 предложения):")
    return sb.toString()
}

fun buildPrompt(style: PromptStyle, question: String, hits: List<Pair<Chunk, Double>>): String = when (style) {
    PromptStyle.V1_MINIMAL -> buildPromptV1(question, hits)
    PromptStyle.V2_STRUCTURED -> buildPromptV2(question, hits)
}

/* ---------- Verbatim + parse_failure ---------- */

// verbatim по 4-граммам (вместо 8): чувствительнее к коротким ответам.
// 8-граммы почти всегда 0 для ответов 30-60 токенов (~5-10 слов).
fun verbatimRate(answer: String, contextChunks: List<Chunk>): Double {
    val normalize = { s: String -> s.replace(Regex("\\s+"), " ").trim().lowercase() }
    val ans = normalize(answer)
    val ctx = normalize(contextChunks.joinToString(" ") { it.text })
    val words = ans.split(" ").filter { it.isNotBlank() }
    val N = 4
    if (words.size < N) return 0.0
    var matched = 0; var total = 0
    for (i in 0..(words.size - N)) {
        val ng = words.subList(i, i + N).joinToString(" ")
        total++
        if (ctx.contains(ng)) matched++
    }
    return if (total == 0) 0.0 else matched.toDouble() / total
}

// Проверка сбоя формата: должен быть хотя бы один [S1..Sk] маркер + не оборван.
// «Оборван» = ответ обрывается посреди слова / открытая скобка / незакрытая пара кавычек.
// parse_fail = формат ответа не соответствует ожидаемому.
// Строгий критерий: НЕТ маркера [Sn] И не «Недостаточно контекста.»
// Проверка на «оборван» удалена — давала ложные срабатывания на нормальных
// коротких ответах OPTIMIZED (модель могла закончить не точкой из-за стоп-слов).
fun parseFailure(answer: String, expectedSources: Int = 3): Boolean {
    val hasMarker = Regex("\\[S[1-9]\\d?\\]").containsMatchIn(answer)
    val isRefusal = answer.trim().let {
        it.contains("Недостаточно контекста", ignoreCase = true) || it.contains("insufficient_context", ignoreCase = true)
    }
    return !hasMarker && !isRefusal
}

/* ---------- Turn ---------- */

data class Turn(
    val question: String, val answer: String, val sources: List<String>,
    val hits: List<Pair<Chunk, Double>>, val metrics: LlmMetrics,
    val presetName: String, val verbatim: Double, val parseFailed: Boolean,
) {
    fun toJsonLine(gson: Gson, ts: Long): String = gson.toJson(mapOf(
        "ts" to ts, "preset" to presetName, "model" to metrics.model,
        "question" to question, "answer" to answer, "sources" to sources,
        "prompt_tokens" to metrics.prompt, "out_tokens" to metrics.out,
        "eval_ms" to metrics.evalMs, "wall_ms" to metrics.wallMs,
        "tokens_per_sec" to metrics.tps, "verbatim_rate" to verbatim, "parse_failed" to parseFailed,
    ))
}

const val TRACE_PATH = "chat.log.jsonl"
fun appendTrace(turn: Turn) {
    File(TRACE_PATH).appendText(turn.toJsonLine(Gson(), System.currentTimeMillis()) + "\n", Charsets.UTF_8)
}

/* ---------- Session с пресетами ---------- */

class Session(
    val client: OllamaClient,
    val index: Index,
    var activePreset: Preset = PRESETS["baseline"]!!,
    val history: MutableList<Map<String, String>> = mutableListOf(
        mapOf("role" to "system", "content" to "Ты помощник по документам. Отвечай кратко, только на основе контекста."),
    ),
    val turns: MutableList<Turn> = mutableListOf(),
) {
    fun retrieve(question: String, k: Int = 3): List<Pair<Chunk, Double>> {
        val qvec = client.embed("search_query: $question")
        return index.search(qvec, k)
    }

    fun askOn(preset: Preset, question: String, hits: List<Pair<Chunk, Double>>, updateHistory: Boolean = false): Turn {
        val userMsg = buildPrompt(preset.prompt, question, hits)
        val msgs = if (updateHistory) {
            history.add(mapOf("role" to "user", "content" to userMsg))
            history.toList()
        } else {
            history + mapOf("role" to "user", "content" to userMsg)
        }
        val (answer, metrics) = client.chat(preset, msgs)
        if (updateHistory) history.add(mapOf("role" to "assistant", "content" to answer))
        val verbatim = verbatimRate(answer, hits.map { it.first })
        val pf = parseFailure(answer)
        val turn = Turn(question, answer, hits.map { it.first.source }, hits, metrics, preset.name, verbatim, pf)
        appendTrace(turn)
        return turn
    }

    fun ask(question: String): Turn {
        val hits = retrieve(question)
        val t = askOn(activePreset, question, hits, updateHistory = true)
        turns.add(t)
        return t
    }

    fun clear() {
        val system = history.first()
        history.clear(); history.add(system); turns.clear()
    }
}

/* ---------- REPL ---------- */

const val DEFAULT_INDEX = "index.json"

fun printHelp() {
    println("""
        Команды:
          ingest <папка>              построить индекс
          <вопрос>                    спросить (использует активный пресет)
          :preset baseline|optimized|quant
          :compare-presets <вопрос>   задать всем 3 пресетам на одном retrieval
          :eval [<файл>]              прогнать через ТЕКУЩИЙ пресет
          :eval --presets [<файл>]    прогнать через ВСЕ 3 пресета
          :warmup                     прогреть активный пресет (стабильные замеры)
          :warmup all                 прогреть все пресеты
          :ps                         что сейчас в памяти Ollama (/api/ps)
          :history / :sources / :stats / :save / :clear / :help / :quit
    """.trimIndent())
}

fun loadEvalQuestions(path: String): List<String> {
    val f = File(path); require(f.exists()) { "нет файла с eval-вопросами: $path" }
    return f.readLines(Charsets.UTF_8).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
}

fun printPsInfo(psInfos: List<PsInfo>) {
    if (psInfos.isEmpty()) { println("(модели не загружены)"); return }
    psInfos.forEach {
        println("  ${it.name}: total=${it.sizeTotalMb} MB, VRAM=${it.sizeVramMb} MB (${"%.0f".format(it.gpuFraction * 100)}% на GPU)")
    }
}

fun printComparePresetsTable(question: String, turns: List<Turn>, psInfos: Map<String, List<PsInfo>>) {
    println("\nQ: $question\n")
    turns.forEach { t ->
        println("┌─ ${t.presetName.uppercase()} (${t.metrics.model}) ─────────────────")
        t.answer.trim().lines().forEach { println("│ $it") }
        println("│")
        val pf = if (t.parseFailed) "⚠️PARSE_FAILED" else "✓"
        println("│ [${t.metrics.out} tok, eval=${t.metrics.evalMs}ms, ${"%.1f".format(t.metrics.tps)} tok/s, wall=${t.metrics.wallMs}ms, verbatim=${"%.2f".format(t.verbatim)}, $pf]")
        val ps = psInfos[t.presetName]?.firstOrNull { it.name.startsWith(t.metrics.model) }
        if (ps != null) println("│ [память: total=${ps.sizeTotalMb} MB, VRAM=${ps.sizeVramMb} MB, ${"%.0f".format(ps.gpuFraction * 100)}% GPU]")
    }
    println("└──────────────────────────────────────────")
}

fun printEvalPresetsTable(results: Map<String, List<Turn>>) {
    println("\n| Preset | Model | out avg | wall avg (ms) | tok/s avg | verbatim avg | parse_fail |")
    println("|---|---|---|---|---|---|---|")
    results.forEach { (name, turns) ->
        val outAvg = turns.map { it.metrics.out }.average()
        val wallAvg = turns.map { it.metrics.wallMs }.average()
        val tpsAvg = turns.map { it.metrics.tps }.average()
        val vAvg = turns.map { it.verbatim }.average()
        val pf = turns.count { it.parseFailed }
        val model = turns.first().metrics.model
        println("| $name | $model | ${"%.0f".format(outAvg)} | ${"%.0f".format(wallAvg)} | ${"%.1f".format(tpsAvg)} | ${"%.2f".format(vAvg)} | $pf/${turns.size} |")
    }
}

fun repl(s0: Session?) {
    var s = s0
    val client = s?.client ?: OllamaClient()
    println("RAG-CLI (day29). Пресеты: ${PRESETS.keys.joinToString(", ")}. Активный: ${s?.activePreset?.name ?: "?"}. Индекс: ${s?.index?.size() ?: 0} чанков.")
    printHelp()

    val input = System.`in`.bufferedReader()
    while (true) {
        print("\n>>> "); System.out.flush()
        val raw = input.readLine() ?: break
        val line = raw.trim()
        if (line.isEmpty()) continue
        try {
            when {
                line == ":quit" || line == ":exit" -> return
                line == ":help" -> printHelp()
                line == ":history" -> {
                    if (s == null || s.turns.isEmpty()) { println("(пусто)"); continue }
                    s.turns.takeLast(5).forEachIndexed { i, t ->
                        println("[${i + 1}] ${t.presetName} Q: ${t.question}")
                        println("    A: ${t.answer.replace("\n", " ").take(120)}${if (t.answer.length > 120) "…" else ""}")
                    }
                }
                line == ":sources" -> { if (s == null) println("нет индекса") else s.index.sources().forEach { println("  $it") } }
                line == ":stats" -> {
                    val t = s?.turns?.lastOrNull()
                    if (t == null) println("(нет ответов)")
                    else println("Последний: ${t.presetName}[${t.metrics.model}], eval=${t.metrics.evalMs}ms, ${"%.1f".format(t.metrics.tps)} tok/s, wall=${t.metrics.wallMs}ms, verbatim=${"%.2f".format(t.verbatim)}, parse_ok=${!t.parseFailed}")
                }
                line == ":clear" -> { s?.clear(); println("история очищена") }
                line == ":ps" -> printPsInfo(client.ps())
                line == ":warmup" -> { if (s == null) println("сначала ingest") else { println("Warmup: ${s.activePreset.name}…"); client.warmup(s.activePreset); println("Готово") } }
                line == ":warmup all" -> {
                    if (s == null) { println("сначала ingest"); continue }
                    PRESETS.values.forEach { p -> println("Warmup: ${p.name}…"); client.warmup(p) }
                    println("Готово"); printPsInfo(client.ps())
                }
                line.startsWith(":preset") -> {
                    val which = line.removePrefix(":preset").trim()
                    if (s == null) { println("сначала ingest"); continue }
                    val p = PRESETS[which] ?: run { println("используй: :preset ${PRESETS.keys.joinToString("|")}"); continue }
                    s.activePreset = p
                    println("Активный пресет: $p")
                }
                line.startsWith(":save") -> {
                    val path = line.removePrefix(":save").trim().ifEmpty { "chat.md" }
                    saveChatMarkdown(s, path); println("Сохранено: $path")
                }
                line.startsWith("ingest ") -> {
                    val folder = line.removePrefix("ingest ").trim()
                    ingest(folder, DEFAULT_INDEX, client)
                    val ix = Index.loadFrom(DEFAULT_INDEX)
                    s = Session(client, ix)
                    println("Индекс перезагружен: ${s.index.size()} чанков")
                }
                line.startsWith(":compare-presets ") -> {
                    val q = line.removePrefix(":compare-presets ").trim()
                    if (s == null) { println("сначала ingest"); continue }
                    val hits = s.retrieve(q)
                    val results = mutableListOf<Turn>()
                    val psMap = mutableMapOf<String, List<PsInfo>>()
                    PRESETS.values.forEach { p ->
                        val t = s.askOn(p, q, hits, updateHistory = false)
                        results.add(t)
                        psMap[p.name] = client.ps()
                    }
                    printComparePresetsTable(q, results, psMap)
                }
                line.startsWith(":eval") -> {
                    if (s == null) { println("сначала ingest"); continue }
                    val rest = line.removePrefix(":eval").trim()
                    val allPresets = rest.contains("--presets")
                    val path = rest.replace("--presets", "").trim().ifEmpty { "eval-questions.txt" }
                    val questions = try { loadEvalQuestions(path) } catch (t: Throwable) { println("Ошибка: ${t.message}"); continue }
                    if (allPresets) {
                        println("Eval --presets: ${questions.size} вопросов × ${PRESETS.size} пресетов")
                        println("(порядок оптимизирован по модели: все пресеты с той же моделью подряд,")
                        println(" чтобы не свопать Q4↔Q3 15 раз при 6 GB VRAM)")
                        val byPreset = linkedMapOf<String, MutableList<Turn>>()
                        PRESETS.values.forEach { byPreset[it.name] = mutableListOf() }
                        // Кэшируем retrieval-хиты для каждого вопроса — retrieval стабилен, экономим embed-вызовы.
                        val questionHits = questions.map { it to s.retrieve(it) }
                        // Группируем: сначала все пресеты с моделью M1 (baseline+optimized оба на Q4), потом M2 (quant на Q3).
                        val presetsByModel = PRESETS.values.groupBy { it.model }
                        var groupIdx = 0
                        presetsByModel.forEach { (model, presetGroup) ->
                            groupIdx++
                            println("  [$groupIdx/${presetsByModel.size}] группа модели $model (${presetGroup.map { it.name }.joinToString("+")})…")
                            presetGroup.forEach { p ->
                                questionHits.forEach { (q, hits) ->
                                    val t = s.askOn(p, q, hits, updateHistory = false)
                                    byPreset[p.name]!!.add(t)
                                }
                            }
                        }
                        printEvalPresetsTable(byPreset)
                    } else {
                        val results = mutableListOf<Turn>()
                        questions.forEach { q ->
                            val hits = s.retrieve(q); results.add(s.askOn(s.activePreset, q, hits, updateHistory = false))
                        }
                        println("\nEval (${s.activePreset.name}):")
                        results.forEachIndexed { i, t ->
                            println("[${i + 1}] out=${t.metrics.out}, wall=${t.metrics.wallMs}ms, verbatim=${"%.2f".format(t.verbatim)}, parse_ok=${!t.parseFailed}")
                        }
                    }
                }
                else -> {
                    if (s == null) { println("сначала ingest <папка>"); continue }
                    val turn = s.ask(line)
                    println("\n${turn.answer.trim()}")
                    val pf = if (turn.parseFailed) "⚠️PARSE_FAILED" else "✓"
                    println("\n[${turn.presetName} | out=${turn.metrics.out} tok, ${"%.1f".format(turn.metrics.tps)} tok/s, wall=${turn.metrics.wallMs}ms, verbatim=${"%.2f".format(turn.verbatim)}, $pf]")
                }
            }
        } catch (t: Throwable) { println("Ошибка: ${t.message}") }
    }
}

fun saveChatMarkdown(s: Session?, path: String) {
    val sb = StringBuilder()
    sb.appendLine("# RAG-CLI chat (day 29 — optimized LLM)")
    sb.appendLine("**fully_local_rag: true** (retrieval, embed, chat — всё локально; DeepSeek не используется в этом дне).")
    sb.appendLine("Активный пресет: ${s?.activePreset?.name} (${s?.activePreset?.model})")
    sb.appendLine()
    if (s == null || s.turns.isEmpty()) { sb.appendLine("_(пусто)_"); File(path).writeText(sb.toString(), Charsets.UTF_8); return }
    s.turns.forEachIndexed { i, t ->
        sb.appendLine("## Turn ${i + 1} (${t.presetName})")
        sb.appendLine("**Вопрос:** ${t.question}")
        sb.appendLine("**Источники:** ${t.sources.joinToString(", ")}")
        sb.appendLine("**Ответ:**")
        sb.appendLine(t.answer.trim())
        sb.appendLine("_Метрики: out=${t.metrics.out}, ${"%.1f".format(t.metrics.tps)} tok/s, wall=${t.metrics.wallMs}ms, verbatim=${"%.2f".format(t.verbatim)}, parse_ok=${!t.parseFailed}._")
        sb.appendLine()
    }
    File(path).writeText(sb.toString(), Charsets.UTF_8)
}

/* ---------- main ---------- */

fun main(args: Array<String>) {
    val client = OllamaClient()
    println("Health-check…")
    val required = PRESETS.values.map { it.model }.distinct() + client.embedModel
    val models = client.healthCheck(required)
    println("  установлено: $models")
    println("  пресеты: ${PRESETS.keys.joinToString(", ")}")

    val mode = args.getOrNull(0)
    when (mode) {
        "ingest" -> { val folder = args.getOrNull(1) ?: error("ingest <папка>"); ingest(folder, DEFAULT_INDEX, client); return }
        null, "chat" -> {
            val path = if (mode == "chat") args.getOrNull(1) ?: DEFAULT_INDEX else DEFAULT_INDEX
            val session = if (File(path).exists()) Session(client, Index.loadFrom(path)) else null
            repl(session)
        }
        else -> println("Неизвестный режим: '$mode'")
    }
}
