import java.io.File

// ─────────────────────────────────────────────────────────────────
// День 15. Контролируемые переходы состояний — REPL.
//
// Команды состояний:
//   /status        — стейдж + гейты + ожидаемое действие
//   /transitions   — что разрешено / запрещено прямо сейчас и почему
//   /goto <stage>  — попытаться явно перейти в PLANNING / EXECUTION / VALIDATION / DONE
//                    (заблокируется если guard не пройден)
//   /advance       — естественный следующий шаг (через advance())
//
// Команды гейтов:
//   /approve-plan      — planApproved = true
//   /unapprove-plan    — planApproved = false
//   /execution-done    — executionComplete = true
//   /validate-ok       — validationPassed = true
//   /validate-fail     — validationPassed = false  (откатит в EXECUTION при попытке)
//
// Прочее:
//   /plan          — показать план
//   /pause /resume — пауза
//   /history       — последние события
//   /prompt        — системный промпт
//   /reset         — сбросить state и гейты
//   exit
//
// Любой не-/ ввод — диалог с агентом, который видит state и гейты в systemPrompt.
// ─────────────────────────────────────────────────────────────────

fun main() {
    val baseDir = File(System.getProperty("user.home"), ".ai-challenge")
    val repo = Repository(File(baseDir, "task-state-day15.json"), File(baseDir, "task-events-day15.jsonl"))
    val machine = TaskMachine(repo)
    val llm = LlmClient()
    val history = mutableListOf<Message>()

    println("╔════════════════════════════════════════════════════════╗")
    println("║  День 15 — Controlled Transitions (gates + table)     ║")
    println("╚════════════════════════════════════════════════════════╝")
    printStatus(machine)
    println("Команды: /status /transitions /goto <stage> /advance")
    println("Гейты:   /approve-plan /unapprove-plan /execution-done /validate-ok /validate-fail")
    println("Прочее:  /plan /pause /resume /history /prompt /reset exit")
    println()

    while (true) {
        val prefix = if (machine.paused) "[PAUSED]" else "[${machine.state.describe()}]"
        print("$prefix You: ")
        val input = readLine()?.trim() ?: break
        if (input.equals("exit", ignoreCase = true)) { println("Состояние сохранено."); break }
        if (input.isBlank()) continue

        try {
            when {
                input == "/status"          -> printStatus(machine)
                input == "/transitions"     -> printTransitions(machine)
                input == "/plan"            -> printPlan(machine)
                input == "/advance"         -> handleAdvance(machine, llm, history)
                input.startsWith("/goto ")  -> handleGoto(input.removePrefix("/goto ").trim(), machine)
                input == "/approve-plan"   -> setGateAndStatus(machine, "planApproved", true, "/approve-plan")
                input == "/unapprove-plan" -> setGateAndStatus(machine, "planApproved", false, "/unapprove-plan")
                input == "/execution-done" -> setGateAndStatus(machine, "executionComplete", true, "/execution-done")
                input == "/validate-ok"    -> setGateAndStatus(machine, "validationPassed", true, "/validate-ok")
                input == "/validate-fail"  -> setGateAndStatus(machine, "validationPassed", false, "/validate-fail")
                input == "/pause"          -> { machine.pause(); println(">>> На паузе.\n") }
                input == "/resume"         -> { machine.resume(); printStatus(machine) }
                input == "/history"        -> printHistory(repo)
                input == "/prompt"         -> { println("\n--- SYSTEM ---"); println(buildSystemPrompt(machine)); println("--------------\n") }
                input == "/reset"          -> { repo.reset(); println(">>> Сброшено. Перезапусти.\n"); break }
                else                       -> handleUserMessage(input, machine, llm, history)
            }
        } catch (e: TransitionDeniedException) {
            println("\n🚫 Переход запрещён: ${e.message}\n")
        } catch (e: Exception) {
            println("\n⛔ ${e.message}\n")
        }
    }
}

// ─── Команды ────────────────────────────────────────────────────

private fun handleGoto(target: String, m: TaskMachine) {
    val stage = runCatching { Stage.valueOf(target.uppercase()) }.getOrNull()
    if (stage == null) { println(">>> Неверная стадия. Возможные: ${Stage.values().joinToString()}\n"); return }
    val newState = m.goto(stage, trigger = "/goto $target")
    println(">>> Переход выполнен: ${newState.describe()}")
    printStatus(m)
}

private fun setGateAndStatus(m: TaskMachine, name: String, value: Boolean, trigger: String) {
    val ok = m.setGate(name, value, trigger)
    if (!ok) println(">>> Неизвестный гейт: $name\n") else printStatus(m)
}

private fun handleAdvance(m: TaskMachine, llm: LlmClient, history: MutableList<Message>) {
    val current = m.state
    val next: TaskState = when (current) {
        is TaskState.Planning -> when (current.step) {
            PlanningStep.DEFINE_GOAL       -> current.copy(step = PlanningStep.LIST_REQUIREMENTS)
            PlanningStep.LIST_REQUIREMENTS -> current.copy(step = PlanningStep.DRAFT_PLAN)
            PlanningStep.DRAFT_PLAN        -> current.copy(step = PlanningStep.CONFIRM_PLAN)
            PlanningStep.CONFIRM_PLAN      -> { println(">>> В PLANNING.CONFIRM_PLAN /advance не идёт. Используй /approve-plan + /goto execution.\n"); return }
        }
        is TaskState.Execution -> {
            if (current.isLastStep) {
                // Авто-выставляем executionComplete и предлагаем перейти
                m.setGate("executionComplete", true, "/advance (auto)")
                println(">>> Все шаги EXECUTION выполнены. Гейт executionComplete=true. Используй /goto validation.\n")
                return
            } else {
                // Симулируем выполнение шага (в реале — вызов LLM)
                val stepText = current.plan[current.currentIdx]
                val (reply, _) = llm.chat(buildSystemPrompt(m), history + Message("user", "Выполни шаг: $stepText"))
                history.add(Message("user", "[/advance] $stepText"))
                history.add(Message("assistant", reply))
                println("\nAgent: $reply\n")
                current.copy(currentIdx = current.currentIdx + 1, results = current.results + reply.take(200))
            }
        }
        is TaskState.Validation -> when (current.step) {
            ValidationStep.VERIFY_CRITERIA -> current.copy(step = ValidationStep.ASK_FEEDBACK)
            ValidationStep.ASK_FEEDBACK    -> { println(">>> В VALIDATION.ASK_FEEDBACK /advance не идёт. Используй /validate-ok + /goto done.\n"); return }
        }
        TaskState.Done -> { println(">>> Уже DONE.\n"); return }
    }
    m.advance(next, "/advance")
    printStatus(m)
}

private fun handleUserMessage(input: String, m: TaskMachine, llm: LlmClient, history: MutableList<Message>) {
    if (m.paused) { println(">>> На паузе.\n"); return }
    val systemPrompt = buildSystemPrompt(m)
    val (reply, usage) = llm.chat(systemPrompt, history + Message("user", input))
    history.add(Message("user", input))
    history.add(Message("assistant", reply))
    println("\nAgent: $reply")
    println("  [↑${usage.prompt_tokens} / ↓${usage.completion_tokens}]\n")

    // PLANNING: если в DRAFT_PLAN LLM выдала список — записываем в draftPlan и идём в CONFIRM_PLAN
    val s = m.state as? TaskState.Planning ?: return
    if (s.step == PlanningStep.DRAFT_PLAN) {
        val plan = parseNumberedList(reply)
        if (plan.size >= 2) {
            m.advance(s.copy(draftPlan = plan, step = PlanningStep.CONFIRM_PLAN), "auto_parse_plan")
            println(">>> План распарсен (${plan.size} шагов). Команда /approve-plan + /goto execution для перехода.\n")
        }
    } else if (s.step == PlanningStep.DEFINE_GOAL && s.goal == null) {
        m.advance(s.copy(goal = input, step = PlanningStep.LIST_REQUIREMENTS), "user_goal")
    } else if (s.step == PlanningStep.LIST_REQUIREMENTS) {
        val items = input.split("\n", ";").map { it.trim() }.filter { it.isNotEmpty() }
        if (items.isNotEmpty()) {
            m.advance(s.copy(requirements = s.requirements + items), "user_reqs")
        }
    }
}

private fun parseNumberedList(text: String): List<String> {
    val re = Regex("""^\s*\d+[.)]\s+(.+)$""", RegexOption.MULTILINE)
    return re.findAll(text).map { it.groupValues[1].trim() }.toList()
}

// ─── Печать ─────────────────────────────────────────────────────

private fun printStatus(m: TaskMachine) {
    println()
    println("┌─ Состояние задачи ──────────────────────────────────")
    if (m.paused) println("│  ⏸  PAUSED")
    println("│  Стейдж: ${m.state.describe()}")
    println("│  Гейты:  ${m.gates.describe()}")
    val available = m.availableTransitions()
    println("│  Доступно: ${if (available.isEmpty()) "(нет)" else available.joinToString { "${it.from}→${it.to}" }}")
    println("└─────────────────────────────────────────────────────")
    println()
}

private fun printTransitions(m: TaskMachine) {
    println()
    println("── Все правила переходов из ${m.state.stage} ──")
    val all = TransitionTable.allFrom(m.state.stage)
    val available = m.availableTransitions().toSet()
    all.forEach { rule ->
        val mark = if (rule in available) "✅" else "🚫"
        println("  $mark ${rule.from} → ${rule.to}")
        println("      guard: ${rule.guardName}")
    }
    println()
    println("Запрещены прямо сейчас (guard не пройден): ${m.blockedTransitions().size}")
    println("Текущие гейты: ${m.gates}")
    println()
}

private fun printPlan(m: TaskMachine) {
    val plan = when (val s = m.state) {
        is TaskState.Planning  -> s.draftPlan
        is TaskState.Execution -> s.plan
        is TaskState.Validation -> s.plan
        TaskState.Done -> emptyList()
    }
    if (plan.isEmpty()) { println(">>> План пуст.\n"); return }
    println("\n── План ──")
    plan.forEachIndexed { i, st -> println("  ${i + 1}. $st") }
    println()
}

private fun printHistory(repo: Repository) {
    val events = repo.readEvents(20)
    if (events.isEmpty()) { println(">>> История пуста.\n"); return }
    println("\n── События ──")
    events.forEach { ev ->
        val t = ev.ts.substringAfter("T").substringBefore(".").substringBeforeLast(":")
        val tag = when (ev.type) {
            EventType.TRANSITION         -> "→   "
            EventType.TRANSITION_BLOCKED -> "🚫  "
            EventType.GATE_FLIP          -> "⚙   "
            EventType.PAUSE              -> "⏸   "
            EventType.RESUME             -> "▶   "
        }
        val route = if (ev.fromStage != null && ev.toStage != null && ev.fromStage != ev.toStage) "${ev.fromStage}→${ev.toStage}" else (ev.fromStage ?: "")
        println("  $t  $tag${route.padEnd(20)} ${ev.trigger}")
        ev.reason?.let { println("                           ⓘ ${it.take(120)}") }
    }
    println()
}

fun buildSystemPrompt(m: TaskMachine): String = buildString {
    appendLine("Ты ассистент-разработчик, работающий по строгому конечному автомату:")
    appendLine("  PLANNING → EXECUTION → VALIDATION → DONE")
    appendLine("Никаких прыжков. Из EXECUTION можно вернуться в EXECUTION (следующий шаг),")
    appendLine("или дальше в VALIDATION (только если executionComplete=true).")
    appendLine("В DONE — только из VALIDATION при validationPassed=true.")
    appendLine()
    if (m.paused) {
        appendLine("⏸ Машина на паузе. Пользователь только что вернулся — кратко напомни, где остановились по данным ниже, и подожди /resume.")
        appendLine()
    }
    appendLine("== СОСТОЯНИЕ ==")
    appendLine("Стейдж: ${m.state.describe()}")
    appendLine("Гейты: ${m.gates}")
    when (val s = m.state) {
        is TaskState.Planning -> {
            s.goal?.let { appendLine("Цель: $it") }
            if (s.requirements.isNotEmpty()) appendLine("Требования: ${s.requirements.joinToString("; ")}")
            if (s.draftPlan.isNotEmpty()) {
                appendLine("План:")
                s.draftPlan.forEachIndexed { i, st -> appendLine("  ${i + 1}. $st") }
            }
        }
        is TaskState.Execution -> {
            appendLine("План:")
            s.plan.forEachIndexed { i, st ->
                val mark = if (i < s.currentIdx) "✓" else if (i == s.currentIdx) "→" else " "
                appendLine("  $mark ${i + 1}. $st")
            }
        }
        is TaskState.Validation -> {
            appendLine("Шаги выполнены. Подшаг: ${s.step}")
        }
        TaskState.Done -> appendLine("Задача завершена.")
    }
    appendLine()
    appendLine("Действуй строго в рамках текущего стейджа. Не предлагай переходов, которые гейты не позволяют.")
}
