# Наш стек

## Языки и библиотеки

Основной язык — Kotlin. Основные версии: 2.1.0 для недель 1–3 (плагин `kotlin("jvm") version "2.1.0"`), 2.1.20 на неделе 4 (нужно для MCP SDK 0.9.0 + Ktor 3.2.3), 2.2.21 для дня 17 (MCP SDK 0.13.0) и следующих. Root-`build.gradle.kts` теперь объявляет плагин через `kotlin("jvm") version "2.2.21" apply false`, а модули просто пишут `kotlin("jvm")` без версии.

Из HTTP-библиотек мы принципиально используем **`java.net.http.HttpClient`** (встроенный в JDK 11+). Никаких Retrofit, никакого OkHttp. JSON парсим Gson (`com.google.code.gson:gson:2.11.0`).

## Ключи и `.env`

Ключи API храним в файле `.env` в корне проекта (в `.gitignore`, не пушим). Содержимое:

```
TELEGRAM_BOT_TOKEN=...
DEEPSEEK_API_KEY=...
OPENROUTER_API_KEY=...
```

`OPENROUTER_API_KEY` — для дня 5 (сравнение моделей через OpenRouter), `DEEPSEEK_API_KEY` — почти для всех остальных дней. `TELEGRAM_BOT_TOKEN` — для дней 6+ (Agent + Telegram-интерфейс).

## Модели

- **DeepSeek Chat** (`deepseek-chat`) через `https://api.deepseek.com/chat/completions` — основная рабочая модель, поддерживает function-calling в формате OpenAI (проверено на day20).
- **OpenRouter** — использовался в day5 для сравнения моделей + судья.
- **Ollama** (локально) — используется с дня 22 для эмбеддингов (`nomic-embed-text`, 768-мерный вектор).
