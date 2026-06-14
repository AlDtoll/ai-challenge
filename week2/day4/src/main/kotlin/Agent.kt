import com.google.gson.Gson
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

private const val DEEPSEEK_URL = "https://api.deepseek.com/chat/completions"
private const val MODEL = "deepseek-chat"
const val KEEP_LAST = 6
const val COMPRESS_THRESHOLD = 10

data class Message(val role: String, val content: String)
private data class ChatRequest(val model: String, val messages: List<Message>)
private data class Choice(val message: Message)
data class Usage(val prompt_tokens: Int, val completion_tokens: Int, val total_tokens: Int)
private data class ChatResponse(val choices: List<Choice>, val usage: Usage)

data class ChatResult(val reply: String, val usage: Usage, val compressed: Boolean = false, val tokensBeforeCompress: Int = 0, val tokensAfterCompress: Int = 0)

class Agent(private val systemPrompt: String) {

    private val gson = Gson()
    private val client = HttpClient.newHttpClient()
    private val apiKey = loadEnvKey("DEEPSEEK_API_KEY")

    private val archive = mutableListOf<Message>()  // сжатые в summary
    private val recent = mutableListOf<Message>()   // последние KEEP_LAST "как есть"
    var summary: String = ""

    var totalPromptTokens = 0
    var totalCompletionTokens = 0
    var compressCount = 0

    fun chat(userMessage: String): ChatResult {
        recent.add(Message("user", userMessage))

        val compressed = shouldCompress()
        var tokensBeforeCompress = 0
        var tokensAfterCompress = 0

        if (compressed) {
            tokensBeforeCompress = estimateTokens()
            compress()
            tokensAfterCompress = estimateTokens()
        }

        val messages = buildMessages()
        val body = gson.toJson(ChatRequest(model = MODEL, messages = messages))
        val request = HttpRequest.newBuilder()
            .uri(URI.create(DEEPSEEK_URL))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        val parsed = gson.fromJson(response.body(), ChatResponse::class.java)
        val reply = parsed.choices.first().message.content
        val usage = parsed.usage

        recent.add(Message("assistant", reply))
        totalPromptTokens += usage.prompt_tokens
        totalCompletionTokens += usage.completion_tokens

        return ChatResult(reply, usage, compressed, tokensBeforeCompress, tokensAfterCompress)
    }

    fun compress() {
        val toCompress = archive + recent.dropLast(KEEP_LAST.coerceAtMost(recent.size))
        if (toCompress.isEmpty()) return

        val historyText = toCompress.joinToString("\n") { "${it.role}: ${it.content}" }
        val prompt = buildString {
            if (summary.isNotBlank()) appendLine("Текущий конспект:\n$summary\n")
            appendLine("Новые сообщения для добавления в конспект:")
            appendLine(historyText)
            appendLine("\nОбнови конспект — сохрани только факты, имена, числа, решения. Убери воду и приветствия. Максимум 200 слов.")
        }

        val summaryMessages = listOf(Message("user", prompt))
        val body = gson.toJson(ChatRequest(model = MODEL, messages = summaryMessages))
        val request = HttpRequest.newBuilder()
            .uri(URI.create(DEEPSEEK_URL))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        val parsed = gson.fromJson(response.body(), ChatResponse::class.java)
        summary = parsed.choices.first().message.content

        archive.clear()
        val keepCount = KEEP_LAST.coerceAtMost(recent.size)
        val kept = recent.takeLast(keepCount).toMutableList()
        recent.clear()
        recent.addAll(kept)

        compressCount++
    }

    private fun shouldCompress(): Boolean = archive.size + recent.size - KEEP_LAST >= COMPRESS_THRESHOLD

    private fun buildMessages(): List<Message> {
        val messages = mutableListOf<Message>()
        messages.add(Message("system", systemPrompt))
        if (summary.isNotBlank()) {
            messages.add(Message("system", "Краткое содержание предыдущей части диалога:\n$summary"))
        }
        messages.addAll(recent)
        return messages
    }

    // Грубая оценка токенов в текущем контексте (символы / 4)
    private fun estimateTokens(): Int {
        val text = buildMessages().joinToString(" ") { it.content }
        return text.length / 4
    }

    fun recentCount() = recent.size
    fun archiveCount() = archive.size
    fun hasSummary() = summary.isNotBlank()
}
