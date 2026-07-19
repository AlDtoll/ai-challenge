import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.time.OffsetDateTime

// Плоский DTO для сериализации (sealed-class напрямую через Gson — больно).
// Поле `type` — дискриминатор; остальные поля nullable/пустые, заполняются по типу.
data class StateDto(
    val type: String,
    val planningStep: PlanningStep? = null,
    val goal: String? = null,
    val requirements: List<String> = emptyList(),
    val draftPlan: List<String> = emptyList(),
    val plan: List<String> = emptyList(),
    val currentIdx: Int = 0,
    val results: List<String> = emptyList(),
    val validationStep: ValidationStep? = null,
    val passed: Boolean? = null,
    val feedback: String? = null
)

fun TaskState.toDto(): StateDto = when (this) {
    is TaskState.Planning   -> StateDto("Planning", planningStep = step, goal = goal, requirements = requirements, draftPlan = draftPlan)
    is TaskState.Execution  -> StateDto("Execution", plan = plan, currentIdx = currentIdx, results = results)
    is TaskState.Validation -> StateDto("Validation", plan = plan, results = results, validationStep = step, passed = passed, feedback = feedback)
    is TaskState.Done       -> StateDto("Done")
}

fun StateDto.toState(): TaskState = when (type) {
    "Planning"   -> TaskState.Planning(planningStep!!, goal, requirements, draftPlan)
    "Execution"  -> TaskState.Execution(plan, currentIdx, results)
    "Validation" -> TaskState.Validation(plan, results, validationStep!!, passed, feedback)
    "Done"       -> TaskState.Done
    else         -> error("Unknown state type: $type")
}

// На диске:
//   stateFile  — одно последнее состояние + флаг паузы
//   eventsFile — JSONL audit log, строка на каждый переход / pause / resume / back
data class PersistedState(val state: StateDto, val paused: Boolean)

data class Event(
    val ts: String,
    val from: String,
    val to: String,
    val trigger: String,
    val paused: Boolean,
    val stateBefore: StateDto,
    val stateAfter: StateDto
)

class Repository(val stateFile: File, val eventsFile: File) {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val gsonCompact: Gson = Gson()  // JSONL — одна строка на событие

    fun load(): Pair<TaskState, Boolean>? {
        if (!stateFile.exists()) return null
        val persisted = runCatching {
            gson.fromJson(stateFile.readText(), PersistedState::class.java)
        }.getOrNull() ?: return null
        return persisted.state.toState() to persisted.paused
    }

    fun save(state: TaskState, paused: Boolean) {
        stateFile.parentFile?.mkdirs()
        stateFile.writeText(gson.toJson(PersistedState(state.toDto(), paused)))
    }

    fun appendEvent(from: TaskState, to: TaskState, trigger: String, paused: Boolean) {
        eventsFile.parentFile?.mkdirs()
        val ev = Event(
            ts = OffsetDateTime.now().toString(),
            from = from.describe(),
            to = to.describe(),
            trigger = trigger,
            paused = paused,
            stateBefore = from.toDto(),
            stateAfter = to.toDto()
        )
        eventsFile.appendText(gsonCompact.toJson(ev) + "\n")
    }

    fun readEvents(): List<Event> {
        if (!eventsFile.exists()) return emptyList()
        return eventsFile.readLines()
            .filter { it.isNotBlank() }
            .mapNotNull { runCatching { gsonCompact.fromJson(it, Event::class.java) }.getOrNull() }
    }

    fun reset() {
        if (stateFile.exists()) stateFile.delete()
        if (eventsFile.exists()) eventsFile.delete()
    }
}
