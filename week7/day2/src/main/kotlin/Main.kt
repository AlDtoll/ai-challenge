import java.io.File
import kotlin.system.exitProcess

/**
 * Day 32 — AI-ревью PR.
 *
 * Пайплайн (см. .github/workflows/pr-review.yml):
 *   1. GitHub Action на pull_request получает PR-diff → сохраняет в pr.diff и changed_files.txt.
 *   2. Запускает `gradle :week7:day2:run --args="--diff pr.diff --changed changed_files.txt"`.
 *   3. Этот main читает diff, строит BM25 индекс над README + docs, дёргает DeepSeek.
 *   4. Ревью печатается в stdout — workflow заберёт и запостит комментарием на PR.
 */
fun main(args: Array<String>) {
    val cfg = parseArgs(args)

    if (!cfg.diffPath.exists()) {
        System.err.println("Diff-файл не найден: ${cfg.diffPath}")
        exitProcess(2)
    }
    if (cfg.deepseekKey.isBlank()) {
        System.err.println("DEEPSEEK_API_KEY пуст. В CI прокинуть через secrets.DEEPSEEK_API_KEY.")
        exitProcess(2)
    }

    val diffRaw = cfg.diffPath.readText(Charsets.UTF_8)
    val diff = if (diffRaw.length > cfg.maxDiffChars) {
        diffRaw.substring(0, cfg.maxDiffChars) + "\n… [diff обрезан на ${cfg.maxDiffChars} символах]"
    } else diffRaw

    val changedFiles: List<String> = cfg.changedFilesPath?.takeIf { it.exists() }
        ?.readLines(Charsets.UTF_8)
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?: emptyList()

    // --- RAG ---
    System.err.println("Собираю BM25 индекс над ${cfg.repoRoot.absolutePath} …")
    val chunks = collectDocChunks(cfg.repoRoot)
    System.err.println("Индексировано ${chunks.size} чанков")
    val index = Bm25Index(chunks)

    // Запрос — заголовки diff'a + список изменённых файлов + первые ~2K символов diff.
    // Заголовки часто содержат имена классов/файлов → BM25 находит родственные разделы доки.
    val query = buildString {
        append(changedFiles.joinToString("\n"))
        append("\n")
        append(diff.take(2000))
    }
    val topDocs = index.topK(query, cfg.topK)
    val ragContext = if (topDocs.isEmpty()) "(нет релевантной документации)"
    else topDocs.joinToString("\n\n") { (chunk, score) ->
        "[${chunk.source}] (BM25=${"%.2f".format(score)})\n${chunk.text}"
    }

    // --- LLM ---
    val system = """
        Ты — старший инженер, делаешь ревью Pull Request'а.
        Твоя аудитория — автор PR. Отвечай кратко, конкретно, инженерно.
        На русском языке. Никаких общих фраз ("хороший PR!", "молодец").

        Проанализируй diff и укажи:
        1) 🐛 ПОТЕНЦИАЛЬНЫЕ БАГИ — конкретные строки, что не так, почему упадёт.
        2) 🏗️ АРХИТЕКТУРНЫЕ ПРОБЛЕМЫ — нарушения слоёв, дублирование, скрытые связи.
        3) 💡 РЕКОМЕНДАЦИИ — конкретно что улучшить (не абстрактно).

        Если в какой-то категории нечего сказать — так и пиши: «нет замечаний».

        Опирайся на DOCS-контекст ниже как на «канон проекта» — если PR нарушает
        соглашения проекта, обязательно укажи.
        Не выдумывай факты про код, которых нет в diff'е.
    """.trimIndent()

    val user = buildString {
        append("=== Изменённые файлы (${changedFiles.size}) ===\n")
        append(if (changedFiles.isEmpty()) "(не переданы)" else changedFiles.joinToString("\n"))
        append("\n\n=== DOCS-контекст (RAG top-${cfg.topK} из README/docs/**) ===\n")
        append(ragContext)
        append("\n\n=== DIFF ===\n```\n")
        append(diff)
        append("\n```\n")
    }

    val llm = DeepSeekClient(cfg.deepseekKey, cfg.deepseekModel)
    val t0 = System.currentTimeMillis()
    val review = try {
        llm.chat(listOf(DeepSeekClient.Msg("system", system), DeepSeekClient.Msg("user", user)))
    } catch (e: Exception) {
        System.err.println("Ошибка DeepSeek: ${e.message}")
        exitProcess(3)
    }
    val wall = System.currentTimeMillis() - t0
    System.err.println("DeepSeek ответил за $wall мс (top-${topDocs.size} RAG чанков)")

    // --- Вывод для workflow'а ---
    println("## 🤖 AI-ревью (день 32, RAG+DeepSeek)")
    println()
    println(review)
    if (topDocs.isNotEmpty()) {
        println()
        println("---")
        println("<sub>Контекст ревью: " + topDocs.joinToString(", ") { it.chunk.source } + "</sub>")
    }
}
