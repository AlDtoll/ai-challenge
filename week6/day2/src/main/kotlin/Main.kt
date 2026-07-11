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

/* ============================================================
 * Day 27 — RAG-CLI над локальными документами.
 *
 * Индексирует .md-файлы папки → nomic-embed-text (Ollama, локально) → JSON-индекс.
 * Затем интерактивный REPL:
 *   ingest <folder>   — построить индекс
 *   ask <вопрос>      — top-3 чанка + qwen2.5:7b с цитатами
 *   :history / :clear / :stats / :save / :quit
 *
 * Всё локально: nomic-embed-text (эмбеддинги) + qwen2.5:7b (чат).
 * ============================================================ */

/* ---------- HTTP-обёртка ---------- */

class OllamaClient(
    private val baseUrl: String = "http://localhost:11434",
    val chatModel: String = "qwen2.5:7b",
    val embedModel: String = "nomic-embed-text",
) {
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()
    private val gson = Gson()

    fun healthCheck(): List<String> {
        val tags = JsonParser.parseString(get("/api/tags")).asJsonObject
        val models = tags.getAsJsonArray("models").map { it.asJsonObject["name"].asString }
        require(chatModel in models) { "модель '$chatModel' не установлена. ollama pull $chatModel" }
        require(embedModel in models) { "модель '$embedModel' не установлена. ollama pull $embedModel" }
        return models
    }

    fun embed(text: String): FloatArray {
        val body = gson.toJson(mapOf("model" to embedModel, "input" to text))
        val resp = post("/api/embed", body)
        val json = JsonParser.parseString(resp).asJsonObject
        val vec = json.getAsJsonArray("embeddings").first().asJsonArray
        return FloatArray(vec.size()) { vec[it].asFloat }
    }

    data class ChatMetrics(val prompt: Int, val out: Int, val evalMs: Long, val wallMs: Long) {
        val tps: Double = if (evalMs > 0) out * 1000.0 / evalMs else 0.0
    }

    // Один turn чата с полной историей + метриками.
    fun chat(messages: List<Map<String, String>>): Pair<String, ChatMetrics> {
        val body = gson.toJson(
            mapOf(
                "model" to chatModel,
                "messages" to messages,
                "stream" to false,
                "options" to mapOf("temperature" to 0.2),
            )
        )
        val wallStart = System.currentTimeMillis()
        val resp = post("/api/chat", body)
        val wall = System.currentTimeMillis() - wallStart

        val json = JsonParser.parseString(resp).asJsonObject
        val answer = json.getAsJsonObject("message")["content"].asString
        fun ns(f: String): Long = json[f]?.asLong ?: 0L
        val metrics = ChatMetrics(
            prompt = json["prompt_eval_count"]?.asInt ?: 0,
            out = json["eval_count"]?.asInt ?: 0,
            evalMs = ns("eval_duration") / 1_000_000,
            wallMs = wall,
        )
        return answer to metrics
    }

    private fun get(path: String): String {
        val req = HttpRequest.newBuilder(URI.create("$baseUrl$path")).timeout(Duration.ofSeconds(10)).GET().build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        require(resp.statusCode() == 200) { "GET $path → ${resp.statusCode()}: ${resp.body()}" }
        return resp.body()
    }

    private fun post(path: String, body: String): String {
        val req = HttpRequest.newBuilder(URI.create("$baseUrl$path"))
            .timeout(Duration.ofMinutes(5))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        require(resp.statusCode() == 200) { "POST $path → ${resp.statusCode()}: ${resp.body()}" }
        return resp.body()
    }
}

/* ---------- Chunk + Index ---------- */

data class Chunk(
    val source: String,   // относительный путь файла
    val idx: Int,         // порядковый номер чанка в файле
    val text: String,     // сам текст чанка
    val vec: FloatArray,  // embedding
) {
    val id: String get() = "$source#$idx"
    val preview: String get() = text.replace(Regex("\\s+"), " ").take(80) + (if (text.length > 80) "…" else "")
}

// Простой fixed-size chunker с overlap.
fun chunkMarkdown(text: String, size: Int = 800, overlap: Int = 150): List<String> {
    val cleaned = text.trim()
    if (cleaned.length <= size) return listOf(cleaned)
    val out = mutableListOf<String>()
    var i = 0
    while (i < cleaned.length) {
        val end = (i + size).coerceAtMost(cleaned.length)
        val piece = cleaned.substring(i, end)
        // Постараемся резать по абзацу если рядом
        var cut = piece
        if (end < cleaned.length) {
            val lastNL = piece.lastIndexOf("\n\n")
            if (lastNL > size - 200) cut = piece.substring(0, lastNL)
        }
        out.add(cut.trim())
        i += cut.length - overlap
        if (i <= 0) i = size
    }
    return out.filter { it.isNotBlank() }
}

fun cosine(a: FloatArray, b: FloatArray): Double {
    require(a.size == b.size) { "vec size mismatch: ${a.size} vs ${b.size}" }
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
            .sortedByDescending { it.second }
            .take(k)

    fun saveTo(path: String) {
        val gson = Gson()
        val jsonList = chunks.map {
            mapOf(
                "source" to it.source,
                "idx" to it.idx,
                "text" to it.text,
                "vec" to it.vec.toList(),
            )
        }
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
                    source = o["source"].asString,
                    idx = o["idx"].asInt,
                    text = o["text"].asString,
                    vec = FloatArray(o.getAsJsonArray("vec").size()) { i -> o.getAsJsonArray("vec")[i].asFloat },
                )
            }
            return Index(ch.toMutableList())
        }
    }
}

/* ---------- Ingest ---------- */

fun ingest(folder: String, indexPath: String, client: OllamaClient) {
    val root = File(folder)
    require(root.exists() && root.isDirectory) { "папка не найдена: $folder" }

    val files = root.walkTopDown()
        .filter { it.isFile && (it.extension.equals("md", true) || it.extension.equals("txt", true)) }
        .toList()
    require(files.isNotEmpty()) { "нет .md/.txt файлов в '$folder'" }

    println("Ingest: '$folder' → ${files.size} файлов")
    val index = Index()
    val t0 = System.currentTimeMillis()

    files.forEachIndexed { fi, file ->
        val text = file.readText(Charsets.UTF_8)
        val rel = file.relativeTo(root).path.replace('\\', '/')
        val pieces = chunkMarkdown(text)
        pieces.forEachIndexed { ci, piece ->
            // Nomic-embed рекомендует префикс "search_document: " для индексируемого текста.
            val vec = client.embed("search_document: $piece")
            index.chunks.add(Chunk(source = rel, idx = ci, text = piece, vec = vec))
        }
        println("  [${fi + 1}/${files.size}] $rel — ${pieces.size} чанков")
    }

    index.saveTo(indexPath)
    val dt = (System.currentTimeMillis() - t0) / 1000.0
    println("Готово: ${index.size()} чанков из ${index.sources().size} файлов за ${"%.1f".format(dt)} с.")
    println("Индекс: $indexPath")
}

/* ---------- Ask (RAG-turn) ---------- */

data class Turn(val question: String, val answer: String, val sources: List<String>, val metrics: OllamaClient.ChatMetrics) {
    fun toJsonLine(gson: Gson, ts: Long): String = gson.toJson(mapOf(
        "ts" to ts,
        "question" to question,
        "answer" to answer,
        "sources" to sources,
        "prompt_tokens" to metrics.prompt,
        "out_tokens" to metrics.out,
        "eval_ms" to metrics.evalMs,
        "wall_ms" to metrics.wallMs,
        "tokens_per_sec" to metrics.tps,
    ))
}

// JSONL-трейс: append-only лог всех turn'ов на диске (для отладки + видео).
const val TRACE_PATH = "chat.log.jsonl"

fun appendTrace(turn: Turn) {
    val gson = Gson()
    File(TRACE_PATH).appendText(turn.toJsonLine(gson, System.currentTimeMillis()) + "\n", Charsets.UTF_8)
}

fun buildPromptWithContext(question: String, hits: List<Pair<Chunk, Double>>): String {
    val sb = StringBuilder()
    sb.appendLine("Контекст (проиндексированные фрагменты локальных документов):")
    sb.appendLine()
    hits.forEachIndexed { i, (chunk, score) ->
        sb.appendLine("[S${i + 1}] источник: ${chunk.source} (score=${"%.3f".format(score)})")
        sb.appendLine(chunk.text.trim())
        sb.appendLine()
    }
    sb.appendLine("---")
    sb.appendLine("Вопрос: $question")
    sb.appendLine()
    sb.appendLine("Отвечай кратко на русском. Ссылайся на источники маркерами [S1]/[S2]/[S3]. Если контекста не хватает — так и скажи.")
    return sb.toString()
}

class Session(
    val client: OllamaClient,
    val index: Index,
    val history: MutableList<Map<String, String>> = mutableListOf(
        mapOf("role" to "system", "content" to "Ты помощник, отвечающий по локальным документам. Отвечай кратко, только на основе контекста. Если контекста не хватает — так и скажи."),
    ),
    val turns: MutableList<Turn> = mutableListOf(),
) {
    fun ask(question: String, k: Int = 3): Turn {
        // 1. Эмбеддинг вопроса.
        val qvec = client.embed("search_query: $question")
        val hits = index.search(qvec, k)

        // 2. Промпт с контекстом.
        val userMsg = buildPromptWithContext(question, hits)
        history.add(mapOf("role" to "user", "content" to userMsg))

        // 3. Чат с моделью.
        val (answer, metrics) = client.chat(history)
        history.add(mapOf("role" to "assistant", "content" to answer))

        val turn = Turn(question, answer, hits.map { it.first.source }, metrics)
        turns.add(turn)
        appendTrace(turn)
        return turn
    }

    // Evaluation: 3-5 контрольных вопросов на чистой истории (регресс-тест RAG).
    fun eval(questions: List<String>): List<Turn> {
        val results = mutableListOf<Turn>()
        val savedHistory = history.toList()
        val savedTurns = turns.toList()
        for (q in questions) {
            clear()  // чистая история между вопросами: eval — не диалог
            val t = ask(q)
            results.add(t)
        }
        // Восстановить пользовательскую сессию.
        history.clear(); history.addAll(savedHistory)
        turns.clear(); turns.addAll(savedTurns)
        return results
    }

    fun clear() {
        val system = history.first()
        history.clear()
        history.add(system)
        turns.clear()
    }
}

/* ---------- REPL ---------- */

const val DEFAULT_INDEX = "index.json"

fun printHelp() {
    println("""
        Команды:
          ingest <папка>         построить индекс из .md/.txt файлов
          <вопрос>               спросить (использует последний индекс)
          :history               последние ходы
          :sources               файлы в индексе
          :stats                 метрики последнего ответа
          :save <путь>           сохранить историю в markdown
          :clear                 очистить историю (индекс не трогает)
          :eval [<путь>]         прогнать контрольные вопросы (по умолчанию eval-questions.txt)
          :help                  эта справка
          :quit / :exit          выход
    """.trimIndent())
}

fun loadEvalQuestions(path: String): List<String> {
    val f = File(path)
    require(f.exists()) { "нет файла с eval-вопросами: $path (одна строка = один вопрос, # для комментария)" }
    return f.readLines(Charsets.UTF_8)
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
}

fun repl(client: OllamaClient, session: Session?) {
    var s = session
    println("RAG-CLI: chat=${client.chatModel}, embed=${client.embedModel}. Индекс: ${s?.index?.size() ?: 0} чанков.")
    printHelp()

    val input = System.`in`.bufferedReader()
    while (true) {
        print("\n>>> "); System.out.flush()
        val raw = input.readLine() ?: break
        val line = raw.trim()
        if (line.isEmpty()) continue

        when {
            line == ":quit" || line == ":exit" -> return
            line == ":help" -> printHelp()
            line == ":history" -> {
                if (s == null || s.turns.isEmpty()) { println("(пусто)"); continue }
                s.turns.takeLast(5).forEachIndexed { i, t ->
                    println("[${i + 1}] Q: ${t.question}")
                    println("    A: ${t.answer.replace("\n", " ").take(120)}${if (t.answer.length > 120) "…" else ""}")
                }
            }
            line == ":sources" -> {
                if (s == null) { println("нет индекса"); continue }
                s.index.sources().forEach { println("  $it") }
            }
            line == ":stats" -> {
                val t = s?.turns?.lastOrNull()
                if (t == null) println("(нет ответов)")
                else {
                    println("Последний ответ: prompt=${t.metrics.prompt} → out=${t.metrics.out} tokens, ${t.metrics.evalMs} ms, ${"%.1f".format(t.metrics.tps)} tok/s, wall=${t.metrics.wallMs} ms")
                    println("Источники: ${t.sources.joinToString(", ")}")
                }
            }
            line == ":clear" -> { s?.clear(); println("история очищена") }
            line.startsWith(":save") -> {
                val path = line.removePrefix(":save").trim().ifEmpty { "chat.md" }
                saveChatMarkdown(s, path)
                println("Сохранено: $path")
            }
            line.startsWith(":eval") -> {
                if (s == null) { println("сначала ingest <папка>"); continue }
                val path = line.removePrefix(":eval").trim().ifEmpty { "eval-questions.txt" }
                val questions = try { loadEvalQuestions(path) } catch (t: Throwable) {
                    println("Ошибка: ${t.message}"); continue
                }
                println("Eval: ${questions.size} вопросов (индекс: ${s.index.size()} чанков, чистая история)")
                val results = s.eval(questions)
                println()
                println("| # | Вопрос | Источники | out | tok/s | wall |")
                println("|---|---|---|---|---|---|")
                results.forEachIndexed { i, t ->
                    val q = t.question.take(60).replace("|", "\\|")
                    val src = t.sources.joinToString(",").replace("|", "\\|")
                    println("| ${i + 1} | $q | $src | ${t.metrics.out} | ${"%.1f".format(t.metrics.tps)} | ${t.metrics.wallMs} |")
                }
                val avgTps = results.map { it.metrics.tps }.average()
                val totalWall = results.sumOf { it.metrics.wallMs }
                println("\nСредняя tok/s: ${"%.1f".format(avgTps)}, суммарный wall: ${totalWall} ms")
            }
            line.startsWith("ingest ") -> {
                val folder = line.removePrefix("ingest ").trim()
                ingest(folder, DEFAULT_INDEX, client)
                s = Session(client, Index.loadFrom(DEFAULT_INDEX))
                println("Индекс перезагружен: ${s.index.size()} чанков")
            }
            else -> {
                if (s == null) {
                    println("сначала ingest <папка> (или запусти CLI с флагом --args=\"chat <index-путь>\")")
                    continue
                }
                val t0 = System.currentTimeMillis()
                val turn = s.ask(line)
                val total = System.currentTimeMillis() - t0
                println("\n${turn.answer.trim()}")
                println("\n[источники: ${turn.sources.joinToString(", ")} | ${turn.metrics.out} tokens, ${"%.1f".format(turn.metrics.tps)} tok/s, wall=${total} ms]")
            }
        }
    }
}

fun saveChatMarkdown(s: Session?, path: String) {
    val sb = StringBuilder()
    sb.appendLine("# RAG-CLI chat")
    sb.appendLine()
    if (s == null || s.turns.isEmpty()) { sb.appendLine("_(пусто)_"); File(path).writeText(sb.toString(), Charsets.UTF_8); return }
    sb.appendLine("Модель chat: ${s.client.chatModel}, embed: ${s.client.embedModel}, чанков в индексе: ${s.index.size()}.")
    sb.appendLine()
    s.turns.forEachIndexed { i, t ->
        sb.appendLine("## Turn ${i + 1}")
        sb.appendLine()
        sb.appendLine("**Вопрос:** ${t.question}")
        sb.appendLine()
        sb.appendLine("**Источники:** ${t.sources.joinToString(", ")}")
        sb.appendLine()
        sb.appendLine("**Ответ:**")
        sb.appendLine()
        sb.appendLine(t.answer.trim())
        sb.appendLine()
        sb.appendLine("_Метрики: prompt=${t.metrics.prompt} → out=${t.metrics.out}, ${"%.1f".format(t.metrics.tps)} tok/s, wall=${t.metrics.wallMs} ms._")
        sb.appendLine()
    }
    File(path).writeText(sb.toString(), Charsets.UTF_8)
}

/* ---------- main ---------- */

fun main(args: Array<String>) {
    val client = OllamaClient()

    println("Health-check…")
    val models = client.healthCheck()
    println("  chat=${client.chatModel}, embed=${client.embedModel}. Всего моделей: ${models.size}")

    // Режимы:
    //   (нет args)              → сразу REPL, если index.json есть — грузим, иначе просим ingest.
    //   "ingest <folder>"       → build index + выход.
    //   "chat <index-path>"     → REPL с указанным индексом.

    val mode = args.getOrNull(0)
    val session: Session? = when (mode) {
        "ingest" -> {
            val folder = args.getOrNull(1) ?: error("ingest <папка>")
            ingest(folder, DEFAULT_INDEX, client)
            return
        }
        "chat" -> {
            val path = args.getOrNull(1) ?: DEFAULT_INDEX
            Session(client, Index.loadFrom(path))
        }
        null -> {
            // Есть готовый индекс — сразу его.
            val f = File(DEFAULT_INDEX)
            if (f.exists()) Session(client, Index.loadFrom(DEFAULT_INDEX)) else null
        }
        else -> {
            println("Неизвестный режим: '$mode'. Ожидаю: ingest <папка> | chat [<index>] | (пусто)")
            return
        }
    }

    repl(client, session)
}
