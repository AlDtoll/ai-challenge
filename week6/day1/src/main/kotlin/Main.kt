import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/* ---------- Domain ---------- */

data class Prompt(
    val id: String,
    val label: String,
    val system: String,
    val user: String,
    val checker: (String) -> Boolean,
    val expectation: String,
)

data class OllamaResult(
    val answer: String,
    val promptTokens: Int,
    val outputTokens: Int,
    val loadDurationMs: Long,
    val promptEvalMs: Long,
    val evalMs: Long,
    val totalMs: Long,
    val wallMs: Long,
) {
    // Честнее чем wall — берём из ответа Ollama.
    val tokensPerSec: Double = if (evalMs > 0) outputTokens * 1000.0 / evalMs else 0.0
}

/* ---------- Ollama client (native /api/chat, не /v1) ---------- */

class OllamaClient(
    private val baseUrl: String = "http://localhost:11434",
    private val model: String = "qwen2.5:7b",
    private val temperature: Double = 0.0,
) {
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()
    private val gson = Gson()

    // Health-check: /api/version + /api/tags (см. day26_research.md § ShirobokovNE).
    fun healthCheck(): String {
        val version = get("/api/version")
        val tags = JsonParser.parseString(get("/api/tags")).asJsonObject
        val models = tags.getAsJsonArray("models").map { it.asJsonObject["name"].asString }
        require(model in models) {
            "модель '$model' не установлена. ollama pull $model. Установлено: $models"
        }
        return "ollama ${JsonParser.parseString(version).asJsonObject["version"].asString}, models=$models"
    }

    fun chat(system: String, user: String): OllamaResult {
        val body = gson.toJson(
            mapOf(
                "model" to model,
                "messages" to listOf(
                    mapOf("role" to "system", "content" to system),
                    mapOf("role" to "user", "content" to user),
                ),
                "stream" to false,
                "options" to mapOf("temperature" to temperature),
            )
        )
        val wallStart = System.currentTimeMillis()
        val resp = post("/api/chat", body)
        val wall = System.currentTimeMillis() - wallStart

        val json = JsonParser.parseString(resp).asJsonObject
        val answer = json.getAsJsonObject("message")["content"].asString

        // Метрики из ответа Ollama — не из System.currentTimeMillis (см. day26_research.md § метрики).
        // *_duration поля — в наносекундах.
        fun ns(field: String): Long = json[field]?.asLong ?: 0L
        return OllamaResult(
            answer = answer,
            promptTokens = json["prompt_eval_count"]?.asInt ?: 0,
            outputTokens = json["eval_count"]?.asInt ?: 0,
            loadDurationMs = ns("load_duration") / 1_000_000,
            promptEvalMs = ns("prompt_eval_duration") / 1_000_000,
            evalMs = ns("eval_duration") / 1_000_000,
            totalMs = ns("total_duration") / 1_000_000,
            wallMs = wall,
        )
    }

    private fun get(path: String): String {
        val req = HttpRequest.newBuilder(URI.create("$baseUrl$path"))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        require(resp.statusCode() == 200) { "GET $path → ${resp.statusCode()}: ${resp.body()}" }
        return resp.body()
    }

    private fun post(path: String, body: String): String {
        val req = HttpRequest.newBuilder(URI.create("$baseUrl$path"))
            .timeout(Duration.ofMinutes(5))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        require(resp.statusCode() == 200) { "POST $path → ${resp.statusCode()}: ${resp.body()}" }
        return resp.body()
    }
}

/* ---------- Helpers ---------- */

// Снимаем ```json fences перед JSON.parse — модели любят обёртывать код в fence.
fun stripCodeFence(text: String): String {
    val trimmed = text.trim()
    val fenceStart = Regex("^```(?:json|kotlin|js)?\\s*\\n")
    val fenceEnd = Regex("\\n```\\s*$")
    return fenceEnd.replace(fenceStart.replace(trimmed, ""), "").trim()
}

/* ---------- Prompts ---------- */

val SYSTEM_RU = "Ты внимательный ассистент. Отвечай кратко и точно, по-русски."

fun buildPrompts(): List<Prompt> = listOf(
    Prompt(
        id = "1_capital_trap",
        label = "Простой факт с ловушкой (столица Австралии)",
        system = SYSTEM_RU,
        user = "Назови столицу Австралии. Ответь одним словом.",
        checker = { it.contains("Канберра", ignoreCase = true) || it.contains("Canberra", ignoreCase = true) },
        expectation = "содержит «Канберра» (Сидней — ловушка)",
    ),
    Prompt(
        id = "2_bat_and_ball",
        label = "Логика (задача Канемана про биту и мяч)",
        system = SYSTEM_RU,
        user = """
            Мяч и бита вместе стоят 110 рублей.
            Бита стоит на 100 рублей дороже мяча.
            Сколько стоит мяч? Ответь одним числом с единицей.
        """.trimIndent(),
        checker = { answer ->
            // Правильный ответ — 5 рублей. Ловушка — 10 (интуитивное «110 − 100»).
            // Ищем «5 руб/рублей» и убеждаемся что не в контексте 10 или 105.
            val hasFive = Regex("\\b5\\s*(руб|₽|р\\b)", RegexOption.IGNORE_CASE).containsMatchIn(answer)
            val hasTenTrap = Regex("\\b10\\s*(руб|₽|р\\b)", RegexOption.IGNORE_CASE).containsMatchIn(answer)
            hasFive && !hasTenTrap
        },
        expectation = "содержит «5 руб» (10 — интуитивная ловушка)",
    ),
    Prompt(
        id = "3_json_extract",
        label = "Извлечение JSON с ISO-датой",
        system = "$SYSTEM_RU Возвращай ТОЛЬКО валидный JSON без пояснений и без markdown-fence.",
        user = """
            Извлеки из текста имя и дату в JSON вида {"name": "...", "date_iso": "YYYY-MM-DD"}.
            Текст: «Алексей родился 15 марта 1990 года в Новосибирске.»
        """.trimIndent(),
        checker = { answer ->
            try {
                val json = JsonParser.parseString(stripCodeFence(answer)).asJsonObject
                val name = json["name"]?.asString ?: return@Prompt false
                val date = json["date_iso"]?.asString ?: return@Prompt false
                // ISO YYYY-MM-DD
                val isoOk = Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(date)
                name.contains("Алексей", ignoreCase = true) && date == "1990-03-15" && isoOk
            } catch (t: Throwable) {
                false
            }
        },
        expectation = "валидный JSON, name=Алексей, date_iso=1990-03-15",
    ),
)

/* ---------- Report ---------- */

data class PromptOutcome(
    val prompt: Prompt,
    val result: OllamaResult,
    val passed: Boolean,
)

fun writeReport(model: String, health: String, outcomes: List<PromptOutcome>, path: String) {
    val ts = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    val sb = StringBuilder()
    sb.appendLine("# Day 26 — локальная LLM ($model)")
    sb.appendLine()
    sb.appendLine("Дата запуска: `$ts`")
    sb.appendLine()
    sb.appendLine("**Health-check:** $health")
    sb.appendLine()
    sb.appendLine("## Итоговая таблица")
    sb.appendLine()
    sb.appendLine("| # | Промпт | Прошёл? | prompt→out | eval, ms | tok/s | wall, ms |")
    sb.appendLine("|---|---|---|---|---|---|---|")
    outcomes.forEach { o ->
        val mark = if (o.passed) "✓" else "✗"
        sb.append("| ${o.prompt.id} | ${o.prompt.label} | $mark ")
        sb.append("| ${o.result.promptTokens}→${o.result.outputTokens} ")
        sb.append("| ${o.result.evalMs} ")
        sb.append("| ${"%.1f".format(o.result.tokensPerSec)} ")
        sb.appendLine("| ${o.result.wallMs} |")
    }
    val passed = outcomes.count { it.passed }
    sb.appendLine()
    sb.appendLine("**Итого:** $passed / ${outcomes.size} промптов прошли автопроверку.")
    sb.appendLine()

    outcomes.forEach { o ->
        sb.appendLine("---")
        sb.appendLine()
        sb.appendLine("## ${o.prompt.id}. ${o.prompt.label}")
        sb.appendLine()
        sb.appendLine("**Ожидание:** ${o.prompt.expectation}")
        sb.appendLine()
        sb.appendLine("**System:**")
        sb.appendLine("```")
        sb.appendLine(o.prompt.system)
        sb.appendLine("```")
        sb.appendLine("**User:**")
        sb.appendLine("```")
        sb.appendLine(o.prompt.user)
        sb.appendLine("```")
        sb.appendLine("**Answer:**")
        sb.appendLine("```")
        sb.appendLine(o.result.answer.trim())
        sb.appendLine("```")
        sb.appendLine("**Проверка:** ${if (o.passed) "✓ прошла" else "✗ не прошла"}")
        sb.appendLine()
        sb.appendLine("**Метрики Ollama:** load=${o.result.loadDurationMs} ms, prompt_eval=${o.result.promptEvalMs} ms, eval=${o.result.evalMs} ms, total=${o.result.totalMs} ms, wall=${o.result.wallMs} ms, ${"%.1f".format(o.result.tokensPerSec)} tok/s")
        sb.appendLine()
    }

    File(path).writeText(sb.toString(), Charsets.UTF_8)
    println("\nОтчёт: $path")
}

/* ---------- main ---------- */

fun main(args: Array<String>) {
    val model = args.getOrNull(0) ?: "qwen2.5:7b"
    val client = OllamaClient(model = model)

    println("Health-check…")
    val health = client.healthCheck()
    println("  $health")

    val prompts = buildPrompts()
    val outcomes = prompts.map { p ->
        println("\n── ${p.id}: ${p.label} ──")
        println("Q: ${p.user.lines().joinToString(" ").take(150)}${if (p.user.length > 150) "…" else ""}")
        val r = client.chat(p.system, p.user)
        val passed = p.checker(r.answer)
        println("A: ${r.answer.trim().take(300)}${if (r.answer.length > 300) "…" else ""}")
        println("[${if (passed) "✓" else "✗"}] eval=${r.evalMs}ms, ${"%.1f".format(r.tokensPerSec)} tok/s, ${r.outputTokens} out tokens, wall=${r.wallMs}ms")
        PromptOutcome(p, r, passed)
    }

    println("\n=== ИТОГОВАЯ ТАБЛИЦА ===")
    println("%-20s | %5s | %6s | %6s | %6s | %5s".format("prompt", "pass", "in→out", "eval ms", "tok/s", "wall"))
    outcomes.forEach { o ->
        println("%-20s | %5s | %3d→%-3d | %6d | %6.1f | %5d".format(
            o.prompt.id, if (o.passed) "✓" else "✗",
            o.result.promptTokens, o.result.outputTokens,
            o.result.evalMs, o.result.tokensPerSec, o.result.wallMs))
    }
    val passed = outcomes.count { it.passed }
    println("\n$passed / ${outcomes.size} прошли автопроверку.")

    writeReport(model, health, outcomes, "day26_report.md")
}
