import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttp
import kotlinx.coroutines.runBlocking

/**
 * День 16 — Подключение к MCP.
 *
 * Минимальный MCP-клиент: подключается к публичному (без авторизации) удалённому
 * MCP-серверу по транспорту Streamable HTTP, выполняет initialize (handshake) и
 * через tools/list получает список доступных инструментов. Сами инструменты не вызываем.
 *
 * По умолчанию сервер — DeepWiki MCP (https://mcp.deepwiki.com/mcp), у него три tool'а:
 * read_wiki_structure, read_wiki_contents, ask_question. Можно переопределить через
 * переменную окружения MCP_SERVER_URL (например, положить её в корневой .env).
 */
fun main() = runBlocking {
    val serverUrl = System.getenv("MCP_SERVER_URL")
        ?.takeIf { it.isNotBlank() }
        ?: "https://mcp.deepwiki.com/mcp"

    println("Подключаюсь к MCP-серверу: $serverUrl")

    // CIO-движок + плагин SSE: на нём работает Streamable HTTP транспорт MCP.
    val httpClient = HttpClient(CIO) {
        install(SSE)
    }

    try {
        // mcpStreamableHttp создаёт MCP-клиент, открывает транспорт и делает initialize.
        val client = httpClient.mcpStreamableHttp(serverUrl)

        // Discovery: запрашиваем список инструментов (tools/list).
        val tools = client.listTools()?.tools.orEmpty()

        if (tools.isEmpty()) {
            println("Соединение установлено, но сервер не вернул ни одного инструмента.")
        } else {
            println("✅ Соединение установлено. Доступно инструментов: ${tools.size}\n")
            tools.forEachIndexed { index, tool ->
                println("${index + 1}. ${tool.name}")
                tool.description?.let { println("   описание: $it") }
                println("   input schema: ${tool.inputSchema}")
                println()
            }
        }

        client.close()
    } catch (e: Exception) {
        // Аккуратная обработка сетевых/протокольных ошибок: нет сети, сервер недоступен,
        // таймаут, несовместимость версий протокола и т.п.
        System.err.println("❌ Не удалось получить список инструментов: ${e.message}")
    } finally {
        httpClient.close()
    }
}
