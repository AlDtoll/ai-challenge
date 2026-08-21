# Local LLM HTTP Service — обёртка Ollama в production-ready API

## What it improves

Ollama слушает на `localhost:11434` без auth, без rate limit, без мониторинга. Для отдачи локальной LLM другим сервисам/агентам нужна обёртка. HTTP-сервис на JDK stdlib (`com.sun.net.httpserver`) — zero external dependencies: `GET /health` (readiness probe — проверяет и Ollama, и RAG-индекс), `POST /v1/chat`, `POST /v1/rag`, `GET /stats`. Bearer auth, concurrency control, rate limiting. Готовый паттерн для любого локального сервиса.

## When to use

- Нужно дать доступ к локальной LLM другим агентам/процессам через HTTP
- Docker/k8s деплой: readiness probe `/health` обязателен для оркестрации
- Rate limiting: несколько клиентов не должны перегружать Ollama
- Встроенный UI для ручного тестирования RAG без написания curl

**Когда НЕ надо:** Ollama используется только одним процессом на той же машине — прямой вызов проще; production с высокой нагрузкой — JDK HttpServer однопоточный по handler, нужен Ktor/Netty.

## How to integrate

1. Используй `com.sun.net.httpserver.HttpServer.create(InetSocketAddress(PORT), 0)` — нет зависимостей.
2. Зарегистрируй handlers: `/health`, `/v1/chat`, `/v1/rag`, `/stats`.
3. `/health` — проверяет Ollama (GET /api/tags) + загружен ли RAG-индекс → `{"status": "ok"}` или 503.
4. Все endpoint'ы кроме `/health` — проверяй `Authorization: Bearer $SECRET` header.
5. Concurrency: `Executors.newFixedThreadPool(N)` как executor для сервера (N = число parallel Ollama потоков).

## Working example (Kotlin)

```kotlin
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class LocalLlmService(
    private val port: Int = 8090,
    private val secret: String = System.getenv("LLM_SECRET") ?: "dev-secret",
    private val ollamaClient: OllamaClient = OllamaClient(),
    private val ragEngine: RagEngine? = null  // null = только /v1/chat, без /v1/rag
) {
    private val requestCount = AtomicInteger(0)
    private val rateLimiter = mutableMapOf<String, Long>()  // IP → last request ms

    fun start() {
        val server = HttpServer.create(InetSocketAddress(port), 0)
        server.executor = Executors.newFixedThreadPool(4)

        server.createContext("/health") { exchange ->
            val ollamaOk = checkOllama()
            val ragOk = ragEngine?.isReady() ?: true
            val status = if (ollamaOk && ragOk) 200 else 503
            val body = """{"status": "${if (status == 200) "ok" else "degraded"}", "ollama": $ollamaOk, "rag": $ragOk}"""
            exchange.sendResponseHeaders(status, body.length.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }

        server.createContext("/v1/chat") { exchange ->
            if (!checkAuth(exchange)) return@createContext
            if (!checkRateLimit(exchange)) return@createContext

            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val messages = parseMessages(body)
            val (response, metrics) = ollamaClient.chat(messages = messages)

            requestCount.incrementAndGet()
            val responseBody = """{"content": "$response", "tokens_per_sec": ${metrics.tokensPerSec}}"""
            exchange.sendResponseHeaders(200, responseBody.length.toLong())
            exchange.responseBody.use { it.write(responseBody.toByteArray()) }
        }

        server.createContext("/stats") { exchange ->
            if (!checkAuth(exchange)) return@createContext
            val body = """{"total_requests": ${requestCount.get()}}"""
            exchange.sendResponseHeaders(200, body.length.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }

        server.start()
        println("Local LLM service started on :$port")
    }

    private fun checkAuth(exchange: com.sun.net.httpserver.HttpExchange): Boolean {
        val auth = exchange.requestHeaders.getFirst("Authorization") ?: ""
        if (auth != "Bearer $secret") {
            val body = """{"error": "Unauthorized"}"""
            exchange.sendResponseHeaders(401, body.length.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
            return false
        }
        return true
    }

    private fun checkRateLimit(exchange: com.sun.net.httpserver.HttpExchange): Boolean {
        val ip = exchange.remoteAddress.address.hostAddress
        val now = System.currentTimeMillis()
        val last = rateLimiter[ip] ?: 0L
        if (now - last < 500) {  // 2 req/sec max
            exchange.sendResponseHeaders(429, 0)
            return false
        }
        rateLimiter[ip] = now
        return true
    }

    private fun checkOllama(): Boolean = runCatching {
        java.net.URL("http://localhost:11434/api/tags").readText()
        true
    }.getOrDefault(false)

    private fun parseMessages(body: String): List<Map<String, String>> {
        // Упрощённый парсинг — в реальной имплементации использовать kotlinx.serialization
        return listOf(mapOf("role" to "user", "content" to body))
    }
}
```

## Metrics

- **Health check success rate** — % `/health` возвращающих 200; при < 99% — Ollama нестабильна или RAG-индекс не загружается
- **Request throughput** (req/min) — сколько запросов обслуживает сервис; при деградации — смотреть concurrency pool utilization
- **Rate limit trigger rate** — % запросов получивших 429; если > 5% — увеличить лимит или добавить queue
- **Auth failure rate** — % запросов с 401; ненулевое = неверная конфигурация клиента или попытка несанкционированного доступа

## Source

- **AI Challenge:** week6/day5 — Локальная LLM как HTTP-сервис
- **Артефакты:** https://github.com/AlDtoll/ai-challenge/tree/week6/day5/week6/day5 — `Main.kt`, `src/main/resources/chat.html`
- **Связано:** [`ollama_local_setup.md`](ollama_local_setup.md) — OllamaClient который этот сервис оборачивает; [`ollama_rag_tuning.md`](ollama_rag_tuning.md) — параметры которые надо зашить в defaults сервиса; [`../security/llm_gateway.md`](../security/llm_gateway.md) — production-grade Gateway с FastAPI если нужно больше (input guard, output guard, audit)
