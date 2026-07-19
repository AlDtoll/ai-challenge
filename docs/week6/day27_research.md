# Day 27 — Локальная LLM в реальном приложении. Разбор решений участников

Дата: 2026-07-11
Наш день: `week6/day2` (Day 27 «Интегрировать локальную LLM в реальное приложение»). Стек Данила: Kotlin JVM, Java HttpClient + Gson (без Retrofit/OkHttp), Windows/JDK 17, Ollama + qwen2.5:7b + nomic-embed-text. Приложение — интерактивный **RAG-CLI**: `ingest <папка>` → индекс md-файлов → `chat` → вопросы с retrieval и цитатами.

Источник авторов: OAuth-токен sheets-reader протух (`invalid_grant`) → использован GitHub API по 74 репо участников (кэш `final_day_map.json`) с поиском по веткам `day27 / day_27 / day-27 / week6/day-2*`. Найдено 5 репо с явной day27-веткой:

| Ник | Автор | Репо | Ветка | Стек | Тип приложения |
|---|---|---|---|---|---|
| kaa | Круглов Андрей | kaa-it/AvaloniaAI | `day27` | **C# .NET 9 + Avalonia** | Desktop-агент (WebAPI + Avalonia UI) |
| sergio | Ssh sh | Sergio-rsd/AI-Challenge | `week6/day-2-total-27` | **Kotlin JVM** | CLI-RAG на реальном Telegram-корпусе |
| mobdev | MovDev | mobdev778/aiadventchallenge8 | `day_27` | **Kotlin + JB Compose** | IntelliJ IDEA-плагин с чатом |
| dgor | Данил Горячковский | dgoryachkovskiy/AiTgsterBot | `codex/day_27_local_llm_app` | Python | CLI без RAG, `argparse` |
| dpmn | Олег Ионов | dpmn/ai-advent-challenge | `day-27` | Python + Flask | Веб-UI, дропдаун моделей |

Список авторов: `/tmp/day27_authors.json`. Локальные копии для перечитывания: `/tmp/day27_research_repos/{kaa,sergio,mobdev,dgor,dpmn}/`.

Итог: самый близкий эталон к нашему стеку и задумке — **sergio** (Kotlin CLI-RAG, тот же qwen2.5:7b + nomic-embed-text, всё тем же nativным `/api/chat`). **dgor** — идеальный минимальный контр-пример «CLI без RAG» — полезен как список обязательных мелочей (диагностика, JSONL-трейс). **kaa** даёт трюк со `response_format: json_schema` через LM Studio. **mobdev** и **dpmn** сильно перегружены обвязкой — брать почти нечего, только идеи.

---

## 1. sergio (Kotlin JVM, RAG-CLI на Telegram-корпусе) — главный эталон

Репо: `Sergio-rsd/AI-Challenge`, ветка `week6/day-2-total-27`.
Файлы: `src/apps/Week6Day2LocLLMApp.kt` (**1473 строки** — вся логика REPL/индексации/tool-use), `src/apps/Week6Day1LocalLLM.kt` (`OllamaClient` из дня 26).

### 1.1 Что за приложение

CLI-ассистент по теме «программирование и нейронные сети» — отвечает по корпусу, собранному из **реального экспорта Telegram Desktop** (канал AI Advent Challenge #8) + опционально загруженных документов. Т.е. функционально то же, что у нас: `ingest → chat`, но:
- индексируемый источник — не md-файлы, а `result.json` от Telegram Desktop;
- retrieval-пайплайн наследован с недели 5 (rewrite → threshold → LLM-судья-реранк → JSON-ответ с источниками);
- поверх RAG — MCP-сервер заметок (тот же, что в неделе 4) с командой `!save` через tool-use.

Есть **диспетчер `callLlm`** — единая точка, где решается: локальная модель (`generateGuarded` с CJK-guard'ом) или облачная (`LlmClient.sendRequestFull`). Все стадии пайплайна (rewrite/реранк/ответ/память) вызывают только эту функцию — им не важно, какая модель. Пользователь при старте выбирает `[1] локальная / [2] облачная`. **Это важная идея для дня 28** — не надо переписывать пайплайн ради переключения провайдера.

### 1.2 Стек

- **HTTP:** `java.net.http.HttpClient` (нативный JDK, без Retrofit). У нас так же.
- **JSON:** `org.json` (`JSONObject`, `JSONArray`). У нас Gson — эквивалентно.
- **Модели по умолчанию:** ответы — `qwen2.5:7b-instruct` (совпадает с нашим `qwen2.5:7b`), эмбеддинги — `nomic-embed-text` (совпадает), LLM-судья при индексации — отдельно выбираемая лёгкая (`qwen2.5:1.5b-instruct` предлагается специально).
- **Ollama endpoint:** нативный `/api/chat` (не OpenAI-compat `/v1`), `stream=false`. Как у нас.
- **Стор:** SQLite `domain_assistant.db` (`DocumentIndex`, наследие недели 5), с проверкой совпадения размерности эмбеддинга при открытии.

### 1.3 REPL — команды

```
<вопрос>                 — rewrite → threshold → LLM-судья → ответ с источниками
!index-telegram <путь>   — индексировать экспорт Telegram Desktop (result.json)
!index-doc <путь>        — индексировать документ (.pdf/.docx/.doc/.md/.txt)
!save [заголовок]        — сохранить последний ответ в заметку (tool-use через MCP)
!eval                    — прогнать 6 контрольных вопросов, сводка в конце
!memory                  — снимок памяти задачи
!history                 — вся история диалога
!sources                 — список источников в базе
!reset                   — начать новый диалог
exit / выход             — завершить работу
```

**Что можно позаимствовать у Данила:**
- `!sources` — сейчас у нас есть, ок.
- **`!memory`** — снимок task_state прямо в чат: полезно для отладки RAG-ответов.
- **`!eval`** — прогон 6 контрольных вопросов с чистой историей на каждом (не как `!scenario` дня 25 — там был многоходовый диалог), сводка в конце. Наш `:stats` показывает только последний ответ — а `!eval` показывает регресс по всей базе. **Стоит взять.**
- **`!reset`** — очистка истории и памяти БЕЗ пересоздания индекса. У нас `:clear` уже так делает.
- **Стартовый мастер** (модель судьи, модель ответов, TOP_K до/после, порог similarity, порог реранка, порог шума) — параметры пайплайна прямо из REPL при старте. У нас всё захардкожено. Не обязательно копировать, но `TOP_K` через arg — 5-минутное улучшение.

### 1.4 Retrieval-пайплайн — устройство (стоит понять для дня 28)

Из README (`README_Week6Day2LocLLMApp.md`, 596 строк, стоит прочитать целиком):

1. **Индексация** (`!index-telegram`):
   - **Слой 1** (эвристики без LLM): `TelegramChatReader.filterNoiseHeuristics` — фильтр по длине, доле букв, дедупу точных повторов.
   - **Слой 2** (LLM-судья): `judgeNoiseWithLlm` — батч-оценка каждого треда 0-10, всегда локальной моделью (независимо от того, какая отвечает на вопросы), порог по умолчанию 4.
   - **Дедуп по контенту** при повторной индексации: экспорт Telegram кумулятивный, `existingTelegramChunks` сравнивает текст с уже сохранёнными — переиндексируются только новые/изменившиеся треды.
   - **Подчанкинг длинных тредов** (`buildChunks`, `THREAD_CHUNK_SIZE=1500`): режет по границам сообщений, не разрывая сообщение посередине. Короткий тред → один чанк с id `tg_<slug>_thread_<rootId>`, длинный → `..._p0/_p1/...`.
2. **Ответ** на вопрос:
   - `REWRITE_SYSTEM` — переписать вопрос как самостоятельный поисковый запрос (разворот местоимений, синонимы) с учётом истории и task_memory.
   - `RERANK_SYSTEM` — LLM-судья 0-10 для каждого кандидата.
   - `ANSWER_SYSTEM` — JSON-ответ с `answer / insufficient_context / sources / quotes`. `quotes` — дословные фрагменты из чанков (не пересказ) — **дешёвая структурная проверка от галлюцинаций** (обнаружено уже в этом дне, поле `quotes` возвращено после того, как `!eval` поймал реальную галлюцинацию).
   - `TASK_MEMORY_SYSTEM` — обновление памяти задачи после ответа.

### 1.5 Формат task_state (наследие дня 25)

```kotlin
private class DomainTaskMemory(
    var goal: String = "",
    val clarifications: MutableList<String> = mutableListOf(),
    val constraints: MutableMap<String, String> = mutableMapOf()  // fixed_terms
)
```

Плоская, три поля. Совпадает с нашей week5/day5 (`goal / clarifications / fixed_terms`), но:
- нет `open_questions` (у Данила было).
- нет `constraints` как списка (Данил различал `constraints: List` и `fixed_terms: Map` — sergio совместил в одно `Map`).

**Обновление state** — ОТДЕЛЬНЫЙ LLM-вызов после ответа (не в одном JSON, как у Данила). Промпт возвращает JSON `{goal, new_clarifications, new_constraints}`, детерминированно мерджится. По времени — второй вызов, лишние 1-2 сек, но state консистентнее (модель уже видит финальный ответ и решает, что добавить).

### 1.6 CJK-guard — важная деталь для локалок (день 26 → 27)

`generateGuarded` (в `Week6Day1LocalLLM.kt`) оборачивает вызов локальной модели проверкой доли CJK-символов в ответе — если больше порога, повторяет запрос с усиленным русским system-prompt'ом. Причина: qwen2.5 после длинного русского контекста иногда «уходит» в китайский. **У нас этого нет.** Стоит держать в голове для дня 28 — если поймаем дрейф на длинных беседах, брать эту защиту.

### 1.7 Стоит / не стоит брать

**Взять:**
- `!eval` с фиксированным списком контрольных вопросов и метриками в конце (регресс-тест RAG за один прогон).
- `!memory` — снимок task_state в REPL для отладки.
- Идея `callLlm` как единого диспетчера local/cloud (пригодится в дне 28, когда добавим tool-use через MCP).
- `search_query:`/`search_document:` префиксы для nomic — у нас уже есть, но проверить, что они реально дают +1-2% recall.
- Флаг `insufficient_context: true` в JSON-ответе — модель сама честно говорит «не знаю», а не выдумывает. У нас сейчас нет — модель может ляпнуть невпопад.

**Не брать:**
- Всю индексацию Telegram-корпуса — нам это ни к чему (mission — папки md-файлов).
- Двухуровневый шумовой фильтр (Слой 1 + Слой 2 LLM-судья) — избыточно для md-файлов, где шума нет.
- SQLite для индекса — у нас `index.json` работает, для 100-1000 чанков разницы нет. SQLite вводить только если появится инкрементальная индексация.

---

## 2. dgor (Python, CLI без RAG) — минимальный чистый эталон CLI

Репо: `dgoryachkovskiy/AiTgsterBot`, ветка `codex/day_27_local_llm_app`. Файлы: `day27_local_llm_app.py` (521 строка) + `DAY27_LOCAL_LLM_APP_REPORT.md`.

### 2.1 Что за приложение

Чистый CLI с `argparse` — **три команды**:
- `status` — Ollama живой, какая модель установлена.
- `ask "<промпт>" --session-id X --model M` — один ход диалога.
- `demo --model M` — прогоняет `DEMO_PROMPTS` (3 захардкоженных вопроса) и генерирует markdown-отчёт с таблицей `turn | ok | elapsed | chars`.

**RAG нет.** Просто чат по локальной модели + persistence сессии на диске + автогенерируемый отчёт для сдачи задания. Задание он трактует буквально: «приложение отправляет запросы, получает и отображает ответы, работает без облака» → доказательство работоспособности → отчёт.

### 2.2 Стек

- **HTTP:** stdlib `urllib.request` (без `requests`). Нативно.
- **`/api/chat`, stream=False, temperature=0.2** (`local_chat_request`, L238).
- **Стор:** плоские файлы:
  - `<store>/<safe_session_id>/messages.json` — история сообщений (последние 8 в промпт, `DEFAULT_RECENT_MESSAGES=8`).
  - `<store>/<safe_session_id>/trace.jsonl` — append-only трейс каждого хода (JSONL, для аудита).
- **Автостарт Ollama:** `start_ollama_if_needed()` (L128) — если API не отвечает, пытается запустить `ollama serve` как subprocess.
- **Отчёт:** таблица через markdown, готовый файл в `DAY27_LOCAL_LLM_APP_REPORT.md`.

### 2.3 Стоит / не стоит брать

**Взять:**
- **JSONL-трейс каждого хода** (`trace.jsonl`) — append-only, для отладки. У нас `:save` сохраняет только по команде — а JSONL пишется всегда, легко потом грепать. Полезно.
- **`--demo` режим** с готовыми prompts и авто-отчётом — 30 минут работы, зато видео пилится за один прогон. Хороший приём для сдачи задания.
- **`app_type / provider / local_only / cloud_models_used` в отчёте** (см. `DAY27_LOCAL_LLM_APP_REPORT.md`, начало) — организаторы явно требуют «без облака» → в отчёте зафиксировать это явно.

**Не брать:**
- Автостарт Ollama-сервера как subprocess — у Данила Windows, `ollama.exe serve` пусть будет ручной задачей. Auto-launch с Windows-путями хрупкий.
- Отсутствие RAG — Данил уже сделал RAG, откатываться не имеет смысла.

---

## 3. kaa (C# .NET 9, Avalonia + WebAPI) — идея с response_format

Репо: `kaa-it/AvaloniaAI`, ветка `day27`. Два .csproj: `Agent/` (WebAPI, порт 5333) и `AvaloniaAI/` (десктопный UI).

### 3.1 Что за приложение

Разделённая архитектура: `Agent` — HTTP-сервер (endpoint `POST /message` возвращает `MessageResponse`), `AvaloniaAI` — десктопный UI на Avalonia, дергает Agent. Пользователь общается с UI, UI шлёт в Agent, Agent идёт в **LM Studio** (`http://localhost:1234/v1/chat/completions`, OpenAI-совместимый).

Есть выбор провайдера через DI (`IConversationManager`): `ClaudeConversationManager` (облачный) или `OpenAICompatibleConversationManager` (локальный). Управляется через `appsettings.json`. По сути, тот же callLlm-диспетчер, но через .NET-DI.

### 3.2 Ключевая деталь — structured output через json_schema

В `OpenAICompatibleConversationManager.CompleteAsync` (L56):

```csharp
if (requiresJsonOutput)
{
    var schemaNode = JsonNode.Parse(PlanJsonSchema.Schema.GetRawText());
    requestBody["response_format"] = new JsonObject
    {
        ["type"] = "json_schema",
        ["json_schema"] = new JsonObject
        {
            ["name"] = "plan",
            ["strict"] = true,
            ["schema"] = schemaNode
        }
    };
}
```

Это **фича LM Studio / OpenAI-compat endpoint'а**, но НЕ нативного `/api/chat` Ollama. LM Studio форсит модель выдать JSON, соответствующий схеме (`PlanJsonSchema.cs`). У Ollama есть аналог — параметр `format: "json"` или (с 0.5.0) `format: {schema}`. **Стоит проверить**, поддерживает ли наша версия Ollama structured output через `format: <json_schema>`. Если да — уберём retry-loop дня 24, где мы 3 раза дёргаем модель, если JSON битый.

### 3.3 Стоит / не стоит брать

**Взять:**
- Проверить `format: {schema}` в `/api/chat` Ollama — Ollama Docs, v0.5+. Если работает — упрощение RAG-ответа. TODO на день 28.
- **Sliding window + StickyFacts** для истории (`ConversationOptions`, `HistoryStrategy`) — идея разделять «свежую историю (окно 15)» и «закреплённые факты (лимит 20)». Хорошо ложится на нашу задачу удержания фактов через 10-15 реплик. У нас `Session.history` пока просто конкатенируется — уплывает по контексту.

**Не брать:**
- Avalonia UI — Данилу не нужен GUI для day27, CLI достаточно (и лаконичнее для видео).
- LM Studio как альтернатива Ollama — Ollama и так стоит.

---

## 4. mobdev (Kotlin + JB Compose Desktop, IDEA-плагин) — контр-пример перегрузки

Репо: `mobdev778/aiadventchallenge8`, ветка `day_27` — **это первый и единственный коммит** («Day 27 Task.», 65+ файлов, вся кодовая база сразу).

### 4.1 Что за приложение

Полноценный **плагин IntelliJ IDEA** с чатом. Экраны (`presentation/`): список чатов, экран чата, экран настроек, экран task-контекста, экран MCP-сервера. Слои: `data / domain / presentation`, DI через Koin, БД Room, HTTP через Retrofit.

**Стек:** Kotlin + Jetpack Compose Desktop + Koin + Room + Retrofit + Coroutines. Пример «промышленной обвязки для чата». Backend — LM Studio `http://localhost:1234/v1/chat/completions` (`SettingsRepository.kt` L72), меняется через настройки плагина.

### 4.2 Есть ли RAG

RAG нет. Но есть **`StickyFactsRepository`** — хранит в Room-таблице `chat_id + message_id`, помеченные пользователем как «важные». Позволяет пришивать эти сообщения к каждому промпту (аналог нашей `task_memory.fixed_terms`, но извлекается вручную юзером, а не LLM).

### 4.3 Стоит / не стоит брать

**Не брать почти ничего** — весь код заточен под IDEA-плагин SDK. Единственная идея — **StickyFacts как пользовательская команда** (`:pin <msg>` в REPL, чтобы пришпилить конкретную реплику к контексту). Проще, чем task_state.update от LLM.

Retrofit — overengineering для нашего минимализма. `java.net.http.HttpClient` + Gson уже есть.

---

## 5. dpmn (Python + Flask WebUI) — контр-пример «конфиг вместо кода»

Репо: `dpmn/ai-advent-challenge`, ветка `day-27`. Файлы: `week-06/day-27/README.md` (только описание, кода в этой папке нет). Правки — в `webui/app.py` и `webui/static/script.js`.

### 5.1 Что за приложение

Существующий Flask WebUI (наследие с прошлых недель, jarvis-стиль). Изменения дня 27 — **дропдаун моделей**: добавили `qwen2.5-coder:7b` как ещё одну модель, привязали к `(base_url, api_key)` через карту `MODEL_PROVIDERS`, при выборе локальной — базовый URL переключается на `http://localhost:11434/v1` (Ollama через OpenAI-compat).

Т.е. буквально «интегрировали локалку через 20 строк конфига в существующий стек». Задание сделано, но кода — почти ноль.

### 5.2 Осознанные ограничения (в README)

> Эмбеддинги памяти/RAG остаются облачными (`openai/text-embedding-3-small` через Cloud.ru) — при срабатывании векторной памяти запрос за эмбеддингом уйдёт в облако даже при выбранной локальной модели.

Т.е. **не все компоненты локальные** — только чат-LLM. Автор явно фиксирует это как ограничение.

### 5.3 Стоит / не стоит брать

**Взять:**
- **Идея «локальная модель — просто новый провайдер в дропдауне»** — валидна, если бы у Данила был WebUI. Для CLI неактуально.
- **Явная фиксация «что осталось облачным»** — правильный тон. У нас всё локальное (nomic + qwen), это стоит **явно написать в README day27**, потому что это не тривиально: некоторые лепят «локальную LLM» + облачные эмбеддинги.

**Не брать:**
- Никакого GUI/веба — сохраняем минимализм.

---

## 6. Сводка: что взять / что избегать / что проверить

### Стоит взять в наш `week6/day2`

1. **`:eval` команда** (у sergio `!eval`) — 3-5 контрольных вопросов, каждый прогоняется с чистой историей, сводка в конце. Регресс-тест RAG за один прогон. **Приоритет: высокий, 30 минут.**
2. **JSONL-трейс каждого хода** (у dgor `trace.jsonl`) — append-only лог `{question, answer, sources, elapsed, model}`. Полезно для видео и отладки. **30 минут.**
3. **`insufficient_context: true` в JSON-ответе** — модель сама говорит «не знаю», а не галлюцинирует. У нас сейчас retry-loop гоняется, пока модель не выдаст структурированный JSON, но что там внутри — не проверяем. **Приоритет: средний.**
4. **Явная фиксация «cloud_models_used=false»** в README и в `--demo` отчёте — организаторы явно требуют «без облака». Написать один параграф про то, что и nomic-embed, и qwen2.5:7b — оба локальные. **10 минут.**
5. **Диспетчер `callLlm(local|cloud)`** — для дня 28 пригодится, когда добавим MCP и захотим переключаться. Пока не делать, отложить в задел.

### Проверить (потенциальные упрощения)

6. **`format: {schema}` в `/api/chat` Ollama** (Ollama 0.5+) — если работает, убираем retry-loop дня 24 и упрощаем ответ RAG до одного вызова с гарантированной схемой. Проверить `curl -X POST http://localhost:11434/api/chat -d '{"format": {"type": "object", "properties": {...}}}'`.
7. **CJK-guard** (у sergio) — оценить, есть ли у нашей qwen2.5:7b языковой дрейф на длинных беседах. Если есть — добавить простую проверку «доля русских букв в ответе > порога, иначе повтор».

### Точно не брать

- **SQLite вместо `index.json`** — на 100-1000 чанков разницы нет, добавляет зависимость.
- **Индексация Telegram-корпуса** — не наша задача.
- **GUI (Avalonia / Compose Desktop / веб)** — CLI достаточно.
- **Retrofit / Ktor** — `java.net.http.HttpClient` уже работает.
- **Автостарт `ollama serve` как subprocess** — Windows-путь хрупкий, `ollama.exe` пусть запускается юзером один раз.

### Что у нас уже сделано лучше, чем у большинства

- Единый `Main.kt` в 412 строк vs sergio 1473 (специфика — Telegram-корпус + tool-use через MCP) или mobdev 65+ файлов. Минимализм — наша сила для day27.
- `search_query:` / `search_document:` префиксы для nomic — у нас есть, ни у кого из разобранных нет.
- Fixed-size 800 + overlap 150 с уважением к абзацу — простая понятная нарезка (у sergio есть подчанкинг по границам сообщений, но там другой корпус; для md-файлов наш вариант ок).

---

## Приложение: команды, полезные ссылки

Клоны участников: `/tmp/day27_research_repos/{kaa,sergio,mobdev,dgor,dpmn}/`.
Список авторов JSON: `/tmp/day27_authors.json`.

Прямые ссылки на day27-код:
- sergio: https://github.com/Sergio-rsd/AI-Challenge/blob/week6/day-2-total-27/src/apps/Week6Day2LocLLMApp.kt
- sergio README: https://github.com/Sergio-rsd/AI-Challenge/blob/week6/day-2-total-27/README_Week6Day2LocLLMApp.md
- dgor: https://github.com/dgoryachkovskiy/AiTgsterBot/blob/codex/day_27_local_llm_app/day27_local_llm_app.py
- kaa: https://github.com/kaa-it/AvaloniaAI/tree/day27/Agent/Services
- mobdev: https://github.com/mobdev778/aiadventchallenge8/tree/day_27
- dpmn: https://github.com/dpmn/ai-advent-challenge/blob/day-27/week-06/day-27/README.md
