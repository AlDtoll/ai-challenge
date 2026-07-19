class Agent(
    val shortTerm: ShortTermMemory,
    val working: WorkingMemory,
    val longTerm: LongTermMemory
) {
    private val client = LlmClient()
    var lastUsage: Usage? = null

    fun chat(userMessage: String): String {
        shortTerm.add("user", userMessage)

        val systemPrompt = buildSystemPrompt()
        val (reply, usage) = client.chat(systemPrompt, shortTerm.getMessages().dropLast(1))
        lastUsage = usage

        shortTerm.add("assistant", reply)
        return reply
    }

    private fun buildSystemPrompt(): String = buildString {
        appendLine("Ты умный персональный ассистент.")
        appendLine()
        appendLine(longTerm.toPromptBlock())
        working.toPromptBlock()?.let {
            appendLine()
            appendLine(it)
        }
        appendLine()
        appendLine("Отвечай в соответствии со стилем и ограничениями профиля.")
    }

    fun printMemoryStatus() {
        println()
        println("┌─────────────────────────────────────────────────┐")
        println("│  СОСТОЯНИЕ ПАМЯТИ                               │")
        println("├─────────────────────────────────────────────────┤")
        println("│  [1] Краткосрочная (текущий диалог)             │")
        println("│      Сообщений: ${shortTerm.size().toString().padEnd(33)}│")
        println("│      ${shortTerm.describe().lines().first().take(45).padEnd(45)}│")
        println("├─────────────────────────────────────────────────┤")
        println("│  [2] Рабочая (текущая задача)                   │")
        working.describe().lines().forEach { line ->
            println("│      ${line.take(45).padEnd(45)}│")
        }
        println("├─────────────────────────────────────────────────┤")
        println("│  [3] Долговременная (профиль + знания)          │")
        longTerm.describe().lines().forEach { line ->
            println("│      ${line.take(45).padEnd(45)}│")
        }
        println("└─────────────────────────────────────────────────┘")
        println()
    }
}
