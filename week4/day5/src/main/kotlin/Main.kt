import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.sse.SSE
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File

/**
 * День 20 — Оркестрация MCP.
 *
 * Поднимаем ТРИ отдельных MCP-сервера (на разных портах): knowledge (search/summarize),
 * weather (get_weather), files (save_to_file). Агент подключается КО ВСЕМ, собирает общий
 * реестр инструментов (имя = «server__tool»), и через DeepSeek function-calling САМ выбирает
 * нужные инструменты, МАРШРУТИЗИРУЯ вызовы на правильный сервер. Получается длинный флоу,
 * в котором используются инструменты с разных серверов в правильном порядке.
 *
 * Запуск со своей целью: ./gradlew :week4:day5:run --args="..."
 */
private fun newServer(name: String) = Server(
    serverInfo = Implementation(name, "1.0.0"),
    options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true))),
)

fun main(args: Array<String>) = runBlocking<Unit> {
    val apiHttp = HttpClient(ClientCIO)
    val deepseekKey = System.getenv("DEEPSEEK_API_KEY") ?: ""

    // ── Сервер 1: knowledge (search, summarize) ──
    val knowledge = newServer("knowledge-server")
    knowledge.addTool(
        name = "search",
        description = "Найти вводный текст статьи Wikipedia по запросу.",
        inputSchema = ToolSchema(
            properties = buildJsonObject { putJsonObject("query") { put("type", "string"); put("description", "Поисковый запрос / тема") } },
            required = listOf("query"),
        ),
    ) { req ->
        val q = req.arguments?.get("query")?.jsonPrimitive?.content ?: ""
        CallToolResult(content = listOf(TextContent(searchWikipedia(apiHttp, q))))
    }
    knowledge.addTool(
        name = "summarize",
        description = "Сделать краткую выжимку текста (через DeepSeek).",
        inputSchema = ToolSchema(
            properties = buildJsonObject { putJsonObject("text") { put("type", "string"); put("description", "Текст для суммаризации") } },
            required = listOf("text"),
        ),
    ) { req ->
        val t = req.arguments?.get("text")?.jsonPrimitive?.content ?: ""
        CallToolResult(content = listOf(TextContent(summarizeWithDeepSeek(apiHttp, deepseekKey, t))))
    }

    // ── Сервер 2: weather (get_weather) ──
    val weather = newServer("weather-server")
    weather.addTool(
        name = "get_weather",
        description = "Текущая погода по городу.",
        inputSchema = ToolSchema(
            properties = buildJsonObject { putJsonObject("city") { put("type", "string"); put("description", "Город латиницей, напр. Novosibirsk") } },
            required = listOf("city"),
        ),
    ) { req ->
        val c = req.arguments?.get("city")?.jsonPrimitive?.content ?: "Novosibirsk"
        CallToolResult(content = listOf(TextContent(fetchWeatherLine(apiHttp, c))))
    }

    // ── Сервер 3: files (save_to_file) ──
    val files = newServer("files-server")
    files.addTool(
        name = "save_to_file",
        description = "Сохранить контент в файл в ~/.ai-challenge/.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("content") { put("type", "string"); put("description", "Что сохранить") }
                putJsonObject("filename") { put("type", "string"); put("description", "Имя файла") }
            },
            required = listOf("content", "filename"),
        ),
    ) { req ->
        val content = req.arguments?.get("content")?.jsonPrimitive?.content ?: ""
        val fn = req.arguments?.get("filename")?.jsonPrimitive?.content ?: "day20_result.txt"
        val dir = File(System.getProperty("user.home"), ".ai-challenge").apply { mkdirs() }
        val f = File(dir, fn)
        f.writeText(content)
        CallToolResult(content = listOf(TextContent("Сохранено: ${f.absolutePath} (${f.length()} байт)")))
    }

    // ── Поднимаем три сервера на разных портах ──
    val s1 = embeddedServer(ServerCIO, host = "127.0.0.1", port = 3010) { mcpStreamableHttp { knowledge } }.start(wait = false)
    val s2 = embeddedServer(ServerCIO, host = "127.0.0.1", port = 3011) { mcpStreamableHttp { weather } }.start(wait = false)
    val s3 = embeddedServer(ServerCIO, host = "127.0.0.1", port = 3012) { mcpStreamableHttp { files } }.start(wait = false)
    println("Подняты 3 MCP-сервера: knowledge:3010, weather:3011, files:3012")
    delay(1800)

    // ── Агент подключается КО ВСЕМ серверам и строит общий реестр (namespace по серверу) ──
    val serverPorts = listOf("knowledge" to 3010, "weather" to 3011, "files" to 3012)
    val registry = mutableListOf<ToolEntry>()
    val httpClients = mutableListOf<HttpClient>()
    for ((key, port) in serverPorts) {
        val h = HttpClient(ClientCIO) { install(SSE) }
        httpClients += h
        val mcp = Client(clientInfo = Implementation("orchestrator-agent", "1.0.0"))
        mcp.connect(StreamableHttpClientTransport(client = h, url = "http://localhost:$port/mcp"))
        for (t in mcp.listTools().tools) {
            val schema = t.inputSchema
            val params = buildJsonObject {
                put("type", "object")
                put("properties", schema.properties ?: buildJsonObject { })
                schema.required?.let { req -> put("required", JsonArray(req.map { JsonPrimitive(it) })) }
            }
            registry += ToolEntry("${key}__${t.name}", key, mcp, t.name, t.description ?: "", params)
        }
        println("  сервер «$key» (порт $port): " + registry.filter { it.serverKey == key }.joinToString { it.originalName })
    }
    println("Общий реестр (${registry.size}): " + registry.joinToString { it.namespaced })

    // ── Длинный флоу: DeepSeek сам выбирает инструменты с разных серверов ──
    val goal = args.firstOrNull()?.takeIf { it.isNotBlank() }
        ?: "Узнай текущую погоду в городе Novosibirsk и краткую справку о Новосибирске, затем сделай общую сводку из этих данных и сохрани её в файл day20_novosibirsk.txt"
    println("\n=== ЦЕЛЬ: $goal ===")

    orchestrate(apiHttp, deepseekKey, goal, registry)

    httpClients.forEach { it.close() }
    apiHttp.close()
    s1.stop(0, 0); s2.stop(0, 0); s3.stop(0, 0)
}
