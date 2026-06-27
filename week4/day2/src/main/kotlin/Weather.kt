import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Тянет текущую погоду по координатам из Open-Meteo (без ключа/авторизации).
 * Это «внешний API», вокруг которого построен MCP-инструмент.
 */
suspend fun fetchForecast(http: HttpClient, lat: Double, lon: Double): String {
    val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current_weather=true"
    return try {
        val body = http.get(url).bodyAsText()
        val current = Json.parseToJsonElement(body).jsonObject["current_weather"]?.jsonObject
            ?: return "нет данных о погоде"
        val temp = current["temperature"]?.jsonPrimitive?.content
        val wind = current["windspeed"]?.jsonPrimitive?.content
        "температура $temp°C, ветер $wind км/ч"
    } catch (e: Exception) {
        "не удалось получить погоду: ${e.message}"
    }
}
