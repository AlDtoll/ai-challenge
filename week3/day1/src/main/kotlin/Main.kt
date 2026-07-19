import java.io.File

fun main() {
    val dataDir = File(System.getProperty("user.home"), ".ai-challenge/memory")
    dataDir.mkdirs()

    val shortTerm = ShortTermMemory()
    val working = WorkingMemory(File(dataDir, "working-task.json"))
    val longTerm = LongTermMemory(File(dataDir, "long-term.json"))

    if (longTerm.isNew()) {
        println("╔══════════════════════════════════════════════════════╗")
        println("║  День 11 — Модель памяти ассистента                 ║")
        println("║  Первый запуск — настройка профиля                  ║")
        println("╚══════════════════════════════════════════════════════╝")
        println()
        println("Эти данные сохранятся в долговременной памяти.")
        println()
        print("Как тебя зовут? ")
        val name = readLine()?.trim() ?: ""

        print("Стиль ответов (краткий/подробный/с примерами): ")
        val style = readLine()?.trim()?.ifEmpty { "краткий" } ?: "краткий"

        print("Твой технологический стек (например: Kotlin, Android, Jetpack Compose): ")
        val stack = readLine()?.trim() ?: ""

        print("Ограничения/запреты (через запятую, или Enter чтобы пропустить): ")
        val constraintsRaw = readLine()?.trim() ?: ""
        val constraints = if (constraintsRaw.isBlank()) emptyList()
        else constraintsRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }

        longTerm.saveProfile(name, style, stack, constraints)
        println("\nПрофиль сохранён в долговременной памяти.\n")
    }

    val agent = Agent(shortTerm, working, longTerm)

    println("╔══════════════════════════════════════════════════════╗")
    println("║  День 11 — Модель памяти ассистента                 ║")
    println("╚══════════════════════════════════════════════════════╝")
    println()
    println("Команды:")
    println("  /memory              — показать состояние всех слоёв памяти")
    println("  /task <описание>     — начать задачу (рабочая память)")
    println("  /stage <стадия>      — обновить стадию задачи")
    println("  /artifact <к>=<в>    — добавить артефакт к задаче")
    println("  /note <текст>        — добавить заметку к задаче")
    println("  /close               — завершить задачу")
    println("  /remember <факт>     — сохранить факт в долговременную память")
    println("  /decide <решение>    — сохранить решение в долговременную память")
    println("  /clear               — очистить краткосрочную память (начать новый диалог)")
    println("  /prompt              — показать системный промпт (что видит LLM)")
    println("  exit                 — выход")
    println()
    println("Привет, ${longTerm.data.profile.name}! Память загружена.")
    working.task?.let { println("Активная задача: ${it.description} [${it.stage}]") }
    println()

    while (true) {
        val taskLabel = working.task?.let { " [задача: ${it.stage}]" } ?: ""
        print("You$taskLabel: ")

        val input = readLine()?.trim() ?: break
        if (input.equals("exit", ignoreCase = true)) break
        if (input.isBlank()) continue

        when {
            input == "/memory" -> agent.printMemoryStatus()

            input.startsWith("/task ") -> {
                val desc = input.removePrefix("/task ").trim()
                val task = working.startTask(desc)
                println(">>> Задача создана: ${task.id}\n    Стадия: ${task.stage}\n    Данные сохранены в рабочей памяти.\n")
            }

            input.startsWith("/stage ") -> {
                val stage = input.removePrefix("/stage ").trim()
                working.updateStage(stage)
                println(">>> Стадия обновлена: $stage\n")
            }

            input.startsWith("/artifact ") -> {
                val pair = input.removePrefix("/artifact ").trim()
                val eq = pair.indexOf('=')
                if (eq < 0) {
                    println(">>> Формат: /artifact ключ=значение\n")
                } else {
                    val key = pair.substring(0, eq).trim()
                    val value = pair.substring(eq + 1).trim()
                    working.addArtifact(key, value)
                    println(">>> Артефакт '$key' сохранён в рабочей памяти.\n")
                }
            }

            input.startsWith("/note ") -> {
                val note = input.removePrefix("/note ").trim()
                working.addNote(note)
                println(">>> Заметка добавлена в рабочую память.\n")
            }

            input == "/close" -> {
                working.closeTask()
                println(">>> Задача завершена. Рабочая память очищена.\n")
            }

            input.startsWith("/remember ") -> {
                val fact = input.removePrefix("/remember ").trim()
                longTerm.addKnowledge(fact)
                println(">>> Факт сохранён в долговременной памяти.\n")
            }

            input.startsWith("/decide ") -> {
                val decision = input.removePrefix("/decide ").trim()
                longTerm.addDecision(decision)
                println(">>> Решение сохранено в долговременной памяти.\n")
            }

            input == "/clear" -> {
                shortTerm.clear()
                println(">>> Краткосрочная память очищена. Новый диалог начат.\n")
            }

            input == "/prompt" -> {
                println("\n--- СИСТЕМНЫЙ ПРОМПТ (видит LLM) ---")
                println(buildPromptPreview(longTerm, working))
                println("--- КРАТКОСРОЧНАЯ ПАМЯТЬ (${shortTerm.size()} сообщений) ---\n")
            }

            else -> {
                try {
                    print("Agent: ")
                    System.out.flush()
                    val reply = agent.chat(input)
                    println(reply)
                    agent.lastUsage?.let { u ->
                        println("\n  [↑${u.prompt_tokens} / ↓${u.completion_tokens} токенов | краткосрочная: ${shortTerm.size()} сообщ.]\n")
                    }
                } catch (e: Exception) {
                    println("\n⛔ Ошибка: ${e.message}\n")
                }
            }
        }
    }

    println("До свидания!")
}

private fun buildPromptPreview(longTerm: LongTermMemory, working: WorkingMemory): String = buildString {
    appendLine("Ты умный персональный ассистент.")
    appendLine()
    appendLine(longTerm.toPromptBlock())
    working.toPromptBlock()?.let {
        appendLine()
        appendLine(it)
    }
}
