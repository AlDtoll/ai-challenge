import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Фоновый планировщик: по запуску раз в interval секунд собирает погоду и пишет в журнал.
 * Это «выполнение по расписанию» из задания.
 */
class WatchService(
    private val http: HttpClient,
    private val store: WeatherStore,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val tsFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private var job: Job? = null

    @Volatile
    private var watchingCity: String? = null

    /** Запустить фоновый сбор (перезапускает, если уже шёл). */
    fun start(city: String, intervalSec: Int): String {
        job?.cancel()
        watchingCity = city
        job = scope.launch {
            while (isActive) {
                val w = fetchWeather(http, city)
                val ts = LocalDateTime.now().format(tsFormat)
                if (w != null) {
                    store.append(city, w.tempC, w.desc, ts)
                    println("  [сбор] $ts  $city: ${w.tempC}°C, ${w.desc}")
                } else {
                    println("  [сбор] $ts  не удалось получить погоду для $city")
                }
                delay(intervalSec * 1000L)
            }
        }
        return "Наблюдение запущено: $city, каждые ${intervalSec}с"
    }

    /** Остановить фоновый сбор. */
    fun stop(): String {
        job?.cancel()
        job = null
        val was = watchingCity
        watchingCity = null
        return if (was != null) "Наблюдение остановлено ($was)" else "Наблюдение не было запущено"
    }
}
