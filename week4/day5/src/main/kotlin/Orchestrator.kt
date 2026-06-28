import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Один инструмент в общем реестре: к какому MCP-клиенту (серверу) относится,
 * исходное имя на сервере, и JSON-схема параметров (для function-calling).
 */
class ToolEntry(
    val namespaced: String,   // напр. "weather__get_weather"
    val serverKey: String,    // напр. "weather"
    val client: Client,       // подключение к конкретному MCP-серверу
    val originalName: String, // имя инструмента на сервере
    val description: String,
    val parameters: JsonObject,
)

/** Вызвать MCP-инструмент на нужном сервере и собрать текстовый результат. */
private suspend fun callMcpText(mcp: Client, tool: String, args: JsonObject): String {
    val res = mcp.callTool(CallToolRequest(CallToolRequestParams(name = tool, arguments = args)))
    return res.content.filterIsInstance<TextContent>().joinToString("\n") { it.text ?: "" }
}

/**
 * LLM-оркестрация: DeepSeek (function-calling) сам выбирает инструменты из ОБЩЕГО реестра
 * (инструменты с разных серверов), агент МАРШРУТИЗИРУЕТ вызов на нужный сервер и
 * возвращает результат модели. Цикл повторяется (длинный флоу) до финального ответа.
 */
suspend fun orchestrate(http: HttpClient, apiKey: String, goal: String, registry: List<ToolEntry>, maxSteps: Int = 10) {
    if (apiKey.isBlank()) {
        println("DEEPSEEK_API_KEY не задан (проверь .env).")
        return
    }
    // Список инструментов для function-calling (имена — namespaced по серверу).
    val toolsJson = buildJsonArray {
        for (e in registry) {
            add(
                buildJsonObject {
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", e.namespaced)
                        put("description", "[сервер ${e.serverKey}] ${e.description}")
                        put("parameters", e.parameters)
                    })
                },
            )
        }
    }

    val messages = mutableListOf<JsonObject>()
    messages += buildJsonObject {
        put("role", "system")
        put(
            "content",
            "Ты агент-оркестратор. Чтобы выполнить задачу пользователя, вызывай доступные инструменты " +
                "по очереди (можно несколько шагов). Когда задача полностью выполнена — дай краткий финальный " +
                "ответ на русском БЕЗ вызова инструментов.",
        )
    }
    messages += buildJsonObject { put("role", "user"); put("content", goal) }

    for (step in 1..maxSteps) {
        val reqBody = buildJsonObject {
            put("model", "deepseek-chat")
            put("tool_choice", "auto")
            put("tools", toolsJson)
            put("messages", JsonArray(messages))
        }.toString()

        val respText = http.post("https://api.deepseek.com/chat/completions") {
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(reqBody)
        }.bodyAsText()

        val msg = Json.parseToJsonElement(respText).jsonObject["choices"]
            ?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
        val toolCalls = (msg?.get("tool_calls") as? JsonArray)?.takeIf { it.isNotEmpty() }

        if (toolCalls == null) {
            val finalText = msg?.get("content")?.jsonPrimitive?.contentOrNull ?: ""
            println("\n=== ФИНАЛЬНЫЙ ОТВЕТ АГЕНТА (шаг $step) ===")
            println(finalText)
            return
        }

        // Эхо ассистентского сообщения с tool_calls (нужно для сопоставления результатов).
        messages += buildJsonObject {
            put("role", "assistant")
            put("content", msg["content"] ?: kotlinx.serialization.json.JsonNull)
            put("tool_calls", toolCalls)
        }

        // Выполняем каждый запрошенный вызов, маршрутизируя на нужный сервер.
        for (tcEl in toolCalls) {
            val tc = tcEl.jsonObject
            val id = tc["id"]?.jsonPrimitive?.content ?: ""
            val fn = tc["function"]?.jsonObject
            val name = fn?.get("name")?.jsonPrimitive?.content ?: ""
            val argsStr = fn?.get("arguments")?.jsonPrimitive?.content ?: "{}"
            val argsObj = runCatching { Json.parseToJsonElement(argsStr).jsonObject }.getOrDefault(buildJsonObject {})

            val entry = registry.firstOrNull { it.namespaced == name }
            val result = if (entry == null) {
                "инструмент «$name» не найден в реестре"
            } else {
                callMcpText(entry.client, entry.originalName, argsObj)
            }

            val srv = entry?.serverKey ?: name.substringBefore("__")
            val tool = entry?.originalName ?: name.substringAfter("__")
            println("[шаг $step · роутинг] сервер «$srv» → $tool($argsStr)")
            println("    → " + result.replace("\n", " ").take(180) + if (result.length > 180) "…" else "")

            messages += buildJsonObject {
                put("role", "tool")
                put("tool_call_id", id)
                put("content", result)
            }
        }
    }
    println("\nДостигнут лимит шагов ($maxSteps).")
}
