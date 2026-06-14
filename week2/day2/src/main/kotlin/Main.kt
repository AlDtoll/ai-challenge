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

fun findEnvKey(key: String): String? {
    System.getenv(key)?.takeIf { it.isNotBlank() }?.let { return it }
    var dir = File(".").canonicalFile
    repeat(5) {
        val value = File(dir, ".env").takeIf { it.exists() }
            ?.readLines()?.firstOrNull { it.startsWith("$key=") }
            ?.substringAfter("=")?.trim()
        if (!value.isNullOrBlank()) return value
        dir = dir.parentFile ?: return@repeat
    }
    return null
}

fun main() {
    val agent = Agent(
        systemPrompt = "Ты полезный ассистент. Отвечай кратко и по делу на русском языке."
    )

    val botToken = findEnvKey("TELEGRAM_BOT_TOKEN")
    if (botToken != null) {
        println("TELEGRAM_BOT_TOKEN found — starting Telegram bot")
        val bot = TelegramBot(token = botToken, agent = agent)
        bot.run()
    } else {
        println("No TELEGRAM_BOT_TOKEN — running in console mode. Type 'exit' to quit.")
        while (true) {
            print("You: ")
            val input = readLine()?.trim() ?: break
            if (input.equals("exit", ignoreCase = true)) break
            if (input.isBlank()) continue
            val reply = agent.chat(input)
            println("Agent: $reply\n")
        }
    }
}
