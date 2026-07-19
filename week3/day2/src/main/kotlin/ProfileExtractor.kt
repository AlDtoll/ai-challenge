import com.google.gson.Gson
import com.google.gson.JsonObject

class ProfileExtractor(private val llm: LlmClient) {
    private val gson = Gson()

    fun extract(profile: UserProfile, userMessage: String, assistantReply: String): UserProfile? {
        val prompt = """
            Проанализируй сообщение пользователя и ответ ассистента.
            Определи, нужно ли обновить профиль пользователя на основе явных или неявных сигналов.

            Текущий профиль:
            - level: ${profile.level} (junior/middle/senior)
            - style: ${profile.style} (casual/formal)
            - format: ${profile.format} (verbose/concise/bullets)

            Сообщение пользователя: "$userMessage"

            Сигналы для изменения level:
            - "объясни попроще", "не понимаю" → junior
            - "коротко", "и так знаю основы" → middle или senior
            - "детали реализации", "под капотом" → senior

            Сигналы для изменения format:
            - "кратко", "в двух словах" → concise
            - "по пунктам", "списком" → bullets
            - "подробно", "с примерами" → verbose

            Верни JSON с полями которые нужно изменить, или пустой объект {} если ничего менять не нужно.
            Только JSON, без пояснений.
            Пример: {"level": "junior"} или {}
        """.trimIndent()

        return try {
            val (response, _) = llm.chat(prompt, emptyList())
            val json = response.trim().removePrefix("```json").removeSuffix("```").trim()
            val obj = gson.fromJson(json, JsonObject::class.java)
            if (obj.size() == 0) return null

            var updated = profile
            obj["level"]?.asString?.let { updated = updated.copy(level = it) }
            obj["style"]?.asString?.let { updated = updated.copy(style = it) }
            obj["format"]?.asString?.let { updated = updated.copy(format = it) }
            if (updated == profile) null else updated
        } catch (e: Exception) {
            null
        }
    }
}
