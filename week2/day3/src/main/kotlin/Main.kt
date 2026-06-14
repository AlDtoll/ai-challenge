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
    val agent = Agent(
        systemPrompt = "Ты полезный ассистент. Отвечай кратко и по делу на русском языке."
    )

    println("Token counter mode. Type 'exit' to quit.\n")

    var turn = 0
    while (true) {
        print("You: ")
        val input = readLine()?.trim() ?: break
        if (input.equals("exit", ignoreCase = true)) break
        if (input.isBlank()) continue

        turn++
        val result = agent.chat(input)

        println("Agent: ${result.reply}\n")
        println("--- Turn $turn tokens ---")
        println("  Request (prompt):    ${result.usage.prompt_tokens}")
        println("  Response (completion): ${result.usage.completion_tokens}")
        println("  Total this turn:     ${result.usage.total_tokens}")
        println("--- Cumulative ---")
        println("  Prompt tokens total:     ${agent.totalPromptTokens}")
        println("  Completion tokens total: ${agent.totalCompletionTokens}")
        println("  Grand total:             ${agent.totalPromptTokens + agent.totalCompletionTokens}")
        println("  Messages in history:     ${agent.historySize()}")
        println()
    }
}
