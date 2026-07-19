import com.google.gson.Gson
import com.google.gson.JsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/** Простая обёртка над DeepSeek chat/completions. Совместима с week4-week6. */
class DeepSeekClient(private val apiKey: String, private val model: String) {
    private val gson = Gson()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    data class Msg(val role: String, val content: String)

    fun chat(messages: List<Msg>, temperature: Double = 0.2, maxTokens: Int = 1500): String {
        require(apiKey.isNotBlank()) { "DEEPSEEK_API_KEY не задан" }
        val body = mapOf(
            "model" to model,
            "temperature" to temperature,
            "max_tokens" to maxTokens,
            "messages" to messages.map { mapOf("role" to it.role, "content" to it.content) },
        )
        val req = HttpRequest.newBuilder(URI.create("https://api.deepseek.com/chat/completions"))
            .timeout(Duration.ofSeconds(180))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body), StandardCharsets.UTF_8))
            .build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (res.statusCode() != 200) error("DeepSeek HTTP ${res.statusCode()}: ${res.body().take(300)}")
        val j = gson.fromJson(res.body(), JsonObject::class.java)
        return j.getAsJsonArray("choices").get(0).asJsonObject
            .getAsJsonObject("message").get("content").asString
    }
}
