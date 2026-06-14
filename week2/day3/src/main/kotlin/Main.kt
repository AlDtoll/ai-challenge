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

private const val PRICE_PER_M_INPUT = 0.27   // $ за 1M токенов (DeepSeek V3)
private const val PRICE_PER_M_OUTPUT = 1.10

fun formatCost(inputTokens: Int, outputTokens: Int): String {
    val cost = (inputTokens / 1_000_000.0) * PRICE_PER_M_INPUT +
               (outputTokens / 1_000_000.0) * PRICE_PER_M_OUTPUT
    return "$%.6f".format(cost)
}

fun printTable(turn: Int, result: ChatResult, agent: Agent) {
    val fill = agent.contextFillPercent()
    val fillBar = when {
        fill >= 100 -> "🔴 OVERFLOW"
        fill >= 80  -> "🟠 ${fill}%"
        fill >= 50  -> "🟡 ${fill}%"
        else        -> "🟢 ${fill}%"
    }
    println()
    println("┌─────────────────────────────────────────────┐")
    println("│  Ход $turn")
    println("├──────────────────┬──────────────────────────┤")
    println("│  Запрос          │  ${result.usage.prompt_tokens} токенов")
    println("│  Ответ           │  ${result.usage.completion_tokens} токенов")
    println("│  Итого этот ход  │  ${result.usage.total_tokens} токенов")
    println("├──────────────────┼──────────────────────────┤")
    println("│  Всего промптов  │  ${agent.totalPromptTokens}")
    println("│  Всего ответов   │  ${agent.totalCompletionTokens}")
    println("│  Стоимость       │  ${formatCost(agent.totalPromptTokens, agent.totalCompletionTokens)}")
    println("│  Контекст        │  $fillBar / $CONTEXT_LIMIT")
    println("│  Сообщений       │  ${agent.historyMessages()}")
    println("└──────────────────┴──────────────────────────┘")
    println()
}

fun main() {
    val agent = Agent(
        systemPrompt = "Ты полезный ассистент. Отвечай кратко и по делу на русском языке."
    )

    println("Day 8 — Token Counter")
    println("Команды: /fill <токены> — симулировать большой диалог | exit — выход")
    println()

    var turn = 0
    while (true) {
        print("You: ")
        val input = readLine()?.trim() ?: break
        if (input.equals("exit", ignoreCase = true)) break
        if (input.isBlank()) continue

        if (input.startsWith("/fill")) {
            val tokens = input.removePrefix("/fill").trim().toIntOrNull() ?: 10_000
            agent.fill(tokens)
            println(">>> Добавлено ~$tokens токенов в историю. Контекст: ${agent.contextFillPercent()}%")
            continue
        }

        turn++
        try {
            val result = agent.chat(input)
            println("\nAgent: ${result.reply}")
            printTable(turn, result, agent)

            if (agent.contextFillPercent() >= 100) {
                println("⛔ КОНТЕКСТ ПЕРЕПОЛНЕН! Следующий запрос вернёт ошибку от API.")
            }
        } catch (e: Exception) {
            println()
            println("⛔ ОШИБКА API: ${e.message}")
            println("   Контекст переполнен — модель не может обработать такой длинный диалог.")
            println("   Используй /fill меньшее значение или начни новый сеанс.")
            println()
        }
    }
}
