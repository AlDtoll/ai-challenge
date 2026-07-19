import com.google.gson.Gson
import java.io.File
import java.time.OffsetDateTime

// JSONL audit log событий guard'а — одна строка на каждое срабатывание.
// Поможет ретроспективно понять как ассистент применял инварианты.

enum class AuditEventType {
    PRE_BLOCK,         // pre-guard заблокировал запрос (regex match)
    POST_VIOLATION,    // LLM-judge нашёл нарушение в ответе
    RETRY,             // попытка переделать ответ
    OK                  // ответ прошёл guard
}

data class AuditEvent(
    val ts: String,
    val type: AuditEventType,
    val invariantId: String?,
    val invariantText: String?,
    val userMessage: String,
    val agentReply: String?,
    val explanation: String?
)

class AuditLog(private val file: File) {
    private val gson = Gson()

    fun append(event: AuditEvent) {
        file.parentFile?.mkdirs()
        file.appendText(gson.toJson(event) + "\n")
    }

    fun read(limit: Int = 20): List<AuditEvent> {
        if (!file.exists()) return emptyList()
        return file.readLines()
            .filter { it.isNotBlank() }
            .takeLast(limit)
            .mapNotNull { runCatching { gson.fromJson(it, AuditEvent::class.java) }.getOrNull() }
    }

    fun event(type: AuditEventType, userMessage: String, inv: Invariant? = null, reply: String? = null, explanation: String? = null): AuditEvent =
        AuditEvent(
            ts = OffsetDateTime.now().toString(),
            type = type,
            invariantId = inv?.id,
            invariantText = inv?.text,
            userMessage = userMessage.take(200),
            agentReply = reply?.take(200),
            explanation = explanation?.take(300)
        )
}
