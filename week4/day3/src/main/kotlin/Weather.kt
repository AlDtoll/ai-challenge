import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Один замер погоды. */
data class WeatherReading(val tempC: Double, val desc: String)

/**
 * Текущая погода по городу из wttr.in (без ключа, доступен из РФ).
 * city — латиницей (напр. Novosibirsk).
 */
suspend fun fetchWeather(http: HttpClient, city: String): WeatherReading? {
    val url = "https://wttr.in/$city?format=j1"
    return try {
        val body = http.get(url) { header("User-Agent", "curl/8.0") }.bodyAsText()
        val cur = Json.parseToJsonElement(body).jsonObject["current_condition"]
            ?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        val temp = cur["temp_C"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return null
        val desc = cur["weatherDesc"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("value")?.jsonPrimitive?.content ?: ""
        WeatherReading(temp, desc)
    } catch (e: Exception) {
        null
    }
}
