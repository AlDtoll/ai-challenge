# Persistent Context — история между рестартами + мониторинг окна

## What it improves

Обычный агент теряет всю историю при перезапуске процесса — пользователь начинает с нуля. Persistent context решает две задачи в одном слое: (1) сохраняет историю на диск по chatId и восстанавливает при рестарте, (2) мониторит процент заполнения контекстного окна и явно обрабатывает overflow-ошибку вместо необработанного краша. Агент переживает деплой, перебои питания, OOM-рестарт без потери диалога.

## When to use

- Production Telegram/Discord бот с долгосрочными пользователями
- Агент который решает задачи в несколько сессий («завтра продолжим»)
- Дорогие сессии (много контекста) — нельзя терять на рестарте
- Нужен мониторинг стоимости: сколько токенов/долларов потрачено по сессии

**Когда НЕ надо:** одноразовые скрипты, stateless API-обёртки, агенты где каждый запрос независим — диск пишется зря, а overhead реален.

## How to integrate

1. Создай `ContextStorage` — класс с `save(chatId, history)` и `load(chatId)` через JSON-файл `~/.ai-challenge/context_{chatId}.json`.
2. При каждом новом сообщении: загрузи историю → добавь → вызови LLM → добавь ответ → сохрани.
3. Считай текущий размер окна: `estimateTokens(history) / MODEL_MAX_TOKENS * 100` → показывай пользователю цветовую шкалу (зелёный/жёлтый/красный).
4. Оборачивай LLM-вызов в try/catch на `context_length_exceeded` ошибку → friendly-сообщение с предложением `/clear` или compression.
5. Добавь `/clear` команду — удаляет файл и сбрасывает историю в памяти.

## Working example (Kotlin)

```kotlin
import kotlinx.serialization.json.Json
import java.io.File

class ContextStorage(private val baseDir: String = System.getProperty("user.home") + "/.ai-challenge") {
    private val dir = File(baseDir).also { it.mkdirs() }

    fun save(chatId: String, history: List<Message>) {
        val file = File(dir, "context_${chatId}.json")
        file.writeText(Json.encodeToString(history))
    }

    fun load(chatId: String): MutableList<Message> {
        val file = File(dir, "context_${chatId}.json")
        if (!file.exists()) return mutableListOf()
        return Json.decodeFromString<List<Message>>(file.readText()).toMutableList()
    }

    fun delete(chatId: String) {
        File(dir, "context_${chatId}.json").delete()
    }
}

class PersistentAgent(
    private val llmClient: DeepSeekClient,
    private val systemPrompt: String,
    private val storage: ContextStorage = ContextStorage(),
    private val modelMaxTokens: Int = 128_000
) {
    // Стоимость DeepSeek V3 в USD/1M токен
    private val costPer1M = 0.27

    suspend fun chat(chatId: String, userMessage: String): String {
        val history = storage.load(chatId)

        if (userMessage == "/clear") {
            storage.delete(chatId)
            return "История очищена."
        }

        history.add(Message(role = "user", content = userMessage))

        val usedTokens = estimateTokens(history)
        val fillPercent = usedTokens * 100 / modelMaxTokens
        val indicator = when {
            fillPercent < 50 -> "[зелёный ${fillPercent}%]"
            fillPercent < 80 -> "[жёлтый ${fillPercent}%]"
            else             -> "[красный ${fillPercent}%] ⚠️ Скоро лимит"
        }

        val messages = buildList {
            add(Message(role = "system", content = systemPrompt))
            addAll(history)
        }

        val response = try {
            llmClient.chat(messages)
        } catch (e: Exception) {
            if ("context_length_exceeded" in (e.message ?: "")) {
                storage.delete(chatId)
                return "Контекст переполнен — история сброшена. Начни снова."
            }
            throw e
        }

        history.add(Message(role = "assistant", content = response))
        storage.save(chatId, history)

        val cost = usedTokens / 1_000_000.0 * costPer1M
        return "$response\n\n$indicator | \$${String.format("%.4f", cost)}"
    }

    private fun estimateTokens(history: List<Message>): Int =
        history.sumOf { it.content.length / 4 }
}
```

## Metrics

- **History restore success rate** — доля рестартов где история успешно восстановилась (цель: 100%; провал = проблема с JSON или правами на файл)
- **Overflow events per day** — сколько раз сработал context_length_exceeded; если > 0 → нужна compression или sliding window
- **Storage size per user** (bytes) — рост файла со временем; если > 1 MB на пользователя → ввести ротацию или compression
- **Session cost distribution** — 95-й перцентиль стоимости сессии; позволяет ставить бюджетный алерт

## Source

- **AI Challenge:** week2/day2 + week2/day3 — Персистентный контекст (disk storage) + Context Window & overflow handling
- **Артефакты:** https://github.com/AlDtoll/ai-challenge/tree/week2/day2/week2/day2 — `ContextStorage.kt`, `Agent.kt`; https://github.com/AlDtoll/ai-challenge/tree/week2/day3/week2/day3 — `Agent.kt`, `Main.kt`
- **Связано:** [`context_compression.md`](context_compression.md) — что делать когда контекст заполняется, а не только мониторить; [`../memory_layers/three_layer_memory.md`](../memory_layers/three_layer_memory.md) — более структурированный подход к персистентности
