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
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

/**
 * Day 35 — Weekly VPS Report Bot (реальная задача).
 *
 * ЧТО РЕШАЕТ:
 *   У Данила 15+ ботов и активных проектов на одном VPS. Раньше он вручную
 *   лазил в bot.log, git log, quickai — понять «что произошло за неделю».
 *   Теперь этот сервис запускается воскресенье вечером, сам собирает данные,
 *   отдаёт LLM и присылает 1 короткое сообщение в Telegram: «за неделю
 *   такое-то, обрати внимание на такое-то».
 *
 * AI-ЧАСТЬ:
 *   1. Свой MCP-сервер над VPS-инфраструктурой (git activity / bots health /
 *      token spend / system snapshot).
 *   2. MCP-клиент внутри же процесса собирает данные через tool-calls.
 *   3. DeepSeek с system prompt «пиши краткий еженедельный отчёт со
 *      структурой» — форматирует данные в человеческий текст.
 *   4. Отправка в Telegram bot API.
 */
fun main(args: Array<String>) = runBlocking<Unit> {
    val cfg = parseArgs(args)
    System.err.println("== Day 35 weekly VPS report ==")
    System.err.println("home: ${cfg.homeDir}, sessions: ${cfg.sessionsDir}")
    System.err.println("projects: ${cfg.projectDirs.map { it.name }}")
    System.err.println("quickai.db: ${cfg.quickaiDb ?: "(нет)"}")
    System.err.println("окно: ${cfg.days} дней; send=${cfg.send}")

    // 1) MCP-сервер над VPS
    val server = buildReportMcpServer(cfg)
    val engine = startReportMcpServer(server, cfg.mcpPort)
    delay(1000)

    // 2) MCP-клиент подключается к своему же серверу и собирает всё
    val mcpHttp = HttpClient(ClientCIO) { install(SSE) }
    val mcp = Client(clientInfo = Implementation(name = "vps-report", version = "1.0.0"))
    mcp.connect(StreamableHttpClientTransport(client = mcpHttp, url = "http://127.0.0.1:${cfg.mcpPort}/mcp"))
    val tools = mcp.listTools().tools
    System.err.println("MCP tools: ${tools.joinToString { it.name }}")

    val gitData = callMcp(mcp, "vps_git_activity", buildJsonObject { put("days", cfg.days) })
    val botsData = callMcp(mcp, "vps_bots_health", buildJsonObject { put("days", cfg.days) })
    val spendData = callMcp(mcp, "vps_token_spend", buildJsonObject { put("days", cfg.days) })
    val sysData = callMcp(mcp, "vps_system", null)

    // 3) DeepSeek форматирует
    val today = LocalDate.now(ZoneId.of("Asia/Novosibirsk")).format(DateTimeFormatter.ISO_DATE)
    val system = """
        Ты — ассистент, который пишет еженедельные отчёты об инфраструктуре VPS Данила.
        Формат отчёта, строго:

        📊 ОТЧЁТ ЗА НЕДЕЛЮ (до $today)

        🚀 Активность в проектах:
        <2-4 строки по существу — где были коммиты, какие важные, кратко>

        🤖 Здоровье ботов:
        <2-3 строки — работают ли, были ли частые падения (exit 137/143), гигантские логи>

        💸 Трата токенов:
        <2 строки — топ-3 проектов по $, суммарно за неделю>

        🖥 Система:
        <1 строка — диск, RAM, load>

        ⚠️ На что обратить внимание:
        <1-3 буллета — только если реально есть тревога. Если всё ок — «всё стабильно».>

        Язык — русский. Никаких вступлений/прощаний. Никаких «отчёт готов!». Только сам отчёт.
        Пиши то что есть в данных, не выдумывай.
    """.trimIndent()

    val user = """
        === Git-активность (${cfg.days} дней) ===
        $gitData

        === Здоровье ботов ===
        $botsData

        === Трата токенов (quickai) ===
        $spendData

        === Система ===
        $sysData
    """.trimIndent()

    val llm = DeepSeekClient(cfg.deepseekKey, cfg.deepseekModel)
    System.err.println("Зову DeepSeek…")
    val report = try {
        llm.chat(listOf(DeepSeekClient.Msg("system", system), DeepSeekClient.Msg("user", user)))
    } catch (e: Exception) {
        System.err.println("Ошибка LLM: ${e.message}")
        engine.stop(1, 3)
        exitProcess(3)
    }

    println(report)

    // 4) Отправка в Telegram (если разрешено)
    if (cfg.send) {
        if (cfg.telegramBotToken.isBlank()) {
            System.err.println("TELEGRAM_BOT_TOKEN пуст — не могу отправить")
        } else {
            val ok = TelegramClient(cfg.telegramBotToken).sendMessage(cfg.telegramChatId, report)
            System.err.println(if (ok) "→ отправлено в chat_id=${cfg.telegramChatId}" else "→ ошибка отправки")
        }
    } else {
        System.err.println("(--send не указан — только распечатал в stdout)")
    }

    engine.stop(1, 3)
    exitProcess(0)
}

private suspend fun callMcp(mcp: Client, tool: String, args: JsonObject?): String {
    val r = mcp.callTool(CallToolRequest(CallToolRequestParams(name = tool, arguments = args)))
    return r.content.filterIsInstance<TextContent>().mapNotNull { it.text }
        .joinToString("\n").ifBlank { "(пусто)" }
}
