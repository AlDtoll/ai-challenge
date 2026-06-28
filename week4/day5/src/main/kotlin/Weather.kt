import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Текущая погода по городу из wttr.in (без ключа, доступен из РФ). */
suspend fun fetchWeatherLine(http: HttpClient, city: String): String {
    val url = "https://wttr.in/$city?format=j1"
    return try {
        val body = http.get(url) { header("User-Agent", "curl/8.0") }.bodyAsText()
        val cur = Json.parseToJsonElement(body).jsonObject["current_condition"]
            ?.jsonArray?.firstOrNull()?.jsonObject ?: return "нет данных о погоде"
        val temp = cur["temp_C"]?.jsonPrimitive?.content
        val wind = cur["windspeedKmph"]?.jsonPrimitive?.content
        val desc = cur["weatherDesc"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("value")?.jsonPrimitive?.content ?: ""
        "температура $temp°C, ветер $wind км/ч, $desc"
    } catch (e: Exception) {
        "не удалось получить погоду: ${e.message}"
    }
}
