import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

private val STORAGE_FILE = File(System.getProperty("user.home"), ".ai-challenge/context_day7.json")
private val gson = Gson()

fun loadHistory(chatId: Long, systemPrompt: String): MutableList<Message> {
    val all = readAll()
    val saved = all[chatId.toString()]
    if (!saved.isNullOrEmpty()) {
        println("[Context] Loaded ${saved.size} messages for chat $chatId")
        return saved.toMutableList()
    }
    return mutableListOf(Message("system", systemPrompt))
}

fun saveHistory(chatId: Long, history: List<Message>) {
    val all = readAll()
    all[chatId.toString()] = history
    STORAGE_FILE.parentFile.mkdirs()
    STORAGE_FILE.writeText(gson.toJson(all))
}

private fun readAll(): MutableMap<String, List<Message>> {
    if (!STORAGE_FILE.exists()) return mutableMapOf()
    return try {
        val type = object : TypeToken<MutableMap<String, List<Message>>>() {}.type
        gson.fromJson(STORAGE_FILE.readText(), type) ?: mutableMapOf()
    } catch (e: Exception) {
        mutableMapOf()
    }
}
