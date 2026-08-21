# Three-Layer Memory — трёхслойная модель памяти агента

## What it improves

Один список `history: List<Message>` смешивает данные с разным временем жизни: текущий диалог (нужен только сейчас), рабочая задача (живёт несколько часов), профиль пользователя (нужен всегда). Трёхслойная модель разделяет их: Short (L1) — живёт в runtime, Working (L2) — персистируется в JSON на задачу, Long (L3) — персистируется в JSON как постоянный профиль. Агент работает как CPU с кэшем: не грузит всё в L1, берёт нужное из нужного слоя.

## When to use

- Персональный ассистент с долгосрочными пользователями (неделя+)
- Агент с многошаговыми задачами (задача живёт несколько сессий)
- Первый запуск требует онбординга: имя, стиль, стек, запреты — это L3
- Нужна отдельная отладка каждого слоя (что знает о задаче / что знает о пользователе)

**Когда НЕ надо:** простой FAQ-бот без персонализации, одноразовые скрипты — three layers = оверинжиниринг без выгоды.

## How to integrate

1. Создай три класса: `ShortTermMemory` (в памяти, MutableList), `WorkingMemory` (JSON по taskId), `LongTermMemory` (JSON по userId).
2. При старте: загрузи L3 (профиль) и активную L2 (задачу если есть).
3. System prompt строй как: `basePrompt + L3.format() + L2.format()`. L1 — это сама история сообщений.
4. При создании новой задачи — создавай/очищай L2 (WorkingMemory), L3 не трогай.
5. При первом запуске (L3 пуст) — запусти онбординг: спроси имя, уровень, стек, ограничения → сохрани в L3.

## Working example (Kotlin)

```kotlin
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class UserProfile(
    val name: String = "",
    val level: String = "intermediate",  // junior / intermediate / senior
    val stack: List<String> = emptyList(),
    val prohibitions: List<String> = emptyList()
)

@Serializable
data class WorkingTask(
    val taskId: String,
    val description: String,
    val notes: MutableList<String> = mutableListOf(),
    val status: String = "in_progress"
)

class LongTermMemory(userId: String, baseDir: String = "${System.getProperty("user.home")}/.ai-challenge") {
    private val file = File(baseDir, "profile_$userId.json").also { it.parentFile.mkdirs() }
    var profile: UserProfile = if (file.exists()) Json.decodeFromString(file.readText()) else UserProfile()

    fun save() = file.writeText(Json.encodeToString(UserProfile.serializer(), profile))
    fun isNew() = profile.name.isEmpty()
    fun format() = if (isNew()) "" else """
        |## Профиль пользователя (L3)
        |Имя: ${profile.name} | Уровень: ${profile.level}
        |Стек: ${profile.stack.joinToString(", ")}
        |Ограничения: ${profile.prohibitions.joinToString(", ").ifEmpty { "нет" }}
    """.trimMargin()
}

class WorkingMemory(baseDir: String = "${System.getProperty("user.home")}/.ai-challenge") {
    private val dir = File(baseDir).also { it.mkdirs() }
    private var current: WorkingTask? = null

    fun start(taskId: String, description: String) {
        current = WorkingTask(taskId, description)
        save()
    }

    fun addNote(note: String) { current?.notes?.add(note); save() }
    private fun save() = current?.let { File(dir, "task_${it.taskId}.json").writeText(Json.encodeToString(WorkingTask.serializer(), it)) }
    fun format() = current?.let { "## Текущая задача (L2)\n${it.description}\nЗаметки: ${it.notes.joinToString("; ")}" } ?: ""
}

// ShortTermMemory = обычный history: MutableList<Message> в агенте
```

## Metrics

- **Onboarding completion rate** — доля пользователей прошедших онбординг (L3 заполнен); < 70% = онбординг слишком длинный
- **L2 task duration** — среднее время жизни WorkingTask; если > 24ч, возможно статус «done» не проставляется
- **L3 profile hit rate** — доля вызовов где system prompt содержал профиль и ответ релевантен стеку пользователя
- **Memory size on disk** — суммарно по всем пользователям; при > 10 MB думать о TTL для старых задач

## Source

- **AI Challenge:** week3/day1 — Трёхслойная модель памяти
- **Артефакты:** https://github.com/AlDtoll/ai-challenge/tree/week3/day1/week3/day1 — `Memory.kt`, `Agent.kt`, `Main.kt`
- **Связано:** [`../context_management/persistent_context.md`](../context_management/persistent_context.md) — персистентность одного слоя (history); [`profile_extractor.md`](profile_extractor.md) — автозаполнение L3 без явного онбординга; [`../state_machine/task_state_machine.md`](../state_machine/task_state_machine.md) — расширение L2 до полноценного state machine с переходами
