# LLM Gateway — единая точка входа с input/output guard

## What it improves

Без gateway каждое приложение напрямую вызывает LLM API: нет аудита, нет rate limiting, нет контроля затрат, нет единой точки защиты. Gateway — FastAPI-прокси между user и LLM: OpenAI-compatible (`POST /v1/chat/completions`), поэтому любой существующий клиент работает без изменений. Input Guard (10 regex: secrets, PII, credit card + Luhn check) блокирует опасные запросы. Output Guard (5 проверок: secrets, system prompt extraction, suspicious URLs) блокирует опасные ответы. Rate limit, SQLite audit, cost tracking — из коробки.

## When to use

- Несколько сервисов/агентов используют один LLM API — нужен централизованный контроль
- Compliance: все LLM-запросы должны быть залогированы (GDPR, SOC2)
- Бюджет: нужно отслеживать стоимость по endpoint/user и ставить алерты
- Defense in depth: даже если агент скомпрометирован, gateway перехватит опасный output

**Когда НЕ надо:** один агент, один разработчик, нет compliance-требований — gateway = лишний сетевой хоп; latency-критичные приложения где каждые 50ms важны.

## How to integrate

1. Запусти gateway: `uvicorn gateway:app --port 8100` (или Docker: `ghcr.io/...`).
2. Смени base_url в клиентах: `https://api.deepseek.com` → `http://localhost:8100/v1`.
3. Добавь Bearer auth: gateway проверяет token из `GATEWAY_SECRET` env.
4. Input Guard: запрос проходит через 10 regex до отправки в LLM; при срабатывании → 400 с reason.
5. Output Guard: ответ от LLM проходит проверку до возврата клиенту; при срабатывании → 500 с фильтрованным ответом.

## Working example (Kotlin)

```kotlin
// Kotlin-клиент, совместимый с Gateway (и напрямую с DeepSeek/OpenAI):
class GatewayClient(
    private val baseUrl: String = "http://localhost:8100/v1",
    private val apiKey: String = System.getenv("GATEWAY_SECRET") ?: ""
) {
    private val http = java.net.http.HttpClient.newHttpClient()

    suspend fun chat(messages: List<Map<String, String>>, model: String = "deepseek-chat"): String {
        val body = buildJsonObject {
            put("model", model)
            put("messages", buildJsonArray {
                messages.forEach { msg ->
                    add(buildJsonObject { msg.forEach { (k, v) -> put(k, v) } })
                }
            })
        }.toString()

        val request = java.net.http.HttpRequest.newBuilder()
            .uri(java.net.URI.create("$baseUrl/chat/completions"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = http.send(request, java.net.http.HttpResponse.BodyHandlers.ofString())

        return when (response.statusCode()) {
            200 -> {
                val json = Json.parseToJsonElement(response.body()).jsonObject
                json["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("message")?.jsonObject
                    ?.get("content")?.jsonPrimitive?.content ?: ""
            }
            400 -> throw InputGuardException("Input blocked: ${response.body()}")
            429 -> throw RateLimitException("Rate limit exceeded")
            else -> throw Exception("Gateway error ${response.statusCode()}: ${response.body()}")
        }
    }
}

class InputGuardException(message: String) : Exception(message)
class RateLimitException(message: String) : Exception(message)

// Gateway архитектура (Python FastAPI ~/tools/llm-gateway/):
// Input Guard паттерны:
//   - r'[A-Za-z0-9+/]{32,}={0,2}'              # base64 (возможный secret)
//   - r'\b\d{4}[- ]?\d{4}[- ]?\d{4}[- ]?\d{4}' # credit card (+ Luhn)
//   - r'(password|passwd|pwd)\s*[:=]\s*\S+'      # password в тексте
//   - r'\b[A-Z0-9]{20,}\b'                       # API key паттерн
//   - r'\d{3}-\d{2}-\d{4}'                       # SSN (PII)
//
// Output Guard проверки:
//   - system prompt extraction: "your instructions are", "you were told to"
//   - IP leakage: IPv4 паттерн в ответе
//   - Dangerous commands: rm -rf, DROP TABLE, eval(
//   - Email в ответе (возможный PII leak)
//
// Endpoints:
// POST /v1/chat/completions — основной (OpenAI-compat)
// GET  /audit/log           — последние N записей из SQLite
// GET  /stats/cost          — расходы по моделям/периодам
// GET  /health              — статус сервиса
```

## Metrics

- **Input guard block rate** — % запросов заблокированных до LLM; при > 1% на production — возможно false positives или реальная атака
- **Output guard trigger rate** — % ответов LLM заблокированных output guard; должен быть близко к 0 при нормальной работе
- **Audit coverage** — 100% LLM-запросов должны попадать в SQLite audit; проверить через count(audit) vs count(api_calls)
- **Cost per endpoint** (USD/день) — позволяет найти expensive агентов; алерт при > $5/день от одного endpoint

## Source

- **AI Challenge:** week10/day13 — LLM Gateway (FastAPI-прокси, 19/19 pytest PASS)
- **Артефакты:** `~/tools/llm-gateway/` (14 файлов: `gateway.py`, `input_guard.py`, `output_guard.py`, `audit.db`, `run.sh`); `week10/day13/REPORT.md`
- **Связано:** [`security_execution_loop.md`](security_execution_loop.md) — execution loop который использует Gateway; [`prompt_injection_defense.md`](prompt_injection_defense.md) — L3 output-guard как упрощённая версия gateway output check; [`../local_llm/local_llm_http_service.md`](../local_llm/local_llm_http_service.md) — аналог для локальных LLM
