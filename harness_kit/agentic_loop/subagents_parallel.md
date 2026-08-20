# Parallel Subagents — batch-выполнение независимых задач

## What it improves

Классический execution loop делает задачи последовательно: main-агент читает issue → генерирует код → verify → следующая. Для 15 issues это 15× serial time. **Параллельные субагенты** — main-агент запускает N задач одновременно (в фоне), получает результаты по мере готовности. При правильной изоляции задач и защите от race — 3-5× ускорение.

## When to use

- Batch: список независимых задач (issues, файлы, документы) — каждая требует своего агента, но между собой не пересекаются
- Каждая задача занимает 30 сек — 10 минут (короче — не окупается overhead запуска)
- Есть чёткий input/output контракт: файл-задача → отчёт-результат
- Ресурсы позволяют (LLM API rate limit, RAM, CPU для локальных LLM)

**Когда НЕ надо:** зависимые задачи (task B нужен результат task A), общий mutable state (файл который все правят), задачи короче 30 сек (overhead > выигрыш).

## How to integrate

1. **Определи изоляцию.** Каждый субагент — своя workspace / временная папка / отдельная git-ветка. Никаких shared state.
2. **Задачи как input-файлы.** Каждая — отдельный markdown/JSON с описанием и ссылками на нужные ресурсы.
3. **Batch launcher.** Главный агент читает список задач, для каждой запускает `Agent tool` с `run_in_background: true`.
4. **Ограничение параллелизма.** Claude Code Task tool держит максимум **5** одновременных субагентов. Больше — очередь.
5. **Collector.** Собери отчёты, сведи в сводный (закрытые / провалено / нуждается в ручном вмешательстве).
6. **Safety:** одна упавшая задача не должна валить весь batch. Верни статус каждой отдельно.

## Working example (Kotlin, псевдокод-обёртка для Claude Code Task API)

```kotlin
data class SubagentTask(
    val id: String,
    val prompt: String,
    val agentType: String = "builder",
    val outputFile: String  // куда субагент сохранит отчёт
)

class BatchExecutor(private val claudeAgent: TaskTool) {
    suspend fun runBatch(
        tasks: List<SubagentTask>,
        maxParallel: Int = 5
    ): Map<String, TaskResult> = coroutineScope {
        tasks.chunked(maxParallel).flatMap { chunk ->
            chunk.map { task ->
                async {
                    val handle = claudeAgent.launch(
                        subagentType = task.agentType,
                        prompt = task.prompt,
                        runInBackground = true
                    )
                    task.id to waitForCompletion(handle, task.outputFile)
                }
            }.awaitAll()
        }.toMap()
    }

    private suspend fun waitForCompletion(
        handle: TaskHandle,
        outputFile: String
    ): TaskResult {
        // poll status или подписка на task-notification
        while (!handle.isComplete()) delay(5_000)
        return when (handle.status()) {
            Status.SUCCESS -> TaskResult.Success(File(outputFile).readText())
            Status.FAILED  -> TaskResult.Failed(handle.errorMessage())
        }
    }
}
```

## Metrics

- **Wall time speedup:** 15 задач по 3 мин: serial = 45 мин, parallel (5 par) = ~9 мин → **5x**
- **Success rate:** доля задач в статусе SUCCESS — если резко упала при переходе serial→parallel, скорее всего пересечение по shared state
- **Cost per batch:** LLM tokens одинаковые в serial и parallel (та же работа), но wall time выигрыш даёт лучший feedback loop
- **Manual intervention rate:** сколько задач потребовали ручного вмешательства (target: <10% для well-defined batch)

## Source

- **AI Challenge:** week8/day5 — Execution Loop (15 issues, 11/15 закрыто автономно, 73% success)
- **Артефакты:** [`AlDtoll/zizz3` ветка с ai-challenge-advanced-day5](https://github.com/AlDtoll/zizz3) → `docs/ai_challenge_advanced_day5/` (batch reports 1-4, exec_loop_summary.md)
- **Связано:** week7/day4 (agentic loop с MAX_TOOL_ITER=12), week8/day2 (специализированные профили-агенты)
- **Claude Code CLI docs:** [Task tool](https://docs.claude.com/en/docs/claude-code/task-tool) — `subagent_type`, `run_in_background`
