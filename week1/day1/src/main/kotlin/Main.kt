import com.google.gson.Gson
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

data class Message(val role: String, val content: String)
data class ChatRequest(val model: String, val messages: List<Message>)
data class ChatResponse(val choices: List<Choice>)
data class Choice(val message: Message)

fun main() {
    val apiKey = System.getenv("DEEPSEEK_API_KEY") ?: error("Set DEEPSEEK_API_KEY environment variable")
    val gson = Gson()

    print("You: ")
    val input = readLine()?.trim() ?: return

    val body = gson.toJson(ChatRequest(
        model = "deepseek-chat",
        messages = listOf(Message("user", input))
    ))

    val request = HttpRequest.newBuilder()
        .uri(URI.create("https://api.deepseek.com/chat/completions"))
        .header("Authorization", "Bearer $apiKey")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()

    val response = HttpClient.newHttpClient()
        .send(request, HttpResponse.BodyHandlers.ofString())

    val chat = gson.fromJson(response.body(), ChatResponse::class.java)
    println("AI: ${chat.choices.first().message.content}")
}
