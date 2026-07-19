# Day 31 — Ассистент разработчика. Разбор решений участников

Дата: 2026-07-17

Автор задания: RAG (README+docs) + MCP (git_branch, файлы/diff по желанию) + REPL с `/help`.

## Список найденных сдач (топ-5 по релевантности)

| Автор                          | Репо                                                                                                | Стек                                          | Что уникально                                                                                                    |
|--------------------------------|-----------------------------------------------------------------------------------------------------|-----------------------------------------------|------------------------------------------------------------------------------------------------------------------|
| **sergio-rsd** («Ssh sh»)      | github.com/Sergio-rsd/DevAssistant `feature/tuning-agent`                                            | Kotlin JVM, свой JSON-RPC MCP                 | Свой MCP-стек с нуля (McpServer/McpConnection), гибрид SQLite FTS5 BM25 + косинус RRF, `!eval` из 10 контрольных вопросов, детерминированный голый `/help` |
| **shirobokov** («Николай»)     | github.com/ShirobokovNE/ai-challenge `day31`                                                        | Kotlin JVM, официальный `io.modelcontextprotocol.kotlin.sdk` | STDIO с subprocess на том же classpath, гибридный поиск (cosine + keyword + phrase/coverage/density buster), 3-слойная память STM/WM/LTM, LangChain4j Ollama эмбеддинги |
| **avalanche** (Anastasia)      | github.com/1Avalanche/SmartReminder `week7-day1`                                                    | Kotlin Multiplatform                          | HTTP + SSE (Streamable HTTP) MCP-транспорт, свой JsonRpcSerializer, режим `question` = RAG-поиск, DeepSeek V4 Pro / Qwen как облачный дефолт, локальные `qwen2.5:14b`/`gemma3:12b` через Ollama |
| **strixg** (Nikita Obrekht)    | github.com/StrixG/codeassistant `day31`                                                             | Python                                        | DeepSeek V4 Pro function-calling, ChromaDB persist на диск, локальные e5-small эмбеддинги, официальный MCP Python SDK (FastMCP stdio), `metrics.jsonl` с P50/P95/токенами/стоимостью, prompt-cache DeepSeek |
| **farmut** (Андрей Мачулин)    | github.com/farmut/ai-advent-challenge `Day31`                                                       | Go, Clean Architecture                        | Оркестратор саб-агентов по YAML-конфигу, spawn/route/finish + `ask_user` (human-in-the-loop), полноэкранный TUI (gotui/tcell), собственный git-mcp-server как отдельный Go-модуль |

Плюс несколько сдач более низкого приоритета (пропустил в детальном разборе): nikbrik (Go, `coding_writer`, свой MCP-клиент stdio) — стек далёкий от Kotlin; swanden (Go), farmut/dpmn для day-31 не выложены в main. **SergioPRM** (наш прошлый эталон Kotlin) остановился на day30, поэтому эталоном на day31 становится **sergio-rsd**.

---

## Разбор по деталям

### 1. sergio-rsd (наш новый эталон Kotlin)

Ссылка: <https://github.com/Sergio-rsd/DevAssistant/tree/feature/tuning-agent> (и «отчётная» ветка `feature/pr-review-pipeline` уже на day33).

**Что использовал:**

- **LLM:** два бэкенда — Ollama (`qwen2.5:14b`, дефолт) и облако через OpenAI-совместимый роутер (`routerai.ru`), выбор по меню при каждом запуске. Ollama — без нативного tool_use, инструменты выбирает эвристика по ключам. Облако — полноценный tool_use цикл.
- **RAG:** SQLite (chunks + FTS5 `chunks_fts` виртуальная таблица) + гибридный поиск. Cosine по эмбеддингам + BM25 через FTS5, слитые через **Reciprocal Rank Fusion** (bm25Weight=4.0, poolSize=30, rrfK=60 — эмпирика перенесена из старого aiexperiment). `sanitizeFtsQuery` — суффиксный стемминг под русские падежные окончания (`token.length > 6 → dropLast(2)`). Эмбеддинги — Ollama `nomic-embed-text`.
- **MCP:** **свой** JSON-RPC 2.0 stdio, без официального SDK — 114 строк `McpServer` (абстрактный `run()`-loop) + 133 строки `McpConnection` (клиент). GitToolsMcpServer запускается как отдельный дочерний JVM-процесс (`classpath` пробрасывается). Пять инструментов: `git_branch`, `git_log` (кол-во параметром), `git_status`, `code_search` (через внешний `ast-index`, фолбэк на текстовый), `grep_project` (regex + mode=`no_match` для отрицательных вопросов).
- **`/help`:** голый `/help` — **детерминированный** (без RAG/LLM), потому что RAG может поднять чужой README другого day-*-README как «свой». `/help <вопрос>` — обычный вопрос через RAG (префикс необязателен).
- **`!eval`:** прогон батареи из 10 контрольных вопросов (RAG, все MCP-инструменты, анти-галлюцинация, свой `/help`, известное принятое ограничение про `/state`) — быстрая проверка работоспособности после правок.
- **Роутинг:** нет как такового. Обе ветки (RAG + tool_use) идут всегда параллельно, финальный контекст = `RAG-документация + tool-результаты`. Приоритет инструментов над документацией **захардкожен в системный промпт** — это защита от бага, когда модель повторяет устаревшую фразу «ничего не закоммичено» из истории документации.
- **Донор:** сам `aiexperiment` (свой репо) + произвольный внешний проект по `TARGET_PROJECT_PATH` в конфиге.

**Живые баги, которые он поймал и починил (важно — прямые уроки):**

1. Модель игнорирует живой `git_log` в пользу устаревшего текста из README. Фикс — усиление системного промпта: «результаты инструментов имеют приоритет над документацией по вопросам git/кода».
2. **Классический ProcessBuilder deadlock**: `waitFor()` до чтения stdout — при выводе >64KB (`git log` с длинными сообщениями) OS pipe-буфер переполнялся, git блокировался на write, waitFor — на ожидании. Фикс — чтение потока в фоновом треде параллельно с `waitFor()`, `outputStream.close()` сразу после старта (защита от пейджера).
3. Языковой дрейф локального `qwen2.5:14b` на китайский/японский посреди русского ответа — регэксп `CJK_REGEX`, ретрай с усилением, извлечение хвоста после последней CJK-строки.
4. Утечка нераспознанного tool-call в текст ответа облачной модели (символы `｜｜`) — `sendRequestWithToolsGuarded` с ретраем.

**Ключевые уроки для нас:**

1. **Голый `/help` — детерминированно, без RAG.** Иначе поиск найдёт README другого дня челленджа с чужой командой `/help` и модель будет описывать чужое приложение.
2. **Хардкодить приоритет источников в системный промпт.** Иначе tool-результаты будут проигрывать устаревшей документации.
3. **`ProcessBuilder` — читать stdout параллельно с `waitFor()`.** Живой deadlock на длинном `git log`.
4. **Свой JSON-RPC MCP на 247 строк реален и работает.** Официальный SDK не обязателен — можно взять `org.json` и напрямую крутить `initialize` / `tools/list` / `tools/call`.
5. **Гибридный поиск (BM25 + cosine через RRF) практически всегда лучше чистого cosine.** BM25 ловит точные слова (имена классов, файлов), cosine — семантику. `bm25Weight=4.0` — эмпирика.
6. **`!eval` — золото для регрессии.** 10 контрольных вопросов, покрывающих все ветки — быстро отваливаются баги после правок.

### 2. shirobokov (второй Kotlin-путь — на официальном SDK)

Ссылка: <https://github.com/ShirobokovNE/ai-challenge/tree/day31/src/main/kotlin/ru/myproject/aichat>.

**Что использовал:**

- **LLM:** только облако — OpenRouter (по умолчанию `google/gemini-2.0-flash-001`), задаётся `API_KEY`/`AI_MODEL` env.
- **RAG:** гибридная модель — **облачный LLM для рассуждений, Ollama только для эмбеддингов** (`mxbai-embed-large` через LangChain4j `OllamaEmbeddingModel`). Индекс в `output/project_index.json` — не SQLite, а JSON. Три чанкера: FixedSize, RecursiveCharacter (по разделителям `\n\n / \n / . / пробел`), HierarchicalMarkdown (по заголовкам с сохранением цепочки `Context: h1 > h2 > h3`).
- **Поиск:** гибрид cosine + keyword-match (без BM25/FTS5) → topKPreFilter → reranking-этап с бустами: точная фраза (+0.3), совпадение цифр (+0.4 для дат/ID), полное покрытие ключевых слов (+0.2), density (близость ключевых слов ≤100 символов, +0.1). Финальный score cutoff → topKPostFilter.
- **MCP:** **официальный Kotlin SDK** `io.modelcontextprotocol.kotlin.sdk` (клиент+сервер). Транспорт **stdio** — subprocess `java -cp <classpath> ProjectContextServerLauncher` (тот же classpath, тот же jar!). Сервер: `ProjectContextServer` — 151 строка, три инструмента: `get_git_branch`, `list_project_files` (path, recursive), `read_project_file`.
- **`/help`:** команда `help` — обычный `printHelp()` со списком REPL-команд. Задание про «/help как справка по проекту» реализовано командой **`info`**: параллельно RAG (по index.json) + MCP (`get_git_branch` + `list_project_files`) → LLM с накопленным контекстом.
- **Роутинг:** нет — команда `info` всегда идёт в оба источника; свободный вопрос — только в RAG.
- **Донор:** свой репо `ru.myproject.aichat` (self-hosted документация).

**Ключевые уроки для нас:**

1. **Официальный `io.modelcontextprotocol:kotlin-sdk` работает и удобен.** SseClientTransport + StdioClientTransport из коробки. Особая находка — spawn собственного `main` через `java -cp <System.getProperty("java.class.path")>` = MCP-сервер запускается из того же jar, без отдельной сборки.
2. **`out → err`-трюк для stdio-сервера.** `System.setOut(System.err)` перед стартом чтобы обычный println сервера не портил JSON-RPC stdout.
3. **Иерархический markdown-чанкер лучше fixed-size** для README/docs: сохраняет цепочку `Context: h1 > h2 > h3` в чанке — модель видит, к какому разделу относится фрагмент.
4. **Ollama только для эмбеддингов, облако для LLM** — удачный гибрид: не платим за embed-запросы, но получаем сильный ответ от Gemini.

### 3. avalanche (Kotlin Multiplatform + HTTP/SSE MCP)

Ссылка: <https://github.com/1Avalanche/SmartReminder/tree/week7-day1>.

**Что использовал:**

- **LLM:** DeepSeek V4 Pro (дефолт) / Qwen (облако) / `qwen2.5:14b`+`gemma3:12b` локально через Ollama. Выбор через `--model deepseek/qwen/qwen-local/…`.
- **RAG:** свой векторный стор (`VectorStore`, `EmbeddingGenerator`, `SearchResult`), индекс через `IndexBuilder` + `IndexStorage` + `MetadataStorage`. Отдельный `RagSearcher` с кэшированием store.
- **MCP:** свой стек с абстрактным `McpTransport`. Реализации: **`ProcessTransport` (stdio)** и **`McpHttpTransport` (Streamable HTTP + SSE!)** — принимает и plain JSON, и `data:`-строки SSE, парсит их в очередь. Есть `McpToolRouter` для маршрутизации между несколькими MCP-серверами.
- **Режимы:** `question` (RAG-поиск + LLM, дефолт), `chat`, `code-analyzer`, `assist` (agentic loop с MCP), `index`, `architect`. `/help` показывает встроенную справку.
- **Донор:** любой репо через `--repo /path/to/repo`.

**Ключевые уроки для нас:**

1. **HTTP/SSE MCP-транспорт можно поднять на OkHttp за 78 строк.** `extractJsonLines` умеет и `{...}` (plain), и `data: {...}` (SSE) — единый код на два транспорта.
2. **Абстракция `McpTransport`** (интерфейс `send(String)` + `pollLine(timeoutMs): String?` + `close()`) — минимальная и правильная. Позволяет добавлять транспорты не меняя ядро.
3. **`McpToolRouter`** для нескольких MCP-серверов сразу — на будущее полезно (сейчас нам нужен только один git-сервер, но структура подсказана).
4. **`--repo /path/to/repo`** как CLI-аргумент = «универсальный ассистент для любого проекта», как у sergio-rsd.

### 4. strixg (Python-эталон, DeepSeek + Chroma + FastMCP)

Ссылка: <https://github.com/StrixG/codeassistant/tree/day31>. Проект-донор — **Element Android** (Matrix-клиент на Kotlin) — очень близко к нашему стеку по духу.

**Что использовал:**

- **LLM:** `deepseek-v4-pro` через библиотеку `openai` (base_url=`https://api.deepseek.com`), thinking mode OFF (RAG-задача не требует CoT, вдвое дешевле и быстрее).
- **RAG:** ChromaDB (embedded, persist на диск, без Docker) + локальные эмбеддинги `sentence-transformers/intfloat/multilingual-e5-small` (вопросы по-русски, документация по-английски). Инкрементальность по хешу файла (`index_state.json`), лимит чанка 512 токенов (предел e5-small).
- **Чанкер:** по markdown-заголовкам (`#`/`##`/`###`), сохраняет цепочку `heading_path` в метаданных.
- **MCP:** **официальный Python MCP SDK** — `mcp.server.fastmcp.FastMCP`, stdio. 4 read-only тула: `git_current_branch`, `git_list_files` (prefix), `git_diff` (`git diff HEAD`), `read_file` (защита от traversal + путь из конфига, не из аргументов LLM).
- **`/help`:** REPL команда `/help <вопрос>` — задать вопрос. Есть `/reindex`, `/metrics`, `/quit`. Строка без слэша тоже трактуется как вопрос.
- **Роутинг:** function-calling — модель сама решает, какие тулы вызывать (до 5 итераций). Плюс *ambient* контекст: **ветка + список модулей** всегда идёт префиксом в systemprompt (кэшируется DeepSeek → ~120× дешевле).
- **Metrics:** `metrics.jsonl` на каждый запрос (latency, токены incl. `cached_tokens`, вызванные тулы, источники), `/metrics` считает P50/P95/среднее/стоимость.
- **Human-in-the-loop:** флаг `requires_confirmation` у каждого тула — executor печатает план и ждёт `y/n` (в бою тулов таких нет, но механизм показан на фиктивном `git_push`).

**Ключевые уроки для нас:**

1. **Ambient-контекст префиксом системного промпта = дешёвый кэш prompt-caching.** Ветка + модули + список тулов всегда одинаковые → cache-hit цена в 120× ниже.
2. **`git_diff` (`git diff HEAD`) — обязательный tool, а не «желательный».** У всех топ-3 есть.
3. **`read_file`-tool с защитой от traversal (`../`, абсолютные пути отклонять) + путь к репо строго из конфига, не из аргументов LLM.** Правильный security-минимум.
4. **`metrics.jsonl` + P50/P95 — хороший стандарт для оценки решения.** Пригодится для наших `!eval`-скриптов.
5. **thinking mode OFF для RAG.** Экономит деньги и время.

### 5. farmut (Go-эталон-переростище)

Ссылка: <https://github.com/farmut/ai-advent-challenge/tree/main/Day31>. Проект **гигантский** (Day31 = четыре компонента + 590 строк Makefile).

**Что использовал:**

- **Оркестратор** (главное — далеко за пределы задания): LLM-цикл эмиссии JSON-экшенов `{spawn, ask_user, finish}` → саб-агенты (`researcher`, `coder`, `reviewer`) → результаты сливаются в транскрипт. YAML-конфиг определяет ростер и права каждого саб-агента (RAG / MCP-серверы по allow-list).
- **Ambient MCP:** отдельный `git-mcp-server/` Go-модуль (stdio JSON-RPC 2.0), 3 read-only тула: `git_current_branch`, `git_list_files` (`filter`: changed/staged/untracked/all), `git_diff` (`staged` bool, optional path). Output cap 64 KB. Путь к репо фиксируется при старте через `-repo` флаг, tool args не могут его сменить.
- **RAG:** отдельный Go-модуль с Clean Architecture. Retrieve → rerank (chat cross-encoder ИЛИ native `POST /rerank` a-la Cohere) → filter (threshold + top_k_final) → grounded answer с sources и quotes. Есть fallback: если rerank упал — деградация до чистого cosine с явным `[rag] rerank failed`.
- **LLM:** OpenAI/OpenRouter/GigaChat + LiteLLM proxy с self-signed cert (`--ca-cert`, TLS verification остаётся ON).
- **`/help`:** repurposed — **documentation-consultant mode**. Отдельный `app.Consultant` с независимым RAG-индексом (`../rag/docs.db`), lazy build при первом входе, `/end`/`/конец` возвращает в оркестратор. **Собственный prompt builder** (не стандартный `BuildRAGPrompt`), потому что «answer using only the context» не даёт модели вызвать git-тулы для вопросов про репо-стейт.
- **TUI:** vendored gotui (patched tcell до v3.4.0 из-за бага в scanUTF8), markdown-lite renderer, `Ctrl+S` = submit, `Enter` = newline.

**Ключевые уроки для нас (что берём, что нет):**

1. **Идея «два RAG-индекса: docs проекта отдельно от documentation-consultant» интересна**, но это Day31+++, для нашего минимума избыточно.
2. **`git_list_files` c параметром `filter=changed/staged/untracked/all`** — полезное расширение поверх минимума.
3. **Output cap 64 KB для tool-результатов** — обязательная защита от «модель захлебнулась diff-ом на весь монорепо».
4. **Оркестратор саб-агентов, YAML-конфиг, TUI, ask_user human-in-the-loop — НЕ БЕРЁМ.** Это уже day-32/33+ уровня, для дня 31 избыточно.

---

## Сводка: что берём в наш day31

### Обязательно (минимум задания)

- **Kotlin JVM + Gradle** (совпадает с нашим стеком, эталоны — sergio-rsd, shirobokov).
- **RAG над `README.md` + `docs/**`.** Чанкер — **иерархический markdown** (по заголовкам, сохраняем цепочку `h1 > h2 > h3` в метаданных чанка — shirobokov / strixg). Fixed-size — плохо; recursive — терпимо.
- **Эмбеддинги — Ollama** (`nomic-embed-text` или `mxbai-embed-large`). Локально, бесплатно, стабильно. Не тянуть LangChain4j — прямой HTTP-вызов Ollama проще (см. sergio-rsd `EmbeddingClient`).
- **Хранилище — SQLite JDBC + FTS5 виртуальная таблица** (`sqlite-jdbc`, вкл. FTS5). Даёт BM25 бесплатно.
- **Гибридный поиск — cosine + BM25 через RRF** (sergio-rsd, `bm25Weight=4.0`, `poolSize=30`, `rrfK=60`).
- **MCP — свой JSON-RPC 2.0 stdio** (sergio-rsd) ИЛИ **официальный SDK `io.modelcontextprotocol:kotlin-sdk`** (shirobokov). Первый — меньше зависимостей и полный контроль, второй — 20 строк на клиент. По привычной нам «Kotlin+Gradle без магии» **берём свой** — понятнее и переносимее.
- **MCP-тулы (минимум):** `git_current_branch`, `git_list_files` (с output cap 64 KB), `git_diff` (`git diff HEAD`).
- **REPL с `/help`.** Голый `/help` — **детерминированный** (список команд + инструментов), НЕ через RAG. `/help <вопрос>` — обычный вопрос (префикс необязателен). Это защита от коллизии с чужими README в корпусе (живой sergio-rsd баг).
- **LLM — OpenRouter** (`google/gemini-2.0-flash-001` дёшево / `deepseek-v4-pro` через api.deepseek.com), tool_use цикл.
- **Ambient контекст префиксом system prompt:** текущая ветка + список tools — как у strixg. Даёт дешёвый prompt-cache.
- **Приоритет tool-результатов над документацией — хардкодить в system prompt.** Иначе модель повторит устаревший README.
- **`ProcessBuilder`: читать stdout в фоновом треде параллельно с `waitFor()`, `outputStream.close()` сразу после старта.** Deadlock-safe.
- **Output cap 64 KB** для tool-результатов (farmut) — защита от `git diff` на 100 файлов.

### Опционально (можно добавить, если останется время)

- `git_status --short` (sergio-rsd, strixg, farmut — у всех есть).
- `code_search` через `ripgrep`/`ast-index` с текстовым фолбэком (sergio-rsd).
- `read_file` c защитой от path-traversal (strixg).
- `!eval`-батарея из 10 контрольных вопросов (sergio-rsd) — для регрессионного тестирования.
- `metrics.jsonl` + `/metrics` c P50/P95 (strixg) — если будем делать замеры.

### НЕ берём

- Оркестратор саб-агентов, YAML-конфиг, `ask_user` human-in-the-loop, TUI (farmut) — это Day32+.
- Documentation-consultant mode с двумя RAG-индексами (farmut) — переусложнение.
- Свой ProcessTransport + HTTP/SSE + McpToolRouter (avalanche) — для одного git-сервера stdio достаточно.
- 3-слойная модель памяти STM/WM/LTM (shirobokov, farmut) — это про Week3, не про Day31.
- Reranker (native `POST /rerank` a-la Cohere) — sergio-rsd/farmut. Для маленького корпуса README+docs гибрид cosine+BM25 RRF даёт достаточное качество без второго LLM-вызова.
- LiteLLM proxy c self-signed cert (farmut) — специфика инфраструктуры.
- Multi-language guard от языкового дрейфа (sergio-rsd) — актуально только для локального `qwen2.5:14b`, у нас OpenRouter/DeepSeek.

---

## Замечания по инструменту

- Sheets-reader работает штатно (2667 комментариев). Токен свежий. День 31 = day31/task31/day-1-total-31/week7-day1/week-7/task-31/task-31 — вариантов написания много, важно фильтровать по всем.
- Некоторые Kotlin-репозитории уже клонированы в `/home/claudeuser/sessions/common/workspace/` (`avalanche`, `mrsmith113`, `nikbrik`, `dpmn`, `karpiuk`, `swanden`, `farmut`) — от прежних research-сессий. Но у большинства ветка `main` без day31 — нужно `git fetch <branch>` вручную (я сделал это для avalanche).

## Ссылки на исходники (детально изучено)

- **sergio-rsd:** <https://github.com/Sergio-rsd/DevAssistant/tree/feature/tuning-agent> — Kotlin JVM, свой MCP, SQLite FTS5 BM25 + RRF
- **shirobokov:** <https://github.com/ShirobokovNE/ai-challenge/tree/day31/src/main/kotlin/ru/myproject/aichat> — Kotlin JVM, официальный `io.modelcontextprotocol:kotlin-sdk`, LangChain4j Ollama
- **avalanche:** <https://github.com/1Avalanche/SmartReminder/tree/week7-day1> — Kotlin Multiplatform, свой MCP HTTP/SSE + stdio
- **strixg:** <https://github.com/StrixG/codeassistant/tree/day31> — Python, DeepSeek+Chroma, официальный MCP Python SDK, донор Element Android
- **farmut:** <https://github.com/farmut/ai-advent-challenge/tree/main/Day31> — Go, оркестратор саб-агентов, git-mcp-server как отдельный модуль
- **nikbrik** (не разбирал детально): <https://github.com/nikbrik/coding_writer> — Go, `cw` TUI, свой MCP client stdio
- **Ssh sh / Sergio-rsd README дня:** <https://github.com/Sergio-rsd/AI-Challenge/tree/week7/day-1-total-31>

## Клонированные для research репозитории (workspace)

- `/home/claudeuser/sessions/common/workspace/shirobokov/` — Kotlin, ветка day31 (свежая)
- `/home/claudeuser/sessions/common/workspace/sergio-rsd/` — Kotlin, ветка week7/day-1-total-31 (только описание дня — код в отдельном репо ниже)
- `/home/claudeuser/sessions/common/workspace/sergio-devassistant/` — Kotlin, DevAssistant `feature/tuning-agent` (основной код)
- `/home/claudeuser/sessions/common/workspace/strixg/` — Python, ветка day31
- `/home/claudeuser/sessions/common/workspace/farmut/` — Go, Day31/ подкаталог
- `/home/claudeuser/sessions/common/workspace/avalanche/` — Kotlin Multiplatform, ветка week7-day1
- (для полноты) `/home/claudeuser/sessions/common/workspace/nikbrik/` — Go, coding_writer main
