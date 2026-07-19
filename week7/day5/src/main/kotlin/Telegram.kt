import com.google.gson.Gson
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/** Тонкий клиент Telegram Bot API. sendMessage без markdown чтобы не воевать с эскейпами. */
class TelegramClient(private val botToken: String) {
    private val gson = Gson()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    fun sendMessage(chatId: String, text: String): Boolean {
        require(botToken.isNotBlank()) { "TELEGRAM_BOT_TOKEN не задан" }
        // Telegram лимит 4096 символов на сообщение — режем.
        val chunks = text.chunked(3900)
        for (chunk in chunks) {
            val body = mapOf("chat_id" to chatId, "text" to chunk, "disable_web_page_preview" to true)
            val req = HttpRequest.newBuilder(URI.create("https://api.telegram.org/bot$botToken/sendMessage"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body), StandardCharsets.UTF_8))
                .build()
            val res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            if (res.statusCode() != 200) {
                System.err.println("Telegram HTTP ${res.statusCode()}: ${res.body().take(200)}")
                return false
            }
        }
        return true
    }
}
