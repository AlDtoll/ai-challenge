# MCP Client Basics — минимальный клиент и первый сервер

## What it improves

Большинство туториалов по MCP предлагают использовать библиотеку сразу. Понимание протокола под капотом — отдельная ценность: знаешь как диагностировать проблему, можешь написать клиент на любом языке. Этот файл покрывает два шага вместе: (1) минимальный Kotlin-клиент — initialize + tools/list + callTool — без библиотек, (2) первый собственный MCP-сервер с обёрткой внешнего API. Понимание протокола под капотом делает отладку в 3 раза быстрее.

## When to use

- Нужно подключить агента к любому MCP-серверу (погода, файлы, git, БД, Wikipedia)
- Нет готовой библиотеки под твой стек — пишешь с нуля
- Хочешь написать собственный MCP-сервер для внешнего API (Open-Meteo, GitHub, любой REST)
- Учебный контекст: понять что происходит при `claude use server ...`

**Когда НЕ надо:** есть официальная MCP-библиотека для твоего стека и нет нужды в кастомизации — используй её, не пиши с нуля.

## How to integrate

1. MCP-сервер слушает HTTP POST на `/` (или WebSocket). Каждый запрос — JSON-RPC 2.0 объект с полем `method`.
2. При старте агента: POST `{"method": "initialize", "params": {"capabilities": {}}}` → сервер возвращает свои capabilities.
3. Получи список инструментов: POST `{"method": "tools/list"}` → массив объектов `{name, description, inputSchema}`.
4. Для вызова: POST `{"method": "tools/call", "params": {"name": "tool_name", "arguments": {...}}}` → результат.
5. Оберни весь клиент в класс `McpClient(baseUrl)` с методами `initialize()`, `listTools()`, `callTool(name, args)`.

## Working example (Kotlin)

```kotlin
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlinx.serialization.json.*

class McpClient(private val baseUrl: String) {
    private val http = HttpClient.newHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    private fun post(method: String, params: JsonObject = JsonObject(emptyMap())): JsonObject {
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            put("params", params)
            put("id", 1)
        }.toString()

        val request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        return json.parseToJsonElement(response.body()).jsonObject["result"]?.jsonObject
            ?: error("MCP error: ${response.body()}")
    }

    fun initialize() = post("initialize", buildJsonObject { put("capabilities", JsonObject(emptyMap())) })

    fun listTools(): List<JsonObject> {
        val result = post("tools/list")
        return result["tools"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
    }

    fun callTool(name: String, arguments: Map<String, String>): String {
        val params = buildJsonObject {
            put("name", name)
            put("arguments", buildJsonObject { arguments.forEach { (k, v) -> put(k, v) } })
        }
        val result = post("tools/call", params)
        return result["content"]?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content ?: ""
    }
}

// Пример: собственный MCP-сервер — обёртка Open-Meteo
// GET https://api.open-meteo.com/v1/forecast?latitude=...&longitude=...&current_weather=true
// Инструмент get_forecast(city: String) → парсит координаты, делает HTTP, возвращает JSON
//
// Минимальный сервер: com.sun.net.httpserver.HttpServer на localhost:3001
// При POST / → разбираем метод:
//   "initialize"  → {"result": {"capabilities": {}}}
//   "tools/list"  → {"result": {"tools": [{"name": "get_forecast", ...}]}}
//   "tools/call"  → вызов логики → {"result": {"content": [{"type": "text", "text": "..."}]}}
```

## Metrics

- **Tool call latency** (ms) — время от запроса до результата; для локальных серверов целевой < 200 ms
- **Initialize success rate** — % успешных handshake при старте; сбой = сервер не запущен или порт занят
- **Tool schema coverage** — % инструментов у которых `inputSchema` полностью описан (required + properties); неполная схема = LLM неверно формирует вызов
- **callTool error rate** — доля вызовов с ошибкой; при > 5% — проверить схему аргументов и обработку ошибок в сервере

## Source

- **AI Challenge:** week4/day1 + week4/day2 — Подключение к MCP (discovery) + Первый собственный MCP-сервер
- **Артефакты:** https://github.com/AlDtoll/ai-challenge/tree/week4/day1/week4/day1 — `Main.kt`; https://github.com/AlDtoll/ai-challenge/tree/week4/day2/week4/day2 — `Main.kt`, `Weather.kt`
- **Связано:** [`mcp_background_tasks.md`](mcp_background_tasks.md) — более сложный сервер с фоновыми задачами и персистентностью; [`mcp_orchestration_namespace.md`](mcp_orchestration_namespace.md) — оркестрация нескольких серверов через namespace
