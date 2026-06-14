import com.google.gson.Gson
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.io.File

private const val DEEPSEEK_URL = "https://api.deepseek.com/chat/completions"
private const val MODEL = "deepseek-chat"

data class Message(val role: String, val content: String)
private data class ChatRequest(val model: String, val messages: List<Message>)
private data class Choice(val message: Message)
data class Usage(val prompt_tokens: Int, val completion_tokens: Int, val total_tokens: Int)
private data class ChatResponse(val choices: List<Choice>, val usage: Usage)

data class ChatResult(val reply: String, val usage: Usage)

class Agent(systemPrompt: String) {

    private val gson = Gson()
    private val client = HttpClient.newHttpClient()
    private val apiKey = loadEnvKey("DEEPSEEK_API_KEY")
    private val history = mutableListOf(Message("system", systemPrompt))

    var totalPromptTokens = 0
    var totalCompletionTokens = 0

    fun chat(userMessage: String): ChatResult {
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
        val usage = parsed.usage

        history.add(Message("assistant", reply))
        totalPromptTokens += usage.prompt_tokens
        totalCompletionTokens += usage.completion_tokens

        return ChatResult(reply, usage)
    }

    fun historySize() = history.size
}
