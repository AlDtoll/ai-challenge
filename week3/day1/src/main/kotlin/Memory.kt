import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File

// ────────────────────────────────────────────────────────────
// Общие типы
// ────────────────────────────────────────────────────────────

data class Message(val role: String, val content: String)

private val gson = GsonBuilder().setPrettyPrinting().create()

// ────────────────────────────────────────────────────────────
// Слой 1: Краткосрочная память — текущий диалог
// ────────────────────────────────────────────────────────────

class ShortTermMemory {
    private val messages = mutableListOf<Message>()

    fun add(role: String, content: String) {
        messages.add(Message(role, content))
    }

    fun getMessages(): List<Message> = messages.toList()

    fun size() = messages.size

    fun clear() = messages.clear()

    fun describe(): String {
        if (messages.isEmpty()) return "(пусто)"
        return messages.joinToString("\n") { "[${it.role}] ${it.content.take(80)}${if (it.content.length > 80) "…" else ""}" }
    }
}

// ────────────────────────────────────────────────────────────
// Слой 2: Рабочая память — данные текущей задачи
// ────────────────────────────────────────────────────────────

data class WorkingTask(
    val id: String,
    val description: String,
    var stage: String = "gathering",
    val artifacts: MutableMap<String, String> = mutableMapOf(),
    val notes: MutableList<String> = mutableListOf()
)

class WorkingMemory(private val file: File) {
    var task: WorkingTask? = null
        private set

    init {
        if (file.exists()) {
            runCatching { task = Gson().fromJson(file.readText(), WorkingTask::class.java) }
        }
    }

    fun startTask(description: String): WorkingTask {
        val t = WorkingTask(id = System.currentTimeMillis().toString(), description = description)
        task = t
        save()
        return t
    }

    fun updateStage(stage: String) {
        task?.let { it.stage = stage; save() }
    }

    fun addArtifact(key: String, value: String) {
        task?.let { it.artifacts[key] = value; save() }
    }

    fun addNote(note: String) {
        task?.let { it.notes.add(note); save() }
    }

    fun closeTask() {
        task = null
        if (file.exists()) file.delete()
    }

    private fun save() {
        task?.let { file.writeText(gson.toJson(it)) }
    }

    fun toPromptBlock(): String? = task?.let { t ->
        buildString {
            appendLine("## Текущая задача [стадия: ${t.stage}]")
            appendLine("Описание: ${t.description}")
            if (t.artifacts.isNotEmpty()) {
                appendLine("Артефакты:")
                t.artifacts.forEach { (k, v) -> appendLine("  $k: $v") }
            }
            if (t.notes.isNotEmpty()) {
                appendLine("Заметки: ${t.notes.joinToString("; ")}")
            }
        }.trimEnd()
    }

    fun describe(): String = task?.let { t ->
        "Задача: ${t.description}\n  Стадия: ${t.stage}\n  Артефактов: ${t.artifacts.size}\n  Заметок: ${t.notes.size}"
    } ?: "(нет активной задачи)"
}

// ────────────────────────────────────────────────────────────
// Слой 3: Долговременная память — профиль, решения, знания
// ────────────────────────────────────────────────────────────

data class UserProfile(
    var name: String = "",
    var style: String = "краткий",
    var stack: String = "",
    val constraints: MutableList<String> = mutableListOf()
)

data class LongTermData(
    val profile: UserProfile = UserProfile(),
    val knowledge: MutableList<String> = mutableListOf(),
    val decisions: MutableList<String> = mutableListOf()
)

class LongTermMemory(private val file: File) {
    var data: LongTermData = if (file.exists()) {
        runCatching { gson.fromJson(file.readText(), LongTermData::class.java) }.getOrDefault(LongTermData())
    } else {
        LongTermData()
    }
        private set

    fun isNew() = data.profile.name.isEmpty()

    fun saveProfile(name: String, style: String, stack: String, constraints: List<String>) {
        data.profile.apply {
            this.name = name
            this.style = style
            this.stack = stack
            this.constraints.clear()
            this.constraints.addAll(constraints)
        }
        save()
    }

    fun addKnowledge(fact: String) {
        data.knowledge.add(fact)
        save()
    }

    fun addDecision(decision: String) {
        data.decisions.add(decision)
        save()
    }

    private fun save() {
        file.parentFile?.mkdirs()
        file.writeText(gson.toJson(data))
    }

    fun toPromptBlock(): String = buildString {
        val p = data.profile
        appendLine("## Профиль пользователя")
        if (p.name.isNotEmpty()) appendLine("Имя: ${p.name}")
        appendLine("Стиль ответов: ${p.style}")
        if (p.stack.isNotEmpty()) appendLine("Стек: ${p.stack}")
        if (p.constraints.isNotEmpty()) appendLine("Ограничения: ${p.constraints.joinToString(", ")}")
        if (data.knowledge.isNotEmpty()) {
            appendLine("\n## Долговременные знания")
            data.knowledge.takeLast(10).forEach { appendLine("- $it") }
        }
        if (data.decisions.isNotEmpty()) {
            appendLine("\n## Принятые решения")
            data.decisions.takeLast(5).forEach { appendLine("- $it") }
        }
    }.trimEnd()

    fun describe(): String = buildString {
        appendLine("Имя: ${data.profile.name.ifEmpty { "(не задано)" }}")
        appendLine("Стиль: ${data.profile.style}")
        appendLine("Стек: ${data.profile.stack.ifEmpty { "(не задан)" }}")
        appendLine("Ограничений: ${data.profile.constraints.size}")
        appendLine("Знаний: ${data.knowledge.size}")
        appendLine("Решений: ${data.decisions.size}")
    }.trimEnd()
}
