# AI Challenge Data — Оглавление обеих треков

**Обновлено:** 2026-08-12 (челлендж завершён)
**Цель этого файла:** быстрый reference — «что было / что сделали / где артефакты». Не углублённое описание, не туториал.
**Как использовать:** ctrl+F по теме или неделе; ссылки на первичные артефакты.

---

## Base Track — 7 недель × ~5 дней в GitHub-репо

**Repo:** https://github.com/AlDtoll/ai-challenge (ветки `weekN/dayM`)
**Локально:** `~/repo/`
**Пропуски:** week1/day1 нет ветки

**Курс:** AI Challenge (формат: еженедельные задания, видео + код)
**Общая тема:** Прокачка от базовых LLM API-вызовов до production-ready агентов с MCP, RAG и локальными LLM. Стек — Kotlin (JVM), DeepSeek API + OpenRouter, Ollama local.

---

## Week 1 — Основы LLM API: промпты, параметры, модели

Работа с DeepSeek API напрямую. Цикл: один вопрос — разные конфиги. Нет агента, нет истории, только single-shot запросы.

> week1/day1 — не существует (ветка отсутствует).

### week1 / day2 — Response Format Control

- **Что сделано:** Один вопрос отправляется дважды: без ограничений и с system prompt + max_tokens + stop sequence. Показывает разницу finish_reason: `stop` vs `length`.
- **Артефакты:** https://github.com/AlDtoll/ai-challenge/tree/week1/day2/week1/day2 — `Main.kt`, `.env.example`, `README.md`
- **Ключевая идея:** finish_reason = индикатор почему модель остановилась; stop sequence как дополнительный «выключатель».

### week1 / day3 — Reasoning Strategies

- **Что сделано:** Одна задача решается четырьмя методами: Direct, Step-by-step, Meta-prompting (модель сначала пишет промпт, потом решает — 2 API-вызова), Panel of experts (Analyst + Engineer + Critic в system prompt).
- **Артефакты:** https://github.com/AlDtoll/ai-challenge/tree/week1/day3/week1/day3 — `Main.kt`, `README.md`
- **Ключевая идея:** Meta-prompting принципиально отличается от остальных — модель сама задаёт себе инструкцию, 2 вызова вместо 1.

### week1 / day4 — Temperature

- **Что сделано:** Один промпт отправляется с температурами 0.0 / 0.7 / 1.2. Таблица: для чего какая подходит (факты, баланс, творчество).
- **Артефакты:** https://github.com/AlDtoll/ai-challenge/tree/week1/day4/week1/day4 — `Main.kt`, `README.md`
- **Ключевая идея:** —

### week1 / day5 — Model Comparison

- **Что сделано:** Один промпт → 3 модели (слабая/средняя/сильная через OpenRouter free-tier) + LLM-судья оценивает ответы. Замеряются время, токены, стоимость.
- **Артефакты:** https://github.com/AlDtoll/ai-challenge/tree/week1/day5/week1/day5 — `Main.kt`, `README.md`
- **Ключевая идея:** LLM-as-judge паттерн для автоматической оценки качества без разметки.

---

## Week 2 — Диалоговые агенты: история, контекст, сжатие

Строим полноценный агент с памятью сессии. Telegram-бот появляется с day1 как интерфейс.

### week2 / day1 — Базовый агент + Telegram-интеграция

- **Что сделано:** Agent-класс с историей сообщений (system prompt + rolling conversation). Telegram-бот как интерфейс; console-режим как fallback без токена.
- **Артефакты:** https://github.com/AlDtoll/ai-challenge/tree/week2/day1/week2/day1 — `Agent.kt`, `TelegramBot.kt`, `Main.kt`
- **Ключевая идея:** Один агент — два интерфейса (Telegram + console) через общий класс Agent.

### week2 / day2 — Персистентный контекст (disk storage)

- **Что сделано:** История диалога сохраняется в JSON (`~/.ai-challenge/context_day7.json`) по chatId. После перезапуска бота сессия восстанавливается.
- **Артефакты:** https://github.com/AlDtoll/ai-challenge/tree/week2/day2/week2/day2 — `ContextStorage.kt`, `Agent.kt`, `TelegramBot.kt`
- **Ключевая идея:** Персистентность = разделить "живую" память (runtime) от "холодной" (диск).

### week2 / day3 — Context Window & overflow handling

- **Что сделано:** Визуализация заполнения контекстного окна (цветовая шкала % заполнения). Обработка API-ошибки overflow с friendly-сообщением. Учёт стоимости (цена/1M токенов DeepSeek).
- **Артефакты:** https://github.com/AlDtoll/ai-challenge/tree/week2/day3/week2/day3 — `Agent.kt`, `Main.kt`
- **Ключевая идея:** Контекстное окно конечно — нужно его мониторить и явно обрабатывать переполнение.

### week2 / day4 — Context Compression (incremental LLM summary)

- **Что сделано:** Когда история > порога, агент запускает LLM-вызов для суммаризации старых сообщений в одну запись, которая заменяет их в контексте.
- **Артефакты:** https://github.com/AlDtoll/ai-challenge/tree/week2/day4/week2/day4 — `Agent.kt`, `Main.kt`
- **Ключевая идея:** Incremental summary = компромисс между «выкинуть старое» и «всё помнить»; суммаризация сама стоит токенов.

### week2 / day5 — Sliding Window + Sticky Facts + Branching

- **Что сделано:** Три стратегии управления контекстом в одном агенте: `SLIDING_WINDOW` (скользящее окно KEEP_LAST сообщений), `STICKY_FACTS` (ключевые факты отдельно от обычной истории), `BRANCHING` (ветвление диалога). Переключение через `/strategy`.
- **Артефакты:** https://github.com/AlDtoll/ai-challenge/tree/week2/day5/week2/day5 — `Agent.kt`, `Main.kt`
- **Ключевая идея:** Разные задачи требуют разных стратегий: FAQ-бот → sliding window; ассистент с профилем → sticky facts.

---

## Week 3 — Управление состоянием агента: память, инварианты, state machine

Агент приобретает структурированную память и поведение, управляемое правилами.

### week3 / day1 — Трёхслойная модель памяти

- **Что сделано:** ShortTermMemory (текущий диалог, runtime), WorkingMemory (текущая задача, JSON на диске), LongTermMemory (профиль пользователя, JSON на диске). При первом запуске — онбординг: имя, стиль, стек, запреты.
- **Артефакты:** https://github.com/AlDtoll/ai-challenge/tree/week3/day1/week3/day1 — `Memory.kt`, `Agent.kt`, `Main.kt`
- **Ключевая идея:** Трёхслойная память = аналог кэша CPU (L1/L2/L3) для агента.

### week3 / day2 — Персонализация через автоматическое извлечение профиля

- **Что сделано:** LLM-экстрактор автоматически извлекает профиль пользователя из диалога (имя, уровень, стек). System prompt строится динамически по профилю (`SystemPromptBuilder`). Команды `/profile`, `/switch junior|senior`.
- **Артефакты:** https://github.com/AlDtoll/ai-challenge/tree/week3/day2/week3/day2 — `ProfileExtractor.kt`, `SystemPromptBuilder.kt`, `UserProfile.kt`
- **Ключевая идея:** Профиль = implicit learning из диалога, без явных вопросов.

### week3 / day3 — Task State Machine + Audit Log

- **Что сделано:** Конечный автомат задачи (IDLE → PLANNING → EXECUTION → VALIDATION → DONE) с флагом paused. Все переходы пишутся в JSONL audit log. System prompt перегенерируется из state при каждом сообщении — агент «знает» на каком этапе он находится.
- **Артефакты:** https://github.com/AlDtoll/ai-challenge/tree/week3/day3/week3/day3 — `TaskMachine.kt`, `TaskState.kt`, `Repository.kt`, `Main.kt`
- **Ключевая идея:** State machine → агент не «помнит», он читает state; audit log → отлаживаемость.

### week3 / day4 — Инварианты с двухслойным guard'ом

- **Что сделано:** Хранилище инвариантов (ARCHITECTURE/STACK/BUSINESS/SECURITY/PROFANITY, hard/soft). Guard работает двумя слоями: pre-check regex → LLM-ответ → post-check LLM-судья. При нарушении: retry или отказ без попадания ответа в историю (silent rollback).
- **Артефакты:** https://github.com/AlDtoll/ai-challenge/tree/week3/day4/week3/day4 — `InvariantGuard.kt`, `Invariant.kt`, `InvariantStore.kt`, `AuditLog.kt`
- **Ключевая идея:** Silent rollback = плохой ответ не «отравляет» историю и не закрепляет неверные паттерны.

### week3 / day5 — Контролируемые переходы состояний + Gate-система

- **Что сделано:** Таблица переходов (`TransitionTable`) с условиями (gates): `planApproved`, `executionComplete`, `validationPassed`. Команды `/goto`, `/advance`, `/approve-plan`, `/validate-ok` etc. Агент видит state и gates в system prompt.
- **Артефакты:** https://github.com/AlDtoll/ai-challenge/tree/week3/day5/week3/day5 — `TransitionTable.kt`, `Gates.kt`, `TaskMachine.kt`
- **Ключевая идея:** Gates делают переходы детерминированными — нельзя перейти в DONE без валидации, даже если LLM «хочет».

---

## Week 4 — MCP (Model Context Protocol): инструменты, расписание, оркестрация

Агент получает внешние инструменты через протокол MCP (Streamable HTTP).

### week4 / day1 — Подключение к MCP (discovery)

- **Что сделано:** Минимальный MCP-клиент на Kotlin: `initialize` + `tools/list` к публичному DeepWiki MCP серверу. Инструменты не вызываются — только discovery.
- **Артефакты:** https://github.com/AlDtoll/ai-challenge/tree/week4/day1/week4/day1 — `Main.kt`, `README.md`
- **Ключевая идея:** Сначала понять протокол, не использовать библиотеку — так понятно что происходит под капотом.

### week4 / day2 — Первый собственный MCP-сервер (get_forecast)

- **Что сделано:** Свой MCP-сервер на localhost:3001 с инструментом `get_forecast` (обёртка Open-Meteo). Агент коннектится к нему же, делает `listTools()` → `callTool()`, формирует ответ.
- **Артефакты:** https://github.com/AlDtoll/ai-challenge/tree/week4/day2/week4/day2 — `Main.kt`, `Weather.kt`, `README.md`
- **Ключевая идея:** Сервер и клиент в одном процессе — упрощает демо, но архитектура идентична distributed.

### week4 / day3 — Планировщик и фоновые задачи (24/7 агент)

- **Что сделано:** MCP-сервер с тремя инструментами: `start_watch(city, interval_sec)`, `get_summary()`, `stop_watch()`. Фоновый сбор погоды каждые 8с → JSONL-журнал. Агент в бесконечном цикле каждые 16с печатает сводку.
- **Артефакты:** https://github.com/AlDtoll/ai-challenge/tree/week4/day3/week4/day3 — `WatchService.kt`, `WeatherStore.kt`, `Weather.kt`
- **Ключевая идея:** Персистентный JSONL журнал переживает перезапуск — агент не теряет накопленные данные.

### week4 / day4 — Композиция инструментов (pipeline через MCP)

- **Что сделано:** MCP-сервер с тремя инструментами: `search(query)` (Wikipedia), `summarize(text)` (DeepSeek), `save_to_file(content)`. Агент вызывает их цепочкой: search → summarize → save.
- **Артефакты:** https://github.com/AlDtoll/ai-challenge/tree/week4/day4/week4/day4 — `Search.kt`, `Summarizer.kt`, `Main.kt`
- **Ключевая идея:** Пайплайн tool-calls — агент сам знает порядок; LLM решает что и куда передать.

### week4 / day5 — Оркестрация нескольких MCP-серверов

- **Что сделано:** Три MCP-сервера (knowledge:3010, weather:3011, files:3012). Агент собирает общий реестр всех инструментов с namespace (prefix по серверу). DeepSeek через function-calling сам выбирает инструмент, агент маршрутизирует по namespace на нужный сервер.
- **Артефакты:** https://github.com/AlDtoll/ai-challenge/tree/week4/day5/week4/day5 — `Orchestrator.kt`, `Search.kt`, `Summarizer.kt`, `Weather.kt`
- **Ключевая идея:** Namespace-префикс — простейший способ маршрутизации без конфликтов имён между серверами.

---

## Week 5 — RAG (Retrieval-Augmented Generation)

Строим RAG от нуля: индексация, retrieval, анти-галлюцинации, task state + RAG.

### week5 / day1 — RAG индексация: fixed vs structural chunking

- **Что сделано:** 25 синтетических Markdown-документов (89KB, темы: напитки/языки/планеты/горы). Два чанкера: `FixedSizeChunker` (600 симв + 100 overlap) и `StructuralChunker` (по Markdown-заголовкам). SQLite-хранилище, bag-of-words эмбеддинги (заглушка). Метрики: Recall@3, MRR.
- **Артефакты:** https://github.com/AlDtoll/ai-challenge/tree/week5/day1/week5/day1 — `Main.kt` + `src/main/resources/data/` (25 .md файлов)
- **Ключевая идея:** Структурный чанкинг сохраняет семантические границы секций; fixed-size проще, но режет по середине абзаца.

### week5 / day2 — Первый RAG-запрос (сравнение без/с RAG)

- **Что сделано:** Интерактивный REPL с двумя режимами ответа: без RAG (модель по своим знаниям) и с RAG (retrieval по базе знаний челленджа). Эмбеддинги — Ollama nomic-embed-text (реальная модель). KB из 15 .md файлов о проекте.
- **Артефакты:** https://github.com/AlDtoll/ai-challenge/tree/week5/day2/week5/day2 — `Main.kt` + `src/main/resources/kb/` (15 файлов)
- **Ключевая идея:** Сравнение рядом = наглядно видно «галлюцинацию» vs «ответ по документу».

### week5 / day3 — Реранкинг, query rewrite, фильтрация

- **Что сделано:** Поверх naive-RAG: 5 режимов — naive / threshold (cosine ≥ 0.28) / rerank (LLM-судья, top-10 → top-3) / rewrite (3 переформулировки + union) / full (все этапы). LLM-реранкер возвращает JSON с float-оценками 0..1, порог 0.45.
- **Артефакты:** https://github.com/AlDtoll/ai-challenge/tree/week5/day3/week5/day3 — `Main.kt`
- **Ключевая идея:** Query rewrite компенсирует «плохие» поисковые вопросы; LLM-reranker дороже, но понимает семантику лучше косинуса.

### week5 / day4 — Цитаты, источники, анти-галлюцинации

- **Что сделано:** Три anti-hallucination слоя: (1) ALLOWED_QUOTES — детерминированная нарезка чанков на кандидат-фразы 40-240 симв, LLM выбирает только из них; (2) Structured JSON output `{answer, sources, citations, confidence, abstained}`; (3) Retry-loop с фидбеком (до 2 повторов если цитата не из ALLOWED_QUOTES). Плюс soft-abstain (отвечает из общих знаний с пометкой, не молчит) и grounded-judge.
- **Артефакты:** https://github.com/AlDtoll/ai-challenge/tree/week5/day4/week5/day4 — `Main.kt`
- **Ключевая идея:** ALLOWED_QUOTES = программный контроль над цитатами без второго LLM-вызова; soft-abstain лучше жёсткого «не знаю».

### week5 / day5 — Мини-чат с RAG + TaskState

- **Что сделано:** Полный стек дней 22–24 + `TaskState` (5 полей: goal, constraints, clarifications, fixed_terms, open_questions). LLM обновляет state одним вызовом вместе с ответом. `build_retrieval_query` обогащает retrieval контекстом сессии. Два тестовых сценария из `scenarios/*.json`.
- **Артефакты:** https://github.com/AlDtoll/ai-challenge/tree/week5/day5/week5/day5 — `Main.kt` + `scenarios/overview.json`, `scenarios/planning.json`
- **Ключевая идея:** TaskState + retrieval query enrichment = агент «помнит цель» и ищет релевантнее с каждым шагом.

---

## Week 6 — Локальные LLM (Ollama)

Полностью офлайн: retrieval + генерация без облачных API.

### week6 / day1 — Запуск локальной LLM через Ollama

- **Что сделано:** Kotlin-клиент Ollama через `/api/chat` (нативный, не OpenAI-compat). 3 промпта с автоверификацией (столица Австралии — ловушка Сидней/Канберра; задача Канемана «мяч+бита»; JSON extraction). Метрики из ответа Ollama: tok/s, load_duration, eval_duration. Отчёт `day26_report.md`.
- **Артефакты:** https://github.com/AlDtoll/ai-challenge/tree/week6/day1/week6/day1 — `Main.kt`, `README.md`
- **Ключевая идея:** Нативный `/api/chat` даёт честные метрики throughput; автоверификация ответов без ручного разбора.

### week6 / day2 — RAG-CLI над локальными документами (fully local)

- **Что сделано:** Полностью локальный RAG-CLI: nomic-embed-text (Ollama) для эмбеддингов + qwen2.5:7b для генерации ответов. Индексирует папку .md/.txt, персистентный `index.json`. Multi-turn диалог с источниками. Ни одного облачного API-вызова.
- **Артефакты:** https://github.com/AlDtoll/ai-challenge/tree/week6/day2/week6/day2 — `Main.kt`, `eval-questions.txt`
- **Ключевая идея:** Полностью локальный RAG = privacy-first; trade-off — качество qwen2.5:7b < DeepSeek, но данные не уходят.

### week6 / day3 — RAG + сравнение local vs cloud

- **Что сделано:** Абстракция `LlmBackend` (sealed interface). Реализации: `OllamaLocal` (qwen2.5:7b) и `DeepSeekCloud`. Retrieval полностью локальный. Режим сравнения — один вопрос → оба бэкенда, метрики качество/скорость/стабильность рядом.
- **Артефакты:** https://github.com/AlDtoll/ai-challenge/tree/week6/day3/week6/day3 — `Main.kt`, `eval-questions.txt`
- **Ключевая идея:** Абстракция LlmBackend = backend-agnostic retrieval; можно менять LLM без переписывания RAG-стека.

### week6 / day4 — Оптимизация локальной LLM под RAG

- **Что сделано:** Три фронта оптимизации: параметры Ollama (temperature 0→0.0, num_ctx 2048→8192, num_predict -1→256, top_p, repeat_penalty, stop-sequences), квантование, prompt-шаблон под RAG. Сравнение метрик до/после: качество, скорость, ресурсы.
- **Артефакты:** https://github.com/AlDtoll/ai-challenge/tree/week6/day4/week6/day4 — `Main.kt`, `eval-questions.txt`
- **Ключевая идея:** num_ctx=2048 по умолчанию Ollama обрезает 3-чанковый RAG-контекст — это критический баг без оптимизации.

### week6 / day5 — Локальная LLM как HTTP-сервис

- **Что сделано:** HTTP-сервис на JDK stdlib (`com.sun.net.httpserver`). 5 endpoints: `GET /` (HTML чат-UI), `GET /health`, `POST /v1/chat`, `POST /v1/rag`, `GET /stats`. Rate limiting, concurrency control, Bearer auth. Chat UI встроен как `resources/chat.html`.
- **Артефакты:** https://github.com/AlDtoll/ai-challenge/tree/week6/day5/week6/day5 — `Main.kt`, `src/main/resources/chat.html`
- **Ключевая идея:** JDK stdlib HttpServer = zero dependencies; `GET /health` с проверкой Ollama + индекса как readiness probe.

---

## Week 7 — Production-ready: реальные задачи

Применение всего стека к реальным кейсам. DeepSeek-chat (cloud) как основная LLM.

### week7 / day1 — Ассистент разработчика (RAG + MCP + git)

- **Что сделано:** REPL-ассистент по проекту `ai-challenge`. RAG-индекс над README/CLAUDE.md/MEMORY.md/docs. Свой MCP-сервер (:3001) с git-инструментами: `get_current_branch`, `git_status`, `git_log`. Агент = MCP-клиент, всё в одном процессе.
- **Артефакты:** https://github.com/AlDtoll/ai-challenge/tree/week7/day1/week7/day1 — `Main.kt`, `Rag.kt`, `GitMcp.kt`, `DeepSeek.kt`, `VIDEO_SCRIPT.md`
- **Ключевая идея:** «Ассистент по своему коду» = RAG + live git state через MCP; не статический FAQ, а актуальная информация.

### week7 / day2 — Автоматизация ревью PR (GitHub Actions)

- **Что сделано:** GitHub Action на `pull_request` → читает diff + изменённые файлы → BM25-индекс (офлайн, детерминированный) над README/docs → DeepSeek с ролью «старший инженер» → `gh pr comment` с ревью. BM25 вместо эмбеддингов: работает без Ollama на CI-раннере.
- **Артефакты:** https://github.com/AlDtoll/ai-challenge/tree/week7/day2/week7/day2 — `Bm25Index.kt`, `Main.kt`, `DeepSeek.kt`, `VIDEO_SCRIPT.md`
- **Ключевая идея:** CI-контекст требует offline-индексирования: BM25 детерминирован и без зависимостей, в отличие от Ollama.

### week7 / day3 — Ассистент поддержки пользователей

- **Что сделано:** REPL с двумя сценариями: `/ask <ticket_id> <вопрос>` (MCP: get_ticket + get_user + list_open_tickets → RAG по FAQ → DeepSeek) и `/faq <вопрос>`. RAG с dual fallback: Ollama nomic-embed-text → BM25 если Ollama недоступна. «CRM» из JSON-файлов (3 юзера, 4 тикета).
- **Артефакты:** https://github.com/AlDtoll/ai-challenge/tree/week7/day3/week7/day3 — `SupportMcp.kt`, `Rag.kt`, `Main.kt`, `data/faq/*.md`
- **Ключевая идея:** Dual fallback Ollama→BM25 = graceful degradation; агент не падает если локальная модель недоступна.

### week7 / day4 — Ассистент работы с файлами (agentic loop)

- **Что сделано:** MCP file-сервер (:3004) с 6 инструментами: `project_stat`, `list_files`, `read_file`, `search_text`, `write_file`, `apply_patch`. Sandboxing через `safeResolve` (блок `..` и абсолютных путей). Agentic loop: DeepSeek с `tool_choice=auto` → `tool_calls` → MCP → обратно в LLM, до `MAX_TOOL_ITER=12`. Режим `--dry-run`.
- **Артефакты:** https://github.com/AlDtoll/ai-challenge/tree/week7/day4/week7/day4 — `FileMcp.kt`, `Main.kt`, `DeepSeek.kt`, `VIDEO_SCRIPT.md`
- **Ключевая идея:** `apply_patch` с `expected_count` = идемпотентная замена с проверкой; agentic loop обрывается по MAX_TOOL_ITER как safety guard.

### week7 / day5 — Weekly VPS Report Bot (реальная задача)

- **Что сделано:** Автоматический еженедельный отчёт о VPS в Telegram. MCP-сервер (:3005) с 4 инструментами: `vps_git_activity(days)`, `vps_bots_health(days)`, `vps_token_spend(days)`, `vps_system()`. DeepSeek форматирует данные в 5-секционный отчёт. Деплой-скрипт `deploy/weekly-vps-report.sh`.
- **Артефакты:** https://github.com/AlDtoll/ai-challenge/tree/week7/day5/week7/day5 — `Main.kt`, `DataSources.kt`, `ReportMcp.kt`, `Telegram.kt`, `deploy/weekly-vps-report.sh`
- **Ключевая идея:** Реальная задача = показатель зрелости: агент заменяет 15 мин ручной работы каждое воскресенье, данные типизированы (не bash-инъекции).

---

## Advanced Track — недели 8-10 (параллельный поток)

**Локально:** `advanced-workspace/advanced/`
**Формат:** отдельная таблица результатов, не пересекается с base.

**GitHub:** `AlDtoll/zizz3` (код) + `AlDtoll/ai-challenge` (артефакты/инфра)

---

## Week 8 — Тюнинг код-ассистента до Джарвиса

**Лекция:** `week8/8неделя1поток-{расшифровка,саммари}.md`

**Ключевые тезисы лекции:**
- Main-agent = планирование и маршрутизация, subagents = выполнение. Контекстное окно ~160-180k токенов — беречь.
- Максимум 5 субагентов у Claude Code одновременно.
- Три уровня правил: глобальные + проектные (наследование). Профиль + инварианты + task states — обязательный минимум.
- Субагенты: параллельно (независимые задачи), последовательно (зависимые), с маршрутизацией по условию. У каждого — строгий input/output контракт.
- **Итоговая метрика:** «Чем чаще поправляешь нейронку, тем хуже настроен execution loop».

---

### week8 / day1 — Rules (CLAUDE.md v1→v2)

- **Что сделано:** Написан `CLAUDE.md v2` (777 строк) для Zizz3. Две генерации одной фичи («Ночные пробуждения») на v1 и v2, дифф 17 файлов 847+/624-. Три конкретных правки v2: запрет `!!` с контр-примером, запрет `Pair<A,B>` в public API, KDoc строго на русском.
- **Артефакты:** ветки `advanced-day1-v1` (коммит `5a92646`) и `advanced-day1-v2` (коммит `4e7245e`) в `AlDtoll/zizz3`. Постмортем: `docs/ai_challenge_advanced_day1_postmortem.md`.
- **Ключевая идея:** 3 точечные правки с явными примерами «плохо/хорошо» дали 100% compliance: 0 `!!`, 0 `Pair` в контрактах, полный русский KDoc. v2 «пошёл дальше» — вместо минимального `WakeupInterval` создал `data class PendingAwakening` с 8 именованными полями.

---

### week8 / day2 — Profiles (агентные профили)

- **Что сделано:** Три специализированных профиля-файла в `zizz3/.claude/agents/`: `bug-fix.md`, `research.md`, `screenshot-baseline.md`. Каждый протестирован на реальной задаче.
- **Артефакты:**
  - `week8/day2/research_test_active_sleep.md` — research-отчёт об Active Sleep Session (DataStore, MainScreen, BootReceiver).
  - `week8/day2/bugfix_test_notifications.md` — найдена реальная prod-регрессия в `NotificationScheduler.rescheduleOrRecalculate` (уведомления пропадают после reboot в первый час сна).
  - `week8/day2/screenshot_baseline_test.md` — виртуальный прогон Paparazzi (не запускается на VPS — нет GPU/X).
- **Ключевая идея:** Bug Fix профиль нашёл реальный баг в prod. Это стало issue #4 — закрыто в day5.

---

### week8 / day3 — Testing (unit-тесты)

- **Что сделано:** 29 unit-тестов на 3 непокрытых domain-модуля Zizz3 (коммит `3c6455b`, 438 строк).
- **Артефакты:**
  - `AgeNormsTest.kt` — 12 тестов (нормы сна по возрасту).
  - `AdaptiveNormCalculatorTest.kt` — 8 тестов (адаптация нормы по истории).
  - `ScheduleIntervalModelTest.kt` — 9 тестов, включая TimeZone-awareness (UTC vs NSK).
  - `week8/day3/day3_report.md` — полный отчёт.
- **Ключевая идея:** Все тесты — pure JVM (без Android SDK / Robolectric), проходят на VPS. Покрытие непокрытых domain-модулей → 100%.

---

### week8 / day4 — Local Boost (сравнение Qwen vs Cursor)

- **Что сделано:** Сравнение Qwen 2.5 Coder 14B (локально, Ollama на Mac M4) vs Cursor Sonnet 4.6. Две задачи: `WakeupInterval` data class и `SleepCard` Composable.
- **Артефакты:**
  - `week8/day4/comparison.md` — сводная таблица результатов.
  - `week8/day4/cursor_sonnet/` — файлы Cursor-генерации.
  - `week8/day4/qwen_no_rules/` и `qwen_with_rules/` — файлы Qwen без/с правилами.
  - `week8/day4/qwen-rules.md`, `day4_local_boost_plan.md`.
- **Ключевая идея:** Qwen без правил — 2/5 (даже React Native выдал вместо Compose). Qwen с правилами — 2/5 (улучшился стек, но Material 3 и пакеты — нет). Cursor Sonnet — 4/5 (читает `CLAUDE.md`, понимает конвенции пакетов).

---

### week8 / day5 — Execution Loop (автономный прогон)

- **Что сделано:** 15 issues в `AlDtoll/zizz3` (3 бага, 4 фичи, 3 рефакторинга, 3 теста, 2 доки) с меткой `ai-challenge-advanced-day5`. Автономный прогон через субагентов батчами.
- **Артефакты:**
  - `week8/day5/exec_loop_report*.md` (батчи 1, 2, 3, 4).
  - `week8/day5/bug4_fix_report.md` — реальный prod-баг `rescheduleOrRecalculate`.
  - `week8/day5/exec_loop_summary.md` — сводка.
  - `week8/day5/issues_created.json`, `run2_pool.json` — метаданные.
- **Метрики:** 11 из 15 issues закрыто (73%). Серия без вмешательства: 11/11. Fail: 0. Среднее время: ~50 сек (простые) / ~5-6 мин (сложные). Затыков концептуальных: 0.
- **Ключевая идея:** «Не написать код, а не сломать проект». Запрет Gradle → минимальное точечное изменение → высокое качество без соблазна рефакторить попутно.

---

**Итог недели 8:** `FINAL_SUBMISSION.md` — сводный набор для сдачи, все 5 дней.
**Постмортем:** `postmortem.md` — ранжирование правил v2 по величине эффекта. Спонтанные бонусы v2 (богаче domain, отдельный UI-компонент).
**Доп. материалы:**
- `zizz3-analysis.md`, `zizz3-CLAUDE-v1.md` — анализ проекта и черновик правил.
- `bonus_twilights_world/application_plan.md` — план применения методологии к RPG-ботам (3 профиля-роли), не реализован.
- `aleknock/aleknock-analysis.md` — анализ применимости к C#/.NET проекту Саши.
- `video-script.md`, `video-script-day1-v2.md`, `video-script-day1-v3-linear.md`, `final_video_script.md` — сценарии.
- `slides_outline.md` — 8 слайдов для CapCut/PowerPoint.

---

## Week 9 — Fine Tuning языковых моделей

**Лекция:** `week9/9неделя1поток-{расшифровка,саммари}.md`

**Ключевые тезисы лекции:**
- Fine Tuning — дообучение под домен, не с нуля. Современный стандарт: LoRA / Q-LoRA.
- Качество данных > количество: 100 хороших примеров лучше 10 000 «шумных».
- FT нужен только когда prompt engineering, RAG и few-shot уже не справляются.
- Q-LoRA 4-bit: обучение на домашнем GPU (RTX 3090/4090 ~18 ГБ), без A100.
- Следить за overfitting: рост на train при падении на val/test — расширять датасет.

---

### week9 / day6 — DataSet (Zizz3 fine-tune датасет)

- **Что сделано:** Датасет для fine-tune локальной LLM под стиль Zizz3 (Kotlin + Compose). Цель: закрепить математически то, что промптом не удалось — Material 3, `require`-инварианты, `stringResource`, KDoc-русский.
- **Артефакты:**
  - `week9/day6/dataset/train.jsonl` (50 примеров) + `eval.jsonl` (15), стратифицированный split по 5 типам.
  - `week9/day6/scripts/validate.py` — валидатор (65 строк, 0 ошибок PASS).
  - `week9/day6/scripts/baseline.py` — прогон 10 eval через Qwen 14B без fine-tune.
  - `week9/day6/scripts/finetune_client.py` — Unsloth Q-LoRA клиент (готов, не запускался).
  - `week9/day6/criteria.md` — 5 бинарных критериев × 15 eval = 75 баллов, порог успеха +30%.
  - `week9/day6/README.md` — полная инструкция воспроизведения.
  - Запушено в `AlDtoll/zizz3` ветка `advanced-week9-day6`, папка `docs/ai_challenge_advanced_week9_day6/`.
- **Боковой трек (Twilights-арбитр, отложен):**
  - `week9/day6/twilights_run007_violations.md` — 24 кандидата нарушений из чата `run_007 «Троица»`.
  - `week9/day6/twilights_labels_partial.md` — разметка первых 10: 6y / 3n / 1e (Данил). Паттерн: «мета в игровом чате» для альфа-стадии ≠ нарушение.
- **Ключевая идея:** JSONL ChatML-формат совместим с OpenAI fine-tune API и Unsloth — если OpenAI разблокируют, датасет переиспользуем без правок.

**Статус паузы (2026-07-30):**
- Для сдачи нужно: запустить `baseline.py` на Маке, записать видео ~2 мин.
- Следующий день (day7+) не получен — ждать формулировку от лектора.
- `finetune_client.py` готов к Q-LoRA на Qwen 2.5 Coder 7B.

---

## Week 10 — Security для LLM

**Лекция:** `week10/day11/10неделя1потокАдванс-{расшифровка,саммари}.md`
**Видео лекции:** https://disk.yandex.ru/i/m0YT5pvlX4l2fg

**Ключевые тезисы лекции:**
- GR-риски (государство/закон) > финансовых/репутационных — могут закрыться уголовкой.
- LLM принципиально недетерминирована → угрозу нельзя предусмотреть исчерпывающе.
- Defense in depth: rate limit → input validation → prompt hardening → output guard.
- LLM Gateway: единая точка входа/выхода с бюджетами, логированием, аудитом.
- Secure AI SDLC: generation → tests → security review → перегенерация при провале.

**Общий статус:** `week10/WEEK10_STATUS.md` — все 4 дня (11-14) закрыты 2026-08-07.
**Полный memory-отчёт:** `memory/project_ai_challenge_week10_security.md` — детали, код, инсайты.

---

### week10 / day11 — Prompt Injection

- **Что сделано:** 3 текстовые техники (role-play DAN / instruction override + impersonation / prompt extraction) — все blocked на @sitebystro. 5 real-world примеров. Атака на common-бот (Attack 1 v1→v5, от `partial` до `blocked`). 3-слойная защита реализована.
- **Артефакты:**
  - `week10/day11/real_world_injections.md` — 5 реальных кейсов с классификацией и разбором защит.
  - `week10/day11/guest_attack_plan_2026-08-07.md` — план атаки v1→v5.
  - Защита: L1 `~/.claude/hooks/skill-injector.sh` (sensitive-фильтр), L2 правила в `CLAUDE.md` (секция Prompt Injection Defense), L3 `~/.claude/hooks/output-guard.sh` (regex на IP/creds/token в reply гостю).
  - Сценарий видео: 8 сцен ~3 мин (немой скринкаст с overlay'ами) — `day11/video_script.md` (не в листинге, но упомянут в WEEK10_STATUS).
- **Ключевая идея:** Lethal Trifecta (Willison) — untrusted input + sensitive data + external comm. Обрубить любую одну ногу снимает класс атак.

---

### week10 / day12 — Indirect Prompt Injection Lab

- **Что сделано:** 3 вектора атаки + 3 защиты + reproduce Copilot-style кейса. Полная лаборатория.
- **Артефакты:** `week10/day12/` (21 файл): attacks/, agents/, defenses/, tests/, reproduce/, results/.
  - REPORT.md — полный отчёт с таблицей результатов.
  - `attacks/vector1_email.txt` (HTML-comment + color:#fff), `vector2_document.md` (zero-width + markdown link title), `vector3_webpage.html` (display:none div).
  - `defenses/sanitize_html.py`, `content_boundary.py`, `output_validator.py`.
  - `reproduce/poisoned_code.py` + `code_reviewer.py` — Copilot-style reproduce.
- **Ключевая находка:** DeepSeek 2026 устойчив к базовым паттернам indirect injection (все 3 вектора без защиты — SAFE). Mock_llm (уязвимая симуляция) подтверждает корректность breach-детекторов. Реальные атаки требуют более тонких методов (Base64, multi-hop, tool-poisoning).

---

### week10 / day13 — LLM Gateway

- **Что сделано:** FastAPI-прокси `:8100` между user и DeepSeek API. OpenAI-compatible. 19/19 pytest PASS.
- **Артефакты:** `~/tools/llm-gateway/` (14 файлов + audit.db). Отчёт: `week10/day13/REPORT.md`.
  - Эндпоинты: `POST /v1/chat/completions`, `GET /audit/log`, `GET /stats/cost`, `GET /health`.
  - Input Guard: 10 regex-паттернов (секреты, PII, credit card + Luhn check), режим block/mask.
  - Output Guard: 5 проверок (secrets, system prompt extraction, suspicious URLs, dangerous commands, email leak).
  - Rate limit: slowapi 60 req/min per IP. Audit: SQLite. Cost tracking: DeepSeek прайсинг.
  - Тесты: 12 unit (input_guard) + 7 unit (output_guard) + E2E 7 сценариев.
- **Ключевая идея:** Gateway — переиспользуемый артефакт для любых проектов. Запуск: `bash ~/tools/llm-gateway/run.sh`.

---

### week10 / day14 — Security Step через Gateway (Execution Loop)

- **Что сделано:** Execution loop: generation → lint → security_review → decide (BLOCK/WARN/OK, max 3 итерации). Все LLM-вызовы через Gateway из day13.
- **Артефакты:** `week10/day14/` (14 файлов + generated/).
  - `loop.py` — оркестратор.
  - `prompts/generation.md`, `prompts/security_review.md` — Kotlin/Android security prompt.
  - `generated/task{1,2,3}/` — Kotlin-код каждой итерации + JSON verdict.
  - `results/all_tasks.json` — сводный trace.
- **3 задачи:**
  | Задача | Итераций | Финал |
  |---|---|---|
  | Сохрани токен авторизации | 2 | committed_clean (iter1 plain-SP → BLOCK, iter2 EncryptedSP → OK) |
  | Логируй запросы | 3 | committed_with_warnings (Auth-в-логах → BLOCK, redactHeader → WARN принято) |
  | API запрос с hardcoded key | 3 | blocked_max_iterations (hardcoded API_KEY + HTTP → BLOCK×3) |
- **Ключевая идея:** Security-step корректно блокирует Critical/High, принимает после исправления, не пропускает упрямую модель. Gateway audit.db — 33 записи, $0.0041 суммарно.

---

### week10 / day15 — Red-team match с @oxaexa

- **Что сделано:** Парная работа. Мы атаковали бота @oxaexa (ip 24.199.94.244:8090, gpt-4o-mini, 7 layers). Он получил доступ к нашему `ai-target-bot` (ip [VPS_IP]:8091, 10 layers, 58/58 pytest).
- **Наш target bot:** `~/sessions/common/workspace/ai_target_bot/`. Ночью 12.08 закрыто 12 дырок до передачи endpoint.
- **Хронология:**
  | Когда | Кто | Payload'ов | Итог |
  |---|---|---|---|
  | 10.08 | Мы → @oxaexa | 73 (3 раунда) | Все blocked (200 OK, injection не прошёл) |
  | 11.08 | Мы → @oxaexa v2 | 42 | Все 401 (auth-слой, добавлен Bearer) |
  | 12.08 08:15+ | @oxaexa → нас | 0 | Не атаковал на момент паузы |
- **Артефакты:**
  - `reports/redteam_ai_advent_pipeline_2026-08-10.md` + `_public.md`.
  - `reports/redteam_partner_v2_2026-08-12.md`.
  - `/tmp/attack-results-*.jsonl`, `/tmp/redteam_v2_results.log`.
- **Открытые вопросы:** нужен новый PARTNER_KEY от @oxaexa (v2 auth-слой). Ветка `week10/day15` в `AlDtoll/ai-challenge` запушена.

---

## Быстрый справочник: где что лежит

| Тема | Путь |
|---|---|
| Правила Zizz3 v2 | `AlDtoll/zizz3:main/CLAUDE.md` (777 строк) |
| Постмортем week8 | `week8/postmortem.md` + `AlDtoll/zizz3:main/docs/ai_challenge_advanced_day1_postmortem.md` |
| Профили агентов Zizz3 | `AlDtoll/zizz3:main/.claude/agents/{bug-fix,research,screenshot-baseline}.md` |
| Датасет fine-tune | `AlDtoll/zizz3:advanced-week9-day6/docs/ai_challenge_advanced_week9_day6/` |
| LLM Gateway (переиспользуемый) | `~/tools/llm-gateway/` |
| Правила Prompt Injection Defense | `~/sessions/common/workspace/CLAUDE.md` (последняя секция) |
| Skill-injector фильтр | `~/.claude/hooks/skill-injector.sh` |
| Output-guard хук | `~/.claude/hooks/output-guard.sh` |
| Target bot (week10) | `~/sessions/common/workspace/ai_target_bot/` |

---

## Gaps (то, где артефактов нет — не копал)

- **Week 1 day 1** base: нет ветки в репо
- **Week 8 day 1** advanced: артефакты не в отдельной папке (в корне `week8/` и в `AlDtoll/zizz3`) — см. FINAL_SUBMISSION.md / postmortem.md
- **Week 9 day7+** advanced: задание от лектора не поступило, работа паузнута 2026-07-30 (см. memory `project_ai_challenge_week9_pause`)
- **Week 10 day 11 video_script.md**: упомянут в WEEK10_STATUS, но на диск не сохранён
- **Attack @oxaexa на наш target bot** (Day 15): ждём его хода, артефактов нет

## Быстрые ссылки на related memory

- `project_ai_challenge` — base трек overview
- `project_ai_challenge_advanced` — advanced трек overview
- `project_ai_challenge_week9_pause` — точка паузы, состояние Zizz3-датасета
- `project_ai_challenge_week10_security` — Week 10 полный overview (дни 11-14)
- `project_ai_challenge_week10_day15_redteam` — Day 15 red-team match
