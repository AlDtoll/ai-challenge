import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

private const val DEEPSEEK_URL = "https://api.deepseek.com/chat/completions"
private const val MODEL = "deepseek-chat"
const val WINDOW_SIZE = 8
const val FACTS_WINDOW = 4

enum class Strategy { SLIDING_WINDOW, STICKY_FACTS, BRANCHING }

data class Message(val role: String, val content: String)
private data class ChatRequest(val model: String, val messages: List<Message>)
private data class Choice(val message: Message)
data class Usage(val prompt_tokens: Int, val completion_tokens: Int, val total_tokens: Int)
private data class ChatResponse(val choices: List<Choice>, val usage: Usage)

data class ChatResult(
    val reply: String,
    val usage: Usage,
    val extraUsage: Usage? = null,
    val info: String = ""
)

class Agent(val systemPrompt: String) {
    private val gson = Gson()
    private val client = HttpClient.newHttpClient()
    private val apiKey = loadEnvKey("DEEPSEEK_API_KEY")

    var strategy: Strategy = Strategy.SLIDING_WINDOW

    // Sliding Window
    private val window = mutableListOf<Message>()
    var windowDropped = 0

    // Sticky Facts
    private val factsHistory = mutableListOf<Message>()
    val facts = mutableMapOf<String, String>()

    // Branching
    private val trunk = mutableListOf<Message>()
    private val branches = mutableMapOf<String, MutableList<Message>>()
    var currentBranch: String? = null
    var hasCheckpoint = false

    var totalPromptTokens = 0
    var totalCompletionTokens = 0

    fun chat(userMessage: String): ChatResult = when (strategy) {
        Strategy.SLIDING_WINDOW -> chatWindow(userMessage)
        Strategy.STICKY_FACTS -> chatFacts(userMessage)
        Strategy.BRANCHING -> chatBranch(userMessage)
    }

    // === SLIDING WINDOW ===
    private fun chatWindow(userMessage: String): ChatResult {
        window.add(Message("user", userMessage))
        pruneWindow()

        val msgs = listOf(Message("system", systemPrompt)) + window
        val (reply, usage) = callApi(msgs)

        window.add(Message("assistant", reply))
        pruneWindow()

        totalPromptTokens += usage.prompt_tokens
        totalCompletionTokens += usage.completion_tokens
        return ChatResult(reply, usage, info = "Окно: ${window.size}/$WINDOW_SIZE | отброшено: $windowDropped")
    }

    private fun pruneWindow() {
        while (window.size > WINDOW_SIZE) {
            window.removeAt(0)
            windowDropped++
        }
    }

    // === STICKY FACTS ===
    private fun chatFacts(userMessage: String): ChatResult {
        val factsUsage = updateFacts(userMessage)

        factsHistory.add(Message("user", userMessage))
        val recent = factsHistory.takeLast(FACTS_WINDOW)

        val msgs = mutableListOf(Message("system", systemPrompt))
        if (facts.isNotEmpty()) {
            val factsText = facts.entries.joinToString("\n") { "- ${it.key}: ${it.value}" }
            msgs.add(Message("system", "Ключевые факты из диалога:\n$factsText"))
        }
        msgs.addAll(recent)

        val (reply, usage) = callApi(msgs)
        factsHistory.add(Message("assistant", reply))

        totalPromptTokens += usage.prompt_tokens + factsUsage.prompt_tokens
        totalCompletionTokens += usage.completion_tokens + factsUsage.completion_tokens

        return ChatResult(reply, usage, extraUsage = factsUsage,
            info = "Фактов: ${facts.size} | последних: ${recent.size}/$FACTS_WINDOW")
    }

    private fun updateFacts(userMessage: String): Usage {
        val history = factsHistory.takeLast(4).joinToString("\n") { "${it.role}: ${it.content}" }
        val currentFacts = if (facts.isEmpty()) "нет" else facts.entries.joinToString(", ") { "${it.key}: ${it.value}" }

        val prompt = buildString {
            appendLine("Обнови список ключевых фактов. Текущие факты: $currentFacts")
            if (history.isNotBlank()) appendLine("История:\n$history")
            appendLine("Новое сообщение: $userMessage")
            append("Верни ТОЛЬКО JSON без пояснений: {\"ключ\": \"значение\", ...}. Максимум 10 фактов.")
        }

        val (raw, usage) = callApi(listOf(Message("user", prompt)))
        try {
            val jsonStart = raw.indexOf('{')
            val jsonEnd = raw.lastIndexOf('}')
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                val type = object : TypeToken<Map<String, String>>() {}.type
                val extracted: Map<String, String> = gson.fromJson(raw.substring(jsonStart, jsonEnd + 1), type)
                facts.putAll(extracted)
            }
        } catch (_: Exception) {}

        return usage
    }

    // === BRANCHING ===
    private fun chatBranch(userMessage: String): ChatResult {
        currentList().add(Message("user", userMessage))

        val msgs = buildBranchMessages()
        val (reply, usage) = callApi(msgs)
        currentList().add(Message("assistant", reply))

        totalPromptTokens += usage.prompt_tokens
        totalCompletionTokens += usage.completion_tokens

        val branchLabel = currentBranch?.let { "Ветка: $it" } ?: "Ствол"
        val msgCount = if (currentBranch != null) branches[currentBranch!!]?.size ?: 0 else trunk.size
        return ChatResult(reply, usage, info = "$branchLabel | msgs: $msgCount | ствол: ${trunk.size}")
    }

    fun setCheckpoint() {
        hasCheckpoint = true
    }

    fun switchBranch(name: String) {
        currentBranch = name
        branches.getOrPut(name) { mutableListOf() }
    }

    private fun currentList(): MutableList<Message> =
        if (currentBranch != null) branches.getOrPut(currentBranch!!) { mutableListOf() }
        else trunk

    private fun buildBranchMessages(): List<Message> {
        val msgs = mutableListOf(Message("system", systemPrompt))
        msgs.addAll(trunk)
        if (currentBranch != null) msgs.addAll(branches[currentBranch!!] ?: emptyList())
        return msgs
    }

    fun branchNames(): List<String> = branches.keys.toList()

    // === API ===
    internal fun callApi(messages: List<Message>): Pair<String, Usage> {
        val body = gson.toJson(ChatRequest(MODEL, messages))
        val request = HttpRequest.newBuilder()
            .uri(URI.create(DEEPSEEK_URL))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        val parsed = gson.fromJson(response.body(), ChatResponse::class.java)
        return parsed.choices.first().message.content to parsed.usage
    }
}
