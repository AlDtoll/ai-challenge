import java.io.File

/**
 * Конфиг ассистента поддержки. По умолчанию всё локально.
 * Данные и FAQ — в `data/` рядом с модулем.
 */
data class Config(
    val dataDir: File,             // папка с users.json / tickets.json / faq/**
    val indexPath: File,           // кэш RAG-индекса
    val ollamaHost: String,        // Ollama для эмбеддингов (опционально)
    val embedModel: String,        // модель эмбеддингов
    val deepseekKey: String,
    val deepseekModel: String,
    val mcpPort: Int,              // порт MCP-сервера поддержки
    val topK: Int,
    val chunkSize: Int,
    val chunkOverlap: Int,
    val useOllama: Boolean,        // если false — BM25 fallback (день работоспособен без Ollama)
)

fun parseArgs(args: Array<String>): Config {
    fun env(name: String) = System.getenv(name)?.takeIf { it.isNotBlank() }

    var dataDir = File("data")
    var indexPath: File? = null

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--data" -> { dataDir = File(args[++i]) }
            "--index" -> { indexPath = File(args[++i]) }
            "--help", "-h" -> { printHelp(); kotlin.system.exitProcess(0) }
            else -> { System.err.println("unknown arg: ${args[i]}"); printHelp(); kotlin.system.exitProcess(2) }
        }
        i++
    }

    return Config(
        dataDir = dataDir.absoluteFile.normalize(),
        indexPath = indexPath ?: File("faq-index.json"),
        ollamaHost = env("OLLAMA_HOST") ?: "http://127.0.0.1:11434",
        embedModel = env("EMBED_MODEL") ?: "nomic-embed-text",
        deepseekKey = env("DEEPSEEK_API_KEY") ?: readDotEnvKey("DEEPSEEK_API_KEY") ?: "",
        deepseekModel = env("DEEPSEEK_MODEL") ?: "deepseek-chat",
        mcpPort = env("MCP_PORT")?.toInt() ?: 3003,
        topK = env("TOP_K")?.toInt() ?: 3,
        chunkSize = env("CHUNK_SIZE")?.toInt() ?: 700,
        chunkOverlap = env("CHUNK_OVERLAP")?.toInt() ?: 120,
        useOllama = env("USE_OLLAMA")?.equals("true", ignoreCase = true) ?: true,
    )
}

fun printHelp() = println(
    """
    Day 33 — Ассистент поддержки пользователей (MCP + RAG над FAQ + DeepSeek)

    Usage: gradle :week7:day3:run [--args="[flags]"]
      --data <path>           папка с users.json / tickets.json / faq/**; default: data/
      --index <path>          кэш индекса FAQ; default: faq-index.json

    Env:
      DEEPSEEK_API_KEY        ключ DeepSeek (обязательно)
      OLLAMA_HOST             http://127.0.0.1:11434 (для nomic-embed)
      EMBED_MODEL             nomic-embed-text
      USE_OLLAMA              true | false (при false — BM25 fallback)
      DEEPSEEK_MODEL          deepseek-chat
      MCP_PORT                3003

    REPL:
      /ask <ticket_id> <вопрос>  — ответить на вопрос с учётом тикета (main use case)
      /faq <вопрос>              — просто ответ по FAQ (без тикета)
      /reindex                   — пересобрать индекс FAQ
      /list                      — показать все тикеты
      /quit                      — выйти
    """.trimIndent(),
)

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
