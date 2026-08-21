# MCP Orchestration Namespace — несколько серверов без конфликтов

## What it improves

При подключении нескольких MCP-серверов возникает коллизия имён: сервер A и сервер B оба имеют инструмент `search`. LLM не знает какой вызвать. Namespace-решение: каждый сервер получает префикс (`knowledge:search`, `weather:get_forecast`, `files:read_file`). Оркестратор собирает общий реестр инструментов со всех серверов, LLM через function-calling выбирает полное имя, роутер по prefix маршрутизирует вызов на нужный сервер — без конфликтов и без изменения самих серверов.

## When to use

- Агент работает с ≥ 2 MCP-серверами одновременно (базы знаний + погода + файлы + CRM)
- Серверы могут иметь одинаковые имена инструментов (несколько `search`, `get_data`)
- Production: серверы разрабатываются независимо и не должны знать друг о друге
- Нужен единый entry point для всей функциональности инструментов агента

**Когда НЕ надо:** один MCP-сервер — namespace не нужен, лишняя индирекция; у серверов гарантированно уникальные имена — можно без prefix.

## How to integrate

1. Определи `McpServer(name, baseUrl, port)` — конфигурация каждого сервера.
2. При старте оркестратора: для каждого сервера вызови `initialize + tools/list`, добавь prefix к именам инструментов.
3. Собери `allTools: List<ToolSchema>` — объединённый список со всех серверов (с prefix).
4. При LLM-вызове: передай `allTools` как function definitions. LLM вернёт `tool_calls` с prefixed именами.
5. Роутер: разбить имя по `:` → найти сервер → `callTool(originalName, args)` на нужном сервере.

## Working example (Kotlin)

```kotlin
data class McpServerConfig(val namespace: String, val baseUrl: String)

data class ToolSchema(
    val name: String,          // с prefix: "knowledge:search"
    val originalName: String,  // без prefix: "search"
    val namespace: String,     // "knowledge"
    val description: String,
    val inputSchema: JsonObject
)

class McpOrchestrator(private val servers: List<McpServerConfig>) {
    private val clients = servers.associateWith { McpClient(it.baseUrl) }
    private val allTools = mutableListOf<ToolSchema>()

    suspend fun initialize() {
        for ((config, client) in clients) {
            client.initialize()
            val tools = client.listTools()
            tools.forEach { tool ->
                allTools.add(ToolSchema(
                    name = "${config.namespace}:${tool["name"]!!.jsonPrimitive.content}",
                    originalName = tool["name"]!!.jsonPrimitive.content,
                    namespace = config.namespace,
                    description = tool["description"]?.jsonPrimitive?.content ?: "",
                    inputSchema = tool["inputSchema"]?.jsonObject ?: JsonObject(emptyMap())
                ))
            }
        }
    }

    fun getToolsForLlm(): List<JsonObject> = allTools.map { tool ->
        buildJsonObject {
            put("name", tool.name)
            put("description", tool.description)
            put("parameters", tool.inputSchema)
        }
    }

    suspend fun route(prefixedName: String, arguments: Map<String, String>): String {
        val (namespace, originalName) = prefixedName.split(":", limit = 2)
            .also { require(it.size == 2) { "Invalid tool name: $prefixedName" } }
            .let { it[0] to it[1] }

        val serverConfig = servers.find { it.namespace == namespace }
            ?: error("Unknown namespace: $namespace")
        val client = clients[serverConfig]!!

        return client.callTool(originalName, arguments)
    }
}

// Пример конфигурации:
// val orchestrator = McpOrchestrator(listOf(
//     McpServerConfig("knowledge", "http://localhost:3010"),
//     McpServerConfig("weather",   "http://localhost:3011"),
//     McpServerConfig("files",     "http://localhost:3012")
// ))
// orchestrator.initialize()
// val tools = orchestrator.getToolsForLlm()  // в DeepSeek function definitions
//
// После LLM возвращает tool_call "knowledge:search":
// val result = orchestrator.route("knowledge:search", mapOf("query" to "..."))
```

## Metrics

- **Routing success rate** — % вызовов где роутер корректно нашёл сервер по namespace; сбой = LLM вернул несуществующий prefix
- **LLM tool selection accuracy** — при 3 серверах с похожими инструментами, выбрал ли LLM правильный namespace? (проверить на 20 тест-вопросах)
- **Inter-server call latency** (ms) — суммарное время вызова через оркестратор vs прямой вызов; overhead должен быть < 10 ms
- **Namespace collision rate** — число случаев когда без prefix имена совпали бы; ненулевое подтверждает ценность namespace

## Source

- **AI Challenge:** week4/day5 — Оркестрация нескольких MCP-серверов; week4/day4 (pipeline tool-calls как предвестник)
- **Артефакты:** https://github.com/AlDtoll/ai-challenge/tree/week4/day5/week4/day5 — `Orchestrator.kt`, `Search.kt`, `Summarizer.kt`, `Weather.kt`; week4/day4: https://github.com/AlDtoll/ai-challenge/tree/week4/day4/week4/day4 — `Search.kt`, `Summarizer.kt`
- **Связано:** [`mcp_client_basics.md`](mcp_client_basics.md) — основы MCP-клиента который используется под капотом; [`../agentic_loop/agentic_loop_tool_calls.md`](../agentic_loop/agentic_loop_tool_calls.md) — agentic loop где LLM вызывает MCP-инструменты итеративно
