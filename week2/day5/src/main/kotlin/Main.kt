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
    println("┌──────────────────────────────────────────────────────┐")
    println("│  Ход $turn  [${agent.strategy.name}]")
    println("├──────────────────────┬───────────────────────────────┤")
    println("│  Запрос              │  ${result.usage.prompt_tokens} токенов")
    println("│  Ответ               │  ${result.usage.completion_tokens} токенов")
    if (result.extraUsage != null) {
        println("│  Facts-запрос        │  ${result.extraUsage.prompt_tokens} токенов")
    }
    println("├──────────────────────┼───────────────────────────────┤")
    println("│  Всего промптов      │  ${agent.totalPromptTokens}")
    println("│  Стоимость           │  ${formatCost(agent.totalPromptTokens, agent.totalCompletionTokens)}")
    println("│  Инфо                │  ${result.info}")
    println("└──────────────────────┴───────────────────────────────┘")
    println()
}

fun runCompare(systemPrompt: String) {
    val scenario = listOf(
        "Меня зовут Данил, я Android-разработчик на Kotlin",
        "Хочу сделать приложение для отслеживания задач команды",
        "Оно должно работать офлайн и синхронизироваться через облако",
        "Целевая аудитория — фрилансеры и малый бизнес, бюджет ограничен",
        "Назови 3 главных риска этого проекта и как их митигировать"
    )

    println()
    println("╔══════════════════════════════════════════════════════════╗")
    println("║  СРАВНЕНИЕ СТРАТЕГИЙ — один сценарий, ${scenario.size} сообщений       ║")
    println("╚══════════════════════════════════════════════════════════╝")
    println()

    data class StrategyResult(val promptTokens: Int, val completionTokens: Int)
    val results = mutableMapOf<Strategy, StrategyResult>()

    for (strategy in Strategy.values()) {
        print("  Прогоняю ${strategy.name.padEnd(16)}... ")
        System.out.flush()
        val agent = Agent(systemPrompt)
        agent.strategy = strategy
        var totalPrompt = 0
        var totalCompletion = 0

        for (msg in scenario) {
            try {
                val result = agent.chat(msg)
                totalPrompt += result.usage.prompt_tokens + (result.extraUsage?.prompt_tokens ?: 0)
                totalCompletion += result.usage.completion_tokens + (result.extraUsage?.completion_tokens ?: 0)
            } catch (e: Exception) {
                println("ошибка: ${e.message}")
                break
            }
        }

        results[strategy] = StrategyResult(totalPrompt, totalCompletion)
        println("${totalPrompt + totalCompletion} токенов")
    }

    println()
    println("┌──────────────────┬──────────────┬──────────────┬──────────────┬──────────────┐")
    println("│ Стратегия        │ Промпт       │ Ответы       │ Итого        │ Стоимость    │")
    println("├──────────────────┼──────────────┼──────────────┼──────────────┼──────────────┤")

    val minTotal = results.values.minOfOrNull { it.promptTokens + it.completionTokens } ?: 1
    for (strategy in Strategy.values()) {
        val r = results[strategy] ?: continue
        val total = r.promptTokens + r.completionTokens
        val ratio = if (total == minTotal) " ✅" else " +${((total - minTotal) * 100) / minTotal}%"
        println("│ ${strategy.name.padEnd(16)} │ ${r.promptTokens.toString().padEnd(12)} │ ${r.completionTokens.toString().padEnd(12)} │ ${total.toString().padEnd(10)}$ratio │ ${formatCost(r.promptTokens, r.completionTokens).padEnd(12)} │")
    }
    println("└──────────────────┴──────────────┴──────────────┴──────────────┴──────────────┘")
    println()
}

fun main() {
    val systemPrompt = "Ты полезный ассистент. Отвечай кратко и по делу на русском языке."
    val agent = Agent(systemPrompt)

    println("Day 10 — Context Strategies")
    println("Стратегии: SLIDING_WINDOW (окно $WINDOW_SIZE) | STICKY_FACTS (факты + $FACTS_WINDOW послед.) | BRANCHING (ветки)")
    println()
    println("Команды:")
    println("  /strategy window|facts|branch  — переключить стратегию")
    println("  /facts                         — показать факты (только STICKY_FACTS)")
    println("  /checkpoint                    — создать точку ветвления (только BRANCHING)")
    println("  /branch <имя>                  — переключиться на ветку (только BRANCHING)")
    println("  /compare                       — сравнить все стратегии на одном сценарии")
    println("  exit                           — выход")
    println()
    println("Текущая стратегия: ${agent.strategy}")
    println()

    var turn = 0
    while (true) {
        val branchLabel = if (agent.strategy == Strategy.BRANCHING && agent.currentBranch != null)
            " [${agent.currentBranch}]" else ""
        print("You$branchLabel: ")

        val input = readLine()?.trim() ?: break
        if (input.equals("exit", ignoreCase = true)) break
        if (input.isBlank()) continue

        when {
            input.startsWith("/strategy ") -> {
                val name = input.removePrefix("/strategy ").trim().uppercase()
                val mapped = when (name) {
                    "WINDOW" -> "SLIDING_WINDOW"
                    "FACTS" -> "STICKY_FACTS"
                    "BRANCH", "BRANCHING" -> "BRANCHING"
                    else -> name
                }
                try {
                    agent.strategy = Strategy.valueOf(mapped)
                    println(">>> Стратегия: ${agent.strategy}\n")
                } catch (_: Exception) {
                    println(">>> Неизвестная стратегия. Используй: window, facts, branch\n")
                }
            }

            input == "/facts" -> {
                if (agent.facts.isEmpty()) println("(фактов ещё нет)\n")
                else {
                    println("\nИзвлечённые факты:")
                    agent.facts.forEach { (k, v) -> println("  $k: $v") }
                    println()
                }
            }

            input == "/checkpoint" -> {
                if (agent.strategy != Strategy.BRANCHING) {
                    println(">>> /checkpoint работает только в стратегии BRANCHING\n")
                } else {
                    agent.setCheckpoint()
                    println(">>> Checkpoint создан. Создай ветки командой /branch a и /branch b\n")
                }
            }

            input.startsWith("/branch ") -> {
                val name = input.removePrefix("/branch ").trim()
                if (agent.strategy != Strategy.BRANCHING) {
                    println(">>> /branch работает только в стратегии BRANCHING\n")
                } else if (!agent.hasCheckpoint) {
                    println(">>> Сначала создай checkpoint командой /checkpoint\n")
                } else {
                    agent.switchBranch(name)
                    println(">>> Переключился на ветку: $name\n")
                }
            }

            input == "/compare" -> {
                runCompare(systemPrompt)
            }

            else -> {
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
    }
}
