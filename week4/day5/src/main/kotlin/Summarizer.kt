import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * «Обработка» (второй инструмент пайплайна): краткая выжимка текста через DeepSeek
 * (OpenAI-совместимый chat/completions). Ключ — DEEPSEEK_API_KEY (из .env челленджа).
 */
suspend fun summarizeWithDeepSeek(http: HttpClient, apiKey: String, text: String): String {
    if (apiKey.isBlank()) return "DEEPSEEK_API_KEY не задан (проверь .env в корне проекта)."
    val reqBody = buildJsonObject {
        put("model", "deepseek-chat")
        put("temperature", 0.3)
        putJsonArray("messages") {
            addJsonObject {
                put("role", "system")
                put("content", "Сделай краткую выжимку текста на русском в 1–2 предложения, только суть.")
            }
            addJsonObject {
                put("role", "user")
                put("content", text)
            }
        }
    }.toString()

    return try {
        val resp = http.post("https://api.deepseek.com/chat/completions") {
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(reqBody)
        }.bodyAsText()
        val content = Json.parseToJsonElement(resp).jsonObject["choices"]
            ?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
        content?.trim() ?: "не удалось получить выжимку (пустой ответ модели)"
    } catch (e: Exception) {
        "ошибка суммаризации: ${e.message}"
    }
}
