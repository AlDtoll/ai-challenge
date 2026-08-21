# Security Execution Loop — security review в Secure AI SDLC

## What it improves

Обычный execution loop: generate code → commit. Security loop добавляет шаг: generate → lint → **security_review** → decide (BLOCK/WARN/OK). Security-шаг — отдельный LLM-вызов с промптом специалиста по безопасности, который оценивает код по критериям Critical/High/Medium/Low. При Critical/High → BLOCK + regenerate с фидбеком. Принимает только после исправления. Не пропускает упрямую модель которая снова генерирует уязвимый код (max 3 итерации). Все вызовы через Gateway для аудита.

## When to use

- Production код генерируется автоматически (agentic loop, batch fixes)
- Код работает с credentials, токенами, базами данных, HTTP-запросами
- Compliance-требования: «весь сгенерированный код проходит security review»
- После инцидента: ужесточить процесс для класса уязвимостей которые были эксплуатированы

**Когда НЕ надо:** прототипы и throwaway-скрипты; генерация статического контента (документация, конфиг-файлы); sandbox-среда без реальных ресурсов — security overhead не окупается.

## How to integrate

1. После generation (и lint) — LLM-вызов через Gateway с security_review промптом.
2. Security review возвращает JSON: `{verdict: BLOCK/WARN/OK, issues: [{severity: Critical, ...}]}`.
3. При BLOCK: добавь issues в контекст → regenerate (попытка 2).
4. При WARN: флаг `committed_with_warnings` — логировать для human review.
5. При OK или после 3-й попытки: финальное решение.

## Working example (Kotlin)

```kotlin
enum class SecurityVerdict { BLOCK, WARN, OK }

data class SecurityIssue(
    val severity: String,  // Critical / High / Medium / Low
    val category: String,  // hardcoded_secrets / sql_injection / path_traversal / ...
    val description: String,
    val line: Int? = null
)

data class SecurityReview(
    val verdict: SecurityVerdict,
    val issues: List<SecurityIssue>
)

class SecurityExecutionLoop(
    private val llmClient: GatewayClient,  // все вызовы через Gateway
    private val maxIterations: Int = 3
) {
    private val generationPrompt = """
        Сгенерируй Kotlin-код для задачи. 
        Следуй OWASP best practices: не хранить секреты в коде, использовать EncryptedSharedPreferences,
        не логировать credentials, использовать HTTPS, валидировать input.
    """.trimIndent()

    private val securityReviewPrompt = """
        Ты — senior security engineer. Проведи security review следующего Kotlin-кода.
        
        Оцени по критериям:
        - Critical: hardcoded secrets, plaintext storage of tokens, SQL injection
        - High: auth в логах, HTTP вместо HTTPS, path traversal
        - Medium: отсутствие input validation, verbose error messages
        - Low: deprecated API, отсутствие rate limiting
        
        Верни JSON строго в формате:
        {"verdict": "BLOCK|WARN|OK", "issues": [{"severity": "Critical", "category": "...", "description": "..."}]}
        
        BLOCK если есть Critical или High. WARN если только Medium/Low. OK если чисто.
    """.trimIndent()

    suspend fun run(taskDescription: String): Pair<String, String> {
        var code = ""
        var verdict = SecurityVerdict.BLOCK
        var issues = emptyList<SecurityIssue>()

        repeat(maxIterations) { iteration ->
            // Генерация
            val genMessages = buildList {
                add(mapOf("role" to "system", "content" to generationPrompt))
                add(mapOf("role" to "user", "content" to taskDescription))
                if (issues.isNotEmpty()) {
                    add(mapOf("role" to "user", "content" to
                        "Предыдущий код заблокирован по причинам:\n" +
                        issues.joinToString("\n") { "- [${it.severity}] ${it.category}: ${it.description}" } +
                        "\nИсправь все найденные проблемы."
                    ))
                }
            }
            code = llmClient.chat(genMessages)

            // Security Review
            val reviewMessages = listOf(
                mapOf("role" to "system", "content" to securityReviewPrompt),
                mapOf("role" to "user", "content" to "```kotlin\n$code\n```")
            )
            val rawReview = llmClient.chat(reviewMessages)

            val review = parseReview(rawReview)
            verdict = review.verdict
            issues = review.issues

            println("Iter ${iteration + 1}: $verdict (${issues.size} issues)")

            if (verdict == SecurityVerdict.OK || (verdict == SecurityVerdict.WARN && iteration == maxIterations - 1)) {
                return code to "committed_${if (verdict == SecurityVerdict.OK) "clean" else "with_warnings"}"
            }

            if (verdict == SecurityVerdict.BLOCK && iteration == maxIterations - 1) {
                return code to "blocked_max_iterations"
            }
        }

        return code to "unknown"
    }

    private fun parseReview(raw: String): SecurityReview {
        return try {
            val json = Json { ignoreUnknownKeys = true }
            val obj = json.parseToJsonElement(
                raw.substringAfter("{").let { "{$it" }.substringBeforeLast("}").let { "$it}" }
            ).jsonObject

            val verdict = when (obj["verdict"]?.jsonPrimitive?.content) {
                "BLOCK" -> SecurityVerdict.BLOCK
                "WARN"  -> SecurityVerdict.WARN
                else    -> SecurityVerdict.OK
            }
            SecurityReview(verdict, emptyList())  // упрощённый парсинг issues
        } catch (e: Exception) {
            SecurityReview(SecurityVerdict.WARN, emptyList())
        }
    }
}

// Результаты из week10/day14 (3 задачи):
// Задача 1 "Сохрани токен": iter1 plaintext SharedPreferences → BLOCK, iter2 EncryptedSharedPreferences → OK
// Задача 2 "Логируй запросы": iter1 Auth-в-логах → BLOCK, iter2 redactHeader → WARN принято
// Задача 3 "API с hardcoded key": iter1-3 всегда hardcoded → BLOCK×3 = blocked_max_iterations
```

## Metrics

- **BLOCK rate** — % задач заблокированных хотя бы раз; < 30% = задачи безопасные, > 80% = generation prompt нужно улучшить
- **Fix success rate** — доля BLOCK → OK/WARN после regenerate; < 50% = либо задача принципиально небезопасная (как hardcoded key), либо security feedback недостаточно конкретен
- **Iterations to clean** — среднее число итераций до OK; > 2 = generation prompt не усвоил security best practices
- **Gateway audit coverage** — 100% security_review вызовов в audit.db; нет = Gateway не работает или клиент обходит его

## Source

- **AI Challenge:** week10/day14 — Security Step через Gateway (Execution Loop)
- **Артефакты:** `week10/day14/loop.py` — оркестратор; `week10/day14/prompts/security_review.md` — Kotlin/Android security prompt; `week10/day14/generated/task{1,2,3}/` — код каждой итерации; `week10/day14/results/all_tasks.json` — trace
- **Связано:** [`llm_gateway.md`](llm_gateway.md) — Gateway через который идут все LLM-вызовы loop'а; [`../agentic_loop/agentic_loop_tool_calls.md`](../agentic_loop/agentic_loop_tool_calls.md) — agentic loop как основа (добавляем security-шаг); [`indirect_injection_defenses.md`](indirect_injection_defenses.md) — защита от injection в генерируемом коде
