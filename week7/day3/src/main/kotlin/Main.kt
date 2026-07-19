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
 * Day 33 — Ассистент поддержки пользователей.
 *
 * ARCH:
 *   REPL
 *     ├── свой MCP-сервер (Streamable HTTP :3003) с 3 tools: get_user / get_ticket / list_open_tickets
 *     ├── MCP-клиент подключается к нему и вызывает tools для контекста
 *     ├── RAG над FAQ (Ollama nomic-embed если доступна, иначе BM25)
 *     └── DeepSeek chat с system-промптом «оператор поддержки»
 *
 *   Основной сценарий: /ask <ticket_id> <вопрос>
 *     — по ticket_id через MCP тянем сам тикет и карточку user'а
 *     — по вопросу+тикету дёргаем RAG top-K по FAQ
 *     — собираем контекст, спрашиваем DeepSeek
 */
fun main(args: Array<String>) = runBlocking<Unit> {
    val cfg = parseArgs(args)
    println("== Day 33 support-assistant ==")
    println("data:   ${cfg.dataDir}")
    println("mcp:    http://127.0.0.1:${cfg.mcpPort}/mcp")
    println("llm:    DeepSeek(${cfg.deepseekModel})")

    // ── Загрузка данных ──
    val repo = SupportRepo(cfg.dataDir)
    println("Данные: ${repo.users.size} юзеров, ${repo.tickets.size} тикетов, ${collectFaqFiles(cfg.dataDir).size} FAQ-файлов")

    // ── Выбор режима RAG ──
    val useOllama = cfg.useOllama && ollamaAvailable(cfg.ollamaHost)
    val mode = if (useOllama) "ollama" else "bm25"
    println("RAG режим: $mode ${if (useOllama) "(эмбеддинги ${cfg.embedModel})" else "(offline, без сети)"}")

    // ── Индекс FAQ ──
    val cached = loadIndex(cfg.indexPath)
    val indexData = when {
        cached != null && cached.mode == mode && cached.chunks.isNotEmpty() -> {
            println("Индекс: ${cached.chunks.size} чанков (из кэша)")
            cached
        }
        else -> {
            println("Строю индекс FAQ …")
            val fresh = ingest(cfg, mode)
            saveIndex(cfg.indexPath, mode, fresh)
            println("Индекс построен: ${fresh.size} чанков")
            IndexFile(mode, fresh)
        }
    }

    // ── Свой MCP support-сервер ──
    val server = buildSupportMcpServer(repo)
    val engine = startSupportMcpServer(server, cfg.mcpPort)
    println("MCP support-сервер поднят на localhost:${cfg.mcpPort}")
    delay(1200)

    // ── Клиент MCP ──
    val mcpHttp = HttpClient(ClientCIO) { install(SSE) }
    val mcp = Client(clientInfo = Implementation(name = "support-agent", version = "1.0.0"))
    mcp.connect(StreamableHttpClientTransport(client = mcpHttp, url = "http://127.0.0.1:${cfg.mcpPort}/mcp"))
    val tools = mcp.listTools().tools
    println("MCP tools: ${tools.joinToString { it.name }}")

    // ── DeepSeek ──
    val llm = DeepSeekClient(cfg.deepseekKey, cfg.deepseekModel)

    // ── REPL ──
    println()
    println("REPL готов. Команды:")
    println("  /ask <ticket_id> <вопрос>  — ответить на вопрос по тикету")
    println("  /faq <вопрос>              — просто ответ по FAQ")
    println("  /reindex                   — пересобрать индекс")
    println("  /list                      — все тикеты")
    println("  /quit                      — выйти")
    println()

    Runtime.getRuntime().addShutdownHook(Thread { engine.stop(1, 3) })

    val stdin = System.`in`.bufferedReader(Charsets.UTF_8)
    var index = indexData
    while (true) {
        print(">>> ")
        val line = stdin.readLine()?.trim() ?: break
        if (line.isEmpty()) continue
        when {
            line == "/quit" || line == "/exit" -> break
            line == "/reindex" -> {
                val fresh = ingest(cfg, mode)
                saveIndex(cfg.indexPath, mode, fresh)
                index = IndexFile(mode, fresh)
                println("Reindex: ${fresh.size} чанков")
            }
            line == "/list" -> {
                repo.tickets.forEach { t -> println("  ${t.id} [${t.status}, ${t.priority}] uid=${t.user_id} — ${t.title}") }
            }
            line.startsWith("/ask ") -> {
                val rest = line.removePrefix("/ask ").trim()
                val space = rest.indexOf(' ')
                if (space < 0) { println("Формат: /ask <ticket_id> <вопрос>"); continue }
                val ticketId = rest.substring(0, space)
                val question = rest.substring(space + 1)
                handleAsk(ticketId, question, mcp, llm, cfg, index)
            }
            line.startsWith("/faq ") -> {
                handleFaq(line.removePrefix("/faq ").trim(), llm, cfg, index)
            }
            else -> println("Неизвестная команда. См. /ask, /faq, /list, /quit.")
        }
    }
    engine.stop(1, 3)
    exitProcess(0)
}

/** /ask — основной сценарий с тикетом. */
private suspend fun handleAsk(
    ticketId: String,
    question: String,
    mcp: Client,
    llm: DeepSeekClient,
    cfg: Config,
    index: IndexFile,
) {
    if (ticketId.isBlank() || question.isBlank()) {
        println("Формат: /ask <ticket_id> <вопрос>"); return
    }

    // 1. MCP get_ticket
    val ticketJson = callMcpText(mcp, "get_ticket", buildJsonObject { put("id", ticketId) })
    if (ticketJson.startsWith("тикет ") && ticketJson.contains("не найден")) {
        println("$ticketJson"); return
    }
    // 2. По user_id из тикета — MCP get_user + list_open_tickets
    val userId = extractField(ticketJson, "\"user_id\"")
    val userJson = if (userId.isNotBlank()) callMcpText(mcp, "get_user", buildJsonObject { put("id", userId) }) else "(user_id пуст)"
    val openList = if (userId.isNotBlank()) callMcpText(mcp, "list_open_tickets", buildJsonObject { put("user_id", userId); put("limit", 5) }) else "[]"

    // 3. RAG по FAQ — комбинация вопроса + тикета
    val query = "$question\n$ticketJson"
    val top = ragSearch(cfg, index, query)
    val ragContext = if (top.isEmpty()) "(нет релевантных FAQ)" else buildString {
        top.forEachIndexed { i, (c, sim) ->
            append("[F${i + 1}] (${c.source}, score=${"%.3f".format(sim)})\n${c.text}\n\n")
        }
    }.trimEnd()

    // 4. Промпт.
    val system = """
        Ты — оператор технической поддержки. Твоя задача — коротко и по делу ответить пользователю,
        опираясь на тикет и FAQ-контекст. Отвечай на русском.

        Приоритет источников (важно, при конфликте — верхний бьёт нижний):
        1. Данные тикета (что реально жалуется человек, статус, приоритет, теги)
        2. Данные пользователя (тариф, регион, дата регистрации — определяют доступные фичи)
        3. FAQ-фрагменты — как канон продуктовых правил
        4. Твои общие знания — только в крайнем случае, помечать «(предположение)»

        Формат ответа:
        1) Одно предложение — суть решения / прямой ответ.
        2) 2-4 шага пользователю (нумерованный список).
        3) Если проблема требует эскалации на инженера — так и напиши, укажи какие данные приложить.
        4) Ссылайся на источники FAQ маркерами [F1]/[F2]/[F3] сразу за фактом.
    """.trimIndent()

    val user = """
        === Тикет ===
        $ticketJson

        === Пользователь ===
        $userJson

        === Другие открытые тикеты этого пользователя ===
        $openList

        === FAQ top-${cfg.topK} ===
        $ragContext

        === Вопрос пользователя ===
        $question
    """.trimIndent()

    val t0 = System.currentTimeMillis()
    val answer = try {
        llm.chat(listOf(DeepSeekClient.Msg("system", system), DeepSeekClient.Msg("user", user)))
    } catch (e: Exception) { println("Ошибка LLM: ${e.message}"); return }
    val wall = System.currentTimeMillis() - t0

    println()
    println(answer)
    println()
    println("[wall ${wall} ms | ticket=$ticketId | uid=$userId | FAQ top-${top.size}]")
    top.forEachIndexed { i, (c, _) -> println("  [F${i + 1}] ${c.source}") }
    println()
}

/** /faq — быстрый ответ по FAQ без тикета. */
private suspend fun handleFaq(
    question: String,
    llm: DeepSeekClient,
    cfg: Config,
    index: IndexFile,
) {
    if (question.isBlank()) { println("Формат: /faq <вопрос>"); return }
    val top = ragSearch(cfg, index, question)
    val ragContext = if (top.isEmpty()) "(нет)" else buildString {
        top.forEachIndexed { i, (c, sim) ->
            append("[F${i + 1}] (${c.source}, score=${"%.3f".format(sim)})\n${c.text}\n\n")
        }
    }.trimEnd()

    val system = "Ты — оператор поддержки. Отвечай коротко на русском, опираясь на FAQ-контекст. Ссылайся на [F1]/[F2]/[F3]."
    val user = "=== FAQ top-${cfg.topK} ===\n$ragContext\n\n=== Вопрос ===\n$question"

    val answer = try {
        llm.chat(listOf(DeepSeekClient.Msg("system", system), DeepSeekClient.Msg("user", user)))
    } catch (e: Exception) { println("Ошибка LLM: ${e.message}"); return }
    println()
    println(answer)
    println()
    top.forEachIndexed { i, (c, _) -> println("  [F${i + 1}] ${c.source}") }
    println()
}

private suspend fun callMcpText(mcp: Client, tool: String, args: JsonObject?): String {
    val result = mcp.callTool(CallToolRequest(CallToolRequestParams(name = tool, arguments = args)))
    val texts = result.content.filterIsInstance<TextContent>().mapNotNull { it.text }
    return texts.joinToString("\n").trim().ifBlank { "(пусто)" }
}

/** Простой extractor — из JSON-строки достаём значение поля-имени. Точечно для user_id. */
private fun extractField(json: String, field: String): String {
    val i = json.indexOf(field)
    if (i < 0) return ""
    val colon = json.indexOf(':', i)
    if (colon < 0) return ""
    var start = colon + 1
    while (start < json.length && json[start].isWhitespace()) start++
    if (start >= json.length) return ""
    return if (json[start] == '"') {
        val end = json.indexOf('"', start + 1)
        if (end < 0) "" else json.substring(start + 1, end)
    } else {
        val end = json.indexOfAny(charArrayOf(',', '}', '\n'), start)
        if (end < 0) json.substring(start).trim() else json.substring(start, end).trim()
    }
}
