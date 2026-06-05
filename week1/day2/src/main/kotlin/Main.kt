import com.google.gson.Gson
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

data class Message(val role: String, val content: String)

data class ChatRequest(
    val model: String,
    val messages: List<Message>,
    val max_tokens: Int? = null,
    val stop: List<String>? = null
)

data class Choice(val message: Message, val finish_reason: String)
data class ChatResponse(val choices: List<Choice>)

val SYSTEM_PROMPT = """
    Ты — краткий технический эксперт по Android-разработке.
    Отвечай строго по следующему формату:

    1. Определение: <одно предложение>
    2. Ключевая особенность: <одно предложение>
    3. Пример использования: <одна строка кода>

    ###END###
""".trimIndent()

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

    println("═".repeat(60))
    println("БЕЗ ОГРАНИЧЕНИЙ")
    println("═".repeat(60))
    val free = sendRequest(client, gson, apiKey, ChatRequest(
        model = "deepseek-chat",
        messages = listOf(Message("user", prompt))
    ))
    println(free.choices.first().message.content)
    println("\nfinish_reason: ${free.choices.first().finish_reason}")

    println()
    println("═".repeat(60))
    println("С ОГРАНИЧЕНИЯМИ  (system prompt + max_tokens=150 + stop=###END###)")
    println("═".repeat(60))
    val controlled = sendRequest(client, gson, apiKey, ChatRequest(
        model = "deepseek-chat",
        messages = listOf(
            Message("system", SYSTEM_PROMPT),
            Message("user", prompt)
        ),
        max_tokens = 150,
        stop = listOf("###END###")
    ))
    println(controlled.choices.first().message.content)
    println("\nfinish_reason: ${controlled.choices.first().finish_reason}")
}

fun sendRequest(client: HttpClient, gson: Gson, apiKey: String, chatRequest: ChatRequest): ChatResponse {
    val request = HttpRequest.newBuilder()
        .uri(URI.create("https://api.deepseek.com/chat/completions"))
        .header("Authorization", "Bearer $apiKey")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(chatRequest)))
        .build()

    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    return gson.fromJson(response.body(), ChatResponse::class.java)
}
