import java.io.File

/**
 * Конфиг Weekly-VPS-Report бота.
 * Все пути имеют разумные default'ы под VPS Данила (см. bot-infrastructure skill).
 */
data class Config(
    val homeDir: File,             // /home/claudeuser
    val sessionsDir: File,         // /home/claudeuser/sessions/  — где живут bot.log каждого бота
    val quickaiDb: File?,          // /home/claudeuser/.claude/quickai.db — SQLite со счётчиком токенов
    val projectDirs: List<File>,   // репозитории для git log за неделю
    val days: Int,                 // окно отчёта (default 7)
    val deepseekKey: String,
    val deepseekModel: String,
    val telegramBotToken: String,  // токен common-бота (в secrets.env)
    val telegramChatId: String,    // куда слать (Данил, 579387502)
    val mcpPort: Int,
    val send: Boolean,             // если false — только распечатать отчёт в stdout, ничего не слать
)

fun parseArgs(args: Array<String>): Config {
    fun env(name: String) = System.getenv(name)?.takeIf { it.isNotBlank() }

    val home = File(env("HOME") ?: "/home/claudeuser")
    var days = 7
    var send = false

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--days" -> { days = args[++i].toInt() }
            "--send" -> { send = true }
            "--help", "-h" -> { printHelp(); kotlin.system.exitProcess(0) }
            else -> { System.err.println("unknown arg: ${args[i]}"); printHelp(); kotlin.system.exitProcess(2) }
        }
        i++
    }

    // Дефолтный набор проектов для git-log — берём то что валяется в HOME.
    val defaultProjects = listOf("ai-challenge", "fuel-map", "zizz3", "antivoice-bot", "twiligihts")
        .map { File(home, it) }
        .filter { it.isDirectory && File(it, ".git").exists() }

    return Config(
        homeDir = home,
        sessionsDir = File(home, "sessions"),
        quickaiDb = File(home, ".claude/quickai.db").takeIf { it.exists() },
        projectDirs = defaultProjects,
        days = days.coerceAtLeast(1),
        deepseekKey = env("DEEPSEEK_API_KEY") ?: readDotEnvKey("DEEPSEEK_API_KEY") ?: "",
        deepseekModel = env("DEEPSEEK_MODEL") ?: "deepseek-chat",
        telegramBotToken = env("TELEGRAM_BOT_TOKEN") ?: readSecretsEnv(home, "TELEGRAM_BOT_TOKEN") ?: "",
        telegramChatId = env("TELEGRAM_CHAT_ID") ?: "579387502", // Данил по default
        mcpPort = env("MCP_PORT")?.toInt() ?: 3005,
        send = send,
    )
}

fun printHelp() = println(
    """
    Day 35 — Weekly VPS Report (реальная задача).

    Каждое воскресенье собирает данные о VPS Данила (git-активность его проектов,
    здоровье ботов, потраченные Claude-токены за неделю), кормит DeepSeek с шаблоном
    отчёта, и отправляет результат в Telegram.

    Usage: gradle :week7:day5:run [--args="[flags]"]
      --days N       окно отчёта в днях (default 7)
      --send         реально отправить в Telegram; без флага — только stdout

    Env (или ~/.claude/env/secrets.env):
      DEEPSEEK_API_KEY, TELEGRAM_BOT_TOKEN, TELEGRAM_CHAT_ID

    Cron (пример, воскресенье 20:00 NSK = 13:00 UTC):
      0 13 * * 0  cd /home/claudeuser/ai-challenge && ./gradlew :week7:day5:run --args="--send" >> /tmp/vps-report.log 2>&1
    """.trimIndent(),
)

private fun readDotEnvKey(name: String): String? {
    val candidates = listOf(File("../../.env"), File("../.env"), File(".env"))
    for (f in candidates) {
        if (!f.exists()) continue
        // File.forEachLine — не inline, non-local return запрещён; используем for + readLines.
        for (line in f.readLines()) {
            val t = line.trim()
            if (t.startsWith("$name=")) return t.substringAfter("=").trim().trim('"')
        }
    }
    return null
}

/** Читает ключ из ~/.claude/env/secrets.env (bash-подобный формат KEY=VALUE или KEY="VALUE"). */
private fun readSecretsEnv(home: File, name: String): String? {
    val f = File(home, ".claude/env/secrets.env")
    if (!f.exists()) return null
    // File.forEachLine — не inline, non-local return запрещён; for + readLines.
    for (line in f.readLines()) {
        val t = line.trim()
        if (t.startsWith("$name=")) return t.substringAfter("=").trim().trim('"').trim('\'')
    }
    return null
}
