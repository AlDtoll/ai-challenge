import java.io.File
import java.sql.DriverManager

/**
 * Все источники данных для еженедельного отчёта. Каждая функция возвращает
 * компактную строку — то что уйдёт в промпт LLM. Никаких абстракций
 * ради абстракций — реальные команды, реальные логи.
 */

/** git log --oneline --since="7 days ago" по каждому репо. */
fun gitActivity(projects: List<File>, days: Int): String {
    if (projects.isEmpty()) return "(нет git-проектов)"
    return buildString {
        for (repo in projects) {
            val out = runCmd(repo, "git", "log", "--oneline", "--all", "--since", "$days days ago")
            val name = repo.name
            if (out.isBlank()) {
                append("• $name: (нет коммитов за $days дней)\n")
            } else {
                val lines = out.lines().filter { it.isNotBlank() }
                append("• $name (${lines.size} коммитов):\n")
                for (l in lines.take(15)) append("    $l\n")
                if (lines.size > 15) append("    … (+${lines.size - 15} ещё)\n")
            }
        }
    }.trimEnd()
}

/** Здоровье всех ботов на VPS: exit-коды за неделю, размер логов, живой ли процесс. */
fun botsHealth(sessionsDir: File, days: Int): String {
    if (!sessionsDir.isDirectory) return "(нет ~/sessions)"
    val ps = runCmd(File("."), "bash", "-c", "ps aux | grep -E 'telegram.sh|bot.py' | grep -v grep | wc -l").trim()
    return buildString {
        append("Работающих ботов сейчас: $ps процессов\n\n")
        val bots = sessionsDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()
        for (dir in bots) {
            val log = File(dir, "bot.log")
            if (!log.isFile) continue
            val exits = runCmd(File("."), "bash", "-c",
                "grep -E 'exited with code' '${log.absolutePath}' | tail -50 | " +
                    "awk -F'exited with code ' '{print \$2}' | awk '{print \$1}' | sort | uniq -c | sort -rn | head -5"
            ).trim()
            val sizeMb = log.length() / (1024 * 1024)
            append("• ${dir.name} (лог ~${sizeMb}MB)")
            if (exits.isNotBlank()) {
                append(":\n")
                for (l in exits.lines()) append("    $l\n")
            } else {
                append(": рестартов не видно\n")
            }
        }
    }.trimEnd()
}

/** SQLite quickai.db → топ проектов по потраченным «долларам»/токенам за неделю. */
fun quickaiTopProjects(db: File?, days: Int): String {
    if (db == null || !db.exists()) return "(quickai.db не найден)"
    val sinceMs = System.currentTimeMillis() - days.toLong() * 24 * 3600 * 1000
    val url = "jdbc:sqlite:${db.absolutePath}"
    return try {
        DriverManager.getConnection(url).use { conn ->
            conn.createStatement().use { st ->
                val rs = st.executeQuery("""
                    SELECT
                      COALESCE(cwd, 'unknown') AS cwd,
                      SUM(cost_usd) AS cost,
                      SUM(input_tokens + output_tokens + cache_read_input_tokens + cache_creation_input_tokens) AS toks,
                      COUNT(DISTINCT task_id) AS tasks
                    FROM tasks
                    WHERE first_ts >= $sinceMs
                    GROUP BY cwd
                    ORDER BY cost DESC
                    LIMIT 10
                """.trimIndent())
                buildString {
                    while (rs.next()) {
                        val cwd = (rs.getString("cwd") ?: "?").substringAfterLast("/").take(30)
                        val cost = rs.getDouble("cost")
                        val toks = rs.getLong("toks")
                        val tasks = rs.getInt("tasks")
                        append("• %-30s $%6.2f  %6d k токенов  %3d задач\n"
                            .format(cwd, cost, toks / 1000, tasks))
                    }
                }.trimEnd().ifBlank { "(за $days дней активности нет)" }
            }
        }
    } catch (e: Exception) { "quickai read error: ${e.message}" }
}

/** df -h / — заполненность диска. */
fun diskUsage(): String = runCmd(File("."), "df", "-h", "/").trim()

/** free -h — память. */
fun memoryUsage(): String = runCmd(File("."), "free", "-h").trim()

/** uptime + load. */
fun systemLoad(): String = runCmd(File("."), "uptime").trim()

/** Запуск subprocess-а, stdout возвращается trimmed. Ошибки не пробрасываются — вернём пусто, чтобы отчёт всё-равно составился. */
private fun runCmd(dir: File, vararg cmd: String): String {
    return try {
        val pb = ProcessBuilder(cmd.toList()).directory(dir).redirectErrorStream(true)
        val proc = pb.start()
        // читаем в фоне — избегаем deadlock на большом выхлопе (правило из day31 lessons)
        val output = StringBuilder()
        val reader = Thread {
            proc.inputStream.bufferedReader(Charsets.UTF_8).use { br ->
                val buf = CharArray(4096)
                while (true) {
                    val n = br.read(buf)
                    if (n < 0) break
                    if (output.length < 64 * 1024) output.append(buf, 0, n)
                }
            }
        }.apply { isDaemon = true; start() }
        proc.waitFor(); reader.join(500)
        output.toString().trim()
    } catch (e: Exception) { "cmd error: ${e.message}" }
}
