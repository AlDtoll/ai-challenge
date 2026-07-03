# Что уже сделано

## Неделя 1 — базовые API-вызовы

- **День 1** — первый запрос к DeepSeek API.
- **День 2** — форматирование ответа (system prompt, `max_tokens`, `stop`).
- **День 3** — четыре стратегии рассуждения на одном вопросе.
- **День 4** — сравнение `temperature` 0.0 / 0.7 / 1.2 на одинаковых промптах.
- **День 5** — сравнение моделей через OpenRouter + LLM-судья.

## Неделя 2 — Agent + Telegram

- **День 6 (week2/day1)** — Agent-класс + Telegram-интерфейс, консольный fallback если нет `TELEGRAM_BOT_TOKEN`.
- **День 7 (week2/day2)** — persistent context storage по `chat_id` в JSON, консольный режим использует `chatId=0L`.
- **День 8 (week2/day3)** — подсчёт токенов, таблица per turn, `context fill %`, команда `/fill`.

## Неделя 3 — архитектура агента

- **День 11 (week3/day1)** — три слоя памяти: short-term / working / long-term.
- **День 12 (week3/day2)** — персонализация: `UserProfile`, `ProfileManager` (JSON), `SystemPromptBuilder`, `ProfileExtractor` (авто-апдейт профиля из диалога), REPL с командами `/profile`, `/switch`.
- **День 13 (week3/day3)** — Task State Machine (`PLANNING → EXECUTION → VALIDATION → DONE`), `paused` флаг, audit log.
- **День 14 (week3/day4)** — инварианты с двухслойным guard + audit log.
- **День 15 (week3/day5)** — controlled transitions: декларативная таблица переходов + гейты, нелегальные переходы блокируются.

## Неделя 4 — MCP

- **День 16** — клиент к DeepWiki по Streamable HTTP.
- **День 17** — свой MCP-сервер `get_forecast(city)` + клиент, всё в одном процессе.
- **День 18** — планировщик + фон, JSONL-журнал, 24/7 работа.
- **День 19** — pipeline из трёх инструментов `search → summarize → save_to_file`.
- **День 20** — оркестрация трёх серверов через DeepSeek function-calling.

## Неделя 5 — RAG

- **День 21 (week5/day1)** — стратегии чанкинга (fixed vs structural) с SQLite и учебным эмбеддером BagOfWords; метрики Recall@3 и MRR.
- **День 22 (week5/day2)** — первый RAG-запрос: агент в двух режимах (с RAG / без RAG) + 10 контрольных вопросов и сравнение.
