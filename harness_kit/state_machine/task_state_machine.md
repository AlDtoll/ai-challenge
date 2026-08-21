# Task State Machine — конечный автомат задачи с audit log

## What it improves

Агент без state machine «помнит» где он находится через историю сообщений — это ненадёжно: история длинная, LLM может «потеряться». TaskStateMachine делает состояние задачи явным: IDLE → PLANNING → EXECUTION → VALIDATION → DONE. System prompt перегенерируется из state при каждом шаге. Агент не «помнит» — он читает state. Все переходы пишутся в JSONL audit log — отладка и воспроизведение любого момента.

## When to use

- Агент решает многошаговые задачи (план → выполнение → проверка)
- Нужна воспроизводимость: «что агент делал в 14:32?» → читаем audit log
- Бизнес-процессы где нельзя пропустить шаг (нельзя DONE без VALIDATION)
- Несколько агентов работают над одной задачей — state = shared source of truth

**Когда НЕ надо:** простые Q&A агенты, одношаговые задачи — конечный автомат добавляет сложность без выгоды.

## How to integrate

1. Определи состояния как enum: `IDLE, PLANNING, EXECUTION, VALIDATION, DONE` + флаг `paused`.
2. Создай `TaskMachine` с методами `transition(to, reason)` — проверяет допустимость перехода, записывает в audit log.
3. После каждого перехода regenerate system prompt: `buildSystemPrompt(state)` — разный prompt для PLANNING («думаем как») и EXECUTION («делаем»).
4. Audit log пиши в JSONL: `{"ts": "...", "from": "...", "to": "...", "reason": "..."}` по одному JSON на строку.
5. Сохраняй current state на диск → агент выживает при рестарте, продолжает с того же состояния.

## Working example (Kotlin)

```kotlin
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

enum class TaskStatus { IDLE, PLANNING, EXECUTION, VALIDATION, DONE }

@Serializable
data class TaskState(
    val status: TaskStatus = TaskStatus.IDLE,
    val paused: Boolean = false,
    val description: String = "",
    val planSummary: String = ""
)

class TaskMachine(private val auditFile: File = File("audit.jsonl")) {
    var state = TaskState()
        private set

    private val allowed = mapOf(
        TaskStatus.IDLE       to setOf(TaskStatus.PLANNING),
        TaskStatus.PLANNING   to setOf(TaskStatus.EXECUTION, TaskStatus.IDLE),
        TaskStatus.EXECUTION  to setOf(TaskStatus.VALIDATION, TaskStatus.PLANNING),
        TaskStatus.VALIDATION to setOf(TaskStatus.DONE, TaskStatus.EXECUTION),
        TaskStatus.DONE       to setOf(TaskStatus.IDLE)
    )

    fun transition(to: TaskStatus, reason: String = ""): Boolean {
        if (to !in (allowed[state.status] ?: emptySet())) return false
        val from = state.status
        state = state.copy(status = to)
        logAudit(from, to, reason)
        return true
    }

    fun pause() { state = state.copy(paused = true) }
    fun resume() { state = state.copy(paused = false) }

    fun buildSystemPrompt(base: String): String = buildString {
        append(base)
        append("\n\n## Текущее состояние задачи\n")
        append("Статус: ${state.status}")
        if (state.paused) append(" (PAUSED)")
        append("\n")
        if (state.description.isNotEmpty()) append("Задача: ${state.description}\n")
        if (state.planSummary.isNotEmpty() && state.status != TaskStatus.PLANNING) {
            append("План: ${state.planSummary}\n")
        }
        when (state.status) {
            TaskStatus.PLANNING   -> append("\nСосредоточься на создании чёткого плана выполнения.")
            TaskStatus.EXECUTION  -> append("\nВыполняй план шаг за шагом, не отступай от него.")
            TaskStatus.VALIDATION -> append("\nПроверяй результат на соответствие исходным требованиям.")
            else -> Unit
        }
    }

    private fun logAudit(from: TaskStatus, to: TaskStatus, reason: String) {
        auditFile.appendText(
            Json.encodeToString(
                mapOf("ts" to Instant.now().toString(), "from" to from.name, "to" to to.name, "reason" to reason)
            ) + "\n"
        )
    }
}
```

## Metrics

- **State transition success rate** — доля attempted transitions которые были допустимы; низкий % = агент пытается «пропрыгнуть» состояния
- **Avg time in EXECUTION state** (минут) — индикатор сложности задач и скорости агента
- **Audit log entries per task** — среднее число переходов; много = задача сложная или агент «мечется»; мало = слишком простые задачи
- **Resume after restart rate** — доля задач корректно продолженных после рестарта агента (цель: 100%)

## Source

- **AI Challenge:** week3/day3 — Task State Machine + Audit Log
- **Артефакты:** https://github.com/AlDtoll/ai-challenge/tree/week3/day3/week3/day3 — `TaskMachine.kt`, `TaskState.kt`, `Repository.kt`, `Main.kt`
- **Связано:** [`gate_transitions.md`](gate_transitions.md) — условия (gates) для более жёсткого контроля переходов; [`invariant_guard.md`](invariant_guard.md) — guard для защиты ответов LLM на каждом шаге; [`../rag/rag_task_state_enrichment.md`](../rag/rag_task_state_enrichment.md) — обогащение RAG-запросов через TaskState
