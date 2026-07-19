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

/**
 * Запуск git-команды в заданном каталоге. Возвращает stdout (trimmed).
 *
 * Важно: stdout читаем в фоновом потоке ПАРАЛЛЕЛЬНО с waitFor() —
 * иначе на большом выхлопе (`git log`, `git diff`) буфер пайпа переполняется
 * и процесс блокируется на write, а мы — на waitFor. Классический deadlock.
 *
 * Плюс cap 64 KB на всю выдачу — защита от переполнения LLM-контекста.
 */
private fun runGit(dir: File, vararg args: String): String {
    val pb = ProcessBuilder(listOf("git") + args.toList())
        .directory(dir)
        .redirectErrorStream(true)
    val proc = pb.start()
    val output = StringBuilder()
    val cap = 64 * 1024
    val reader = Thread {
        proc.inputStream.bufferedReader(Charsets.UTF_8).use { br ->
            val buf = CharArray(4096)
            while (true) {
                val n = br.read(buf)
                if (n < 0) break
                if (output.length < cap) {
                    val room = cap - output.length
                    output.append(buf, 0, minOf(n, room))
                    if (output.length >= cap) output.append("\n… [обрезано на 64 KB]")
                }
            }
        }
    }.apply { isDaemon = true; start() }
    val ok = proc.waitFor()
    reader.join(1000)
    return if (ok == 0) output.toString().trim() else "git error (exit=$ok): ${output.toString().trim()}"
}

/** Поднимает MCP-сервер на порту, не блокирует. Возвращает handle для stop(). */
fun startGitMcpServer(server: Server, port: Int) =
    embeddedServer(ServerCIO, host = "127.0.0.1", port = port) {
        mcpStreamableHttp { server }
    }.start(wait = false)
