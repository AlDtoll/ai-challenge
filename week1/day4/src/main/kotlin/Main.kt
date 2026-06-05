import com.google.gson.Gson
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

data class Message(val role: String, val content: String)
data class ChatRequest(val model: String, val messages: List<Message>, val temperature: Double)
data class Choice(val message: Message)
data class ChatResponse(val choices: List<Choice>)

val TEMPERATURES = listOf(0.0, 0.7, 1.2)

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
    val client = HttpClient.newHttpClient()

    print("You: ")
    val prompt = readLine()?.trim() ?: return
    println()

    for (temp in TEMPERATURES) {
        println("═".repeat(60))
        println("temperature = $temp")
        println("═".repeat(60))
        println(ask(client, gson, apiKey, prompt, temp))
        println()
    }

    println("─".repeat(60))
    println("Когда использовать:")
    println("  0.0  — факты, код, точные ответы (детерминировано)")
    println("  0.7  — баланс точности и разнообразия (по умолчанию)")
    println("  1.2  — творчество, генерация идей, нестандартные ответы")
}

fun ask(client: HttpClient, gson: Gson, apiKey: String, prompt: String, temperature: Double): String {
    val body = gson.toJson(ChatRequest(
        model = "deepseek-chat",
        messages = listOf(Message("user", prompt)),
        temperature = temperature
    ))
    val request = HttpRequest.newBuilder()
        .uri(URI.create("https://api.deepseek.com/chat/completions"))
        .header("Authorization", "Bearer $apiKey")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    return gson.fromJson(response.body(), ChatResponse::class.java).choices.first().message.content
}
