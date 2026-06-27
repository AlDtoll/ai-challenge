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
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * День 17 — Первый инструмент MCP.
 *
 * В одной программе: (1) поднимаем СВОЙ MCP-сервер с инструментом get_forecast
 * (обёртка над Open-Meteo) по Streamable HTTP на localhost, затем (2) агент =
 * MCP-клиент подключается к нему, находит инструмент (tools/list), вызывает его
 * (tools/call) и использует результат.
 */
private const val PORT = 3001
private const val MCP_URL = "http://localhost:$PORT/mcp"

fun main() = runBlocking {
    // HTTP-клиент для запросов к Open-Meteo внутри инструмента.
    val apiHttp = HttpClient(ClientCIO)

    // ── 1. Свой MCP-сервер + регистрация инструмента ──
    val mcpServer = Server(
        serverInfo = Implementation(name = "weather-mcp-server", version = "1.0.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true)),
        ),
    )

    mcpServer.addTool(
        name = "get_forecast",
        description = "Текущая погода по координатам (источник Open-Meteo): температура и ветер.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("latitude") { put("type", "number"); put("description", "Широта") }
                putJsonObject("longitude") { put("type", "number"); put("description", "Долгота") }
            },
            required = listOf("latitude", "longitude"),
        ),
    ) { request ->
        val lat = request.arguments["latitude"]?.jsonPrimitive?.doubleOrNull
        val lon = request.arguments["longitude"]?.jsonPrimitive?.doubleOrNull
        if (lat == null || lon == null) {
            CallToolResult(content = listOf(TextContent("нужны числовые latitude и longitude")), isError = true)
        } else {
            CallToolResult(content = listOf(TextContent(fetchForecast(apiHttp, lat, lon))))
        }
    }

    // Поднимаем сервер на localhost (Streamable HTTP), не блокируя поток.
    val server = embeddedServer(ServerCIO, host = "127.0.0.1", port = PORT) {
        mcpStreamableHttp { mcpServer }
    }.start(wait = false)
    println("MCP-сервер поднят: $MCP_URL")
    delay(1500) // даём серверу подняться перед подключением агента

    // ── 2. Агент (MCP-клиент) подключается к нашему серверу ──
    val agentHttp = HttpClient(ClientCIO) { install(SSE) }
    val mcp = Client(clientInfo = Implementation(name = "weather-agent", version = "1.0.0"))
    mcp.connect(StreamableHttpClientTransport(client = agentHttp, url = MCP_URL))
    println("Агент подключился к серверу")

    // Discovery: какие инструменты у сервера.
    val tools = mcp.listTools().tools
    println("Инструменты сервера: " + tools.joinToString { it.name })

    // ── 3. Вызов инструмента (координаты Новосибирска) ──
    val lat = 55.03
    val lon = 82.92
    println("Вызываю get_forecast(latitude=$lat, longitude=$lon)")
    val result = mcp.callTool(
        CallToolRequest(
            CallToolRequestParams(
                name = "get_forecast",
                arguments = buildJsonObject {
                    put("latitude", lat)
                    put("longitude", lon)
                },
            ),
        ),
    )

    // ── 4. Используем результат ──
    val forecast = result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text ?: "" }
    println("Результат инструмента: $forecast")
    println("Ответ агента: сейчас в Новосибирске — $forecast")

    // Завершение.
    agentHttp.close()
    apiHttp.close()
    server.stop(0, 0)
}
