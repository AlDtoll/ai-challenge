# MCP Background Tasks — фоновые задачи и 24/7 агент

## What it improves

Обычный MCP-инструмент — synchronous request/response. Для мониторинга, агрегации, периодических задач это не работает: нужен инструмент который что-то делает в фоне, а агент периодически запрашивает результат. `start_watch / get_summary / stop_watch` — паттерн: агент запускает фоновый сборщик, продолжает работать, через N минут спрашивает сводку. Персистентный JSONL журнал переживает рестарт — агент не теряет накопленные данные при сбое.

## When to use

- Мониторинг: метрики, логи, курсы валют — нужно собирать данные в фоне пока агент занят другим
- Долгие задачи которые нельзя выполнить за один synchronous вызов (> 30 сек)
- 24/7 агент: после рестарта должен продолжить сбор с того же момента
- Сценарий «агрегация за период»: watch 8 часов → summary в конце рабочего дня

**Когда НЕ надо:** одноразовые запросы которые выполняются быстро; нет нужды в данных за период — классический sync call проще.

## How to integrate

1. Создай `WatchService` — запускает фоновый корутин (или Thread) при вызове `start_watch`.
2. Каждый N секунд фоновый поток записывает snapshot в JSONL-файл: `{"ts": "...", "value": ...}`.
3. `get_summary` — читает JSONL, вычисляет агрегаты (avg, min, max, count) и возвращает агенту.
4. `stop_watch` — останавливает фоновый поток, файл остаётся для истории.
5. При рестарте: если JSONL-файл существует — `get_summary` работает без `start_watch`, данные не теряются.

## Working example (Kotlin)

```kotlin
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File
import java.time.Instant

data class WeatherSnapshot(val ts: String, val temp: Double, val city: String)

class WatchService(private val journalFile: File = File("weather_watch.jsonl")) {
    private var watchJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    fun startWatch(city: String, intervalSec: Int = 60): String {
        if (watchJob?.isActive == true) return "Наблюдение уже запущено"

        watchJob = scope.launch {
            while (isActive) {
                val temp = fetchTemperature(city)  // ваш HTTP-вызов к Open-Meteo
                val snapshot = buildJsonObject {
                    put("ts", Instant.now().toString())
                    put("city", city)
                    put("temp", temp)
                }
                journalFile.appendText(snapshot.toString() + "\n")
                delay(intervalSec * 1000L)
            }
        }
        return "Наблюдение запущено: $city, интервал ${intervalSec}с, журнал ${journalFile.path}"
    }

    fun getSummary(): String {
        if (!journalFile.exists()) return "Нет данных. Запустите start_watch."

        val snapshots = journalFile.readLines()
            .filter { it.isNotBlank() }
            .mapNotNull { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }

        if (snapshots.isEmpty()) return "Журнал пуст."

        val temps = snapshots.mapNotNull { it["temp"]?.jsonPrimitive?.doubleOrNull }
        return buildString {
            append("Записей: ${snapshots.size}\n")
            append("Температура: avg=%.1f°C, min=%.1f°C, max=%.1f°C\n".format(
                temps.average(), temps.min(), temps.max()
            ))
            append("Первая запись: ${snapshots.first()["ts"]?.jsonPrimitive?.content}\n")
            append("Последняя: ${snapshots.last()["ts"]?.jsonPrimitive?.content}")
        }
    }

    fun stopWatch(): String {
        watchJob?.cancel()
        watchJob = null
        return "Наблюдение остановлено. Журнал сохранён: ${journalFile.path}"
    }

    private suspend fun fetchTemperature(city: String): Double {
        // HTTP-запрос к Open-Meteo по координатам города
        // Упрощённо — реальный вызов в артефактах week4/day3
        return 20.0 + (Math.random() * 10 - 5)
    }
}
```

## Metrics

- **Journal integrity** — % сессий где JSONL корректно парсится после рестарта (цель: 100%; ошибка = неполная запись при краше — решить через atomic write)
- **Data freshness** — задержка между реальным событием и записью в журнал; при > 2× интервала = фоновый job завис
- **Summary accuracy** — сравни avg temperature из get_summary с реальной метеостанцией выборочно
- **Background job resource usage** — CPU и RAM при 10+ параллельных watch jobs; при росте — пул потоков вместо отдельного Thread на job

## Source

- **AI Challenge:** week4/day3 — Планировщик и фоновые задачи (24/7 агент)
- **Артефакты:** https://github.com/AlDtoll/ai-challenge/tree/week4/day3/week4/day3 — `WatchService.kt`, `WeatherStore.kt`, `Weather.kt`
- **Связано:** [`mcp_client_basics.md`](mcp_client_basics.md) — протокол MCP для вызова этих инструментов; [`mcp_orchestration_namespace.md`](mcp_orchestration_namespace.md) — как включить WatchService в мульти-серверную оркестрацию
