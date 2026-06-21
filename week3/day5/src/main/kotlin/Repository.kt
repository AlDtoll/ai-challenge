import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.time.OffsetDateTime

// Плоский DTO для сериализации (sealed-class напрямую через Gson — больно).
data class StateDto(
    val type: String,
    val planningStep: PlanningStep? = null,
    val goal: String? = null,
    val requirements: List<String> = emptyList(),
    val draftPlan: List<String> = emptyList(),
    val plan: List<String> = emptyList(),
    val currentIdx: Int = 0,
    val results: List<String> = emptyList(),
    val validationStep: ValidationStep? = null
)

fun TaskState.toDto(): StateDto = when (this) {
    is TaskState.Planning   -> StateDto("Planning", planningStep = step, goal = goal, requirements = requirements, draftPlan = draftPlan)
    is TaskState.Execution  -> StateDto("Execution", plan = plan, currentIdx = currentIdx, results = results)
    is TaskState.Validation -> StateDto("Validation", plan = plan, results = results, validationStep = step)
    is TaskState.Done       -> StateDto("Done")
}

fun StateDto.toState(): TaskState = when (type) {
    "Planning"   -> TaskState.Planning(planningStep!!, goal, requirements, draftPlan)
    "Execution"  -> TaskState.Execution(plan, currentIdx, results)
    "Validation" -> TaskState.Validation(plan, results, validationStep!!)
    "Done"       -> TaskState.Done
    else         -> error("Unknown state type: $type")
}

data class Persisted(val state: StateDto, val gates: Gates, val paused: Boolean)

enum class EventType {
    TRANSITION,           // успешный переход
    TRANSITION_BLOCKED,   // попытка запрещённого перехода (guard не пропустил)
    GATE_FLIP,            // изменение гейта (/approve-plan и т.д.)
    PAUSE,
    RESUME
}

data class Event(
    val ts: String,
    val type: EventType,
    val fromStage: String?,
    val toStage: String?,
    val gates: Gates,
    val trigger: String,
    val reason: String?
)

class Repository(val stateFile: File, val eventsFile: File) {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val gsonCompact: Gson = Gson()

    fun load(): Persisted? {
        if (!stateFile.exists()) return null
        return runCatching { gson.fromJson(stateFile.readText(), Persisted::class.java) }.getOrNull()
    }

    fun save(state: TaskState, gates: Gates, paused: Boolean) {
        stateFile.parentFile?.mkdirs()
        stateFile.writeText(gson.toJson(Persisted(state.toDto(), gates, paused)))
    }

    fun appendEvent(type: EventType, from: Stage?, to: Stage?, gates: Gates, trigger: String, reason: String? = null) {
        eventsFile.parentFile?.mkdirs()
        eventsFile.appendText(gsonCompact.toJson(
            Event(
                ts = OffsetDateTime.now().toString(),
                type = type,
                fromStage = from?.name,
                toStage = to?.name,
                gates = gates,
                trigger = trigger,
                reason = reason
            )
        ) + "\n")
    }

    fun readEvents(limit: Int = 30): List<Event> {
        if (!eventsFile.exists()) return emptyList()
        return eventsFile.readLines()
            .filter { it.isNotBlank() }
            .takeLast(limit)
            .mapNotNull { runCatching { gsonCompact.fromJson(it, Event::class.java) }.getOrNull() }
    }

    fun reset() {
        if (stateFile.exists()) stateFile.delete()
        if (eventsFile.exists()) eventsFile.delete()
    }
}
