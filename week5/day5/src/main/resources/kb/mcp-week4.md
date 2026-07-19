# Неделя 4 — MCP (детали)

Model Context Protocol — способ подключения инструментов и внешних источников к LLM-агенту. Стандартизирован Anthropic, использует JSON-RPC 2.0. Транспорты: **stdio** (локальный subprocess, чаще для CLI) и **Streamable HTTP** (удалённый сервер).

## День 16 (week4/day1) — клиент к DeepWiki

Kotlin MCP-клиент, коннект к публичному no-auth MCP `https://mcp.deepwiki.com/mcp` по Streamable HTTP, `listTools()` для discovery. Стек: `io.modelcontextprotocol:kotlin-sdk:0.9.0` + `io.ktor:ktor-client-cio:3.2.3`.

## День 17 (week4/day2) — свой MCP-сервер

Свой MCP-сервер на `localhost:3001` со Streamable HTTP. Инструмент `get_forecast(city)` — обёртка над погодным API. Клиент и сервер в одном main-процессе (`embeddedServer.start(wait=false) → delay → client`).

**Важный нюанс:** Open-Meteo из РФ не открывается (connect timeout, проверено с VPS). Инструмент переключён на **wttr.in** — `https://wttr.in/{lat},{lon}?format=j1` + User-Agent `curl` (обязателен, иначе HTML). Работает из РФ.

## День 18 (week4/day3) — планировщик + фон

Фоновая корутина каждые 8 секунд собирает погоду в JSONL-журнал `~/.ai-challenge/day18_weather.jsonl` (append, переживает рестарт). MCP-сервер отдаёт `start_watch` / `get_summary` / `stop_watch`. Стек: `kotlinx-coroutines-core:1.10.2`. Хранение JSONL, без SQLite.

## День 19 (week4/day4) — pipeline

Три инструмента: `search(query)` (Wikipedia ru REST API), `summarize(text)` (DeepSeek chat), `save_to_file(content, filename)`. Агент гоняет цепочку `search → summarize → save_to_file`, передавая данные явно.

## День 20 (week4/day5) — оркестрация

Три MCP-сервера на разных портах (`knowledge:3010`, `weather:3011`, `files:3012`). Агент подключается ко всем, собирает общий реестр инструментов с namespace `server__tool`, отдаёт их DeepSeek через **function-calling** (формат OpenAI: `tools[]` + `tool_choice`). Модель сама выбирает инструмент, агент маршрутизирует по namespace.
