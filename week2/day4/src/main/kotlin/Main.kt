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

private const val PRICE_PER_M_INPUT = 0.27
private const val PRICE_PER_M_OUTPUT = 1.10

fun formatCost(inputTokens: Int, outputTokens: Int): String {
    val cost = (inputTokens / 1_000_000.0) * PRICE_PER_M_INPUT +
               (outputTokens / 1_000_000.0) * PRICE_PER_M_OUTPUT
    return "$%.6f".format(cost)
}

fun printTable(turn: Int, result: ChatResult, agent: Agent) {
    println()
    println("┌─────────────────────────────────────────────────┐")
    println("│  Ход $turn")
    println("├──────────────────────┬──────────────────────────┤")
    println("│  Запрос              │  ${result.usage.prompt_tokens} токенов")
    println("│  Ответ               │  ${result.usage.completion_tokens} токенов")
    println("│  Итого этот ход      │  ${result.usage.total_tokens} токенов")
    println("├──────────────────────┼──────────────────────────┤")
    println("│  Всего промптов      │  ${agent.totalPromptTokens}")
    println("│  Стоимость           │  ${formatCost(agent.totalPromptTokens, agent.totalCompletionTokens)}")
    println("│  Живых сообщений     │  ${agent.recentCount()} (хранится как есть)")
    println("│  Summary             │  ${if (agent.hasSummary()) "есть (сжатий: ${agent.compressCount})" else "нет"}")
    if (result.compressed) {
        println("├──────────────────────┼──────────────────────────┤")
        println("│  ⚡ СЖАТИЕ           │  было ~${result.tokensBeforeCompress} → стало ~${result.tokensAfterCompress} токенов")
        println("│  Экономия           │  ~${result.tokensBeforeCompress - result.tokensAfterCompress} токенов (${
            if (result.tokensBeforeCompress > 0) "${((result.tokensBeforeCompress - result.tokensAfterCompress) * 100) / result.tokensBeforeCompress}%" else "?"
        })")
    }
    println("└──────────────────────┴──────────────────────────┘")
    println()
}

fun main() {
    val agent = Agent(
        systemPrompt = "Ты полезный ассистент. Отвечай кратко и по делу на русском языке."
    )

    println("Day 9 — Context Compression")
    println("Хранит последние $KEEP_LAST сообщений, остальное сжимает в summary (каждые $COMPRESS_THRESHOLD).")
    println("Команды: /compress — сжать сейчас | /summary — показать summary | exit — выход")
    println()

    var turn = 0
    while (true) {
        print("You: ")
        val input = readLine()?.trim() ?: break
        if (input.equals("exit", ignoreCase = true)) break
        if (input.isBlank()) continue

        if (input == "/summary") {
            if (agent.hasSummary()) println("\nSummary:\n${agent.summary}\n")
            else println("(summary ещё нет)\n")
            continue
        }

        if (input == "/compress") {
            println(">>> Принудительное сжатие...")
            agent.compress()
            println(">>> Summary обновлён. Живых сообщений: ${agent.recentCount()}")
            if (agent.hasSummary()) println(">>> ${agent.summary}\n")
            continue
        }

        turn++
        try {
            val result = agent.chat(input)
            println("\nAgent: ${result.reply}")
            printTable(turn, result, agent)
        } catch (e: Exception) {
            println("\n⛔ ОШИБКА: ${e.message}\n")
        }
    }
}
