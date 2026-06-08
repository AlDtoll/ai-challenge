import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

private data class TgUser(val username: String?)
private data class TgMessage(
    val message_id: Long,
    val text: String?,
    val chat: TgChat,
    val from: TgUser?
)
private data class TgChat(val id: Long)
private data class TgUpdate(val update_id: Long, val message: TgMessage?)
private data class TgUpdatesResponse(val ok: Boolean, val result: List<TgUpdate>)

class TelegramBot(private val token: String, private val agent: Agent) {

    private val gson = Gson()
    private val client = HttpClient.newHttpClient()
    private val apiBase = "https://api.telegram.org/bot$token"
    private var offset = 0L

    fun run() {
        println("Bot started. Waiting for messages...")
        while (true) {
            try {
                val updates = getUpdates()
                for (update in updates) {
                    offset = update.update_id + 1
                    val message = update.message ?: continue
                    val text = message.text ?: continue
                    val chatId = message.chat.id
                    val user = message.from?.username ?: "unknown"

                    println("[$user]: $text")
                    val reply = agent.chat(text)
                    println("[Agent]: $reply\n")
                    sendMessage(chatId, reply)
                }
            } catch (e: Exception) {
                System.err.println("Error: ${e.message}")
                Thread.sleep(5_000)
            }
            Thread.sleep(1_000)
        }
    }

    private fun getUpdates(): List<TgUpdate> {
        val url = "$apiBase/getUpdates?offset=$offset&timeout=30"
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        val parsed = gson.fromJson(response.body(), TgUpdatesResponse::class.java)
        return if (parsed.ok) parsed.result else emptyList()
    }

    private fun sendMessage(chatId: Long, text: String) {
        data class SendRequest(val chat_id: Long, val text: String)
        val body = gson.toJson(SendRequest(chatId, text))
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$apiBase/sendMessage"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        client.send(request, HttpResponse.BodyHandlers.ofString())
    }
}
