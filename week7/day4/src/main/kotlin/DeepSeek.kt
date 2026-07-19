import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * DeepSeek chat/completions с поддержкой function calling (tool_calls).
 *
 * DeepSeek API совместим с OpenAI: параметр `tools` — список deklarаций,
 * ответ может содержать `message.tool_calls`. Мы вызываем каждый и подсовываем
 * ответом с role="tool".
 */
class DeepSeekClient(private val apiKey: String, private val model: String) {

    private val gson = Gson()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    /** Универсальный msg — role может быть system|user|assistant|tool. */
    data class Msg(
        val role: String,
        val content: String? = null,
        val name: String? = null,          // для role=tool: имя вызванного tool'а
        val toolCallId: String? = null,    // для role=tool: id ответного вызова
        val toolCalls: List<JsonObject>? = null, // для role=assistant: сырые tool_calls как пришли от API
    )

    data class ChatResult(
        val content: String?,               // текстовый ответ ассистента, если он есть
        val toolCalls: List<ToolCall>,      // если LLM попросил дёрнуть tools
        val raw: JsonObject,                // весь message для follow-up round
    )

    data class ToolCall(val id: String, val name: String, val argumentsJson: String)

    fun chat(
        messages: List<Msg>,
        toolsSchema: JsonArray? = null,
        temperature: Double = 0.1,
        maxTokens: Int = 1500,
    ): ChatResult {
        require(apiKey.isNotBlank()) { "DEEPSEEK_API_KEY не задан" }
        val bodyMap: MutableMap<String, Any> = mutableMapOf(
            "model" to model,
            "temperature" to temperature,
            "max_tokens" to maxTokens,
            "messages" to messages.map { msg ->
                val m = mutableMapOf<String, Any?>("role" to msg.role)
                if (msg.content != null) m["content"] = msg.content
                if (msg.name != null) m["name"] = msg.name
                if (msg.toolCallId != null) m["tool_call_id"] = msg.toolCallId
                if (msg.toolCalls != null) m["tool_calls"] = msg.toolCalls
                m
            },
        )
        if (toolsSchema != null && toolsSchema.size() > 0) {
            bodyMap["tools"] = toolsSchema
            bodyMap["tool_choice"] = "auto"
        }
        val req = HttpRequest.newBuilder(URI.create("https://api.deepseek.com/chat/completions"))
            .timeout(Duration.ofSeconds(180))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(bodyMap), StandardCharsets.UTF_8))
            .build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (res.statusCode() != 200) error("DeepSeek HTTP ${res.statusCode()}: ${res.body().take(400)}")
        val j = gson.fromJson(res.body(), JsonObject::class.java)
        val message = j.getAsJsonArray("choices").get(0).asJsonObject.getAsJsonObject("message")

        val text = message.get("content")?.takeIf { !it.isJsonNull }?.asString
        val calls = mutableListOf<ToolCall>()
        val callsEl = message.get("tool_calls")
        if (callsEl != null && callsEl.isJsonArray) {
            for (el in callsEl.asJsonArray) {
                val o = el.asJsonObject
                val id = o.get("id").asString
                val fn = o.getAsJsonObject("function")
                val name = fn.get("name").asString
                val argsRaw = fn.get("arguments")
                val argsJson = when {
                    argsRaw == null || argsRaw.isJsonNull -> "{}"
                    argsRaw.isJsonPrimitive -> argsRaw.asString
                    else -> argsRaw.toString()
                }
                calls.add(ToolCall(id, name, argsJson))
            }
        }
        return ChatResult(content = text, toolCalls = calls, raw = message)
    }
}
