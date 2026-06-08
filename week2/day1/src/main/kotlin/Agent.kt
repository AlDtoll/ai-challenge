import com.google.gson.Gson
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

private const val DEEPSEEK_URL = "https://api.deepseek.com/chat/completions"
private const val MODEL = "deepseek-chat"

data class Message(val role: String, val content: String)
private data class ChatRequest(val model: String, val messages: List<Message>)
private data class Choice(val message: Message)
private data class ChatResponse(val choices: List<Choice>)

class Agent(systemPrompt: String) {

    private val gson = Gson()
    private val client = HttpClient.newHttpClient()
    private val apiKey = loadApiKey()
    private val history = mutableListOf(Message("system", systemPrompt))

    fun chat(userMessage: String): String {
        history.add(Message("user", userMessage))

        val body = gson.toJson(ChatRequest(model = MODEL, messages = history))
        val request = HttpRequest.newBuilder()
            .uri(URI.create(DEEPSEEK_URL))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        val parsed = gson.fromJson(response.body(), ChatResponse::class.java)
        val reply = parsed.choices.first().message.content

        history.add(Message("assistant", reply))
        return reply
    }

    private fun loadApiKey(): String {
        System.getenv("DEEPSEEK_API_KEY")?.takeIf { it.isNotBlank() }?.let { return it }
        var dir = java.io.File(".").canonicalFile
        repeat(5) {
            val value = java.io.File(dir, ".env").takeIf { it.exists() }
                ?.readLines()?.firstOrNull { it.startsWith("DEEPSEEK_API_KEY=") }
                ?.substringAfter("=")?.trim()
            if (!value.isNullOrBlank()) return value
            dir = dir.parentFile ?: return@repeat
        }
        error("Set DEEPSEEK_API_KEY in environment or .env file")
    }
}
