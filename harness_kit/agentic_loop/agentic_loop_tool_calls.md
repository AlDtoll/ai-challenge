# Agentic Loop Tool Calls — LLM с tool_choice=auto до MAX_TOOL_ITER

## What it improves

Классический агент делает один LLM-вызов → один ответ. Agentic loop позволяет LLM самостоятельно решать: «мне нужно вызвать инструмент, получить результат, потом ещё один, потом сформировать финальный ответ». Это реальная автономность: `tool_choice=auto` → LLM возвращает `tool_calls` → агент вызывает MCP → результат обратно в LLM → и так до MAX_TOOL_ITER или финального text-ответа. `apply_patch` с `expected_count` — идемпотентная замена с проверкой. `safeResolve` — sandbox блокирует выход за рамки рабочей директории.

## When to use

- Агент должен самостоятельно прочитать файл, применить изменение, проверить результат
- Задача требует нескольких последовательных инструментов где следующий зависит от результата предыдущего
- Git assistant, file assistant, code review — нужно многошаговое reasoning с инструментами
- Длинные автономные прогоны: 12 итераций × N задач

**Когда НЕ надо:** один deterministic вызов инструмента (просто вызови напрямую); задачи без инструментов; когда MAX_TOOL_ITER ≥ 20 и нет бюджета токенов.

## How to integrate

1. Передай инструменты в LLM как `functions` (или `tools`) с `tool_choice: "auto"`.
2. Если LLM вернул `tool_calls` — выполни каждый вызов, добавь результат в историю как role=`tool`.
3. Снова вызови LLM с обновлённой историей (включая tool results) — повтори.
4. Стоп-условие: LLM вернул text без `tool_calls` ИЛИ достигнут `MAX_TOOL_ITER` (safety guard).
5. При `safeResolve` — всегда проверяй что resolved path начинается с workspace root.

## Working example (Kotlin)

```kotlin
const val MAX_TOOL_ITER = 12

data class ToolCall(val id: String, val name: String, val arguments: Map<String, String>)

class AgenticLoop(
    private val llmClient: DeepSeekClient,
    private val mcpRouter: McpOrchestrator,
    private val workspaceRoot: String = System.getProperty("user.home") + "/workspace"
) {
    suspend fun run(
        systemPrompt: String,
        userRequest: String,
        tools: List<JsonObject>
    ): String {
        val messages = mutableListOf(
            Message("system", systemPrompt),
            Message("user", userRequest)
        )

        repeat(MAX_TOOL_ITER) { iteration ->
            val response = llmClient.chatWithTools(messages, tools)

            val toolCalls = response.toolCalls
            if (toolCalls.isNullOrEmpty()) {
                // LLM не хочет больше инструментов — финальный ответ
                return response.content ?: "No response"
            }

            // Выполняем все tool_calls из этого шага
            messages.add(Message("assistant", null, toolCalls = toolCalls))

            for (tc in toolCalls) {
                val result = try {
                    when (tc.name) {
                        "apply_patch" -> applyPatch(tc.arguments)
                        else          -> mcpRouter.route(tc.name, tc.arguments)
                    }
                } catch (e: Exception) {
                    "ERROR: ${e.message}"
                }

                messages.add(Message("tool", result, toolCallId = tc.id))
            }
        }

        return "Достигнут лимит итераций ($MAX_TOOL_ITER). Задача не завершена."
    }

    private fun safeResolve(path: String): String {
        val resolved = java.io.File(workspaceRoot, path).canonicalPath
        require(resolved.startsWith(workspaceRoot)) {
            "Path escape attempt: $path → $resolved"
        }
        return resolved
    }

    private fun applyPatch(args: Map<String, String>): String {
        val filePath = safeResolve(args["file"] ?: error("file required"))
        val oldText  = args["old_text"] ?: error("old_text required")
        val newText  = args["new_text"] ?: error("new_text required")
        val expectedCount = args["expected_count"]?.toIntOrNull() ?: 1

        val file = java.io.File(filePath)
        val content = file.readText()
        val actualCount = content.split(oldText).size - 1

        require(actualCount == expectedCount) {
            "apply_patch: expected $expectedCount occurrences of old_text, found $actualCount"
        }

        file.writeText(content.replace(oldText, newText))
        return "Patch applied: $actualCount replacement(s) in $filePath"
    }
}

// Python/TypeScript note: та же логика через OpenAI-compatible API —
// finish_reason="tool_calls" → вызываем → добавляем role="tool" → снова chat
```

## Metrics

- **Avg iterations per task** — сколько шагов нужно агенту; < 3 = задача простая (не нужен loop), > 10 = задача сложная или агент «петляет»
- **Tool iteration timeout rate** — % задач где достигнут MAX_TOOL_ITER без финального ответа; > 5% = нужно увеличить лимит или упростить задачи
- **apply_patch success rate** — % идемпотентных замен где actual_count == expected_count; сбои = old_text не точный
- **safeResolve block rate** — число заблокированных path escape попыток; ненулевое = реальная угроза или баг в генерации путей LLM

## Source

- **AI Challenge:** week7/day4 — Ассистент работы с файлами (agentic loop); week4/day4 (pipeline tool-calls как предвестник agentic loop)
- **Артефакты:** https://github.com/AlDtoll/ai-challenge/tree/week7/day4/week7/day4 — `FileMcp.kt`, `Main.kt`, `DeepSeek.kt`
- **Связано:** [`subagents_parallel.md`](subagents_parallel.md) — параллельный запуск нескольких agentic loops одновременно; [`../mcp/mcp_orchestration_namespace.md`](../mcp/mcp_orchestration_namespace.md) — источник инструментов для mcpRouter
