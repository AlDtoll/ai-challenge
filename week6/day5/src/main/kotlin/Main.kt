import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.sun.net.httpserver.Filter
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt
import kotlin.system.exitProcess

/* =====================================================================
 * Day 30 (week6/day5) — Локальная LLM как приватный HTTP-сервис.
 * JDK HttpServer + auth + token-bucket + semaphore + /v1/chat + /v1/rag.
 * Ноль сторонних HTTP-зависимостей (com.sun.net.httpserver + java.net.http).
 * ===================================================================== */

// =====================================================================
// Config
// =====================================================================

data class Config(
    val port: Int = 7788,
    val apiKey: String = "",
    val rateRpm: Int = 60,
    val maxConcurrent: Int = 2,
    val maxCtx: Int = 4096,
    val ollamaHost: String = "http://127.0.0.1:11434",
    val model: String = "qwen2.5:7b",
    val embedModel: String = "nomic-embed-text",
    val demo: Boolean = false,
    val indexPath: String = "index.json",
    val ingestDir: String? = null,
    val maxInputChars: Int = 8000,
    val timeoutSec: Long = 180,
) {
    fun redacted(): String =
        "Config(port=$port, apiKey=${if (apiKey.isBlank()) "<open>" else "<set,len=${apiKey.length}>"}, " +
            "rateRpm=$rateRpm, maxConcurrent=$maxConcurrent, maxCtx=$maxCtx, model=$model, " +
            "demo=$demo, indexPath=$indexPath)"
}

fun parseArgs(args: Array<String>): Config {
    fun env(name: String) = System.getenv(name)?.takeIf { it.isNotBlank() }

    var port = env("LLM_SERVICE_PORT")?.toInt() ?: 7788
    var apiKey = env("LLM_API_KEY") ?: env("SERVICE_API_KEY") ?: ""
    var rateRpm = env("LLM_RATE_RPM")?.toInt() ?: 60
    var maxConcurrent = env("LLM_MAX_CONCURRENT")?.toInt() ?: 2
    var maxCtx = env("LLM_MAX_CTX")?.toInt() ?: 4096
    val ollamaHost = env("OLLAMA_HOST") ?: "http://127.0.0.1:11434"
    var model = env("LLM_MODEL") ?: "qwen2.5:7b"
    var demo = false
    var ingestDir: String? = null

    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "--port" -> { port = args[++i].toInt() }
            "--api-key" -> { apiKey = args[++i] }
            "--rate-rpm" -> { rateRpm = args[++i].toInt() }
            "--max-concurrent" -> { maxConcurrent = args[++i].toInt() }
            "--max-ctx" -> { maxCtx = args[++i].toInt() }
            "--model" -> { model = args[++i] }
            "--demo" -> { demo = true }
            "--ingest" -> { ingestDir = args[++i] }
            "--help", "-h" -> { printHelp(); exitProcess(0) }
            else -> { System.err.println("unknown arg: $a"); printHelp(); exitProcess(2) }
        }
        i++
    }

    return Config(
        port = port, apiKey = apiKey, rateRpm = rateRpm, maxConcurrent = maxConcurrent,
        maxCtx = maxCtx, ollamaHost = ollamaHost, model = model, demo = demo,
        ingestDir = ingestDir,
    )
}

fun printHelp() {
    println(
        """
        Usage: gradlew :week6:day5:run --args="[flags]"

          --port <n>              HTTP port (default 7788, env LLM_SERVICE_PORT)
          --api-key <s>           API key; empty = open mode (env LLM_API_KEY)
          --rate-rpm <n>          Per-IP token bucket rpm (default 60)
          --max-concurrent <n>    Concurrency semaphore size (default 2)
          --max-ctx <n>           num_ctx passed to Ollama (default 4096)
          --model <name>          Chat model (default qwen2.5:7b)
          --ingest <dir>          Build index.json from dir and exit
          --demo                  Run built-in demo (in-process curl-equivalents)
          --help                  This message
        """.trimIndent()
    )
}

// =====================================================================
// Ollama client
// =====================================================================

private val gson = Gson()
private val http: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .build()

data class ChatMsg(val role: String, val content: String)

data class ChatResult(
    val content: String,
    val tokensOut: Int,
    val wallMs: Long,
    val evalMs: Long,
    val timedOut: Boolean,
)

class OllamaClient(private val cfg: Config) {

    fun tags(): List<String> {
        val req = HttpRequest.newBuilder(URI.create("${cfg.ollamaHost}/api/tags"))
            .timeout(Duration.ofSeconds(5))
            .GET().build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (res.statusCode() != 200) return emptyList()
        val json = gson.fromJson(res.body(), JsonObject::class.java)
        val arr = json.getAsJsonArray("models") ?: return emptyList()
        return arr.mapNotNull { it.asJsonObject.get("name")?.asString }
    }

    fun ping(): Boolean = try { tags().isNotEmpty() } catch (_: Exception) { false }

    fun chat(
        messages: List<ChatMsg>,
        temperature: Double = 0.0,
        numPredict: Int = 256,
        numCtx: Int = cfg.maxCtx,
        model: String = cfg.model,
    ): ChatResult {
        val body = mapOf(
            "model" to model,
            "messages" to messages.map { mapOf("role" to it.role, "content" to it.content) },
            "stream" to false,
            "options" to mapOf(
                "temperature" to temperature,
                "num_ctx" to numCtx,
                "num_predict" to numPredict,
                "top_p" to 0.5,
                "repeat_penalty" to 1.2,
            ),
        )
        val req = HttpRequest.newBuilder(URI.create("${cfg.ollamaHost}/api/chat"))
            .timeout(Duration.ofSeconds(cfg.timeoutSec))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body), StandardCharsets.UTF_8))
            .build()

        val t0 = System.currentTimeMillis()
        return try {
            val res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            val wall = System.currentTimeMillis() - t0
            val j = gson.fromJson(res.body(), JsonObject::class.java)
            val content = j.getAsJsonObject("message")?.get("content")?.asString ?: ""
            val evalCount = j.get("eval_count")?.asInt ?: 0
            val evalNs = j.get("eval_duration")?.asLong ?: 0L
            ChatResult(content, evalCount, wall, evalNs / 1_000_000, false)
        } catch (e: java.net.http.HttpTimeoutException) {
            ChatResult("", 0, System.currentTimeMillis() - t0, 0, true)
        }
    }

    fun embed(text: String): DoubleArray {
        val body = mapOf("model" to cfg.embedModel, "input" to text)
        val req = HttpRequest.newBuilder(URI.create("${cfg.ollamaHost}/api/embed"))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body), StandardCharsets.UTF_8))
            .build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        val j = gson.fromJson(res.body(), JsonObject::class.java)
        val arr = j.getAsJsonArray("embeddings").get(0).asJsonArray
        return DoubleArray(arr.size()) { arr.get(it).asDouble }
    }
}

// =====================================================================
// RAG (mini): читаем/строим index.json — совместимый формат с day2/3/4
// =====================================================================

data class Chunk(val id: Int, val source: String, val text: String, val embedding: DoubleArray)
data class IndexFile(val chunks: List<Chunk>)

fun cosine(a: DoubleArray, b: DoubleArray): Double {
    var dot = 0.0; var na = 0.0; var nb = 0.0
    for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
    return if (na == 0.0 || nb == 0.0) 0.0 else dot / (sqrt(na) * sqrt(nb))
}

fun loadIndex(path: String): List<Chunk> {
    val f = File(path)
    if (!f.exists()) return emptyList()
    val type = object : TypeToken<IndexFile>() {}.type
    return gson.fromJson<IndexFile>(f.readText(Charsets.UTF_8), type).chunks
}

fun saveIndex(path: String, chunks: List<Chunk>) {
    File(path).writeText(gson.toJson(IndexFile(chunks)), Charsets.UTF_8)
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

fun ingest(dir: File, ollama: OllamaClient): List<Chunk> {
    val files = dir.walkTopDown().filter { it.isFile && it.extension.lowercase() in listOf("md", "txt") }.toList()
    val out = mutableListOf<Chunk>()
    var id = 0
    for (f in files) {
        val chunks = chunkMarkdown(f.readText(Charsets.UTF_8))
        for (c in chunks) {
            val emb = ollama.embed(c)
            out.add(Chunk(id++, f.name, c, emb))
        }
    }
    return out
}

// =====================================================================
// TokenBucket per-IP
// =====================================================================

class TokenBucket(private val rpm: Int) {
    private data class State(var tokens: Double, var lastMs: Long)
    private val buckets = ConcurrentHashMap<String, State>()
    private val lock = Any()
    fun allow(ip: String): Boolean {
        val now = System.currentTimeMillis()
        val rate = rpm / 60.0
        synchronized(lock) {
            val s = buckets.getOrPut(ip) { State(rpm - 1.0, now) }
            val elapsed = (now - s.lastMs) / 1000.0
            s.tokens = (s.tokens + elapsed * rate).coerceAtMost(rpm.toDouble())
            s.lastMs = now
            if (s.tokens < 1.0) return false
            s.tokens -= 1.0
            return true
        }
    }
}

// =====================================================================
// Stats
// =====================================================================

object Stats {
    val totalRequests = AtomicLong()
    val ok = AtomicLong()
    val err400 = AtomicLong()
    val err401 = AtomicLong()
    val err429 = AtomicLong()
    val err503 = AtomicLong()
    val err504 = AtomicLong()
    val chatOk = AtomicLong()
    val ragOk = AtomicLong()
    val totalWallMs = AtomicLong()
    val startedAtMs = System.currentTimeMillis()
}

// =====================================================================
// HTTP helpers
// =====================================================================

fun sendJson(x: HttpExchange, code: Int, obj: Any) {
    val body = gson.toJson(obj).toByteArray(Charsets.UTF_8)
    x.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
    x.sendResponseHeaders(code, body.size.toLong())
    x.responseBody.use { it.write(body) }
    Stats.totalRequests.incrementAndGet()
    when (code) {
        in 200..299 -> Stats.ok.incrementAndGet()
        400 -> Stats.err400.incrementAndGet()
        401 -> Stats.err401.incrementAndGet()
        429 -> Stats.err429.incrementAndGet()
        503 -> Stats.err503.incrementAndGet()
        504 -> Stats.err504.incrementAndGet()
    }
}

fun sendError(x: HttpExchange, code: Int, type: String, message: String) {
    val extra = if (code == 429) mapOf("Retry-After" to "60") else emptyMap()
    for ((k, v) in extra) x.responseHeaders.set(k, v)
    sendJson(x, code, mapOf("error" to mapOf("type" to type, "message" to message)))
}

fun readBody(x: HttpExchange, maxChars: Int): String {
    val bytes = x.requestBody.readBytes()
    val s = String(bytes, StandardCharsets.UTF_8)
    return if (s.length > maxChars) s.substring(0, maxChars) else s
}

fun resolveClientIp(x: HttpExchange): String {
    val xff = x.requestHeaders.getFirst("X-Forwarded-For")
    if (!xff.isNullOrBlank()) return xff.substringBefore(',').trim()
    return x.remoteAddress.address.hostAddress
}

// =====================================================================
// Middleware: auth → rate limit → semaphore
// =====================================================================

class AuthFilter(private val key: String) : Filter() {
    override fun description() = "auth"
    override fun doFilter(x: HttpExchange, chain: Chain) {
        if (key.isEmpty()) { chain.doFilter(x); return }
        val hdr = (x.requestHeaders.getFirst("X-API-Key")
            ?: x.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer "))
            ?.trim()
        if (hdr != key) {
            sendError(x, 401, "invalid_api_key", "X-API-Key mismatch or missing")
            return
        }
        chain.doFilter(x)
    }
}

class RateFilter(private val bucket: TokenBucket) : Filter() {
    override fun description() = "rate-limit"
    override fun doFilter(x: HttpExchange, chain: Chain) {
        val ip = resolveClientIp(x)
        if (!bucket.allow(ip)) {
            sendError(x, 429, "rate_limit_exceeded", "Too many requests. Retry-After: 60s")
            return
        }
        chain.doFilter(x)
    }
}

class ConcurrencyFilter(private val sem: Semaphore, private val timeoutSec: Long = 30) : Filter() {
    override fun description() = "concurrency"
    override fun doFilter(x: HttpExchange, chain: Chain) {
        val acquired = try { sem.tryAcquire(timeoutSec, TimeUnit.SECONDS) } catch (_: InterruptedException) { false }
        if (!acquired) {
            sendError(x, 503, "busy", "Server overloaded. Try again shortly.")
            return
        }
        try { chain.doFilter(x) } finally { sem.release() }
    }
}

// =====================================================================
// Handlers
// =====================================================================

class IndexHandler : HttpHandler {
    private val html: String by lazy {
        this::class.java.getResourceAsStream("/chat.html")?.use {
            it.readBytes().toString(Charsets.UTF_8)
        } ?: "<!doctype html><h1>chat.html not found in classpath</h1>"
    }
    override fun handle(x: HttpExchange) {
        val path = x.requestURI.path
        if (path != "/" && path != "/index.html") {
            sendError(x, 404, "not_found", "No such endpoint: $path"); return
        }
        if (x.requestMethod != "GET") { sendError(x, 405, "method_not_allowed", "Use GET"); return }
        val bytes = html.toByteArray(Charsets.UTF_8)
        x.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
        x.responseHeaders.set("Cache-Control", "no-store")
        x.sendResponseHeaders(200, bytes.size.toLong())
        x.responseBody.use { it.write(bytes) }
        Stats.totalRequests.incrementAndGet()
        Stats.ok.incrementAndGet()
    }
}

class HealthHandler(
    private val cfg: Config,
    private val ollama: OllamaClient,
    private val chunksProvider: () -> Int,
) : HttpHandler {
    override fun handle(x: HttpExchange) {
        try {
            val ollamaUp = try { ollama.ping() } catch (_: Exception) { false }
            sendJson(
                x, 200, mapOf(
                    "status" to "ok",
                    "ollama" to ollamaUp,
                    "model" to cfg.model,
                    "index_chunks" to chunksProvider(),
                    "uptime_ms" to (System.currentTimeMillis() - Stats.startedAtMs),
                )
            )
        } catch (e: Exception) {
            sendError(x, 500, "internal_error", e.message ?: "?")
        }
    }
}

class StatsHandler : HttpHandler {
    override fun handle(x: HttpExchange) {
        val uptime = System.currentTimeMillis() - Stats.startedAtMs
        val total = Stats.totalRequests.get()
        val avgWall = if (Stats.ok.get() > 0) Stats.totalWallMs.get() / Stats.ok.get() else 0
        sendJson(
            x, 200, mapOf(
                "uptime_ms" to uptime,
                "total_requests" to total,
                "ok" to Stats.ok.get(),
                "chat_ok" to Stats.chatOk.get(),
                "rag_ok" to Stats.ragOk.get(),
                "err400" to Stats.err400.get(),
                "err401" to Stats.err401.get(),
                "err429" to Stats.err429.get(),
                "err503" to Stats.err503.get(),
                "err504" to Stats.err504.get(),
                "avg_wall_ms" to avgWall,
            )
        )
    }
}

class ChatHandler(private val cfg: Config, private val ollama: OllamaClient) : HttpHandler {
    override fun handle(x: HttpExchange) {
        if (x.requestMethod != "POST") { sendError(x, 405, "method_not_allowed", "Use POST"); return }
        val body = try { readBody(x, cfg.maxInputChars) } catch (e: Exception) {
            sendError(x, 400, "bad_request", "Cannot read body"); return
        }
        val req = try { gson.fromJson(body, JsonObject::class.java) } catch (_: Exception) {
            sendError(x, 400, "bad_request", "Invalid JSON"); return
        }
        if (req.get("stream")?.asBoolean == true) {
            sendError(x, 400, "unsupported", "stream=true is not supported; use stream=false")
            return
        }
        val messages = req.getAsJsonArray("messages")?.map {
            val m = it.asJsonObject
            ChatMsg(m.get("role").asString, m.get("content").asString)
        } ?: run {
            sendError(x, 400, "bad_request", "'messages' array required")
            return
        }
        if (messages.isEmpty()) { sendError(x, 400, "bad_request", "'messages' empty"); return }

        val temp = req.get("temperature")?.asDouble ?: 0.2
        val numPredict = req.get("max_tokens")?.asInt ?: 256

        val res = ollama.chat(messages, temperature = temp, numPredict = numPredict, numCtx = cfg.maxCtx)
        if (res.timedOut) {
            sendError(x, 504, "model_timeout", "Ollama did not respond within ${cfg.timeoutSec}s"); return
        }
        Stats.chatOk.incrementAndGet()
        Stats.totalWallMs.addAndGet(res.wallMs)
        sendJson(
            x, 200, mapOf(
                "answer" to res.content,
                "model" to cfg.model,
                "wall_ms" to res.wallMs,
                "eval_ms" to res.evalMs,
                "tokens_out" to res.tokensOut,
            )
        )
    }
}

class RagHandler(private val cfg: Config, private val ollama: OllamaClient, private val chunks: () -> List<Chunk>) : HttpHandler {
    override fun handle(x: HttpExchange) {
        if (x.requestMethod != "POST") { sendError(x, 405, "method_not_allowed", "Use POST"); return }
        val idx = chunks()
        if (idx.isEmpty()) { sendError(x, 503, "no_index", "Index not built. Run with --ingest <dir> first."); return }
        val body = try { readBody(x, cfg.maxInputChars) } catch (e: Exception) {
            sendError(x, 400, "bad_request", "Cannot read body"); return
        }
        val req = try { gson.fromJson(body, JsonObject::class.java) } catch (_: Exception) {
            sendError(x, 400, "bad_request", "Invalid JSON"); return
        }
        val question = req.get("question")?.asString?.trim().orEmpty()
        if (question.isEmpty()) { sendError(x, 400, "bad_request", "'question' required"); return }
        val k = (req.get("k")?.asInt ?: 3).coerceIn(1, 10)

        val qEmb = ollama.embed(question)
        val top = idx.map { it to cosine(qEmb, it.embedding) }
            .sortedByDescending { it.second }.take(k)

        val ctx = top.mapIndexed { i, (c, _) -> "[S${i + 1}] (${c.source})\n${c.text}" }.joinToString("\n\n")
        val system = """
            Ты — точный документальный ассистент. Правила:
            1) Каждый факт → маркер [S1]/[S2]/[S3] СРАЗУ за фразой.
            2) Только по контексту. Не хватает — «Недостаточно контекста.»
            3) Максимум 3 предложения. Без вводных слов.
        """.trimIndent()
        val user = "Контекст:\n$ctx\n---\nВопрос: $question\nОтвет:"
        val res = ollama.chat(
            listOf(ChatMsg("system", system), ChatMsg("user", user)),
            temperature = 0.0, numPredict = 256, numCtx = cfg.maxCtx,
        )
        if (res.timedOut) { sendError(x, 504, "model_timeout", "Ollama timeout"); return }
        Stats.ragOk.incrementAndGet()
        Stats.totalWallMs.addAndGet(res.wallMs)
        sendJson(
            x, 200, mapOf(
                "answer" to res.content,
                "sources" to top.mapIndexed { i, (c, sim) ->
                    mapOf("marker" to "[S${i + 1}]", "source" to c.source, "similarity" to "%.3f".format(sim))
                },
                "wall_ms" to res.wallMs,
                "eval_ms" to res.evalMs,
                "tokens_out" to res.tokensOut,
            )
        )
    }
}

// =====================================================================
// Server bootstrap
// =====================================================================

data class ServerHandle(val server: HttpServer, val chunksRef: () -> List<Chunk>)

fun buildServer(cfg: Config, ollama: OllamaClient, initialChunks: List<Chunk>): ServerHandle {
    val chunksBox = java.util.concurrent.atomic.AtomicReference(initialChunks)
    val chunksProvider: () -> List<Chunk> = { chunksBox.get() }
    val chunksCountProvider: () -> Int = { chunksBox.get().size }

    val bucket = TokenBucket(cfg.rateRpm)
    val sem = Semaphore(cfg.maxConcurrent, true)
    val auth = AuthFilter(cfg.apiKey)
    val rate = RateFilter(bucket)
    val conc = ConcurrencyFilter(sem)

    val server = HttpServer.create(InetSocketAddress(cfg.port), 0)
    server.executor = Executors.newFixedThreadPool(maxOf(4, cfg.maxConcurrent + 2))

    server.createContext("/", IndexHandler())
    server.createContext("/health", HealthHandler(cfg, ollama, chunksCountProvider))

    server.createContext("/stats", StatsHandler()).apply {
        filters.add(auth) // stats под auth: чтобы не палить лимиты внешним
    }

    server.createContext("/v1/chat", ChatHandler(cfg, ollama)).apply {
        filters.add(auth); filters.add(rate); filters.add(conc)
    }

    server.createContext("/v1/rag", RagHandler(cfg, ollama, chunksProvider)).apply {
        filters.add(auth); filters.add(rate); filters.add(conc)
    }

    return ServerHandle(server, chunksProvider)
}

// =====================================================================
// Warmup
// =====================================================================

fun warmup(cfg: Config, ollama: OllamaClient) {
    try {
        ollama.chat(
            listOf(ChatMsg("user", "hi")),
            temperature = 0.0, numPredict = 1, numCtx = cfg.maxCtx,
        )
        ollama.embed("warmup")
    } catch (e: Exception) {
        System.err.println("warmup skipped: ${e.message}")
    }
}

// =====================================================================
// Demo mode: поднять сервер, прогнать локально 6 сценариев, оставить сервер жить.
// =====================================================================

fun runDemo(cfg: Config, chunks: List<Chunk>) {
    val ollama = OllamaClient(cfg)
    val handle = buildServer(cfg, ollama, chunks)
    handle.server.start()
    val base = "http://127.0.0.1:${cfg.port}"
    println("== DEMO ==")
    println("service: $base   api-key=${if (cfg.apiKey.isBlank()) "<open>" else "SET"}")
    println("index: ${chunks.size} chunks")
    println()

    // 1) /health без ключа
    section("1. /health (no auth)")
    curl("GET", "$base/health", key = null)

    // 2) /v1/chat без ключа → 401 (если ключ задан)
    if (cfg.apiKey.isNotBlank()) {
        section("2. /v1/chat WITHOUT key → 401")
        curl(
            "POST", "$base/v1/chat", key = null,
            body = """{"messages":[{"role":"user","content":"ping"}]}""",
        )
    } else {
        section("2. (skipped — api-key not set; use --api-key demo-2026 to see 401)")
    }

    // 3) /v1/chat с ключом → 200
    section("3. /v1/chat WITH key → 200")
    curl(
        "POST", "$base/v1/chat", key = cfg.apiKey.ifBlank { null },
        body = """{"messages":[{"role":"user","content":"Скажи одно слово: OK."}],"max_tokens":16}""",
    )

    // 4) stream=true → 400
    section("4. /v1/chat stream=true → 400")
    curl(
        "POST", "$base/v1/chat", key = cfg.apiKey.ifBlank { null },
        body = """{"messages":[{"role":"user","content":"hi"}],"stream":true}""",
    )

    // 5) burst → 429 (быстро наливаем ведро)
    section("5. Burst 20 requests → last should be 429")
    val codes = (1..20).map {
        val (code, _) = doHttp("GET", "$base/health", key = null, body = null)
        code
    }
    println("codes: $codes")
    println("429 count: ${codes.count { it == 429 }}")

    // 6) /v1/rag
    if (chunks.isNotEmpty()) {
        section("6. /v1/rag")
        curl(
            "POST", "$base/v1/rag", key = cfg.apiKey.ifBlank { null },
            body = """{"question":"Что такое RAG в двух предложениях?","k":3}""",
        )
    } else {
        section("6. /v1/rag (skipped — index empty; run with --ingest ../../docs first)")
    }

    // 7) /stats
    if (cfg.apiKey.isNotBlank()) {
        section("7. /stats")
        curl("GET", "$base/stats", key = cfg.apiKey)
    }

    println()
    println("Demo done. Server stays up on ${cfg.port}. Ctrl+C to stop.")
    println("Try from another host on your LAN: http://<your-lan-ip>:${cfg.port}/health")
}

private fun section(title: String) {
    println()
    println("── $title ─────────────────────────────────────".take(70))
}

private fun curl(method: String, url: String, key: String?, body: String? = null) {
    val (code, resp) = doHttp(method, url, key, body)
    println("$method $url  → $code")
    println("  " + resp.take(400))
}

private fun doHttp(method: String, url: String, key: String?, body: String?): Pair<Int, String> {
    val b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(120))
    if (!key.isNullOrBlank()) b.header("X-API-Key", key)
    when (method) {
        "GET" -> b.GET()
        "POST" -> {
            b.header("Content-Type", "application/json")
            b.POST(HttpRequest.BodyPublishers.ofString(body ?: "", StandardCharsets.UTF_8))
        }
    }
    return try {
        val res = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        res.statusCode() to res.body()
    } catch (e: Exception) {
        -1 to (e.message ?: "err")
    }
}

// =====================================================================
// Entry
// =====================================================================

fun main(args: Array<String>) {
    val cfg = parseArgs(args)
    val ollama = OllamaClient(cfg)

    if (cfg.ingestDir != null) {
        val dir = File(cfg.ingestDir)
        if (!dir.isDirectory) {
            System.err.println("ingest dir not found: ${cfg.ingestDir}")
            exitProcess(2)
        }
        println("Building index from ${dir.absolutePath} …")
        val chunks = ingest(dir, ollama)
        saveIndex(cfg.indexPath, chunks)
        println("Saved ${chunks.size} chunks → ${cfg.indexPath}")
        return
    }

    println("== Day 30 local LLM service ==")
    println(cfg.redacted())
    if (cfg.apiKey.isBlank()) {
        println("⚠️  API key is EMPTY → open mode (dev). Set --api-key or LLM_API_KEY for production.")
    }
    print("Health-check Ollama … ")
    val ollamaUp = ollama.ping()
    println(if (ollamaUp) "OK (${ollama.tags().size} models)" else "FAIL")
    if (!ollamaUp) {
        System.err.println("Ollama not reachable at ${cfg.ollamaHost}. Start Ollama first.")
        exitProcess(2)
    }

    val chunks = loadIndex(cfg.indexPath)
    println("Index: ${chunks.size} chunks from ${cfg.indexPath}")

    print("Warmup … ")
    warmup(cfg, ollama)
    println("done")

    if (cfg.demo) {
        runDemo(cfg, chunks)
        // хук на Ctrl+C — красивый log
        Runtime.getRuntime().addShutdownHook(Thread { println("\nShutting down."); })
        Thread.currentThread().join()
    } else {
        val handle = buildServer(cfg, ollama, chunks)
        handle.server.start()
        println("Serving on http://0.0.0.0:${cfg.port}")
        println("  UI:        GET /                (chat UI in browser)")
        println("  Endpoints: GET /health, POST /v1/chat, POST /v1/rag, GET /stats (auth)")
        println("Ctrl+C to stop.")
        Runtime.getRuntime().addShutdownHook(Thread { handle.server.stop(1) })
        Thread.currentThread().join()
    }
}
