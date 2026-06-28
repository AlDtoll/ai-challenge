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
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File

/**
 * День 18 — Планировщик и фоновые задачи.
 *
 * MCP-сервер с инструментами расписания: start_watch (фоновый сбор погоды по городу
 * каждые N секунд → пишет в JSON-журнал), get_summary (агрегат по журналу), stop_watch.
 * Агент 24/7: через MCP запускает наблюдение и периодически печатает сводку.
 *
 * Закрывает задание: сохранение данных (JSONL), выполнение по расписанию (фон. планировщик),
 * агрегированный результат (get_summary), агент 24/7 с регулярной сводкой.
 */
private const val PORT = 3002
private const val MCP_URL = "http://localhost:$PORT/mcp"
private const val COLLECT_INTERVAL_SEC = 8   // как часто собираем погоду
private const val SUMMARY_INTERVAL_MS = 16_000L // как часто агент печатает сводку

fun main() = runBlocking<Unit> {
    val apiHttp = HttpClient(ClientCIO) // для запросов к wttr.in внутри планировщика
    val storeFile = File(System.getProperty("user.home"), ".ai-challenge/day18_weather.jsonl")
    val store = WeatherStore(storeFile)
    val watch = WatchService(apiHttp, store)

    // ── MCP-сервер с тремя инструментами расписания ──
    val mcpServer = Server(
        serverInfo = Implementation(name = "weather-watch-server", version = "1.0.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true)),
        ),
    )

    mcpServer.addTool(
        name = "start_watch",
        description = "Запустить фоновый сбор погоды по городу каждые N секунд (данные пишутся в журнал).",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("city") { put("type", "string"); put("description", "Город латиницей, напр. Novosibirsk") }
                putJsonObject("interval_sec") { put("type", "number"); put("description", "Период сбора в секундах") }
            },
            required = listOf("city"),
        ),
    ) { request ->
        val city = request.arguments?.get("city")?.jsonPrimitive?.content ?: "Novosibirsk"
        val interval = request.arguments?.get("interval_sec")?.jsonPrimitive?.intOrNull ?: COLLECT_INTERVAL_SEC
        CallToolResult(content = listOf(TextContent(watch.start(city, interval))))
    }

    mcpServer.addTool(
        name = "get_summary",
        description = "Вернуть агрегированную сводку по собранным замерам (кол-во, мин/средняя/макс температура, период).",
        inputSchema = ToolSchema(properties = buildJsonObject {}),
    ) { _ ->
        CallToolResult(content = listOf(TextContent(store.summary())))
    }

    mcpServer.addTool(
        name = "stop_watch",
        description = "Остановить фоновый сбор погоды.",
        inputSchema = ToolSchema(properties = buildJsonObject {}),
    ) { _ ->
        CallToolResult(content = listOf(TextContent(watch.stop())))
    }

    val server = embeddedServer(ServerCIO, host = "127.0.0.1", port = PORT) {
        mcpStreamableHttp { mcpServer }
    }.start(wait = false)
    println("MCP-сервер поднят: $MCP_URL")
    println("Журнал данных: ${storeFile.absolutePath}")
    delay(1500)

    // ── Агент (MCP-клиент) ──
    val agentHttp = HttpClient(ClientCIO) { install(SSE) }
    val mcp = Client(clientInfo = Implementation(name = "weather-agent", version = "1.0.0"))
    mcp.connect(StreamableHttpClientTransport(client = agentHttp, url = MCP_URL))
    println("Агент подключился. Инструменты: " + mcp.listTools().tools.joinToString { it.name })

    // 1) Агент через MCP запускает фоновое наблюдение
    val started = mcp.callTool(
        CallToolRequest(
            CallToolRequestParams(
                name = "start_watch",
                arguments = buildJsonObject {
                    put("city", "Novosibirsk")
                    put("interval_sec", COLLECT_INTERVAL_SEC)
                },
            ),
        ),
    )
    println("start_watch -> " + started.content.filterIsInstance<TextContent>().joinToString { it.text ?: "" })

    // 2) Агент 24/7: периодически запрашивает сводку через MCP
    println("Агент работает 24/7. Сводку печатаю каждые ${SUMMARY_INTERVAL_MS / 1000}с. (Ctrl+C — выход)")
    while (true) {
        delay(SUMMARY_INTERVAL_MS)
        val res = mcp.callTool(
            CallToolRequest(CallToolRequestParams(name = "get_summary", arguments = buildJsonObject {})),
        )
        val summary = res.content.filterIsInstance<TextContent>().joinToString { it.text ?: "" }
        println("[СВОДКА] $summary")
    }
}
