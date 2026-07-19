import java.io.File

// ─────────────────────────────────────────────────────────────────
// День 13. Task State Machine — REPL.
//
// Команды:
//   /status   — текущее состояние, шаг, ожидаемое действие
//   /plan     — показать собранный план (когда есть)
//   /next     — перейти к следующему шагу (PLANNING или EXECUTION)
//   /confirm  — подтвердить план / валидацию (зависит от стадии)
//   /reject   — вернуться из VALIDATION в EXECUTION для правок
//   /back     — откатить последний переход
//   /pause    — поставить машину на паузу (можно exit и вернуться)
//   /resume   — снять паузу
//   /reset    — стереть всё состояние и начать заново
//   /history  — последние 10 событий из audit log
//   /prompt   — показать текущий системный промпт
//   exit      — выйти (состояние сохраняется!)
//
// Любой не-/ ввод трактуется как сообщение в чате.
// Системный промпт собирается каждый раз заново из текущего state'а —
// в этом и есть «продолжение без повторных объяснений»: всё уже в state.
// ─────────────────────────────────────────────────────────────────

fun main() {
    val baseDir = File(System.getProperty("user.home"), ".ai-challenge")
    val repo = Repository(
        stateFile  = File(baseDir, "task-state.json"),
        eventsFile = File(baseDir, "task-events.jsonl")
    )
    val machine = TaskMachine(repo)
    val llm = LlmClient()
    val history = mutableListOf<Message>()

    println("╔═══════════════════════════════════════════════════════╗")
    println("║  День 13 — Task State Machine (planning → done)      ║")
    println("╚═══════════════════════════════════════════════════════╝")
    printStatus(machine)
    println("Команды: /status /plan /next /confirm /reject /back /pause /resume /reset /history /prompt exit")
    println()

    while (true) {
        val prompt = if (machine.paused) "[PAUSED] You: " else "[${machine.state.describe()}] You: "
        print(prompt)
        val input = readLine()?.trim() ?: break
        if (input.equals("exit", ignoreCase = true)) {
            println("Состояние сохранено. До встречи!"); break
        }
        if (input.isBlank()) continue

        try {
            when {
                input == "/status"  -> printStatus(machine)
                input == "/plan"    -> printPlan(machine)
                input == "/next"    -> handleNext(machine, llm, history)
                input == "/confirm" -> handleConfirm(machine)
                input == "/reject"  -> handleReject(machine)
                input == "/back"    -> if (machine.back()) printStatus(machine) else println(">>> Нечего откатывать.\n")
                input == "/pause"   -> { machine.pause(); println(">>> На паузе. Можно exit — состояние сохранится.\n") }
                input == "/resume"  -> { machine.resume(); printStatus(machine) }
                input == "/reset"   -> { repo.reset(); println(">>> Состояние стёрто. Перезапусти программу.\n"); break }
                input == "/history" -> printHistory(repo)
                input == "/prompt"  -> { println("\n--- СИСТЕМНЫЙ ПРОМПТ ---"); println(buildSystemPrompt(machine.state, machine.paused)); println("------------------------\n") }
                else                -> handleUserMessage(input, machine, llm, history)
            }
        } catch (e: Exception) {
            println("\n⛔ ${e.message}\n")
        }
    }
}

// ─── Команды ──────────────────────────────────────────────────────

private fun handleUserMessage(input: String, m: TaskMachine, llm: LlmClient, history: MutableList<Message>) {
    if (m.paused) { println(">>> Машина на паузе. /resume чтобы продолжить.\n"); return }

    // 1) Содержание ввода может обновить state ДО запроса к LLM
    //    (например, в DEFINE_GOAL ввод = это и есть цель)
    absorbUserInput(input, m)

    // 2) Вызываем LLM с системным промптом, отражающим АКТУАЛЬНОЕ состояние
    val systemPrompt = buildSystemPrompt(m.state, m.paused)
    val (reply, usage) = llm.chat(systemPrompt, history + Message("user", input))
    history.add(Message("user", input))
    history.add(Message("assistant", reply))

    println("\nAgent: $reply")
    println("  [↑${usage.prompt_tokens} / ↓${usage.completion_tokens}]\n")

    // 3) Содержание ответа LLM может тоже двинуть state
    //    (например, в DRAFT_PLAN LLM выдаёт пронумерованный план → парсим в draftPlan и переходим в CONFIRM_PLAN)
    absorbAssistantReply(reply, m)
}

private fun handleNext(m: TaskMachine, llm: LlmClient, history: MutableList<Message>) {
    when (val s = m.state) {
        is TaskState.Planning -> {
            // Продвинуть шаг планирования вручную, если требования собраны
            val next = when (s.step) {
                PlanningStep.LIST_REQUIREMENTS -> if (s.requirements.isNotEmpty()) s.copy(step = PlanningStep.DRAFT_PLAN) else null
                else -> null
            }
            if (next != null) { m.transition(next, "/next"); printStatus(m) }
            else println(">>> /next тут не помогает — сначала собери требования.\n")
        }
        is TaskState.Execution -> {
            val stepText = s.plan[s.currentIdx]
            val systemPrompt = buildSystemPrompt(s, m.paused)
            val (reply, _) = llm.chat(systemPrompt, history + Message("user", "Выполни шаг: $stepText"))
            history.add(Message("user", "[/next $stepText]"))
            history.add(Message("assistant", reply))
            println("\nAgent: $reply\n")
            val newResults = s.results + reply.take(200)
            val nextState = if (s.isLastStep) {
                TaskState.Validation(s.plan, newResults, ValidationStep.VERIFY_CRITERIA)
            } else {
                TaskState.Execution(s.plan, s.currentIdx + 1, newResults)
            }
            m.transition(nextState, "/next")
            printStatus(m)
        }
        else -> println(">>> /next доступна только в PLANNING.LIST_REQUIREMENTS или EXECUTION.\n")
    }
}

private fun handleConfirm(m: TaskMachine) {
    when (val s = m.state) {
        is TaskState.Planning -> {
            if (s.step != PlanningStep.CONFIRM_PLAN) { println(">>> /confirm в PLANNING доступна только на шаге CONFIRM_PLAN.\n"); return }
            if (s.draftPlan.isEmpty()) { println(">>> Нет плана для подтверждения.\n"); return }
            m.transition(TaskState.Execution(s.draftPlan, currentIdx = 0), "/confirm")
            println(">>> План подтверждён. Переходим к EXECUTION.")
            printStatus(m)
        }
        is TaskState.Validation -> {
            m.transition(TaskState.Done, "/confirm")
            println(">>> ✅ Задача завершена.\n")
        }
        else -> println(">>> /confirm недоступна на этом этапе.\n")
    }
}

private fun handleReject(m: TaskMachine) {
    when (val s = m.state) {
        is TaskState.Validation -> {
            m.transition(TaskState.Execution(s.plan, s.plan.lastIndex, s.results), "/reject")
            println(">>> Возврат в EXECUTION для доработки.")
            printStatus(m)
        }
        else -> println(">>> /reject доступна только в VALIDATION.\n")
    }
}

// ─── Поглощение пользовательского ввода ──

private fun absorbUserInput(input: String, m: TaskMachine) {
    val s = m.state as? TaskState.Planning ?: return
    val updated = when (s.step) {
        PlanningStep.DEFINE_GOAL -> s.copy(goal = input, step = PlanningStep.LIST_REQUIREMENTS)
        PlanningStep.LIST_REQUIREMENTS -> {
            val items = input.split("\n", ";").map { it.trim() }.filter { it.isNotEmpty() }
            if (items.isEmpty()) null else s.copy(requirements = s.requirements + items)
        }
        else -> null
    }
    if (updated != null) m.transition(updated, "user_input")
}

// ─── Поглощение ответа LLM ──
// Главный кейс: LLM в DRAFT_PLAN выдала пронумерованный список 1. ... 2. ... → парсим как план
// и переводим машину в CONFIRM_PLAN.

private fun absorbAssistantReply(reply: String, m: TaskMachine) {
    val s = m.state as? TaskState.Planning ?: return
    if (s.step != PlanningStep.DRAFT_PLAN) return
    val plan = parseNumberedList(reply)
    if (plan.size >= 2) {
        m.transition(s.copy(draftPlan = plan, step = PlanningStep.CONFIRM_PLAN), "auto_parse_plan")
    }
}

private fun parseNumberedList(text: String): List<String> {
    val re = Regex("""^\s*\d+[.)]\s+(.+)$""", RegexOption.MULTILINE)
    return re.findAll(text).map { it.groupValues[1].trim() }.toList()
}

// ─── Печать ──

private fun printStatus(m: TaskMachine) {
    println()
    println("┌─ Состояние задачи ──────────────────────────────────")
    if (m.paused) println("│  ⏸  PAUSED (исходное состояние сохранено)")
    println("│  Стадия: ${m.state.describe()}")
    println("│  Ожидается: ${m.expectedAction()}")
    when (val s = m.state) {
        is TaskState.Planning -> {
            s.goal?.let       { println("│  Цель: $it") }
            if (s.requirements.isNotEmpty()) println("│  Требований: ${s.requirements.size}")
            if (s.draftPlan.isNotEmpty())    println("│  Шагов плана: ${s.draftPlan.size}")
        }
        is TaskState.Execution -> {
            println("│  Шаг ${s.currentIdx + 1} из ${s.plan.size}: ${s.plan[s.currentIdx]}")
            if (s.results.isNotEmpty()) println("│  Завершено шагов: ${s.results.size}")
        }
        is TaskState.Validation -> {
            println("│  Подшаг: ${s.step}")
            s.passed?.let { println("│  Проверка: ${if (it) "PASS" else "FAIL"}") }
        }
        TaskState.Done -> println("│  🎉 Задача завершена.")
    }
    println("└─────────────────────────────────────────────────────")
    println()
}

private fun printPlan(m: TaskMachine) {
    val plan = when (val s = m.state) {
        is TaskState.Planning   -> s.draftPlan
        is TaskState.Execution  -> s.plan
        is TaskState.Validation -> s.plan
        TaskState.Done          -> emptyList()
    }
    if (plan.isEmpty()) { println(">>> План пока пуст.\n"); return }
    println("\n── План ──")
    plan.forEachIndexed { i, step -> println("  ${i + 1}. $step") }
    println()
}

private fun printHistory(repo: Repository) {
    val events = repo.readEvents().takeLast(10)
    if (events.isEmpty()) { println(">>> История пуста.\n"); return }
    println("\n── Последние ${events.size} событий ──")
    events.forEach { ev ->
        val time = ev.ts.substringAfter("T").substringBefore(".").substringBeforeLast(":")
        println("  $time  ${ev.from}  →  ${ev.to}   [${ev.trigger}]")
    }
    println()
}

// ─── Сборка системного промпта из состояния ──
// Сердце «продолжения без повторных объяснений»: всё необходимое для агента
// есть в state — он не задаёт уже отвеченных вопросов.

fun buildSystemPrompt(state: TaskState, paused: Boolean): String = buildString {
    appendLine("Ты ассистент-разработчик, ведущий пользователя через структурированную задачу.")
    appendLine("Работа разбита на этапы конечного автомата. НЕ отступай от инструкций текущей стадии.")
    appendLine()
    if (paused) {
        appendLine("⏸ Машина состояний на паузе. Пользователь только что вернулся — кратко напомни, на чём остановились (по данным ниже), и подожди /resume. Не задавай новых вопросов.")
        appendLine()
    }
    appendLine("== ТЕКУЩЕЕ СОСТОЯНИЕ ==")
    appendLine("Стадия: ${state.describe()}")
    when (state) {
        is TaskState.Planning -> {
            state.goal?.let { appendLine("Цель задачи: $it") }
            if (state.requirements.isNotEmpty()) {
                appendLine("Уже собранные требования:")
                state.requirements.forEach { appendLine("  • $it") }
            }
            if (state.draftPlan.isNotEmpty()) {
                appendLine("Черновой план:")
                state.draftPlan.forEachIndexed { i, s -> appendLine("  ${i + 1}. $s") }
            }
            appendLine()
            appendLine(planningInstruction(state.step))
        }
        is TaskState.Execution -> {
            appendLine("План задачи:")
            state.plan.forEachIndexed { i, s ->
                val mark = when {
                    i < state.currentIdx -> "✓"
                    i == state.currentIdx -> "→"
                    else -> " "
                }
                appendLine("  $mark ${i + 1}. $s")
            }
            appendLine()
            appendLine("Текущий шаг: ${state.plan[state.currentIdx]}")
            appendLine("Сделай конкретный, осязаемый прогресс именно по этому шагу. Не забегай вперёд.")
        }
        is TaskState.Validation -> {
            appendLine("План был выполнен:")
            state.plan.forEachIndexed { i, s -> appendLine("  ${i + 1}. $s") }
            appendLine()
            when (state.step) {
                ValidationStep.VERIFY_CRITERIA -> appendLine("Проверь: достигнута ли изначальная цель? Какие критерии выполнены, какие нет?")
                ValidationStep.ASK_FEEDBACK    -> appendLine("Спроси пользователя, всё ли его устраивает в результате.")
            }
        }
        TaskState.Done -> {
            appendLine("Задача завершена. Поздравь пользователя и предложи следующую.")
        }
    }
}

private fun planningInstruction(step: PlanningStep): String = when (step) {
    PlanningStep.DEFINE_GOAL ->
        "Шаг: DEFINE_GOAL. Спроси пользователя, ЧТО ему нужно сделать. Одним коротким вопросом."
    PlanningStep.LIST_REQUIREMENTS ->
        "Шаг: LIST_REQUIREMENTS. Задавай уточняющие вопросы (1–2 за раз), пока требования не станут достаточно полными. Когда их хотя бы 2 — попроси пользователя /next, чтобы перейти к составлению плана."
    PlanningStep.DRAFT_PLAN ->
        "Шаг: DRAFT_PLAN. На основе цели и требований составь пронумерованный план из 3–5 шагов EXECUTION. ВАЖНО: формат строго '1. ... 2. ... 3. ...' с новой строки. После плана попроси /confirm для перехода к выполнению или /back для пересмотра."
    PlanningStep.CONFIRM_PLAN ->
        "Шаг: CONFIRM_PLAN. План уже представлен. Жди /confirm или /reject — не повторяй план без надобности и не задавай новых вопросов."
}
