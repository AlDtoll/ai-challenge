# Ollama Local Setup — Kotlin-клиент, RAG-CLI, local↔cloud абстракция

## What it improves

Все предыдущие техники требуют облачного API (DeepSeek, OpenRouter). Ollama даёт полностью offline-стек: генерация через qwen2.5:7b, эмбеддинги через nomic-embed-text — всё локально. Нативный клиент через `/api/chat` (не OpenAI-compat) выдаёт честные метрики: tok/s, load_duration, eval_duration. Абстракция `LlmBackend` позволяет без переписывания менять qwen↔DeepSeek: один и тот же RAG-стек, разные бэкенды.

## When to use

- Privacy-first: данные не должны покидать сервер/машину
- Offline: нет интернета или надёжного API (авиорежим, изолированная сеть)
- Высокая нагрузка: cloud API дорогой при > 100K запросов/день → local выгоднее
- Тестирование/прототипирование: мгновенные ответы без latency облака, нет биллинга

**Когда НЕ надо:** качество qwen2.5:7b не устраивает и компромисс неприемлем; машина без GPU (на CPU Ollama очень медленная); multi-modal задачи (Ollama поддерживает частично).

## How to integrate

1. Установи Ollama: `curl -fsSL https://ollama.ai/install.sh | sh`. Скачай модели: `ollama pull qwen2.5:7b && ollama pull nomic-embed-text`.
2. Напиши `OllamaClient` — POST `/api/chat` с `{"model": "qwen2.5:7b", "messages": [...], "stream": false}`.
3. Для эмбеддингов: POST `/api/embeddings` с `{"model": "nomic-embed-text", "prompt": "..."}` → float array.
4. RAG-CLI: индексируй папку → персистентный `index.json` (chunked content + embeddings) → multi-turn REPL.
5. `LlmBackend` interface: `chat(messages): String` + `embed(text): FloatArray`. Реализации: `OllamaLocal`, `DeepSeekCloud`.

## Working example (Kotlin)

```kotlin
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlinx.serialization.json.*

class OllamaClient(private val baseUrl: String = "http://localhost:11434") {
    private val http = HttpClient.newHttpClient()

    data class ChatMetrics(val tokensPerSec: Double, val loadDuration: Long, val evalDuration: Long)

    fun chat(model: String = "qwen2.5:7b", messages: List<Map<String, String>>): Pair<String, ChatMetrics> {
        val body = buildJsonObject {
            put("model", model)
            put("messages", buildJsonArray {
                messages.forEach { msg ->
                    add(buildJsonObject { msg.forEach { (k, v) -> put(k, v) } })
                }
            })
            put("stream", false)
        }.toString()

        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/api/chat"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        val json = Json.parseToJsonElement(response.body()).jsonObject

        val content = json["message"]?.jsonObject?.get("content")?.jsonPrimitive?.content ?: ""
        val metrics = ChatMetrics(
            tokensPerSec = json["eval_count"]?.jsonPrimitive?.doubleOrNull
                ?.let { eval -> json["eval_duration"]?.jsonPrimitive?.longOrNull?.let { dur -> eval / (dur / 1e9) } } ?: 0.0,
            loadDuration = json["load_duration"]?.jsonPrimitive?.longOrNull ?: 0L,
            evalDuration = json["eval_duration"]?.jsonPrimitive?.longOrNull ?: 0L
        )

        return content to metrics
    }

    fun embed(model: String = "nomic-embed-text", text: String): FloatArray {
        val body = buildJsonObject {
            put("model", model)
            put("prompt", text)
        }.toString()

        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/api/embeddings"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        val json = Json.parseToJsonElement(response.body()).jsonObject
        return json["embedding"]?.jsonArray
            ?.map { it.jsonPrimitive.float }
            ?.toFloatArray() ?: FloatArray(0)
    }
}

// LlmBackend абстракция для swap local↔cloud:
interface LlmBackend {
    suspend fun chat(messages: List<Map<String, String>>): String
    suspend fun embed(text: String): FloatArray
}

class OllamaBackend(private val client: OllamaClient, private val model: String = "qwen2.5:7b") : LlmBackend {
    override suspend fun chat(messages: List<Map<String, String>>): String = client.chat(model, messages).first
    override suspend fun embed(text: String): FloatArray = client.embed(text = text)
}

// class DeepSeekBackend : LlmBackend — реализация с HTTP-вызовами к api.deepseek.com
// RAG использует LlmBackend — backend-agnostic
```

## Metrics

- **Tokens/sec** — производительность генерации; qwen2.5:7b на M4 Mac ~30-50 tok/s; на VPS без GPU ~3-8 tok/s
- **Load duration** (мс) — время первой загрузки модели в VRAM; при > 30 сек — модель выгружается между вызовами, нужно keepAlive
- **Local vs Cloud quality gap** — Recall@3 или ROUGE-L на тест-наборе; qwen2.5:7b обычно 80-90% от DeepSeek
- **Privacy compliance** — 0 network requests к external hosts во время inference (проверить через `tcpdump` или `netstat`)

## Source

- **AI Challenge:** week6/day1 + week6/day2 + week6/day3 — Запуск локальной LLM + RAG-CLI + local vs cloud сравнение
- **Артефакты:** https://github.com/AlDtoll/ai-challenge/tree/week6/day1/week6/day1 — `Main.kt`; https://github.com/AlDtoll/ai-challenge/tree/week6/day2/week6/day2 — `Main.kt`, `eval-questions.txt`; https://github.com/AlDtoll/ai-challenge/tree/week6/day3/week6/day3 — `Main.kt`
- **Связано:** [`ollama_rag_tuning.md`](ollama_rag_tuning.md) — критический тюнинг Ollama под RAG (особенно num_ctx!); [`local_llm_http_service.md`](local_llm_http_service.md) — обёртка Ollama в HTTP-сервис с auth и rate-limit; [`../rag/bm25_offline_index.md`](../rag/bm25_offline_index.md) — fallback когда Ollama недоступна
