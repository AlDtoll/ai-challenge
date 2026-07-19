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
import java.io.File

/**
 * Свой MCP-сервер с file-tools над projectDir.
 *
 * Все пути принимаются относительно projectDir. Абсолютные / пути «..» отсекаются
 * (защита от выхода за пределы проекта).
 *
 * Tools:
 *  - list_files(pattern?, subdir?)   — glob-подобный список
 *  - read_file(path)                 — прочитать файл
 *  - search_text(query, glob?)       — grep по коду
 *  - write_file(path, content)       — создать/перезаписать (при writeAllowed)
 *  - apply_patch(path, find, replace, expected_count?) — точечная замена
 *  - project_stat()                  — краткая сводка проекта (root, кол-во kt/md/…)
 */
fun buildFileMcpServer(cfg: Config): Server {
    val server = Server(
        serverInfo = Implementation(name = "file-mcp-server", version = "1.0.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true)),
        ),
    )
    val root = cfg.projectDir

    server.addTool(
        name = "project_stat",
        description = "Краткая сводка проекта: корень, топ-уровневые папки, кол-во .kt/.md/.json файлов.",
        inputSchema = ToolSchema(properties = buildJsonObject { }, required = emptyList()),
    ) { _ ->
        val topDirs = root.listFiles()?.filter { it.isDirectory && it.name !in IGNORED }?.map { it.name }.orEmpty().sorted()
        val ktCount = root.walkAllowed().filter { it.isFile && it.extension == "kt" }.count()
        val mdCount = root.walkAllowed().filter { it.isFile && it.extension == "md" }.count()
        val jsonCount = root.walkAllowed().filter { it.isFile && it.extension == "json" }.count()
        CallToolResult(content = listOf(TextContent(
            "root: ${root.absolutePath}\n" +
            "topdirs: ${topDirs.joinToString(", ")}\n" +
            ".kt: $ktCount, .md: $mdCount, .json: $jsonCount"
        )))
    }

    server.addTool(
        name = "list_files",
        description = "Список файлов в проекте с фильтром. pattern — суффикс имени файла (например '.kt', '.md') или полное имя. subdir — относительный путь.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("pattern") { put("type", "string"); put("description", "суффикс или имя (опц)") }
                putJsonObject("subdir") { put("type", "string"); put("description", "поддиректория (опц)") }
            },
            required = emptyList(),
        ),
    ) { req ->
        val pattern = req.arguments?.get("pattern")?.jsonPrimitive?.contentOrNull.orEmpty()
        val subdir = req.arguments?.get("subdir")?.jsonPrimitive?.contentOrNull.orEmpty()
        val start = safeResolve(root, subdir)
            ?: return@addTool CallToolResult(listOf(TextContent("недопустимый путь: '$subdir'")), isError = true)
        if (!start.exists()) return@addTool CallToolResult(listOf(TextContent("нет такой директории: $subdir")), isError = true)
        val matches = start.walkAllowed()
            .filter { it.isFile }
            .filter { pattern.isEmpty() || it.name.endsWith(pattern) || it.name == pattern }
            .map { it.relativeTo(root).path.replace(File.separatorChar, '/') }
            .sorted()
            .toList()
        val body = if (matches.isEmpty()) "(нет файлов, подходящих под фильтр)"
        else matches.take(200).joinToString("\n") + if (matches.size > 200) "\n… (+${matches.size - 200} ещё)" else ""
        CallToolResult(listOf(TextContent(body)))
    }

    server.addTool(
        name = "read_file",
        description = "Прочитать файл. Возвращает содержимое (обрезано если больше MAX_FILE_BYTES).",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("path") { put("type", "string"); put("description", "относительный путь") }
            },
            required = listOf("path"),
        ),
    ) { req ->
        val path = req.arguments?.get("path")?.jsonPrimitive?.contentOrNull.orEmpty()
        val f = safeResolve(root, path)
            ?: return@addTool CallToolResult(listOf(TextContent("недопустимый путь: '$path'")), isError = true)
        if (!f.isFile) return@addTool CallToolResult(listOf(TextContent("нет такого файла: $path")), isError = true)
        var text = f.readText(Charsets.UTF_8)
        if (text.length > cfg.maxFileBytes) text = text.take(cfg.maxFileBytes) + "\n… [обрезано на ${cfg.maxFileBytes} символах]"
        CallToolResult(listOf(TextContent(text)))
    }

    server.addTool(
        name = "search_text",
        description = "Поиск подстроки по коду (grep). Возвращает файл:строка:фрагмент.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("query") { put("type", "string"); put("description", "искомая подстрока") }
                putJsonObject("glob") { put("type", "string"); put("description", "фильтр по суффиксу (например .kt); default: любой текст") }
                putJsonObject("max_results") { put("type", "integer"); put("description", "макс совпадений (default 100)") }
            },
            required = listOf("query"),
        ),
    ) { req ->
        val query = req.arguments?.get("query")?.jsonPrimitive?.contentOrNull.orEmpty()
        val glob = req.arguments?.get("glob")?.jsonPrimitive?.contentOrNull.orEmpty()
        val max = req.arguments?.get("max_results")?.jsonPrimitive?.intOrNull ?: 100
        if (query.isEmpty()) return@addTool CallToolResult(listOf(TextContent("query пуст")), isError = true)
        val hits = mutableListOf<String>()
        outer@ for (f in root.walkAllowed().filter { it.isFile }) {
            if (glob.isNotEmpty() && !f.name.endsWith(glob)) continue
            // защита от бинарников — пропускаем всё что не выглядит как текст
            if (f.length() > 2_000_000) continue
            val lines = try { f.readLines(Charsets.UTF_8) } catch (_: Exception) { continue }
            for ((i, line) in lines.withIndex()) {
                if (line.contains(query)) {
                    val rel = f.relativeTo(root).path.replace(File.separatorChar, '/')
                    hits.add("$rel:${i + 1}: ${line.trim().take(200)}")
                    if (hits.size >= max) break@outer
                }
            }
        }
        val body = if (hits.isEmpty()) "не найдено" else hits.joinToString("\n")
        CallToolResult(listOf(TextContent(body)))
    }

    server.addTool(
        name = "write_file",
        description = "Создать или полностью переписать файл. При --dry-run возвращает preview.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("path") { put("type", "string") }
                putJsonObject("content") { put("type", "string") }
            },
            required = listOf("path", "content"),
        ),
    ) { req ->
        val path = req.arguments?.get("path")?.jsonPrimitive?.contentOrNull.orEmpty()
        val content = req.arguments?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
        val f = safeResolve(root, path)
            ?: return@addTool CallToolResult(listOf(TextContent("недопустимый путь: '$path'")), isError = true)
        if (!cfg.writeAllowed) {
            return@addTool CallToolResult(listOf(TextContent(
                "[DRY-RUN] Не пишу. Предварительный контент ($path, ${content.length} симв.):\n" +
                    content.take(2000) + if (content.length > 2000) "\n…" else ""
            )))
        }
        f.parentFile?.mkdirs()
        f.writeText(content, Charsets.UTF_8)
        CallToolResult(listOf(TextContent("записал ${content.length} символов в $path")))
    }

    server.addTool(
        name = "apply_patch",
        description = "Точечная замена: в файле path заменить все вхождения find на replace. Опц. expected_count — сколько замен ожидается.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("path") { put("type", "string") }
                putJsonObject("find") { put("type", "string") }
                putJsonObject("replace") { put("type", "string") }
                putJsonObject("expected_count") { put("type", "integer") }
            },
            required = listOf("path", "find", "replace"),
        ),
    ) { req ->
        val path = req.arguments?.get("path")?.jsonPrimitive?.contentOrNull.orEmpty()
        val find = req.arguments?.get("find")?.jsonPrimitive?.contentOrNull.orEmpty()
        val replace = req.arguments?.get("replace")?.jsonPrimitive?.contentOrNull.orEmpty()
        val expected = req.arguments?.get("expected_count")?.jsonPrimitive?.intOrNull
        val f = safeResolve(root, path)
            ?: return@addTool CallToolResult(listOf(TextContent("недопустимый путь: '$path'")), isError = true)
        if (!f.isFile) return@addTool CallToolResult(listOf(TextContent("нет файла: $path")), isError = true)
        if (find.isEmpty()) return@addTool CallToolResult(listOf(TextContent("find пуст")), isError = true)
        val old = f.readText(Charsets.UTF_8)
        val count = old.windowed(find.length, 1, true).count { it == find }
        if (expected != null && expected != count) {
            return@addTool CallToolResult(listOf(TextContent(
                "expected_count=$expected, но реально найдено $count. Не меняю. Уточни find."
            )), isError = true)
        }
        if (count == 0) return@addTool CallToolResult(listOf(TextContent("find не найден в файле")), isError = true)
        val new = old.replace(find, replace)
        if (!cfg.writeAllowed) {
            return@addTool CallToolResult(listOf(TextContent(
                "[DRY-RUN] Не пишу. Заменил бы $count вхождений в $path."
            )))
        }
        f.writeText(new, Charsets.UTF_8)
        CallToolResult(listOf(TextContent("заменил $count вхождений в $path")))
    }

    return server
}

fun startFileMcpServer(server: Server, port: Int) =
    embeddedServer(ServerCIO, host = "127.0.0.1", port = port) {
        mcpStreamableHttp { server }
    }.start(wait = false)

// ────────────── helpers ──────────────

private val IGNORED = setOf(".git", "node_modules", "build", ".gradle", ".idea", ".kotlin", "tmp", "target", ".cache")

/** walk с игнорированием служебных папок. */
private fun File.walkAllowed(): Sequence<File> = this.walk().onEnter { it.name !in IGNORED }

/** Безопасное разрешение относительного пути внутри root. Отказ на '..' и абсолютные. */
private fun safeResolve(root: File, rel: String): File? {
    val trimmed = rel.trim().removePrefix("./")
    if (trimmed.startsWith("/")) return null
    if (trimmed.split("/", "\\").any { it == ".." }) return null
    val candidate = if (trimmed.isEmpty()) root else File(root, trimmed).normalize()
    if (!candidate.absolutePath.startsWith(root.absolutePath)) return null
    return candidate
}
