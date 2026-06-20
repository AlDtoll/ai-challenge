import java.io.File

data class Message(val role: String, val content: String)

fun main() {
    val profileFile = File(System.getProperty("user.home"), ".ai-challenge/profile-day12.json")
    val manager = ProfileManager(profileFile)
    val llm = LlmClient()
    val extractor = ProfileExtractor(llm)
    val history = mutableListOf<Message>()

    println("╔══════════════════════════════════════════════════════╗")
    println("║  День 12 — Персонализация ассистента                ║")
    println("╚══════════════════════════════════════════════════════╝")
    println()
    println("Команды:")
    println("  /profile                  — показать профиль")
    println("  /profile set <поле> <зн>  — изменить поле профиля")
    println("  /switch junior|senior     — быстро переключить уровень")
    println("  /prompt                   — показать системный промпт")
    println("  /clear                    — очистить историю диалога")
    println("  exit                      — выход")
    println()

    manager.print()

    while (true) {
        print("[${manager.profile.level}] You: ")
        val input = readLine()?.trim() ?: break
        if (input.equals("exit", ignoreCase = true)) break
        if (input.isBlank()) continue

        when {
            input == "/profile" -> manager.print()

            input.startsWith("/profile set ") -> {
                val parts = input.removePrefix("/profile set ").trim().split(" ", limit = 2)
                if (parts.size < 2) {
                    println(">>> Формат: /profile set <поле> <значение>\n")
                } else {
                    val (field, value) = parts
                    if (manager.set(field, value)) println(">>> Обновлено: $field = $value\n")
                    else println(">>> Неизвестное поле: $field\n    Доступные: name, level, style, format, language, stack, constraints\n")
                }
            }

            input.startsWith("/switch ") -> {
                val level = input.removePrefix("/switch ").trim()
                if (manager.set("level", level)) println(">>> Уровень переключён на: $level\n")
                else println(">>> Укажи: junior / middle / senior\n")
            }

            input == "/prompt" -> {
                println("\n--- СИСТЕМНЫЙ ПРОМПТ ---")
                println(SystemPromptBuilder.build(manager.profile))
                println("------------------------\n")
            }

            input == "/clear" -> {
                history.clear()
                println(">>> История очищена.\n")
            }

            else -> {
                try {
                    val systemPrompt = SystemPromptBuilder.build(manager.profile)
                    val (reply, usage) = llm.chat(systemPrompt, history)
                    history.add(Message("user", input))
                    history.add(Message("assistant", reply))

                    println("\nAgent: $reply")
                    println("  [↑${usage.prompt_tokens} / ↓${usage.completion_tokens} токенов]\n")

                    // Автообновление профиля
                    val updated = extractor.extract(manager.profile, input, reply)
                    if (updated != null) {
                        println("  ✦ Профиль обновлён автоматически:")
                        if (updated.level != manager.profile.level) println("    level: ${manager.profile.level} → ${updated.level}")
                        if (updated.style != manager.profile.style) println("    style: ${manager.profile.style} → ${updated.style}")
                        if (updated.format != manager.profile.format) println("    format: ${manager.profile.format} → ${updated.format}")
                        println()
                        manager.update(updated)
                    }
                } catch (e: Exception) {
                    println("\n⛔ Ошибка: ${e.message}\n")
                }
            }
        }
    }

    println("До свидания!")
}
