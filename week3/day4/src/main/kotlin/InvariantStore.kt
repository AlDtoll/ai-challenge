import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File

// Persistence для инвариантов. Один JSON — список всех инвариантов.
// Хранится ОТДЕЛЬНО от диалога (это важно: инварианты переживают любую сессию).

class InvariantStore(private val file: File) {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val listType = object : TypeToken<MutableList<Invariant>>() {}.type
    private var items: MutableList<Invariant> = load()

    private fun load(): MutableList<Invariant> {
        if (!file.exists()) return mutableListOf()
        return runCatching { gson.fromJson<MutableList<Invariant>>(file.readText(), listType) }
            .getOrDefault(mutableListOf()) ?: mutableListOf()
    }

    private fun persist() {
        file.parentFile?.mkdirs()
        file.writeText(gson.toJson(items))
    }

    fun all(): List<Invariant> = items.toList()

    fun byId(id: String): Invariant? = items.firstOrNull { it.id == id }

    fun add(inv: Invariant) {
        items.removeAll { it.id == inv.id }
        items.add(inv)
        persist()
    }

    fun remove(id: String): Boolean {
        val removed = items.removeAll { it.id == id }
        if (removed) persist()
        return removed
    }

    fun clear() {
        items.clear()
        persist()
    }

    fun size() = items.size
}
