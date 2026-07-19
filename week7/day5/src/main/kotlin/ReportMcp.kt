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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Свой MCP-сервер над VPS-инфраструктурой Данила.
 *
 * Не обёртка над Bash — реальные типизированные tools:
 *  - vps_git_activity(days) — git log по его проектам
 *  - vps_bots_health(days)  — статус ботов + падения
 *  - vps_token_spend(days)  — токен-жёр из quickai.db
 *  - vps_system()           — disk / memory / load
 *
 * Плюс: даёт AI-агенту в других MCP-клиентах (например, Claude Desktop) видимость VPS
 * без bash-инъекций.
 */
fun buildReportMcpServer(cfg: Config): Server {
    val server = Server(
        serverInfo = Implementation(name = "vps-report-mcp", version = "1.0.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true)),
        ),
    )

    server.addTool(
        name = "vps_git_activity",
        description = "Git-активность проектов Данила за последние N дней (default 7). Возвращает список коммитов на репо.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("days") { put("type", "integer"); put("description", "окно в днях, default 7") }
            },
            required = emptyList(),
        ),
    ) { req ->
        val d = req.arguments?.get("days")?.jsonPrimitive?.intOrNull ?: 7
        CallToolResult(listOf(TextContent(gitActivity(cfg.projectDirs, d.coerceIn(1, 90)))))
    }

    server.addTool(
        name = "vps_bots_health",
        description = "Статус ботов на VPS: сколько работает сейчас, exit-коды за последние N дней, размер логов.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("days") { put("type", "integer") }
            },
            required = emptyList(),
        ),
    ) { req ->
        val d = req.arguments?.get("days")?.jsonPrimitive?.intOrNull ?: 7
        CallToolResult(listOf(TextContent(botsHealth(cfg.sessionsDir, d.coerceIn(1, 30)))))
    }

    server.addTool(
        name = "vps_token_spend",
        description = "Топ проектов по потраченным Claude-токенам и $-эквиваленту за N дней (из quickai.db).",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("days") { put("type", "integer") }
            },
            required = emptyList(),
        ),
    ) { req ->
        val d = req.arguments?.get("days")?.jsonPrimitive?.intOrNull ?: 7
        CallToolResult(listOf(TextContent(quickaiTopProjects(cfg.quickaiDb, d.coerceIn(1, 90)))))
    }

    server.addTool(
        name = "vps_system",
        description = "System-снапшот: df -h /, free -h, uptime.",
        inputSchema = ToolSchema(properties = buildJsonObject { }, required = emptyList()),
    ) { _ ->
        CallToolResult(listOf(TextContent("=== disk ===\n${diskUsage()}\n\n=== memory ===\n${memoryUsage()}\n\n=== load ===\n${systemLoad()}")))
    }

    return server
}

fun startReportMcpServer(server: Server, port: Int) =
    embeddedServer(ServerCIO, host = "127.0.0.1", port = port) {
        mcpStreamableHttp { server }
    }.start(wait = false)
