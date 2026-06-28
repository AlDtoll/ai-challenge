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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File

/**
 * День 19 — Композиция MCP-инструментов (пайплайн).
 *
 * MCP-сервер с тремя инструментами: search (получить данные из Wikipedia),
 * summarize (обработать через DeepSeek), save_to_file (сохранить результат).
 * Агент гоняет их цепочкой, передавая выход одного на вход следующего, и печатает
 * каждый шаг — видно автоматическое выполнение цепочки и передачу данных между инструментами.
 *
 * Запуск с темой: ./gradlew :week4:day4:run --args="Кофе"
 */
private const val PORT = 3003
private const val MCP_URL = "http://localhost:$PORT/mcp"

fun main(args: Array<String>) = runBlocking<Unit> {
    val apiHttp = HttpClient(ClientCIO)
    val deepseekKey = System.getenv("DEEPSEEK_API_KEY") ?: ""

    // ── MCP-сервер с тремя инструментами пайплайна ──
    val mcpServer = Server(
        serverInfo = Implementation(name = "pipeline-mcp-server", version = "1.0.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true)),
        ),
    )

    mcpServer.addTool(
        name = "search",
        description = "Получить данные: вводный текст статьи Wikipedia по запросу.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("query") { put("type", "string"); put("description", "Поисковый запрос / тема") }
            },
            required = listOf("query"),
        ),
    ) { request ->
        val q = request.arguments?.get("query")?.jsonPrimitive?.content
        if (q.isNullOrBlank()) CallToolResult(content = listOf(TextContent("нужен параметр query")), isError = true)
        else CallToolResult(content = listOf(TextContent(searchWikipedia(apiHttp, q))))
    }

    mcpServer.addTool(
        name = "summarize",
        description = "Обработать: краткая выжимка текста через DeepSeek.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("text") { put("type", "string"); put("description", "Текст для суммаризации") }
            },
            required = listOf("text"),
        ),
    ) { request ->
        val text = request.arguments?.get("text")?.jsonPrimitive?.content
        if (text.isNullOrBlank()) CallToolResult(content = listOf(TextContent("нужен параметр text")), isError = true)
        else CallToolResult(content = listOf(TextContent(summarizeWithDeepSeek(apiHttp, deepseekKey, text))))
    }

    mcpServer.addTool(
        name = "save_to_file",
        description = "Сохранить: записать контент в файл в ~/.ai-challenge/.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("content") { put("type", "string"); put("description", "Что сохранить") }
                putJsonObject("filename") { put("type", "string"); put("description", "Имя файла") }
            },
            required = listOf("content", "filename"),
        ),
    ) { request ->
        val content = request.arguments?.get("content")?.jsonPrimitive?.content ?: ""
        val filename = request.arguments?.get("filename")?.jsonPrimitive?.content ?: "day19_result.txt"
        val dir = File(System.getProperty("user.home"), ".ai-challenge").apply { mkdirs() }
        val file = File(dir, filename)
        file.writeText(content)
        CallToolResult(content = listOf(TextContent("Сохранено: ${file.absolutePath} (${file.length()} байт)")))
    }

    val server = embeddedServer(ServerCIO, host = "127.0.0.1", port = PORT) {
        mcpStreamableHttp { mcpServer }
    }.start(wait = false)
    println("MCP-сервер поднят: $MCP_URL")
    delay(1500)

    // ── Агент (MCP-клиент) ──
    val agentHttp = HttpClient(ClientCIO) { install(SSE) }
    val mcp = Client(clientInfo = Implementation(name = "pipeline-agent", version = "1.0.0"))
    mcp.connect(StreamableHttpClientTransport(client = agentHttp, url = MCP_URL))
    println("Агент подключился. Инструменты: " + mcp.listTools().tools.joinToString { it.name })

    // ── Пайплайн: search → summarize → save_to_file (данные текут через агента) ──
    val query = args.firstOrNull()?.takeIf { it.isNotBlank() } ?: "Кофе"
    println("\n=== ПАЙПЛАЙН для запроса: «$query» ===")

    // шаг 1 — search
    val searchOut = callText(mcp, "search", buildJsonObject { put("query", query) })
    println("\n[1/3 search] получено ${searchOut.length} символов:")
    println("    " + searchOut.take(200) + if (searchOut.length > 200) "…" else "")

    // шаг 2 — summarize (вход = выход search)
    val summaryOut = callText(mcp, "summarize", buildJsonObject { put("text", searchOut) })
    println("\n[2/3 summarize] выжимка:")
    println("    " + summaryOut)

    // шаг 3 — save_to_file (вход = выход summarize)
    val fname = "day19_" + query.lowercase().replace(Regex("[^a-zа-яё0-9]+"), "_").trim('_') + ".txt"
    val saveOut = callText(mcp, "save_to_file", buildJsonObject { put("content", summaryOut); put("filename", fname) })
    println("\n[3/3 save_to_file] $saveOut")

    println("\n=== Пайплайн завершён ===")

    agentHttp.close()
    apiHttp.close()
    server.stop(0, 0)
}

/** Вызвать MCP-инструмент и собрать его текстовый результат. */
private suspend fun callText(mcp: Client, tool: String, arguments: JsonObject): String {
    val res = mcp.callTool(CallToolRequest(CallToolRequestParams(name = tool, arguments = arguments)))
    return res.content.filterIsInstance<TextContent>().joinToString("\n") { it.text ?: "" }
}
