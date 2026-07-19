# День 33 — Ассистент поддержки пользователей

Модуль `:week7:day3`. REPL-ассистент, отвечающий на вопросы пользователей: тянет карточку тикета/пользователя из своего MCP-сервера, ищет по FAQ, отвечает через DeepSeek.

## Что внутри

1. **Свой MCP-сервер** (Streamable HTTP `:3003`) с 3 инструментами:
   - `get_ticket(id)` — карточка тикета
   - `get_user(id)` — карточка пользователя
   - `list_open_tickets(user_id)` — открытые тикеты пользователя
2. **«CRM»** — JSON-файлы в `data/`:
   - `users.json` — 3 пользователя (Данил, Саша, Юрий)
   - `tickets.json` — 4 тикета разной остроты (email confirmation, SSO, MCP auth, resolved)
3. **RAG над FAQ** (`data/faq/*.md`, 4 файла: auth, billing, mcp, editor):
   - Приоритет — Ollama `nomic-embed-text` + cosine (day31-style).
   - **Fallback — BM25 offline** (day32-style), автоматически если Ollama недоступна.
4. **REPL с 2 сценариями**:
   - `/ask <ticket_id> <вопрос>` — основной. Пример: `/ask t_501 почему не приходит письмо?`
     - MCP: `get_ticket(t_501)` → извлекаем `user_id`
     - MCP: `get_user(user_id)` + `list_open_tickets(user_id, 5)`
     - RAG по FAQ с комбинированным запросом (вопрос + JSON тикета)
     - DeepSeek с system prompt «оператор поддержки»
   - `/faq <вопрос>` — быстрый ответ без тикета.

## Как запустить

Локально (Windows):
```powershell
chcp 65001
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
.\gradlew.bat :week7:day3:run
```

Ключ DeepSeek — `.env` в корне репо: `DEEPSEEK_API_KEY=sk-…`.

Ollama желательна (запусти `ollama pull nomic-embed-text && ollama serve`), но не обязательна — если её нет, RAG сам переключится на BM25.

## Промпт-контракт

- Приоритет источников зашит в system prompt:
  1. данные тикета — верхний приоритет
  2. данные пользователя (тариф ограничивает доступ к фичам — важное правило)
  3. FAQ-фрагменты
  4. общие знания LLM — только в крайнем случае с пометкой «(предположение)»
- Формат ответа: (1) суть в одном предложении, (2) 2-4 шага пользователю, (3) когда эскалировать на инженера, (4) сноски [F1]/[F2]/[F3].

## Ограничения

- Данные — статические JSON, не живая CRM. Реальная CRM подключается заменой `SupportRepo` на клиент к Zendesk/Intercom API — MCP-tools остаются те же.
- Индекс FAQ пересобирается только по `/reindex`. При редактировании `data/faq/*.md` в live-режиме — вызвать команду.
- Нет памяти между репликами REPL. При многошаговом диалоге можно накапливать в `chatHistory: List<Msg>` — не реализовал ради простоты.

## Флаги / env

- `--data <path>` — папка с users/tickets/faq. Default: `data/`.
- `--index <path>` — путь к кэшу индекса. Default: `faq-index.json`.
- Env: `DEEPSEEK_API_KEY`, `DEEPSEEK_MODEL`, `OLLAMA_HOST`, `EMBED_MODEL`, `USE_OLLAMA=true|false`, `MCP_PORT=3003`, `TOP_K=3`.
