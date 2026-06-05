import com.google.gson.Gson
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

data class Message(val role: String, val content: String)
data class ChatRequest(val model: String, val messages: List<Message>)
data class Choice(val message: Message)
data class ChatResponse(val choices: List<Choice>)

val EXPERTS_SYSTEM = """
    Ты — панель из трёх экспертов, которые совместно решают задачи.
    Каждый эксперт даёт свой ответ, затем делается общий вывод.

    Формат ответа:
    🔹 Аналитик: <анализ задачи и подход к решению>
    🔹 Инженер: <конкретное техническое или практическое решение>
    🔹 Критик: <оценка решений, возможные ошибки, финальный вывод>
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
    val task = readLine()?.trim() ?: return
    println()

    // 1. Прямой ответ
    println("═".repeat(60))
    println("1. ПРЯМОЙ ОТВЕТ")
    println("═".repeat(60))
    println(ask(client, gson, apiKey, listOf(Message("user", task))))

    // 2. Пошагово
    println()
    println("═".repeat(60))
    println("2. ПОШАГОВО")
    println("═".repeat(60))
    println(ask(client, gson, apiKey, listOf(
        Message("user", "$task\n\nРешай пошагово.")
    )))

    // 3. Метапромптинг
    println()
    println("═".repeat(60))
    println("3. МЕТАПРОМПТИНГ")
    println("═".repeat(60))
    val metaRequest = "Напиши идеальный промпт для LLM чтобы максимально точно решить задачу: $task\nВыведи ТОЛЬКО текст промпта без пояснений."
    val generatedPrompt = ask(client, gson, apiKey, listOf(Message("user", metaRequest)))
    println("[Промпт от модели]:\n$generatedPrompt")
    println()
    println("[Ответ по промпту]:")
    println(ask(client, gson, apiKey, listOf(Message("user", generatedPrompt))))

    // 4. Группа экспертов
    println()
    println("═".repeat(60))
    println("4. ГРУППА ЭКСПЕРТОВ")
    println("═".repeat(60))
    println(ask(client, gson, apiKey, listOf(
        Message("system", EXPERTS_SYSTEM),
        Message("user", task)
    )))
}

fun ask(client: HttpClient, gson: Gson, apiKey: String, messages: List<Message>): String {
    val body = gson.toJson(ChatRequest(model = "deepseek-chat", messages = messages))
    val request = HttpRequest.newBuilder()
        .uri(URI.create("https://api.deepseek.com/chat/completions"))
        .header("Authorization", "Bearer $apiKey")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    return gson.fromJson(response.body(), ChatResponse::class.java).choices.first().message.content
}
