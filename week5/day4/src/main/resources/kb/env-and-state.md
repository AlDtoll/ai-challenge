# Файлы окружения и локальное состояние

## `.env` в корне

Все секреты и токены живут в файле `.env` в корне проекта:

```
TELEGRAM_BOT_TOKEN=<bot-token>
DEEPSEEK_API_KEY=<key>
OPENROUTER_API_KEY=<key>
```

Файл в `.gitignore` — никогда не пушится. Модули читают его самостоятельно (простой парсер `key=value`).

Для дня 22 нужен только `DEEPSEEK_API_KEY`. Ollama и `nomic-embed-text` — без ключей вообще.

## Локальное состояние в `~/.ai-challenge/`

Все дни, у которых есть персистентное состояние, пишут в папку `~/.ai-challenge/` (в домашнем каталоге пользователя, кроссплатформенно).

- **День 7:** `~/.ai-challenge/context_<chatId>.json` — история диалога по чату.
- **День 12:** `~/.ai-challenge/profile_<userId>.json` — профиль пользователя (интересы, навыки, стиль общения).
- **День 13/14/15:** `~/.ai-challenge/task_state.json` — состояние Task State Machine + audit log.
- **День 18:** `~/.ai-challenge/day18_weather.jsonl` — JSONL-журнал погодных замеров (append-only, переживает рестарт).
- **День 21:** SQLite-база чанков в `~/.ai-challenge/day21_chunks.db`.
- **День 22:** JSON-индекс в `~/.ai-challenge/day22_index.json` (чанки + эмбеддинги + метаданные источника).

Перед записью видео Данил часто чистит соответствующий файл (`Remove-Item ~\.ai-challenge\day22_index.json`), чтобы демонстрация была «с нуля».

## Windows-путь vs Unix-путь

В Kotlin используем `System.getProperty("user.home")` для кроссплатформенного пути. На Windows это `C:\Users\<user>`, на Linux — `/home/<user>`. Пример:

```
val stateDir = File(System.getProperty("user.home"), ".ai-challenge")
```
