// Машина с контролируемыми переходами.
//
// Два метода продвижения:
//   advance(newState, trigger) — естественный шаг внутри stage'а
//       (PLANNING.DEFINE_GOAL → PLANNING.DRAFT_PLAN). Меняет state БЕЗ
//       смены stage'а или с разрешённым переходом stage'а — но всё
//       равно проверяется через TransitionTable.
//   goto(targetStage, trigger) — явный переход в другой stage.
//       Проверяет TransitionTable + guard. Запрещённая попытка =
//       исключение + запись TRANSITION_BLOCKED в audit.
//
// Гейты переключаются ОТДЕЛЬНО — командами /approve-plan, /validate-ok и т.д.
// state переходит только если гейт уже стоит.

class TransitionDeniedException(message: String) : Exception(message)

class TaskMachine(
    private val repo: Repository,
    initial: TaskState = TaskState.Planning(PlanningStep.DEFINE_GOAL),
    initialGates: Gates = Gates()
) {
    var state: TaskState
        private set
    var gates: Gates
        private set
    var paused: Boolean
        private set

    init {
        val loaded = repo.load()
        if (loaded != null) {
            state = loaded.state.toState()
            gates = loaded.gates
            paused = loaded.paused
        } else {
            state = initial
            gates = initialGates
            paused = false
            repo.save(state, gates, paused)
        }
    }

    /** Естественный переход — например замена шага внутри PLANNING. Проверяется TransitionTable. */
    fun advance(newState: TaskState, trigger: String) {
        if (paused) error("Машина на паузе. Сначала /resume.")
        val rule = TransitionTable.findAllowed(state.stage, newState.stage, state, gates)
        if (rule == null) {
            repo.appendEvent(EventType.TRANSITION_BLOCKED, state.stage, newState.stage, gates, trigger,
                reason = "advance(): нет разрешённого правила или guard не пройден")
            throw TransitionDeniedException("Переход ${state.stage} → ${newState.stage} запрещён: " +
                "${TransitionTable.findByPath(state.stage, newState.stage)?.guardName ?: "нет такого пути"}")
        }
        val from = state.stage
        state = newState
        repo.save(state, gates, paused)
        repo.appendEvent(EventType.TRANSITION, from, newState.stage, gates, trigger, reason = "rule: ${rule.guardName}")
    }

    /** Явный переход в другой stage. Удобно для команд /goto. */
    fun goto(target: Stage, payload: TaskState? = null, trigger: String = "/goto"): TaskState {
        if (paused) error("Машина на паузе. Сначала /resume.")
        val targetState = payload ?: defaultStateFor(target)
        val rule = TransitionTable.findAllowed(state.stage, target, state, gates)
        if (rule == null) {
            val path = TransitionTable.findByPath(state.stage, target)
            val reason = when {
                path == null -> "Не существует разрешённого пути ${state.stage} → $target. Нельзя перепрыгивать стадии."
                else         -> "Guard не пройден: ${path.guardName}"
            }
            repo.appendEvent(EventType.TRANSITION_BLOCKED, state.stage, target, gates, trigger, reason)
            throw TransitionDeniedException(reason)
        }
        val from = state.stage
        state = targetState
        repo.save(state, gates, paused)
        repo.appendEvent(EventType.TRANSITION, from, target, gates, trigger, reason = "rule: ${rule.guardName}")
        return state
    }

    // ── Управление гейтами ─────────────────────────────────────

    fun setGate(name: String, value: Boolean, trigger: String): Boolean {
        val old = gates
        gates = when (name) {
            "planApproved"       -> gates.copy(planApproved = value)
            "executionComplete"  -> gates.copy(executionComplete = value)
            "validationPassed"   -> gates.copy(validationPassed = value)
            else -> return false
        }
        repo.save(state, gates, paused)
        repo.appendEvent(EventType.GATE_FLIP, state.stage, state.stage, gates, trigger,
            reason = "$name: ${oldValue(old, name)} → $value")
        return true
    }

    private fun oldValue(g: Gates, name: String): Boolean = when (name) {
        "planApproved" -> g.planApproved
        "executionComplete" -> g.executionComplete
        "validationPassed" -> g.validationPassed
        else -> false
    }

    // ── Пауза/возобновление ────────────────────────────────────

    fun pause(): Boolean {
        if (paused) return false
        paused = true
        repo.save(state, gates, paused)
        repo.appendEvent(EventType.PAUSE, state.stage, state.stage, gates, "/pause", null)
        return true
    }

    fun resume(): Boolean {
        if (!paused) return false
        paused = false
        repo.save(state, gates, paused)
        repo.appendEvent(EventType.RESUME, state.stage, state.stage, gates, "/resume", null)
        return true
    }

    // ── Что разрешено прямо сейчас ─────────────────────────────

    fun availableTransitions(): List<TransitionRule> =
        if (paused) emptyList() else TransitionTable.availableNow(state, gates)

    fun blockedTransitions(): List<TransitionRule> {
        val available = availableTransitions().toSet()
        return TransitionTable.allFrom(state.stage).filterNot { it in available }
    }

    private fun defaultStateFor(stage: Stage): TaskState = when (stage) {
        Stage.PLANNING   -> TaskState.Planning(PlanningStep.DEFINE_GOAL)
        Stage.EXECUTION  -> {
            val plan = (state as? TaskState.Planning)?.draftPlan
                ?: (state as? TaskState.Execution)?.plan
                ?: (state as? TaskState.Validation)?.plan
                ?: error("Невозможно перейти в EXECUTION — нет плана.")
            TaskState.Execution(plan, currentIdx = 0)
        }
        Stage.VALIDATION -> {
            val s = state as? TaskState.Execution ?: error("В VALIDATION можно только из EXECUTION")
            TaskState.Validation(s.plan, s.results, ValidationStep.VERIFY_CRITERIA)
        }
        Stage.DONE       -> TaskState.Done
    }
}
