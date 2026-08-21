# Gate Transitions — таблица переходов с условиями (gates)

## What it improves

Task state machine без gates позволяет LLM «перепрыгнуть» в DONE без реальной валидации — достаточно одного неверного ответа модели. Gates делают переходы **детерминированными**: каждый переход в таблице имеет условие (`planApproved`, `executionComplete`, `validationPassed`), которое должно быть явно выставлено (человеком или агентом) перед переходом. LLM видит gates в system prompt и знает что нельзя перейти вперёд без «разрешения».

## When to use

- Задачи с обязательными checkpoint'ами: нельзя начинать execution до одобрения плана
- Процессы с человеком в контуре (human-in-the-loop): gate `planApproved` ставит человек
- Multi-agent workflow: один агент ставит gate, другой двигает задачу дальше
- Compliance-требования: задокументировать что validation прошла перед деплоем

**Когда НЕ надо:** полностью автономный агент без контрольных точек, простые одношаговые задачи — gates добавляют ceremony без ценности.

## How to integrate

1. Определи `Gates` — набор boolean-флагов (по одному на значимое событие в workflow).
2. Создай `TransitionTable` — Map<Pair<from, to>, Gate> с указанием какой gate нужен для каждого перехода.
3. В `TaskMachine.transition(to)` проверяй gate перед переходом: если не выставлен — отказ с ясным сообщением.
4. Инъектируй gates в system prompt: «Для продолжения нужно: ${unmet_gates.joinToString()}»; LLM адаптирует ответ.
5. Предусмотри команды: `/approve-plan`, `/validate-ok`, `/advance` (автоматический сдвиг когда все условия выполнены).

## Working example (Kotlin)

```kotlin
data class Gates(
    val planApproved: Boolean = false,
    val executionComplete: Boolean = false,
    val validationPassed: Boolean = false
)

// Таблица: (from, to) → необходимый gate
val transitionTable: Map<Pair<TaskStatus, TaskStatus>, (Gates) -> Boolean> = mapOf(
    (TaskStatus.PLANNING   to TaskStatus.EXECUTION)  to { g -> g.planApproved },
    (TaskStatus.EXECUTION  to TaskStatus.VALIDATION) to { g -> g.executionComplete },
    (TaskStatus.VALIDATION to TaskStatus.DONE)       to { g -> g.validationPassed }
)

class GatedTaskMachine {
    var state = TaskStatus.IDLE
        private set
    var gates = Gates()
        private set

    fun approveGate(gate: String): String = when (gate) {
        "plan"       -> { gates = gates.copy(planApproved = true); "✓ Plan approved" }
        "execution"  -> { gates = gates.copy(executionComplete = true); "✓ Execution marked complete" }
        "validation" -> { gates = gates.copy(validationPassed = true); "✓ Validation passed" }
        else         -> "Unknown gate: $gate"
    }

    fun tryTransition(to: TaskStatus): Result<Unit> {
        val key = state to to
        val gateCheck = transitionTable[key]
            ?: return Result.failure(IllegalArgumentException("Переход $state→$to не разрешён"))

        if (!gateCheck(gates)) {
            val missing = getMissingGates(key)
            return Result.failure(IllegalStateException("Выставьте gate: $missing"))
        }

        state = to
        return Result.success(Unit)
    }

    fun buildSystemPromptSection(): String = buildString {
        append("## Состояние и Gates\n")
        append("Статус: $state\n")
        append("Gates: planApproved=${gates.planApproved}, executionComplete=${gates.executionComplete}, validationPassed=${gates.validationPassed}\n")
        val next = getNextPossibleTransitions()
        if (next.isNotEmpty()) append("Для продолжения нужно: ${next.joinToString(", ")}\n")
    }

    private fun getMissingGates(key: Pair<TaskStatus, TaskStatus>): String {
        return when (key) {
            TaskStatus.PLANNING to TaskStatus.EXECUTION   -> "planApproved (команда /approve-plan)"
            TaskStatus.EXECUTION to TaskStatus.VALIDATION -> "executionComplete (команда /validate-ok)"
            TaskStatus.VALIDATION to TaskStatus.DONE      -> "validationPassed (команда /done)"
            else -> "unknown"
        }
    }

    private fun getNextPossibleTransitions(): List<String> {
        return transitionTable.keys
            .filter { it.first == state }
            .mapNotNull { key -> if (!transitionTable[key]!!(gates)) getMissingGates(key) else null }
    }
}
```

## Metrics

- **Gate bypass attempts** — число попыток перейти без нужного gate; ненулевое = агент «хочет пропустить» → усилить инструкцию в system prompt
- **Avg time gate-pending** (минут) — сколько задача ждёт одобрения; при > 30 мин для plan-approval = возможно человек не в контуре
- **False gate hold rate** — gate не выставлен хотя условие выполнено; индикатор что автоматическое определение completion нечёткое
- **Tasks reaching DONE** — % задач доходящих до DONE vs застревающих в промежуточных состояниях

## Source

- **AI Challenge:** week3/day5 — Контролируемые переходы состояний + Gate-система
- **Артефакты:** https://github.com/AlDtoll/ai-challenge/tree/week3/day5/week3/day5 — `TransitionTable.kt`, `Gates.kt`, `TaskMachine.kt`
- **Связано:** [`task_state_machine.md`](task_state_machine.md) — базовый state machine без gates; [`invariant_guard.md`](invariant_guard.md) — guard на уровне контента ответа, не перехода
