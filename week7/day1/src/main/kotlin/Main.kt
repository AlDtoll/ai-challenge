import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.system.exitProcess

/**
 * Day 31 (week7/day1) — Ассистент разработчика.
 *
 * ARCH:
 *   REPL / help
 *     ├── всегда: MCP call get_current_branch (живой git-контекст)
 *     ├── router: если вопрос про git/файлы → git_status / git_log
 *     ├── RAG: cosine top-3 по README + docs/** проекта
 *     └── DeepSeek chat с system-prompt-ассистента + собранным контекстом
 */
fun main(args: Array<String>) = runBlocking<Unit> {
    val cfg = parseArgs(args)
    println("== Day 31 dev-assistant ==")
    println("project: ${cfg.projectDir}")
    println("index:   ${cfg.indexPath}")
    println("llm:     DeepSeek(${cfg.deepseekModel})")
    println("embed:   Ollama ${cfg.embedModel} @ ${cfg.ollamaHost}")
    println("mcp:     http://127.0.0.1:${cfg.mcpPort}/mcp")

    // ── INGEST режим ──
    if (cfg.ingestOnly) {
        val chunks = ingest(cfg.projectDir, cfg)
        saveIndex(cfg.indexPath, chunks)
        println("Готово: ${chunks.size} чанков → ${cfg.indexPath}")
        return@runBlocking
    }

    // ── Загрузка индекса ──
    var chunks = loadIndex(cfg.indexPath)
    if (chunks.isEmpty()) {
        println("Индекс пуст. Запускаю ingest ${cfg.projectDir} …")
        chunks = ingest(cfg.projectDir, cfg)
        saveIndex(cfg.indexPath, chunks)
        println("Индекс построен: ${chunks.size} чанков")
    } else {
        println("Индекс: ${chunks.size} чанков")
    }

    // ── Свой MCP git-сервер ──
    val gitServer = buildGitMcpServer(cfg.projectDir)
    val engine = startGitMcpServer(gitServer, cfg.mcpPort)
    println("MCP git-сервер поднят на localhost:${cfg.mcpPort}")
    delay(1200)  // даём серверу подняться перед подключением клиента

    // ── Агент = MCP клиент ──
    val mcpHttp = HttpClient(ClientCIO) { install(SSE) }
    val mcp = Client(clientInfo = Implementation(name = "dev-assistant", version = "1.0.0"))
    mcp.connect(StreamableHttpClientTransport(client = mcpHttp, url = "http://127.0.0.1:${cfg.mcpPort}/mcp"))
    val tools = mcp.listTools().tools
    println("MCP tools доступны: ${tools.joinToString { it.name }}")

    // ── DeepSeek ──
    val llm = DeepSeekClient(cfg.deepseekKey, cfg.deepseekModel)

    // ── REPL ──
    println()
    println("REPL готов. Команды:")
    println("  /help <вопрос>     — спросить ассистента")
    println("  /reindex           — перестроить индекс")
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
            line == "/reindex" -> {
                chunks = ingest(cfg.projectDir, cfg)
                saveIndex(cfg.indexPath, chunks)
                println("Reindex: ${chunks.size} чанков")
            }
            line.startsWith("/help ") -> handleHelp(line.removePrefix("/help ").trim(), mcp, llm, chunks, cfg)
            line.startsWith("/help") -> println("Пример: /help какая ветка активна?")
            else -> println("Неизвестная команда. См. /help или /quit.")
        }
    }
    engine.stop(1, 3)
    exitProcess(0)
}

/** Основной хендлер /help — собирает контекст (git + RAG) и зовёт LLM. */
private suspend fun handleHelp(
    question: String,
    mcp: Client,
    llm: DeepSeekClient,
    chunks: List<Chunk>,
    cfg: Config,
) {
    if (question.isBlank()) { println("Пустой вопрос. Пример: /help какая ветка активна?"); return }

    // 1. Всегда — get_current_branch (дешёвый живой контекст).
    val branch = callMcpText(mcp, "get_current_branch", null)

    // 2. Router — если про git/status/файлы, дёрнем git_status. Про историю — git_log.
    val lc = question.lowercase()
    val gitContext = buildString {
        append("Текущая ветка: $branch")
        if (lc.contains("измен") || lc.contains("status") || lc.contains("файл") || lc.contains("грязн")) {
            val status = callMcpText(mcp, "git_status", null)
            append("\n\nGit status (--short):\n$status")
        }
        if (lc.contains("коммит") || lc.contains("commit") || lc.contains("недавн") || lc.contains("истори")) {
            val log = callMcpText(mcp, "git_log", buildJsonObject { put("limit", 10) })
            append("\n\nПоследние коммиты:\n$log")
        }
    }

    // 3. RAG над проектом.
    val top = ragSearch(cfg, question, chunks)
    val ragContext = if (top.isEmpty()) "(нет)" else buildString {
        top.forEachIndexed { i, (c, sim) ->
            append("[S${i + 1}] (${c.source}, sim=${"%.3f".format(sim)})\n${c.text}\n\n")
        }
    }.trimEnd()

    // 4. Промпт.
    val system = """
        Ты — ассистент разработчика проекта AI Challenge (7-недельный челлендж, Kotlin JVM).
        Отвечай кратко и по делу на русском.
        Используй ТОЛЬКО предоставленный контекст: git-контекст и RAG-фрагменты из README/docs.
        Если контекста недостаточно — так и напиши, не выдумывай.

        Приоритет источников (важно):
        1. Git-контекст (ветка, статус, лог) — АКТУАЛЬНОЕ живое состояние репо. При конфликте с документацией — верь git-контексту.
        2. RAG-фрагменты из README/docs — могут быть устаревшими; используй как справочник о структуре и намерениях.

        Ссылайся на RAG-источники маркерами [S1]/[S2]/[S3] сразу за фактом.
        Git-факты помечай «(git)».
    """.trimIndent()

    val user = """
        Контекст git:
        $gitContext

        Контекст из документации (RAG top-${cfg.topK}):
        $ragContext

        Вопрос:
        $question

        Ответ:
    """.trimIndent()

    val t0 = System.currentTimeMillis()
    val answer = try {
        llm.chat(listOf(DeepSeekClient.Msg("system", system), DeepSeekClient.Msg("user", user)))
    } catch (e: Exception) {
        println("Ошибка LLM: ${e.message}")
        return
    }
    val wall = System.currentTimeMillis() - t0

    println()
    println(answer)
    println()
    println("[wall ${wall} ms | RAG top-${top.size} | ветка $branch]")
    top.forEachIndexed { i, (c, _) -> println("  [S${i + 1}] ${c.source}") }
    println()
}

private suspend fun callMcpText(mcp: Client, tool: String, args: JsonObject?): String {
    val result = mcp.callTool(CallToolRequest(CallToolRequestParams(name = tool, arguments = args)))
    val texts = result.content.filterIsInstance<TextContent>().mapNotNull { it.text }
    return texts.joinToString("\n").trim().ifBlank { "(пусто)" }
}
