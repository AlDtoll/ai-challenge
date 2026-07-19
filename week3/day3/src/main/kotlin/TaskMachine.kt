// Машина состояний задачи.
// Все мутации идут через transition() — атомарно:
// 1) валидируем переход (запрещаем перепрыгивания)
// 2) сохраняем новое состояние в state.json
// 3) пишем запись в events.jsonl (audit log)

class TaskMachine(
    private val repo: Repository,
    initial: TaskState = TaskState.Planning(PlanningStep.DEFINE_GOAL)
) {
    var state: TaskState
        private set
    var paused: Boolean
        private set

    init {
        val loaded = repo.load()
        if (loaded != null) {
            state = loaded.first
            paused = loaded.second
        } else {
            state = initial
            paused = false
            repo.save(state, paused)
        }
    }

    fun transition(newState: TaskState, trigger: String) {
        if (paused) error("Машина на паузе. Сначала /resume.")
        validate(state, newState)
        val old = state
        state = newState
        repo.save(state, paused)
        repo.appendEvent(old, newState, trigger, paused)
    }

    fun pause(): Boolean {
        if (paused) return false
        paused = true
        repo.save(state, paused)
        repo.appendEvent(state, state, "/pause", paused)
        return true
    }

    fun resume(): Boolean {
        if (!paused) return false
        paused = false
        repo.save(state, paused)
        repo.appendEvent(state, state, "/resume", paused)
        return true
    }

    /** Откат на состояние ДО последнего реального перехода. /pause /resume не считаются. */
    fun back(): Boolean {
        val realTransitions = repo.readEvents().filter { it.trigger !in setOf("/pause", "/resume", "/back") }
        val last = realTransitions.lastOrNull() ?: return false
        val recovered = last.stateBefore.toState()
        val old = state
        state = recovered
        repo.save(state, paused)
        repo.appendEvent(old, recovered, "/back", paused)
        return true
    }

    /** Что должно произойти дальше — для индикации в /status. */
    fun expectedAction(): ExpectedAction = if (paused) ExpectedAction.ASK_USER else state.expectedAction

    // ── Валидация разрешённых переходов ──
    // Запрещаем перепрыгивать стадии. Из VALIDATION можно назад в EXECUTION (если что-то не так).
    private fun validate(from: TaskState, to: TaskState) {
        val allowed = when (from) {
            is TaskState.Planning   -> to is TaskState.Planning  || to is TaskState.Execution
            is TaskState.Execution  -> to is TaskState.Execution || to is TaskState.Validation
            is TaskState.Validation -> to is TaskState.Validation || to is TaskState.Done || to is TaskState.Execution
            TaskState.Done          -> false
        }
        if (!allowed) error("Запрещённый переход: ${from.describe()} → ${to.describe()}")
    }
}
