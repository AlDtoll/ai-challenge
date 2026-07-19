import java.io.File

/**
 * Конфиг ассистента-ревьюера. В CI все значения приходят из env
 * (см. .github/workflows/pr-review.yml — DEEPSEEK_API_KEY и GITHUB_TOKEN там прокинуты).
 * Локально можно положить DEEPSEEK_API_KEY в .env корня репо (как в day31).
 */
data class Config(
    val repoRoot: File,           // корень проекта, над которым делается RAG
    val diffPath: File,           // где лежит diff (записывается workflow'ом перед запуском)
    val changedFilesPath: File?,  // список изменённых файлов (по одному в строке), может быть null
    val deepseekKey: String,      // ключ DeepSeek
    val deepseekModel: String,    // deepseek-chat
    val topK: Int,                // сколько RAG-чанков подмешивать в промпт
    val maxDiffChars: Int,        // огрубляем длинный diff — не отправлять всё в LLM
)

fun parseArgs(args: Array<String>): Config {
    fun env(name: String) = System.getenv(name)?.takeIf { it.isNotBlank() }

    var repoRoot = env("GITHUB_WORKSPACE")?.let { File(it) } ?: File(".").absoluteFile
    var diffPath = File("pr.diff")
    var changedFilesPath: File? = null

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--repo" -> { repoRoot = File(args[++i]) }
            "--diff" -> { diffPath = File(args[++i]) }
            "--changed" -> { changedFilesPath = File(args[++i]) }
            "--help", "-h" -> { printHelp(); kotlin.system.exitProcess(0) }
            else -> { System.err.println("unknown arg: ${args[i]}"); printHelp(); kotlin.system.exitProcess(2) }
        }
        i++
    }

    return Config(
        repoRoot = repoRoot.absoluteFile.normalize(),
        diffPath = diffPath.absoluteFile.normalize(),
        changedFilesPath = changedFilesPath?.absoluteFile?.normalize(),
        deepseekKey = env("DEEPSEEK_API_KEY") ?: readDotEnvKey(repoRoot, "DEEPSEEK_API_KEY") ?: "",
        deepseekModel = env("DEEPSEEK_MODEL") ?: "deepseek-chat",
        topK = env("TOP_K")?.toInt() ?: 3,
        maxDiffChars = env("MAX_DIFF_CHARS")?.toInt() ?: 20_000,
    )
}

fun printHelp() = println(
    """
    Day 32 — AI PR review (RAG + DeepSeek)

    Usage: gradle :week7:day2:run --args="--diff pr.diff [--changed changed_files.txt] [--repo <path>]"

      --diff <path>           файл с полным diff'ом PR (обычно pr.diff записывается workflow'ом)
      --changed <path>        (опц.) файл со списком изменённых путей, по одному в строке
      --repo <path>           корень репозитория; default: $GITHUB_WORKSPACE или .

    Env:
      DEEPSEEK_API_KEY        ключ DeepSeek (обязательно; в CI — из GitHub Secrets)
      DEEPSEEK_MODEL          deepseek-chat (default)
      TOP_K                   сколько RAG-фрагментов подмешать (default 3)
      MAX_DIFF_CHARS          обрезка diff'а (default 20000) — не отправлять километры в LLM

    Вывод: текст ревью на stdout (workflow заберёт и запостит комментарием на PR).
    """.trimIndent(),
)

private fun readDotEnvKey(repoRoot: File, name: String): String? {
    val candidates = listOf(
        File(repoRoot, ".env"),
        File("../../.env"), // локальный запуск из week7/day2/
        File(".env"),
    )
    for (f in candidates) {
        if (!f.exists()) continue
        // File.forEachLine — не inline, non-local return запрещён; используем for + readLines.
        for (line in f.readLines()) {
            val t = line.trim()
            if (t.startsWith("$name=")) return t.substringAfter("=").trim().trim('"')
        }
    }
    return null
}
