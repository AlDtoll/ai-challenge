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

class Agent(private val systemPrompt: String) {

    private val gson = Gson()
    private val client = HttpClient.newHttpClient()
    private val apiKey = loadEnvKey("DEEPSEEK_API_KEY")
    private val histories = mutableMapOf<Long, MutableList<Message>>()

    fun chat(chatId: Long, userMessage: String): String {
        val history = histories.getOrPut(chatId) { loadHistory(chatId, systemPrompt) }
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
        saveHistory(chatId, history)
        return reply
    }
}
