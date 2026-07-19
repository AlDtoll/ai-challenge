// ─────────────────────────────────────────────────────────────────
// День 15. Контролируемые переходы состояний.
//
// Стейджи остались как в Day 13, но теперь добавлены ГЕЙТЫ (Gates)
// и явная TransitionTable. Переходы возможны только при выполнении
// гейтов — попытка перейти раньше = понятная ошибка + audit log.
// ─────────────────────────────────────────────────────────────────

enum class Stage { PLANNING, EXECUTION, VALIDATION, DONE }

enum class PlanningStep { DEFINE_GOAL, LIST_REQUIREMENTS, DRAFT_PLAN, CONFIRM_PLAN }
enum class ValidationStep { VERIFY_CRITERIA, ASK_FEEDBACK }

sealed class TaskState {
    abstract val stage: Stage
    abstract fun describe(): String

    data class Planning(
        val step: PlanningStep,
        val goal: String? = null,
        val requirements: List<String> = emptyList(),
        val draftPlan: List<String> = emptyList()
    ) : TaskState() {
        override val stage = Stage.PLANNING
        override fun describe() = "PLANNING.$step"
    }

    data class Execution(
        val plan: List<String>,
        val currentIdx: Int,
        val results: List<String> = emptyList()
    ) : TaskState() {
        override val stage = Stage.EXECUTION
        val isLastStep: Boolean get() = currentIdx >= plan.lastIndex
        override fun describe() = "EXECUTION step ${currentIdx + 1}/${plan.size}"
    }

    data class Validation(
        val plan: List<String>,
        val results: List<String>,
        val step: ValidationStep
    ) : TaskState() {
        override val stage = Stage.VALIDATION
        override fun describe() = "VALIDATION.$step"
    }

    object Done : TaskState() {
        override val stage = Stage.DONE
        override fun describe() = "DONE"
    }
}
