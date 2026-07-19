# Инструменты вокруг челленджа

## Локальные

- **Ollama** — движок для локальных LLM/embedding-моделей на Windows. Ставится через `OllamaSetup.exe` с ollama.com. После установки поднимает фоновый сервис на `localhost:11434`. Модели тянуться командой `ollama pull <name>`. Для дня 22 используется `nomic-embed-text` (~137 MB, 768-мерный вектор, ~10-50 мс на текст на CPU).
- **`sheets-reader`** — скрипт для чтения комментариев участников в общей Google Sheets. Использует OAuth-токен, обновляемый через ручной ввод code из URL. Живёт на VPS в `~/tools/sheets-reader/`.

## Внешние

- **DeepSeek** — основная LLM для челленджа (`deepseek-chat`, поддерживает function-calling).
- **OpenRouter** — использовался в day5 для сравнения нескольких моделей + судья.
- **Wikipedia REST API** (`ru.wikipedia.org/api/rest_v1/...`) — доступен из РФ, использован в day19 для `search(query)`.
- **wttr.in** — простой weather API, доступен из РФ, использован в day17/day18 после падения Open-Meteo.
- **DeepWiki MCP** (`https://mcp.deepwiki.com/mcp`) — публичный no-auth MCP-сервер, использован в day16 для discovery `listTools`.

## Git-репо

- **Основное:** `https://github.com/AlDtoll/ai-challenge` — сам челлендж. Каждый день в своей ветке, main не трогаем.
- Локально: `~/ai-challenge/` на VPS (для написания кода) и `C:\D\Develop\ai-challenge` на Windows у Данила (для сборки/запуска).
