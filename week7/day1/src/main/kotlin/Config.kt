import java.io.File

/**
 * Конфиг ассистента: путь к проекту (donor), эндпоинты LLM/embed, тонкие настройки.
 * CLI-флаги и env-переменные с разумными дефолтами.
 */
data class Config(
    val projectDir: File,          // корень проекта, на который отвечает ассистент (README + docs)
    val indexPath: File,           // кэш RAG-индекса на диске
    val ollamaHost: String,        // локальная Ollama для nomic-embed
    val embedModel: String,        // модель эмбеддингов
    val deepseekKey: String,       // ключ DeepSeek (env DEEPSEEK_API_KEY)
    val deepseekModel: String,     // модель для чата
    val mcpPort: Int,              // порт нашего MCP git-сервера
    val topK: Int,                 // сколько RAG-чанков брать
    val chunkSize: Int,
    val chunkOverlap: Int,
    val ingestOnly: Boolean,       // --ingest: построить индекс и выйти
)

fun parseArgs(args: Array<String>): Config {
    fun env(name: String) = System.getenv(name)?.takeIf { it.isNotBlank() }

    // По умолчанию донор — сам ai-challenge (родитель модуля week7/day1).
    var projectDir = env("PROJECT_DIR")?.let { File(it) } ?: File("../../")
    var indexPath: File? = null
    var ingestOnly = false

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--project", "--project-dir" -> { projectDir = File(args[++i]) }
            "--index" -> { indexPath = File(args[++i]) }
            "--ingest" -> {
                projectDir = File(args[++i])
                ingestOnly = true
            }
            "--help", "-h" -> { printHelp(); kotlin.system.exitProcess(0) }
            else -> { System.err.println("unknown arg: ${args[i]}"); printHelp(); kotlin.system.exitProcess(2) }
        }
        i++
    }

    val absProject = projectDir.absoluteFile.normalize()

    return Config(
        projectDir = absProject,
        indexPath = indexPath ?: File("index.json"),
        ollamaHost = env("OLLAMA_HOST") ?: "http://127.0.0.1:11434",
        embedModel = env("EMBED_MODEL") ?: "nomic-embed-text",
        deepseekKey = env("DEEPSEEK_API_KEY") ?: readDotEnvKey("DEEPSEEK_API_KEY") ?: "",
        deepseekModel = env("DEEPSEEK_MODEL") ?: "deepseek-chat",
        mcpPort = env("MCP_PORT")?.toInt() ?: 3001,
        topK = env("TOP_K")?.toInt() ?: 3,
        chunkSize = env("CHUNK_SIZE")?.toInt() ?: 800,
        chunkOverlap = env("CHUNK_OVERLAP")?.toInt() ?: 150,
        ingestOnly = ingestOnly,
    )
}

fun printHelp() = println(
    """
    Day 31 — Ассистент разработчика (RAG + MCP + REPL)

    Usage: gradlew :week7:day1:run [--args="[flags]"]

      --project <path>        путь к проекту-донору (README + docs/**); default: ../../
      --ingest <path>         построить index.json и выйти
      --index <path>          где хранить/читать индекс; default: index.json

    Env:
      DEEPSEEK_API_KEY        ключ DeepSeek (обязательно; можно в .env)
      OLLAMA_HOST             http://127.0.0.1:11434 (для nomic-embed-text)
      EMBED_MODEL             nomic-embed-text
      DEEPSEEK_MODEL          deepseek-chat
      MCP_PORT                3001

    REPL:
      /help <вопрос>          спросить ассистента (RAG + git через MCP + DeepSeek)
      /reindex                перестроить индекс проекта
      /quit                   выйти
    """.trimIndent(),
)

/** Читает ключ из ../../.env (корень репо челленджа), если он там есть. Формат: KEY=VALUE. */
private fun readDotEnvKey(name: String): String? {
    val candidates = listOf(File("../../.env"), File("../.env"), File(".env"))
    for (f in candidates) {
        if (!f.exists()) continue
        f.forEachLine { line ->
            val t = line.trim()
            if (t.startsWith("$name=")) return t.substringAfter("=").trim().trim('"')
        }
    }
    return null
}
