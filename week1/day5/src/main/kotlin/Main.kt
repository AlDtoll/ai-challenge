import com.google.gson.Gson
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

val OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"

data class Message(val role: String, val content: String)
data class ChatRequest(val model: String, val messages: List<Message>)
data class Usage(val prompt_tokens: Int, val completion_tokens: Int, val total_tokens: Int)
data class Choice(val message: Message)
data class ChatResponse(val choices: List<Choice>, val usage: Usage?)

data class Model(
    val label: String,
    val id: String,
    val inputPricePerM: Double,
    val outputPricePerM: Double
)

val MODELS = listOf(
    Model("Слабая  (Gemma 4 31B)",      "google/gemma-4-31b-it:free",              0.0, 0.0),
    Model("Средняя (GPT-OSS 120B)",     "openai/gpt-oss-120b:free",                0.0, 0.0),
    Model("Сильная (Nemotron 550B)",    "nvidia/nemotron-3-ultra-550b-a55b:free",  0.0, 0.0)
)

fun loadApiKey(): String {
    System.getenv("OPENROUTER_API_KEY")?.takeIf { it.isNotBlank() }?.let { return it }
    var dir = File(".").canonicalFile
    repeat(5) {
        val value = File(dir, ".env").takeIf { it.exists() }
            ?.readLines()?.firstOrNull { it.startsWith("OPENROUTER_API_KEY=") }
            ?.substringAfter("=")?.trim()
        if (!value.isNullOrBlank()) return value
        dir = dir.parentFile ?: return@repeat
    }
    error("Set OPENROUTER_API_KEY in environment or .env file")
}

fun main() {
    val apiKey = loadApiKey()
    val gson = Gson()
    val client = HttpClient.newHttpClient()

    print("You: ")
    val prompt = readLine()?.trim() ?: return
    println()

    val results = mutableListOf<Triple<Model, String, Long>>()

    for (model in MODELS) {
        println("═".repeat(60))
        println("${model.label}  [${model.id}]")
        println("═".repeat(60))
        print("⏳ Запрос...")

        val start = System.currentTimeMillis()
        val response = ask(client, gson, apiKey, model.id, prompt)
        val elapsed = System.currentTimeMillis() - start

        val content = response.choices.first().message.content
        val usage = response.usage

        print("\r")
        println(content)
        println()

        val tokenInfo = if (usage != null) {
            val cost = (usage.prompt_tokens * model.inputPricePerM +
                    usage.completion_tokens * model.outputPricePerM) / 1_000_000
            val costStr = if (cost > 0) " | \$%.5f".format(cost) else " | бесплатно"
            "⏱ ${elapsed}ms | 🔤 ${usage.prompt_tokens}→${usage.completion_tokens} токенов$costStr"
        } else {
            "⏱ ${elapsed}ms"
        }
        println(tokenInfo)

        results.add(Triple(model, content, elapsed))
    }

    // Судья
    println()
    println("═".repeat(60))
    println("⚖️  СУДЬЯ (Nemotron 550B сравнивает ответы)")
    println("═".repeat(60))
    val judgePrompt = buildString {
        appendLine("Вопрос был: «$prompt»")
        appendLine()
        results.forEachIndexed { i, (model, answer, _) ->
            appendLine("Ответ модели ${i + 1} (${model.label}):")
            appendLine(answer)
            appendLine()
        }
        append("Сравни ответы: точность, полнота, качество. Вынеси краткий вердикт.")
    }
    print("⏳ Запрос судьи...")
    val judgeResponse = ask(client, gson, apiKey, "nvidia/nemotron-3-ultra-550b-a55b:free", judgePrompt)
    print("\r")
    println(judgeResponse.choices.first().message.content)
}

fun ask(client: HttpClient, gson: Gson, apiKey: String, modelId: String, prompt: String): ChatResponse {
    val body = gson.toJson(ChatRequest(model = modelId, messages = listOf(Message("user", prompt))))
    val request = HttpRequest.newBuilder()
        .uri(URI.create(OPENROUTER_URL))
        .header("Authorization", "Bearer $apiKey")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    val parsed = gson.fromJson(response.body(), ChatResponse::class.java)
    if (parsed.choices.isNullOrEmpty()) {
        error("Empty response from $modelId (HTTP ${response.statusCode()}): ${response.body()}")
    }
    return parsed
}
