import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.Locale

/**
 * Персистентное хранилище замеров — JSONL-журнал (по одной JSON-строке на замер).
 * Append-only: переживает перезапуск, сводка считается по всему накопленному.
 */
class WeatherStore(private val file: File) {
    init {
        file.parentFile?.mkdirs()
    }

    /** Дописать один замер в журнал. */
    @Synchronized
    fun append(city: String, tempC: Double, desc: String, ts: String) {
        val line = buildJsonObject {
            put("ts", ts)
            put("city", city)
            put("tempC", tempC)
            put("desc", desc)
        }.toString()
        file.appendText(line + "\n")
    }

    /** Агрегированная сводка по всем замерам. */
    @Synchronized
    fun summary(): String {
        if (!file.exists()) return "Журнал пуст — замеров ещё не было."
        val temps = mutableListOf<Double>()
        var first: String? = null
        var last: String? = null
        var city = ""
        file.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val o = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return@forEachLine
            val t = o["tempC"]?.jsonPrimitive?.doubleOrNull ?: return@forEachLine
            temps += t
            val ts = o["ts"]?.jsonPrimitive?.content
            if (first == null) first = ts
            last = ts
            city = o["city"]?.jsonPrimitive?.content ?: city
        }
        if (temps.isEmpty()) return "Журнал пуст — замеров ещё не было."
        val avg = temps.average()
        val mn = temps.minOrNull() ?: 0.0
        val mx = temps.maxOrNull() ?: 0.0
        return "Сводка по «$city»: замеров ${temps.size}; " +
            "темп. мин ${fmt(mn)}°C / средняя ${fmt(avg)}°C / макс ${fmt(mx)}°C; " +
            "период $first … $last"
    }

    private fun fmt(v: Double) = String.format(Locale.US, "%.1f", v)
}
