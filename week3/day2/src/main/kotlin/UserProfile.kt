import com.google.gson.Gson
import java.io.File

data class UserProfile(
    val name: String = "User",
    val level: String = "middle",       // junior / middle / senior
    val style: String = "casual",       // casual / formal
    val format: String = "verbose",     // verbose / concise / bullets
    val language: String = "ru",        // ru / en
    val stack: String = "Kotlin",
    val constraints: List<String> = emptyList()
)

class ProfileManager(private val file: File) {
    private val gson = Gson()
    var profile: UserProfile = load()
        private set

    private fun load(): UserProfile =
        if (file.exists()) gson.fromJson(file.readText(), UserProfile::class.java) ?: UserProfile()
        else UserProfile()

    fun save() {
        file.parentFile?.mkdirs()
        file.writeText(gson.toJson(profile))
    }

    fun set(field: String, value: String): Boolean {
        profile = when (field) {
            "name"        -> profile.copy(name = value)
            "level"       -> profile.copy(level = value)
            "style"       -> profile.copy(style = value)
            "format"      -> profile.copy(format = value)
            "language"    -> profile.copy(language = value)
            "stack"       -> profile.copy(stack = value)
            "constraints" -> profile.copy(constraints = value.split(",").map { it.trim() }.filter { it.isNotEmpty() })
            else          -> return false
        }
        save()
        return true
    }

    fun update(updated: UserProfile) {
        profile = updated
        save()
    }

    fun print() {
        println("\n┌─ Профиль пользователя ─────────────────────────────")
        println("│  name:        ${profile.name}")
        println("│  level:       ${profile.level}  (junior / middle / senior)")
        println("│  style:       ${profile.style}  (casual / formal)")
        println("│  format:      ${profile.format}  (verbose / concise / bullets)")
        println("│  language:    ${profile.language}  (ru / en)")
        println("│  stack:       ${profile.stack}")
        println("│  constraints: ${profile.constraints.joinToString().ifEmpty { "—" }}")
        println("└────────────────────────────────────────────────────\n")
    }
}
