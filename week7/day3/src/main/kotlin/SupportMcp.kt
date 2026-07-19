import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File

/**
 * Свой MCP-сервер поддержки: 3 инструмента над JSON-«CRM».
 *  - get_user(id)
 *  - get_ticket(id)
 *  - list_open_tickets(user_id)
 *
 * Данные — статические JSON в `data/users.json`, `data/tickets.json`. В реальной
 * жизни здесь был бы CRM API (Zendesk/Intercom/HubSpot); канал MCP делает подмену
 * прозрачной для LLM.
 */

data class User(
    val id: String, val name: String, val email: String, val plan: String,
    val tz: String, val created_at: String, val notes: String,
)

data class Ticket(
    val id: String, val user_id: String, val status: String, val priority: String,
    val created_at: String, val title: String, val description: String, val tags: List<String>,
)

class SupportRepo(dataDir: File) {
    private val gson = Gson()
    val users: List<User> = gson.fromJson(
        File(dataDir, "users.json").readText(Charsets.UTF_8),
        object : TypeToken<List<User>>() {}.type,
    )
    val tickets: List<Ticket> = gson.fromJson(
        File(dataDir, "tickets.json").readText(Charsets.UTF_8),
        object : TypeToken<List<Ticket>>() {}.type,
    )

    fun user(id: String) = users.firstOrNull { it.id == id }
    fun ticket(id: String) = tickets.firstOrNull { it.id == id }
    fun openTicketsOf(userId: String, limit: Int = 10) =
        tickets.filter { it.user_id == userId && it.status in listOf("open", "waiting_reply") }
            .sortedByDescending { it.created_at }
            .take(limit)
}

fun buildSupportMcpServer(repo: SupportRepo): Server {
    val server = Server(
        serverInfo = Implementation(name = "support-mcp-server", version = "1.0.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true)),
        ),
    )
    val gson = Gson()

    server.addTool(
        name = "get_user",
        description = "Вернуть карточку пользователя по id (u_101 и т.п.). Поля: name, email, plan, tz, notes.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("id") { put("type", "string"); put("description", "user id, e.g. u_101") }
            },
            required = listOf("id"),
        ),
    ) { req ->
        val id = req.arguments?.get("id")?.jsonPrimitive?.contentOrNull.orEmpty()
        val u = repo.user(id)
        CallToolResult(
            content = listOf(TextContent(u?.let { gson.toJson(it) } ?: "пользователь $id не найден")),
            isError = (u == null),
        )
    }

    server.addTool(
        name = "get_ticket",
        description = "Вернуть тикет по id (t_501 и т.п.). Поля: user_id, status, priority, title, description, tags.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("id") { put("type", "string"); put("description", "ticket id, e.g. t_501") }
            },
            required = listOf("id"),
        ),
    ) { req ->
        val id = req.arguments?.get("id")?.jsonPrimitive?.contentOrNull.orEmpty()
        val t = repo.ticket(id)
        CallToolResult(
            content = listOf(TextContent(t?.let { gson.toJson(it) } ?: "тикет $id не найден")),
            isError = (t == null),
        )
    }

    server.addTool(
        name = "list_open_tickets",
        description = "Список открытых тикетов пользователя (status=open|waiting_reply), сортировка по дате создания.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("user_id") { put("type", "string"); put("description", "user id") }
                putJsonObject("limit") { put("type", "integer"); put("description", "макс. 20") }
            },
            required = listOf("user_id"),
        ),
    ) { req ->
        val uid = req.arguments?.get("user_id")?.jsonPrimitive?.contentOrNull.orEmpty()
        val limit = req.arguments?.get("limit")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 10
        val list = repo.openTicketsOf(uid, limit.coerceIn(1, 20))
        CallToolResult(content = listOf(TextContent(gson.toJson(list))))
    }

    return server
}

fun startSupportMcpServer(server: Server, port: Int) =
    embeddedServer(ServerCIO, host = "127.0.0.1", port = port) {
        mcpStreamableHttp { server }
    }.start(wait = false)
