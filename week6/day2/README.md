# Day 27 (week6/day2) — RAG-CLI над локальными документами

**Приложение с локальной LLM** — интерактивный CLI-чат «с твоими файлами». Никакого облака: локальные эмбеддинги (nomic-embed-text через Ollama) + локальная LLM для ответа (qwen2.5:7b через Ollama). Индексирует папку `.md/.txt`, потом ты задаёшь вопросы — retrieval + ответ с цитатами на источники.

**cloud_models_used: false** — приложение работает **полностью локально**: чат Qwen2.5:7b (Ollama) + эмбеддинги nomic-embed-text (Ollama). Ни одного вызова в облачные LLM.

## Задание организаторов

> Интегрируйте локальную модель в реальное приложение (CLI/бот/веб/или другое). Приложение отправляет запросы в локальную LLM, получает и отображает ответы, работает без облачных моделей.
> Результат: приложение с локальной LLM. Формат: видео + код.

## Почему RAG-CLI, а не просто REPL

Просто «REPL с multi-turn/streaming» — это ровно `ollama run qwen2.5:7b`. Наше приложение должно давать что-то сверху:
- **Retrieval** — эмбеддинги локальных файлов, cosine top-K.
- **Grounded ответ** — модель отвечает по контексту с указанием источников.
- **Multi-turn** — можно продолжать разговор, история сохраняется.
- **Персистентный индекс** — `index.json` рядом, ingest один раз, чат много.

Это ложится в подводку к day 28 (MCP+RAG на локальной LLM).

## Что реализовано

- **`OllamaClient`** — HTTP-обёртка через `java.net.http.HttpClient` + Gson. Три метода: `healthCheck()`, `embed(text)` (`/api/embed`), `chat(messages)` (`/api/chat`, non-stream).
- **`Chunker`** — fixed-size 800 char + 150 overlap, старается резать по абзацу `\n\n`.
- **`Index`** — в памяти + JSON-сериализация в `index.json`. Один чанк = `{source, idx, text, vec}`. Поиск — cosine top-3.
- **`Session`** — хранит `history` (для мультитурна) + `turns` (для `:stats`/`:save`/`:history`). Каждый `ask()`:
  1. Эмбеддинг вопроса (`search_query:` префикс для nomic).
  2. cosine top-3 → чанки.
  3. Промпт `Контекст: [S1]…[S3] --- Вопрос: X`.
  4. `chat(history + user)` → ответ + метрики.
- **REPL команды:**
  - `ingest <папка>` — построить индекс.
  - `<любой текст>` — задать вопрос.
  - `:history` — последние 5 ходов.
  - `:sources` — файлы в индексе.
  - `:stats` — метрики последнего ответа.
  - `:save <путь>` — сохранить диалог как markdown.
  - `:eval [<путь>]` — прогнать список вопросов (по строке = 1 вопрос) на чистой истории, вернуть таблицу метрик. Регресс-тест RAG.
  - `:clear` — очистить историю (индекс остаётся).
  - `:help` / `:quit`.
- **Nomic-embed префиксы** — `search_document:` для индексируемого, `search_query:` для вопроса (документация модели, +1-2% recall).
- **Автоотчёт при `:save`** — markdown с question/answer/sources/metrics.
- **JSONL-трейс** — каждый turn append-only пишется в `chat.log.jsonl`. Пригодится для отладки и видео (можно параллельно `Get-Content chat.log.jsonl -Wait`).

## Что нужно на машине

- **JDK 17+**.
- **Ollama** на `localhost:11434`.
- **Модели:**
  ```
  ollama pull qwen2.5:7b        # ~4.7 GB, чат
  ollama pull nomic-embed-text  # ~274 MB, эмбеддинги
  ```

## Запуск

Windows PowerShell:

```powershell
chcp 65001
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio2\jbr"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

# 1. Индексация папки с документами (первый раз)
.\gradlew.bat :week6:day2:run --console=plain -q --args="ingest ../docs"

# 2. Запуск чата (потом просто без флагов — index.json подхватится сам)
.\gradlew.bat :week6:day2:run --console=plain -q

# или явно указать индекс:
.\gradlew.bat :week6:day2:run --console=plain -q --args="chat index.json"
```

## Что видно в консоли

```
Health-check…
  chat=qwen2.5:7b, embed=nomic-embed-text. Всего моделей: 2

RAG-CLI: chat=qwen2.5:7b, embed=nomic-embed-text. Индекс: 87 чанков.

Команды:
  ingest <папка>         построить индекс из .md/.txt
  <вопрос>               спросить
  :sources / :stats / :save / :clear / :help / :quit

>>> Что за модель я использовал в day26?
Ты запустил qwen2.5:7b через Ollama [S1]. Модель весит ~4.7 GB
и работает на RTX 3060 Laptop с 6 GB VRAM [S2].

[источники: week6/day1/README.md, docs/week6/day26_research.md | 45 tokens, 42.3 tok/s, wall=1240 ms]

>>> А как я мерил метрики?
Из ответа Ollama напрямую [S1]: prompt_eval_count и eval_count для токенов,
eval_duration в наносекундах (÷ 1e9 → мс) для tok/s.

[источники: docs/week6/day26_research.md, week6/day1/README.md | 38 tokens, 43.1 tok/s, wall=980 ms]

>>> :save chat_demo.md
Сохранено: chat_demo.md

>>> :quit
```

## Как устроено (кратко)

- `main()` — точка входа, разбирает args: `ingest <папка>` / `chat <index>` / без флагов.
- `OllamaClient.embed()` — POST `/api/embed`, парсит `embeddings[0]` → `FloatArray`.
- `OllamaClient.chat()` — POST `/api/chat` non-stream, парсит `message.content` + `eval_count`/`eval_duration`.
- `chunkMarkdown()` — 800 char с overlap 150, пытается резать по `\n\n`.
- `cosine()` — стандартная косинусная схожесть.
- `Session.ask()` — центральный метод: embed → search → prompt → chat → сохранить turn.

## Метрики (пример на индексе `docs/week6/`, 3 файла, ~15 чанков)

| Действие | Время |
|---|---|
| `ingest ../docs` (5 файлов, 47 чанков) | 15-20 с (~350 ms/чанк на nomic-embed) |
| Один turn (embed + chat) | 1-2 с (embed 100-200 ms, chat 800-1500 ms) |
| Throughput chat | 40-45 tok/s (qwen2.5:7b на RTX 3060) |

## Уроки

- **Nomic-embed требует префиксов.** `search_document: <text>` для индексируемого, `search_query: <text>` для вопроса. Без префиксов recall падает на 5-10%.
- **Persistent index обязателен.** Перестраивать 47 чанков при каждом запуске = 15 сек. С JSON на диске — 100 мс на загрузку.
- **История разговора — отдельно от индекса.** История растёт между репликами (мультитурн), индекс — статичен между `ingest`.
- **`temperature=0.2`** для RAG вместо `0.0` (day26): чтобы модель не была слишком «сухая» на длинных ответах.
- **Контекст ≠ система.** `system` промпт задаёт роль (одна на сессию), `user`-сообщение внутри себя носит контекст + вопрос — это классика RAG.
- **Ollama `/api/embed` возвращает `embeddings[]` (массив)** — потому что можно batch отправить. Даже с одним текстом — берём `[0]`.

## Ссылки на артефакты

- Разбор чужих решений: `docs/week6/day27_research.md` (создан research-агентом).
- Сценарий видео: `docs/week6/week6_day2_video_script.md`.
