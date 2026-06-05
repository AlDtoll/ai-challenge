import com.google.gson.Gson
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

data class Message(val role: String, val content: String)
data class ChatRequest(val model: String, val messages: List<Message>)
data class ChatResponse(val choices: List<Choice>)
data class Choice(val message: Message)

fun loadApiKey(): String {
    System.getenv("DEEPSEEK_API_KEY")?.takeIf { it.isNotBlank() }?.let { return it }
    var dir = File(".").canonicalFile
    repeat(5) {
        val value = File(dir, ".env").takeIf { it.exists() }
            ?.readLines()?.firstOrNull { it.startsWith("DEEPSEEK_API_KEY=") }
            ?.substringAfter("=")?.trim()
        if (!value.isNullOrBlank()) return value
        dir = dir.parentFile ?: return@repeat
    }
    error("Set DEEPSEEK_API_KEY in environment or .env file")
}

fun main() {
    val apiKey = loadApiKey()
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
