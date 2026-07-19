import com.google.gson.Gson
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

data class LlmResponse(val choices: List<Choice>, val usage: Usage)
data class Choice(val message: ResponseMessage)
data class ResponseMessage(val content: String)
data class Usage(val prompt_tokens: Int, val completion_tokens: Int)

class LlmClient {
    private val apiKey: String = loadEnvKey("DEEPSEEK_API_KEY")
    private val http = HttpClient.newHttpClient()
    private val gson = Gson()

    fun chat(systemPrompt: String, messages: List<Message>): Pair<String, Usage> {
        val payload = mapOf(
            "model" to "deepseek-chat",
            "messages" to buildList {
                add(mapOf("role" to "system", "content" to systemPrompt))
                messages.forEach { add(mapOf("role" to it.role, "content" to it.content)) }
            },
            "max_tokens" to 1024
        )

        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.deepseek.com/chat/completions"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
            .build()

        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) error("API error ${response.statusCode()}: ${response.body()}")

        val result = gson.fromJson(response.body(), LlmResponse::class.java)
        return result.choices.first().message.content to result.usage
    }
}

fun loadEnvKey(key: String): String {
    System.getenv(key)?.takeIf { it.isNotBlank() }?.let { return it }
    var dir = File(".").canonicalFile
    repeat(6) {
        val value = File(dir, ".env").takeIf { it.exists() }
            ?.readLines()?.firstOrNull { it.startsWith("$key=") }
            ?.substringAfter("=")?.trim()
        if (!value.isNullOrBlank()) return value
        dir = dir.parentFile ?: return@repeat
    }
    error("Set $key in environment or .env file")
}
