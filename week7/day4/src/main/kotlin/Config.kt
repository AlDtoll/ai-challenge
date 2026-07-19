import java.io.File

data class Config(
    val projectDir: File,          // корень репо, над которым работает ассистент
    val deepseekKey: String,
    val deepseekModel: String,
    val mcpPort: Int,
    val maxToolIterations: Int,    // лимит на agentic loop
    val maxFileBytes: Int,         // защита от чтения гигантских файлов
    val writeAllowed: Boolean,     // dry-run режим — можно ли реально писать
)

fun parseArgs(args: Array<String>): Config {
    fun env(name: String) = System.getenv(name)?.takeIf { it.isNotBlank() }

    var projectDir = env("PROJECT_DIR")?.let { File(it) } ?: File(".").absoluteFile
    var writeAllowed = true

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--project", "--project-dir" -> { projectDir = File(args[++i]) }
            "--dry-run" -> { writeAllowed = false }
            "--help", "-h" -> { printHelp(); kotlin.system.exitProcess(0) }
            else -> { System.err.println("unknown arg: ${args[i]}"); printHelp(); kotlin.system.exitProcess(2) }
        }
        i++
    }

    return Config(
        projectDir = projectDir.absoluteFile.normalize(),
        deepseekKey = env("DEEPSEEK_API_KEY") ?: readDotEnvKey("DEEPSEEK_API_KEY") ?: "",
        deepseekModel = env("DEEPSEEK_MODEL") ?: "deepseek-chat",
        mcpPort = env("MCP_PORT")?.toInt() ?: 3004,
        maxToolIterations = env("MAX_TOOL_ITER")?.toInt() ?: 12,
        maxFileBytes = env("MAX_FILE_BYTES")?.toInt() ?: 200_000,
        writeAllowed = writeAllowed,
    )
}

fun printHelp() = println(
    """
    Day 34 — Ассистент работы с файлами (MCP file-tools + agentic loop)

    Usage: gradle :week7:day4:run [--args="[flags]"]
      --project <path>        корень проекта (default: cwd)
      --dry-run               НЕ применять write_file / apply_diff — только показать что бы сделал

    Env:
      DEEPSEEK_API_KEY        ключ DeepSeek (обязательно)
      DEEPSEEK_MODEL          deepseek-chat
      MCP_PORT                3004
      MAX_TOOL_ITER           12 (защита от бесконечных loop'ов)
      MAX_FILE_BYTES          200 000 (обрезка больших файлов)

    REPL:
      /where <symbol>         найти все использования символа/паттерна в проекте
      /doc <path>             сгенерировать/обновить документацию для файла или папки
      /adr <title>            создать новый ADR (Architecture Decision Record) из последних изменений
      /task <цель>            дать ассистенту свободную цель — сам решит какие tools дёрнуть
      /list_tools             показать доступные MCP tools
      /quit                   выйти

    Правило: цель ставим на уровне ЦЕЛИ, а не «открой файл X». Ассистент сам инициирует чтение/поиск/запись.
    """.trimIndent(),
)

private fun readDotEnvKey(name: String): String? {
    val candidates = listOf(File("../../.env"), File("../.env"), File(".env"))
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
