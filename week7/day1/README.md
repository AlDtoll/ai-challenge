# День 31 — Ассистент разработчика (RAG + MCP + REPL)

Модуль `:week7:day1`. REPL-ассистент, отвечающий на вопросы о самом проекте `ai-challenge`.

## Из чего он состоит

1. **RAG-индекс** над `README.md`, `CLAUDE.md`, `MEMORY.md` и всем `docs/**/*.md` проекта-донора.
   Эмбеддинги — Ollama `nomic-embed-text`, поиск — cosine top-K (по умолчанию 3).
2. **Свой MCP-сервер** (Streamable HTTP на `127.0.0.1:3001`) с git-инструментами:
   - `get_current_branch` — `git rev-parse --abbrev-ref HEAD`
   - `git_status` — `git status --short`
   - `git_log` — `git log --oneline -N` (limit параметр, 1..100)
3. **Агент = MCP-клиент**: подключается к собственному серверу, вызывает инструменты.
4. **LLM** — DeepSeek (`deepseek-chat`), температура 0.2, max_tokens 512.

Все компоненты в одном процессе — MCP-сервер поднимается фоново, REPL подключается клиентом.

## Как запустить

Ключ DeepSeek — в корне репо `.env`:
```
DEEPSEEK_API_KEY=sk-...
```

Ollama с моделью embed должна крутиться локально:
```
ollama pull nomic-embed-text
ollama serve
```

Запуск (Windows PowerShell):
```powershell
chcp 65001
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
.\gradlew.bat :week7:day1:run
```

По умолчанию проект-донор — корень репо (`../../` относительно модуля). Индекс кладётся в `week7/day1/index.json`. При первом запуске ассистент сам сделает `ingest`.

## Команды REPL

- `/help <вопрос>` — задать вопрос ассистенту. Ассистент всегда дёргает `get_current_branch`; если в вопросе есть «файл/status/измен/грязн» — ещё и `git_status`; «коммит/недавн/истори» — ещё и `git_log`. Плюс RAG top-3 по README+docs+CLAUDE.
- `/reindex` — пересобрать индекс (если поменял docs).
- `/quit` — выйти.

## Примеры

```
>>> /help что делает модуль week4 day2?
```
Ассистент ответит по RAG-контексту из README/docs и укажет источник `[S1]`.

```
>>> /help на какой я ветке и что сейчас изменено?
```
Дёрнет `get_current_branch` + `git_status`, ответит по live-состоянию репо.

## Флаги / env

- `--project <path>` — сменить проект-донор.
- `--ingest <path>` — построить индекс и выйти (batch-mode).
- `--index <path>` — путь к `index.json`.
- Env: `DEEPSEEK_API_KEY`, `DEEPSEEK_MODEL`, `OLLAMA_HOST`, `EMBED_MODEL`, `MCP_PORT`, `TOP_K`, `CHUNK_SIZE`, `CHUNK_OVERLAP`.

## Что «нового» по сравнению с прошлыми неделями

- Впервые собран **RAG + MCP + LLM в единый REPL** — не отдельные упражнения.
- MCP-инструмент теперь про **сам процесс разработки** (git-состояние), а не про внешние API — это как раз про «ассистент разработчика».
- Router: набор вызываемых MCP-tools зависит от вопроса (не всегда полный сет).
