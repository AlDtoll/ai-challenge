import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject as KxJsonObject
import kotlinx.serialization.json.Json
import kotlin.system.exitProcess

/**
 * Day 34 — Ассистент, который АКТИВНО работает с файлами проекта.
 *
 * ARCH:
 *   REPL с командой типа /task <цель> или готовыми /where, /doc, /adr.
 *   Ассистент = DeepSeek с включённым function-calling (tool_calls).
 *   MCP-tools зарегистрированы в LLM, LLM сам решает какие дёрнуть.
 *   Agentic loop: пока LLM возвращает tool_calls — исполняем через MCP,
 *   результат возвращаем как role="tool", идём в следующий круг.
 *   Лимит итераций cfg.maxToolIterations.
 */
fun main(args: Array<String>) = runBlocking<Unit> {
    val cfg = parseArgs(args)
    println("== Day 34 file-agent ==")
    println("project: ${cfg.projectDir}")
    println("mcp:     http://127.0.0.1:${cfg.mcpPort}/mcp")
    println("llm:     DeepSeek(${cfg.deepseekModel})")
    println("write:   ${if (cfg.writeAllowed) "разрешено" else "DRY-RUN (только preview)"}")

    // ── MCP сервер файловых tool'ов ──
    val server = buildFileMcpServer(cfg)
    val engine = startFileMcpServer(server, cfg.mcpPort)
    println("MCP file-сервер поднят")
    delay(1200)

    // ── MCP клиент ──
    val mcpHttp = HttpClient(ClientCIO) { install(SSE) }
    val mcp = Client(clientInfo = Implementation(name = "file-agent", version = "1.0.0"))
    mcp.connect(StreamableHttpClientTransport(client = mcpHttp, url = "http://127.0.0.1:${cfg.mcpPort}/mcp"))
    val tools = mcp.listTools().tools
    println("MCP tools: ${tools.joinToString { it.name }}")

    // ── DeepSeek ──
    val llm = DeepSeekClient(cfg.deepseekKey, cfg.deepseekModel)
    val toolsSchema = toolsToDeepSeekSchema(tools)

    println()
    println("REPL готов. Команды:")
    println("  /where <symbol>    — найти использования символа/API")
    println("  /doc <path>        — сгенерировать/обновить README для файла или папки")
    println("  /adr <title>       — создать новый ADR из изменений и структуры")
    println("  /task <цель>       — свободная цель для агента")
    println("  /list_tools        — показать доступные MCP tools")
    println("  /quit              — выйти")
    println()

    Runtime.getRuntime().addShutdownHook(Thread { engine.stop(1, 3) })

    val stdin = System.`in`.bufferedReader(Charsets.UTF_8)
    while (true) {
        print(">>> ")
        val line = stdin.readLine()?.trim() ?: break
        if (line.isEmpty()) continue
        when {
            line == "/quit" || line == "/exit" -> break
            line == "/list_tools" -> {
                tools.forEach { println("  - ${it.name}: ${it.description ?: ""}") }
            }
            line.startsWith("/where ") -> runGoal("Найти все места в проекте, где используется `${line.removePrefix("/where ").trim()}`. Сначала search_text по символу, потом read_file для 2-3 самых значимых мест. В финале — краткая карта: где определено, где вызывается, есть ли непрямые вызовы. НЕ пиши файлы.", mcp, llm, toolsSchema, cfg)
            line.startsWith("/doc ") -> runGoal("Сгенерировать/обновить README.md для директории или файла `${line.removePrefix("/doc ").trim()}`. Шаги: read_file / list_files для контекста, определи назначение модуля, создай через write_file README.md с секциями: Назначение, Как использовать, Ключевые файлы, Ограничения. Если README уже есть — обнови, сохранив полезное.", mcp, llm, toolsSchema, cfg)
            line.startsWith("/adr ") -> runGoal("Создать новый ADR (Architecture Decision Record) с заголовком «${line.removePrefix("/adr ").trim()}». Шаги: (1) project_stat + list_files docs/ чтобы понять есть ли уже ADR и где их класть; (2) list_files/read_file основных модулей чтобы понять контекст; (3) через write_file создай docs/adr/NNNN-<slug>.md со стандартной шапкой Status/Context/Decision/Consequences.", mcp, llm, toolsSchema, cfg)
            line.startsWith("/task ") -> runGoal(line.removePrefix("/task ").trim(), mcp, llm, toolsSchema, cfg)
            else -> println("Неизвестная команда. См. /where, /doc, /adr, /task, /list_tools, /quit.")
        }
    }
    engine.stop(1, 3)
    exitProcess(0)
}

/**
 * Agentic loop. LLM видит tools, сама решает какой дёрнуть, мы исполняем и возвращаем ответ.
 * Крутимся до maxToolIterations, чтобы не улететь в бесконечность.
 */
private suspend fun runGoal(
    goal: String,
    mcp: Client,
    llm: DeepSeekClient,
    toolsSchema: JsonArray,
    cfg: Config,
) {
    if (goal.isBlank()) { println("Пустая цель"); return }

    val system = """
        Ты — инженер-ассистент, работающий с файлами проекта. У тебя есть MCP-инструменты
        для чтения, поиска, изменения и создания файлов. Твоя задача — САМА цель, а не
        «открой файл X». Планируй, дёргай столько tools сколько нужно, потом отвечай.

        Правила:
        1. Начинай обычно с project_stat / list_files чтобы понять структуру, если она неясна.
        2. Не выдумывай содержимое файлов — читай через read_file.
        3. Для точечных правок используй apply_patch с find/replace (безопаснее write_file).
        4. write_file только на новые файлы или явно требующие полной перезаписи.
        5. В финальном ответе — что сделал, какие файлы затронуты, какие остались вопросы.
        6. Если в MCP-ответе видишь `[DRY-RUN]` — предупреди пользователя, что изменения не применены.
        7. Не пиши больше 3 файлов за один цикл без явного разрешения в цели.
        8. Работать на русском.
    """.trimIndent()

    val messages = mutableListOf(
        DeepSeekClient.Msg("system", system),
        DeepSeekClient.Msg("user", goal),
    )

    val t0 = System.currentTimeMillis()
    var iterations = 0
    var toolCallsTotal = 0

    while (iterations < cfg.maxToolIterations) {
        iterations++
        val result = try {
            llm.chat(messages, toolsSchema = toolsSchema, temperature = 0.1, maxTokens = 2000)
        } catch (e: Exception) {
            println("Ошибка LLM: ${e.message}"); return
        }

        if (result.toolCalls.isEmpty()) {
            val text = result.content ?: "(пусто)"
            println()
            println(text)
            println()
            val wall = System.currentTimeMillis() - t0
            println("[$iterations итераций, $toolCallsTotal tool-calls, wall ${wall} ms]")
            return
        }

        // 1) добавляем ответ ассистента (со списком вызовов) в историю в исходном виде.
        messages.add(
            DeepSeekClient.Msg(
                role = "assistant",
                content = result.content,
                toolCalls = result.raw.get("tool_calls")?.asJsonArray?.map { it.asJsonObject },
            ),
        )

        // 2) исполняем каждый tool_call через MCP, результат — role=tool.
        for (call in result.toolCalls) {
            toolCallsTotal++
            System.err.println("  → tool #${toolCallsTotal}: ${call.name}(${call.argumentsJson.take(120)})")
            val kxArgs = try {
                Json.parseToJsonElement(call.argumentsJson).let { it as? KxJsonObject }
            } catch (_: Exception) { null }
            val toolResult = try {
                mcp.callTool(CallToolRequest(CallToolRequestParams(name = call.name, arguments = kxArgs)))
            } catch (e: Exception) {
                messages.add(DeepSeekClient.Msg(role = "tool", toolCallId = call.id, name = call.name, content = "MCP call failed: ${e.message}"))
                continue
            }
            val text = toolResult.content.filterIsInstance<TextContent>().mapNotNull { it.text }.joinToString("\n").ifBlank { "(tool result пуст)" }
            // Обрезаем большие ответы — экономим токены и не роняем модель.
            val trimmed = if (text.length > 4000) text.take(4000) + "\n… [обрезано]" else text
            messages.add(DeepSeekClient.Msg(role = "tool", toolCallId = call.id, name = call.name, content = trimmed))
        }
    }
    println("[достигнут лимит итераций ${cfg.maxToolIterations}, tool-calls=$toolCallsTotal]")
}

/** Переводим MCP-tools в схему DeepSeek (OpenAI-совместимую). */
private fun toolsToDeepSeekSchema(tools: List<Tool>): JsonArray {
    val gson = Gson()
    val arr = JsonArray()
    for (t in tools) {
        // MCP inputSchema уже приходит в JSON-schema форме — заворачиваем в {"type":"function", ...}
        val schemaJson = gson.toJsonTree(t.inputSchema).asJsonObject
        val fn = JsonObject().apply {
            addProperty("name", t.name)
            addProperty("description", t.description ?: "")
            add("parameters", buildParametersFrom(schemaJson))
        }
        val entry = JsonObject().apply {
            addProperty("type", "function")
            add("function", fn)
        }
        arr.add(entry)
    }
    return arr
}

/** MCP ToolSchema сериализуется в объект с полями properties/required.
 *  DeepSeek/OpenAI ждёт JSON-schema с полями type/properties/required. Обёртка простая. */
private fun buildParametersFrom(schemaJson: JsonObject): JsonObject {
    val out = JsonObject()
    out.addProperty("type", "object")
    val props = schemaJson.getAsJsonObject("properties") ?: JsonObject()
    out.add("properties", props)
    val required = schemaJson.get("required")?.asJsonArray ?: JsonArray()
    out.add("required", required)
    return out
}
