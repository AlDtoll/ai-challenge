import java.io.File

fun loadEnvKey(key: String): String {
    System.getenv(key)?.takeIf { it.isNotBlank() }?.let { return it }
    var dir = File(".").canonicalFile
    repeat(5) {
        val value = File(dir, ".env").takeIf { it.exists() }
            ?.readLines()?.firstOrNull { it.startsWith("$key=") }
            ?.substringAfter("=")?.trim()
        if (!value.isNullOrBlank()) return value
        dir = dir.parentFile ?: return@repeat
    }
    error("Set $key in environment or .env file")
}

fun main() {
    val botToken = loadEnvKey("TELEGRAM_BOT_TOKEN")

    val agent = Agent(
        systemPrompt = "Ты полезный ассистент. Отвечай кратко и по делу на русском языке."
    )
    val bot = TelegramBot(token = botToken, agent = agent)
    bot.run()
}
