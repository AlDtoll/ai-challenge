import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLPath
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * «Поиск» (первый инструмент пайплайна): вводный текст статьи из Wikipedia (ru) по запросу.
 * Wikipedia REST API — без ключа, доступен из РФ.
 */
suspend fun searchWikipedia(http: HttpClient, query: String): String {
    val url = "https://ru.wikipedia.org/api/rest_v1/page/summary/${query.encodeURLPath()}"
    return try {
        val body = http.get(url) { header("User-Agent", "ai-challenge-day19/1.0") }.bodyAsText()
        val extract = Json.parseToJsonElement(body).jsonObject["extract"]?.jsonPrimitive?.content
        if (extract.isNullOrBlank()) "По запросу «$query» ничего не найдено." else extract
    } catch (e: Exception) {
        "Ошибка поиска: ${e.message}"
    }
}
