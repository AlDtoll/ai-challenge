import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Тянет текущую погоду по координатам из wttr.in (без ключа/авторизации).
 * Это «внешний API», вокруг которого построен MCP-инструмент.
 * wttr.in выбран потому, что доступен из РФ (Open-Meteo из РФ не открывается).
 */
suspend fun fetchForecast(http: HttpClient, lat: Double, lon: Double): String {
    val url = "https://wttr.in/$lat,$lon?format=j1"
    return try {
        // User-Agent как у curl — чтобы wttr.in отдал JSON, а не HTML.
        val body = http.get(url) { header("User-Agent", "curl/8.0") }.bodyAsText()
        val current = Json.parseToJsonElement(body).jsonObject["current_condition"]
            ?.jsonArray?.firstOrNull()?.jsonObject
            ?: return "нет данных о погоде"
        val temp = current["temp_C"]?.jsonPrimitive?.content
        val wind = current["windspeedKmph"]?.jsonPrimitive?.content
        val desc = current["weatherDesc"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("value")?.jsonPrimitive?.content
        buildString {
            append("температура $temp°C, ветер $wind км/ч")
            if (!desc.isNullOrBlank()) append(", $desc")
        }
    } catch (e: Exception) {
        "не удалось получить погоду: ${e.message}"
    }
}
