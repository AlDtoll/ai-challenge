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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File

/**
 * Свой MCP-сервер для git-инструментов над проектом-донором.
 * Инструменты: get_current_branch, git_status, git_log.
 * Все — через ProcessBuilder + git (must be in PATH).
 *
 * Дизайн повторяет week4/day2 (get_forecast), только рабочий каталог = projectDir.
 */
fun buildGitMcpServer(projectDir: File): Server {
    val server = Server(
        serverInfo = Implementation(name = "git-mcp-server", version = "1.0.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true)),
        ),
    )

    server.addTool(
        name = "get_current_branch",
        description = "Возвращает имя текущей git-ветки проекта-донора.",
        inputSchema = ToolSchema(properties = buildJsonObject { }, required = emptyList()),
    ) { _ ->
        val branch = runGit(projectDir, "rev-parse", "--abbrev-ref", "HEAD")
        CallToolResult(content = listOf(TextContent(branch)))
    }

    server.addTool(
        name = "git_status",
        description = "Короткий git status (сокращённый формат --short): какие файлы изменены/добавлены.",
        inputSchema = ToolSchema(properties = buildJsonObject { }, required = emptyList()),
    ) { _ ->
        val status = runGit(projectDir, "status", "--short")
        val payload = if (status.isBlank()) "рабочее дерево чистое" else status
        CallToolResult(content = listOf(TextContent(payload)))
    }

    server.addTool(
        name = "git_log",
        description = "Последние N коммитов (default 10) в формате oneline.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("limit") { put("type", "integer"); put("description", "Сколько коммитов, default 10") }
            },
            required = emptyList(),
        ),
    ) { request ->
        val limit = (request.arguments?.get("limit")?.jsonPrimitive?.intOrNull ?: 10).coerceIn(1, 100)
        val log = runGit(projectDir, "log", "--oneline", "-$limit")
        CallToolResult(content = listOf(TextContent(log)))
    }

    return server
}

/** Запуск git-команды в заданном каталоге. Возвращает stdout (trimmed). */
private fun runGit(dir: File, vararg args: String): String {
    val pb = ProcessBuilder(listOf("git") + args.toList())
        .directory(dir)
        .redirectErrorStream(true)
    val proc = pb.start()
    val output = proc.inputStream.bufferedReader(Charsets.UTF_8).readText()
    val ok = proc.waitFor()
    return if (ok == 0) output.trim() else "git error (exit=$ok): $output".trim()
}

/** Поднимает MCP-сервер на порту, не блокирует. Возвращает handle для stop(). */
fun startGitMcpServer(server: Server, port: Int) =
    embeddedServer(ServerCIO, host = "127.0.0.1", port = port) {
        mcpStreamableHttp { server }
    }.start(wait = false)
