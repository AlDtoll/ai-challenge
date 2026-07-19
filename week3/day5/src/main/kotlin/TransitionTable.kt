// Явная таблица разрешённых переходов с предусловиями (guards).
// Это сердце Day 15: вместо «можно ли из A в B?» в коде разбросанного,
// единая декларативная таблица — её можно показать пользователю через /transitions.

data class TransitionRule(
    val from: Stage,
    val to: Stage,
    val guardName: String,                       // человекочитаемое имя предусловия
    val guard: (TaskState, Gates) -> Boolean     // само предусловие
)

object TransitionTable {

    val rules: List<TransitionRule> = listOf(
        // PLANNING → PLANNING — внутренние шаги (DEFINE_GOAL → LIST_REQS → ...) — без гейтов
        TransitionRule(Stage.PLANNING, Stage.PLANNING, "always",
            { _, _ -> true }),

        // PLANNING → EXECUTION — только если план одобрен и не пустой
        TransitionRule(Stage.PLANNING, Stage.EXECUTION,
            "planApproved == true И draftPlan не пустой",
            { state, gates ->
                gates.planApproved &&
                    (state as? TaskState.Planning)?.draftPlan?.isNotEmpty() == true
            }),

        // EXECUTION → EXECUTION — следующий шаг плана
        TransitionRule(Stage.EXECUTION, Stage.EXECUTION, "currentIdx < plan.size - 1",
            { state, _ ->
                val s = state as? TaskState.Execution
                s != null && s.currentIdx < s.plan.lastIndex
            }),

        // EXECUTION → VALIDATION — только если все шаги выполнены (executionComplete=true)
        TransitionRule(Stage.EXECUTION, Stage.VALIDATION, "executionComplete == true",
            { _, gates -> gates.executionComplete }),

        // VALIDATION → VALIDATION — переход между шагами валидации
        TransitionRule(Stage.VALIDATION, Stage.VALIDATION, "always",
            { _, _ -> true }),

        // VALIDATION → EXECUTION — возврат для доработки (валидация не прошла)
        TransitionRule(Stage.VALIDATION, Stage.EXECUTION,
            "validationPassed == false (нужны правки)",
            { _, gates -> !gates.validationPassed }),

        // VALIDATION → DONE — только если валидация прошла
        TransitionRule(Stage.VALIDATION, Stage.DONE, "validationPassed == true",
            { _, gates -> gates.validationPassed })
        // НЕТ правил вида PLANNING → DONE, EXECUTION → DONE и т.п. — нельзя перепрыгнуть.
    )

    /** Возвращает rule, если переход разрешён С УЧЁТОМ guard'а. null если нельзя. */
    fun findAllowed(from: Stage, to: Stage, state: TaskState, gates: Gates): TransitionRule? =
        rules.firstOrNull { it.from == from && it.to == to && it.guard(state, gates) }

    /** Возвращает rule, который КОНЦЕПТУАЛЬНО описывает переход (без guard-проверки). null если такого пути нет. */
    fun findByPath(from: Stage, to: Stage): TransitionRule? =
        rules.firstOrNull { it.from == from && it.to == to }

    /** Список всех ВОЗМОЖНЫХ переходов из текущего state+gates прямо сейчас. */
    fun availableNow(state: TaskState, gates: Gates): List<TransitionRule> =
        rules.filter { it.from == state.stage && it.guard(state, gates) }

    /** Все rule'ы из заданной stage — для показа «куда теоретически может уйти». */
    fun allFrom(from: Stage): List<TransitionRule> = rules.filter { it.from == from }
}
