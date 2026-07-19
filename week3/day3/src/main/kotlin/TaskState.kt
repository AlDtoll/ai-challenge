// ─────────────────────────────────────────────────────────────────
// День 13. Task State Machine — состояние задачи как FSM.
//
//    PLANNING ─► EXECUTION ─► VALIDATION ─► DONE
//        ▲          ▲             │
//        └──────────┴─── /back ───┘   (откат при пересмотре)
//
// `paused` — отдельный флаг (не часть state'а). Это позволяет
// прервать работу на ЛЮБОМ этапе и продолжить ровно там же,
// без потери контекста.
// ─────────────────────────────────────────────────────────────────

enum class PlanningStep { DEFINE_GOAL, LIST_REQUIREMENTS, DRAFT_PLAN, CONFIRM_PLAN }
enum class ValidationStep { VERIFY_CRITERIA, ASK_FEEDBACK }

enum class ExpectedAction { ASK_USER, AGENT_ACT }

sealed class TaskState {
    abstract val expectedAction: ExpectedAction
    abstract fun describe(): String

    data class Planning(
        val step: PlanningStep,
        val goal: String? = null,
        val requirements: List<String> = emptyList(),
        val draftPlan: List<String> = emptyList()
    ) : TaskState() {
        override val expectedAction: ExpectedAction = when (step) {
            PlanningStep.DEFINE_GOAL        -> if (goal == null) ExpectedAction.ASK_USER else ExpectedAction.AGENT_ACT
            PlanningStep.LIST_REQUIREMENTS  -> ExpectedAction.ASK_USER
            PlanningStep.DRAFT_PLAN         -> ExpectedAction.AGENT_ACT
            PlanningStep.CONFIRM_PLAN       -> ExpectedAction.ASK_USER
        }

        override fun describe() = "PLANNING.$step"
    }

    data class Execution(
        val plan: List<String>,
        val currentIdx: Int,
        val results: List<String> = emptyList()
    ) : TaskState() {
        override val expectedAction = ExpectedAction.AGENT_ACT
        val isLastStep: Boolean get() = currentIdx >= plan.lastIndex
        override fun describe() = "EXECUTION step ${currentIdx + 1}/${plan.size}"
    }

    data class Validation(
        val plan: List<String>,
        val results: List<String>,
        val step: ValidationStep,
        val passed: Boolean? = null,
        val feedback: String? = null
    ) : TaskState() {
        override val expectedAction = when (step) {
            ValidationStep.VERIFY_CRITERIA -> ExpectedAction.AGENT_ACT
            ValidationStep.ASK_FEEDBACK    -> ExpectedAction.ASK_USER
        }
        override fun describe() = "VALIDATION.$step"
    }

    object Done : TaskState() {
        override val expectedAction = ExpectedAction.AGENT_ACT
        override fun describe() = "DONE"
    }
}
