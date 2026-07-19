# Day 28 — Локальная LLM + RAG (сравнение с облаком). Разбор решений участников

Дата: 2026-07-11
Наш день: `week6/day3` (Day 28 «Подключите локальную LLM к RAG-пайплайну; сравните local vs cloud по качеству/скорости/стабильности»). Наш день 27 (`week6/day2`) уже полностью локальный (nomic-embed-text + qwen2.5:7b через Ollama, native `/api/chat`), с REPL, `:eval`, JSONL-трейсом. План на day28: расширить `:compare <вопрос>` и `:eval --both`, добавить облачную DeepSeek через OpenRouter, раздел «Оценка».

Источник авторов: OAuth-токен sheets-reader снова протух (`invalid_scope` — тот же класс проблемы, что в day27_research). Использован GitHub API по 76 репо участников (кэш `final_day_map.json`) с поиском по веткам `day28/day_28/day-28/week6/day-3*/w6-d3`. Найдено 5 репо с явной day28-веткой (те же 5 авторов, что в day27 — костяк регулярно сдающих). Denis Suprun (упомянут в задании как уже сдавший release `week_6_day_28`) в кэше не найден и GitHub search не нашёл — вероятно, ссылка в свежем комментарии, недоступна без работающего sheets-reader.

| Ник | Автор | Репо | Ветка | Стек | Тип приложения |
|---|---|---|---|---|---|
| sergio | Ssh sh | Sergio-rsd/AI-Challenge | `week6/day-3-total-28` | **Kotlin JVM** | CLI RAG + `!eval`/`!stability` + local vs cloud на ОДНОМ контексте |
| dgor | Данил Горячковский | dgoryachkovskiy/AiTgsterBot | `codex/day_28_local_llm_rag` | Python + urllib + openai SDK | CLI `argparse`: status/ask/compare/verify + автоотчёт |
| mobdev | MovDev | mobdev778/aiadventchallenge8 | `day_28` | **Kotlin + JB Compose + langchain4j ONNX** | IDEA-плагин, RAG-чат, embed в JVM без Ollama |
| dpmn | Олег Ионов | dpmn/ai-advent-challenge | `day-28` | Python + Flask + FAISS | Web UI, дропдаун моделей, `/rag config strict on` |
| avalanche | Anastasia Anisimova | 1Avalanche/SmartReminder | `week6-day3` | Kotlin JVM + OkHttp | CLI `smartreminder --model qwen-local`, enum ModelConfig(isLocal) |

Список авторов: `/tmp/day28_authors.json`. Локальные копии: `/tmp/day28_research_repos/{sergio,dgor,mobdev,dpmn,avalanche}/`.

Итог: **sergio — снова главный эталон** (Kotlin + Ollama, тот же стек, что у нас, задача ровно та же). Его подход к сравнению — **общий retrieval + две генерации на одном контексте** — то, что мы должны взять один-в-один. **dgor** — минимальный контр-пример с чётким `--demo`-режимом и автоотчётом (полезно для видео). **dpmn** — глубокие метрики и телеметрия, полезен как чек-лист что показывать в сравнении. **avalanche** даёт компактный enum с `isLocal`-флагом (пригодится, если будем расширять список моделей). **mobdev** отсутствует полезная логика сравнения — это RAG-плагин без cloud, из day28 брать нечего.

---

## 1. sergio (Kotlin JVM, `Week6Day3LocLLMwithRAG.kt`) — главный эталон

Репо: `Sergio-rsd/AI-Challenge`, ветка `week6/day-3-total-28`.
Файлы: `src/apps/Week6Day3LocLLMwithRAG.kt` (**1429 строк**), README `README_Week6Day3LocLLMwithRAG.md` (**529 строк** — читать целиком, там 5 постфактумов с реальными фиксами живого прогона).

### 1.1 Ключевой архитектурный приём — «один retrieval, две генерации»

Из README (раздел «Ключевые решения», пункты 1-2):

> 1. Ретривел — ВСЕГДА на локальной модели, включая LLM-этапы (rewrite, реранк), не только эмбеддинг — буквальное выполнение требования «retrieval выполняется локально» из задания.
> 2. Генерация — локальная модель обязательна, облачная — только для сравнения на **идентичном** контексте (иначе сравнение не имеет смысла: разный контекст — разная задача).

Это самое важное для нас: `cmpRetrieve` → `outcome.chunks` → `cmpAskForAnswer(chunks, Local)` → `cmpAskForAnswer(chunks, Cloud)` с ТЕМИ ЖЕ chunks. Без этого сравнение измеряет не «local vs cloud», а «local retrieval + local gen vs cloud retrieval + cloud gen» — задание разное, вывод бессмысленный.

`callLlmCmp(kind, prompt, system, temperature, config)` — единая точка вызова, `CmpModelKind.Local(name) | CmpModelKind.Cloud(model)`. sealed class. У нас пока Ollama-only, но диспетчер стоит завести сразу — иначе при расширении переписывать весь `Session`.

### 1.2 Команды REPL

```
<вопрос>                — retrieval (локально) → ответ Local → ответ Cloud (если выбран) → сравнительная строка
!stability [N]          — N повторов генерации ПОСЛЕДНЕГО вопроса на КАЖДОЙ модели, retrieval не повторяется
!eval [N]               — 4 контрольных вопроса × N генераций/модель, таблица + агрегаты
!sources                — список источников в базе
!index-telegram <путь>  — пополнить корпус (Telegram export)
!index-doc <путь>       — пополнить корпус (pdf/docx/md/txt)
exit / выход
```

**Ключевое отличие `!stability` от `!eval`**: stability повторяет ТОЛЬКО генерацию (retrieval один раз), потому что иначе смешаются нестабильность модели ответа и нестабильность LLM-судьи реранка. Умно — стоит взять эту дисциплину и у нас.

### 1.3 Метрики сравнения (что печатает `cmpPrintComparisonLine`)

Для одного вопроса:
```
── Сравнение ──
Время: локально 48200мс vs облако 6800мс (облако быстрее на 41400мс)
Токены: локально 1602+270 vs облако 1445+570
«Не знаю»: локально=false облако=false (совпадает)
```

Для `!eval` — агрегаты `CmpAggregateStats`:
- `n, minMs, avgMs, maxMs, totalMs` — распределение времени
- `insufficientCount` — сколько раз «не знаю»
- `avgAnswerLen, avgInputTokens, avgOutputTokens`
- **`verbatimRate, quotesTotal`** — доля цитат, дословно найденных в контексте (Слой А, structural check без LLM)

**Verbatim-доля цитат** — это ключевая находка sergio. Не «мнение LLM-судьи о качестве», не «совпадение источников», а прямая измеримая величина: цитата в поле `quotes` дословно есть в тексте чанка, реально переданного в контекст → 1, иначе → 0. Модель, которая часто выдумывает цитаты (verbatim=0.5), объективно хуже, чем модель с verbatim=1.0. Это дешёвая структурная проверка, работает без LLM-судьи, невозможно «подделать промптом». Стоит взять **обязательно**.

### 1.4 JSON-схема ответа (наследие day24-27)

```json
{
  "answer": "текст ответа на русском",
  "insufficient_context": false,
  "sources": [{"source": "...", "section": "...", "chunk_id": "..."}],
  "quotes": [{"chunk_id": "...", "text": "дословный фрагмент"}]
}
```

Anti-hallucination guards, все три работают в бою (подтверждено `!eval` sergio):
- `insufficient_context=true` — единственный источник истины, остальные поля игнорируются;
- `sources_without_quotes` → один retry с напоминанием, потом отказ;
- `empty_answer` без флага → код-level отказ;
- `cmpFindInventedAcronymExpansion` — regex `\b([A-ZА-Я]{2,6})\s*\(([^)]{3,80})\)` ловит паттерн «АББРЕВИАТУРА (расшифровка)» и проверяет, есть ли расшифровка в чанках дословно. У нас пока нет — стоит подумать (наш qwen2.5:7b тоже может выдумывать расшифровки).

### 1.5 Реальные результаты живого прогона sergio (CPU, no GPU)

Из README, раздел «Результаты прогона» — 6 контрольных вопросов × 2 генерации/модель:

| | Локально qwen2.5:7b | Облако deepseek/v4-flash |
|---|---|---|
| Среднее время | 28.9 с | 5.0 с (**~6× быстрее**) |
| «Не знаю» | 4/10 | 4/10 (одинаковое число, но НА РАЗНЫХ вопросах) |
| Verbatim цитат | 6 проверено | 7 проверено |
| CJK-дрейф | 1 случай (guard поймал) | — |

Ключевая мысль: у моделей **одинаковое число отказов, но на разных вопросах** → они не дублируют друг друга ни в ошибках, ни в силе. На Q1 (MCP vs function calling) локальная рискнула и наполовину галлюцинировала (verbatim=50%), облачная честно сказала «не знаю». На Q3 — наоборот. Это важный факт для нашего вывода: **local НЕ хуже cloud по «уверенности», просто ошибаются в разных местах**.

### 1.6 Стоит / не стоит брать у sergio

**Взять обязательно:**
1. **Один retrieval → две генерации на идентичном контексте** — это ЯДРО правильного сравнения. Наш план `:compare` должен работать ровно так.
2. **`verbatimRate` как метрика качества** — дословная проверка цитат в контексте без LLM. Дешёво, объективно, никогда не подделать промптом.
3. **`!stability` отдельно от `!eval`** — retrieval один раз, повтор ТОЛЬКО генерации. Иначе не поймём, что колеблется.
4. **`CmpAggregateStats` целиком** как модель метрик: n/min/avg/max/total ms, insufficientCount, avgAnswerLen, avg tokens, verbatimRate. Это готовый шаблон таблицы для нашего `:eval --both`.
5. **`insufficientCount` — сравнение «где отказы совпадают/расходятся»** между моделями. Отличная сигнальная колонка.
6. **Диспетчер `callLlm(kind)` с sealed `LlmBackend { Local, Cloud }`** — даже если сейчас пока один cloud-провайдер, интерфейс стоит завести.

**Взять опционально (если хватит времени в day28):**
7. `cmpFindInventedAcronymExpansion` — regex-guard от выдуманных расшифровок. У нас не проверено, что qwen2.5:7b этим не грешит — но эксперимент дешёвый.
8. Точное совпадение по номеру дня (Постфактум 2 в README) — регулярка `(?:день|дн\p{L}*|day)\s+(\d+)` для форсирования чанка объявления в контекст. Пример показательного дебага у sergio — три бага сразу закрыты. Нам это ни к чему (у нас другой корпус), но приём «принудительное включение чанка после точного текстового матча» — общий, полезен.

**Не брать:**
- Индексация Telegram-корпуса (у нас md-файлы).
- SQLite для индекса (у нас `index.json` работает).
- CJK-guard — если наша qwen2.5:7b на текущем корпусе не даёт дрейфа, не добавлять.
- Стартовый мастер настроек (topK, thresholds) — CLI-минимализм важнее.

---

## 2. dgor (Python argparse, `day28_local_rag.py`) — минимальный чистый эталон

Репо: `dgoryachkovskiy/AiTgsterBot`, ветка `codex/day_28_local_llm_rag`.
Файлы: `day28_local_rag.py` (609 строк) + `DAY28_LOCAL_RAG_REPORT.md` (автогенерируемый отчёт).

### 2.1 Что за приложение

CLI с `argparse`, 4 команды:
- `status` — Ollama живой, какие модели установлены.
- `ask <qid|text>` — retrieval + локальная генерация (без cloud).
- `compare <qid|text>` — retrieval + локальная + облачная генерация → per-question сравнение.
- `verify --limit N` — прогоняет N контрольных вопросов из `CONTROL_QUESTIONS` (наследие day22) и генерирует `DAY28_LOCAL_RAG_REPORT.md` с таблицей.

**RAG есть** (переиспользован из day21 — `retrieve_chunks`, `build_rag_context`). Приложение — тонкая обёртка над готовым индексом, что и требуется по заданию.

### 2.2 Стек

- **HTTP:** stdlib `urllib.request` (для локального Ollama).
- **Cloud:** `openai` SDK с `base_url=https://api.deepseek.com` (DeepSeek через OpenAI-совместимый endpoint).
- **Локальная модель:** `qwen2.5:0.5b` (очень маленькая, 500M) — из-за этого и `local_quality: weak` на всех вопросах в отчёте (см. ниже).
- **Cloud модель:** `deepseek-v4-flash`.
- `THINKING_DISABLED = {"thinking": {"type": "disabled"}}` — интересный трюк для DeepSeek, чтобы отключить thinking-mode.

### 2.3 Метрика «качества» — по количеству цитат-меток `[S1]/[S2]`

```python
def evaluate(local_answer, cloud_answer, chunks):
    source_ids = [f"[s{i}]" for i in range(1, len(chunks) + 1)]
    local_cites = sum(1 for sid in source_ids if sid in local_text)
    return {
        "local_quality": "ok" if local_answer.ok and local_cites > 0 else "weak",
        "local_has_source_citations": local_cites > 0,
        ...
    }
```

Модель просят цитировать источники как `[S1]`, `[S2]`. Если в ответе нет ни одной такой метки → `weak`. Это **проще, чем verbatim у sergio**, но и слабее: модель может написать `[S1]` и полностью выдумать содержание.

### 2.4 Автоотчёт `DAY28_LOCAL_RAG_REPORT.md`

Показательные первые строки:
```
- generated_at: 2026-07-09T11:19:13+00:00
- fully_local_rag: True
- retrieval: local SQLite index + local Ollama embeddings
- generation: local Ollama chat model
- local_model: qwen2.5:0.5b
- cloud_compare: False
- questions: 3
- local_passed: 3 / cloud_passed: 0
- avg_retrieval_seconds: 0.105
- avg_local_seconds: 0.163
```

**Явное фиксирование `fully_local_rag: True`, `retrieval: local ...`, `cloud_compare: False`** — прямой ответ на «требование задания». Это хороший **чек-лист для нашего README и видео**: организаторы явно ждут, чтобы кандидат сказал «моя система локальна» — так и написать.

### 2.5 Стоит / не стоит брать

**Взять:**
- **`--verify` режим с готовыми prompts и авто-отчётом** — 30 минут работы, но зато видео пилится одним прогоном. Наш `:eval` уже делает регресс, но не пишет MD-отчёт → в day28 добавить `:eval --report path.md`.
- **`fully_local_rag: True` в шапке отчёта** — явно сказать, что и retrieval, и generation локальны.
- **`THINKING_DISABLED` для DeepSeek** — если будем брать deepseek-v4-flash через OpenRouter, проверить, не жрёт ли он токены на «thinking», и явно отключить.

**Не брать:**
- Метрика качества «`[S1]` есть в ответе» — слишком слабо, у нас `verbatimRate` sergio лучше.
- `qwen2.5:0.5b` как локальная модель — 500M вечно `weak`. Оставляем `qwen2.5:7b`.

---

## 3. dpmn (Python Flask WebUI + FAISS) — глубокие метрики и телеметрия

Репо: `dpmn/ai-advent-challenge`, ветка `day-28`. README `week-06/day-28/README.md` (135 строк). Правки в `webui/app.py`, `ragger/*.py`, `agents/jarvis.py`.

### 3.1 Что за приложение

Уже существующий Flask WebUI (jarvis-стиль, наследие с прошлых недель). День 28 — добавили выбор `qwen2.5-coder:7b (local)` в дропдаун моделей рядом с облачной Qwen через Cloud.ru. Через один флаг `agent.model_provider="cloud"|"local"` весь RAG-путь переключается: эмбеддер (nomic vs text-embedding-3-small), FAISS-индекс (`data_local/` vs `data/`), rerank/verify/answer модель.

### 3.2 Ключевая идея — **телеметрическая строка под каждым ответом в UI**

Пример:
```
RAG [local · qwen2.5-coder:7b · emb: nomic-embed-text]
    embed 0.3s · rerank 11.9s · verify+generate 47.8s · chunks 5 · confidence high
```

vs облачный:
```
RAG [cloud · Qwen/Qwen3-Coder-Next · emb: openai/text-embedding-3-small]
    embed 1.2s · rerank 0.6s · verify+generate 12.4s · chunks 5 · confidence high
```

**Тайминги этапов раздельно** (embed / rerank / verify+generate) — это гораздо информативнее нашего одного «elapsed». Позволяет понять, где именно локальный медленный: retrieval быстр, гонка на generation. У нас сейчас `metrics: eval_count, total_duration_ns` из Ollama — этого мало для day28.

### 3.3 Реальные цифры dpmn

- Генерация: локально 75-104 с vs облако 12-16 с → **~6-8× медленнее** (совпадает с оценкой sergio).
- Эмбеддинг запроса: локально 0.3-0.4 с (**быстрее** облака 0.8-1.9 с) на тёплой модели.
- Холодный старт локальной модели после ~5 мин простоя: **+9-28 с** (Ollama выгружает модель из памяти).
- **Стабильность:** после подъёма таймаутов (генерация 300s) — стабильно. До подъёма — падало по timeout на холодном старте.
- **Verify на 7B местами недетерминирован** — тот же вопрос то yes, то no. У нас тоже, вероятно, будет — стоит зафиксировать в отчёте.

### 3.4 Полезный фикс промпта, о котором стоит знать (bug day-w5)

> Фикс бага недели 5: в промпт генерации не подставлялся вопрос пользователя. Облачная модель это маскировала (пересказывала найденные документы), локальная 7B честно отвечала «Я не знаю».

Классический баг «cloud LLM компенсирует некачественный промпт, local — нет». Стоит вручную проверить наш промпт day26/27 — реально ли туда попадает `question`.

### 3.5 Строгий режим `/rag config strict on`

При `confidence=none` агент возвращает «Я не знаю» вместо fallback на основную LLM без RAG. Это ровно наш `insufficient_context=true` guard, только через флаг переключается. **У нас всегда strict** (мы не делаем fallback без RAG) — не нужно копировать.

### 3.6 Стоит / не стоит брать

**Взять:**
- **Раздельные тайминги этапов** (embed / rerank / verify / generate) — не одно `elapsed`. В `chat.log.jsonl` каждый этап отдельным полем. Это делает раздел «Оценка скорости» в отчёте гораздо содержательнее.
- **Разница «холодный vs тёплый» первый запрос** — учесть при замерах, отбросить первый запрос как warm-up.
- **`confidence: high/medium/low/none`** — числовой сигнал уверенности от модели. У нас пока бинарно (insufficient_context) — можно расширить до 3-4 уровней.
- **Явная проверка «а точно ли вопрос попадает в промпт?»** — не забыть проверить наш prompt, если ответ подозрительно похож на пересказ документов.

**Не брать:**
- WebUI/Flask — CLI достаточно.
- Fallback на LLM без RAG — у нас всё через retrieval, fallback ослабит защиту от галлюцинаций.

---

## 4. avalanche (Kotlin JVM, `smartreminder` CLI) — компактный provider-enum

Репо: `1Avalanche/SmartReminder`, ветка `week6-day3`. Файлы: `smartagent/cli/src/main/kotlin/smartagent/{QuestionHandler,ChatClient,ModelConfig}.kt` + модуль `llm-client/`.

### 4.1 Ключевая идея — enum моделей с `isLocal` флагом

```kotlin
enum class ModelConfig(
    val shortName: String,
    val apiModelId: String,
    val apiKeyProperty: String,
    val url: String,
    val contextWindow: Int,
    val isLocal: Boolean = false
) {
    DEEPSEEK(shortName = "deepseek", url = "https://api.deepseek.com/v1/chat/completions", ...),
    QWEN(shortName = "qwen", url = "https://openrouter.ai/api/v1/chat/completions", ...),
    QWEN_LOCAL(
        shortName = "qwen-local",
        apiModelId = "qwen2.5:14b",
        apiKeyProperty = "",
        url = "http://localhost:11434/v1/chat/completions",
        contextWindow = 32_000,
        isLocal = true
    ),
    GEMMA_LOCAL(...isLocal = true);
}
```

Всё в одном enum. `Config.apiKey(model)` возвращает null, если `isLocal=true` (пропускает Authorization header). `ChatClient` тот же для local и cloud — оба используют OpenAI-совместимый endpoint (Ollama `/v1/chat/completions`, а не native `/api/chat`).

**Компромисс OpenAI-compat vs native**: у нас (и у sergio) — native `/api/chat`, который даёт метрики Ollama (`total_duration`, `eval_count`, `prompt_eval_count`). У avalanche — `/v1/chat/completions`, отдаёт `usage.completion_tokens` в OpenAI-формате, но нативные тайминги теряются. Для сравнения local vs cloud единый endpoint удобнее, но метрики беднее.

### 4.2 Стоит / не стоит брать

**Взять:**
- **Enum-паттерн `ModelConfig(isLocal=true|false)`** — если решим расширить до 3+ моделей (naming, url, api key, context window в одном месте). Компактнее, чем sealed class у sergio, но менее выразительно (нельзя добавить local-specific поля).

**Не брать:**
- OkHttp — у нас `java.net.http.HttpClient` уже работает, не менять.
- OpenAI-compat endpoint — native `/api/chat` даёт больше метрик Ollama.

---

## 5. mobdev (Kotlin + JB Compose Desktop, IDEA-плагин) — ONNX embed без Ollama

Репо: `mobdev778/aiadventchallenge8`, ветка `day_28` — 1 коммит («Day 28 Task.», 65+ файлов, в day27 был отдельный набор).

Новизна day28: добавили RAG (Room-таблицы `RagDocumentEntity`, `RagDocumentChunkEntity`, `RagConfigEntity` + классы `SimpleRagSearcher`/`RankedRagSearcher`/`RagChunkGenerator`).

### 5.1 Единственная реально интересная деталь — эмбеддинги через ONNX in-process

```kotlin
import dev.langchain4j.model.embedding.onnx.OnnxEmbeddingModel

class RankerFactory {
    val embeddingModel: OnnxEmbeddingModel by lazy {
        Thread.currentThread().contextClassLoader = OnnxEmbeddingModel::class.java.classLoader
        OnnxEmbeddingModel(...)  // модель в JAR
    }
}
```

**Эмбеддинги не через Ollama HTTP, а прямо в JVM** — langchain4j грузит ONNX-модель (обычно `all-MiniLM-L6-v2` или аналог) и считает эмбеддинги локально в процессе, без сетевого вызова. Плюсы: нулевая латентность (нет HTTP), нет зависимости от Ollama-сервера. Минусы: другой embedding-размер (обычно 384 vs наш 768 у nomic), качество может быть хуже.

### 5.2 Сравнения local vs cloud НЕТ

У mobdev нет облачной модели вообще — они полностью в Ollama/LM Studio (LM Studio через `SettingsRepository.kt` — endpoint `http://localhost:1234/v1/chat/completions`). Задание day28 в части «сравните» — не выполнено. Возможно, автор считает, что smoke-теста хватит, но это провал задания.

### 5.3 Стоит / не стоит брать

**Взять:**
- **Идея in-process ONNX эмбеддинга** — интересна как «ещё более локальный» вариант (не нужен Ollama-сервер для embed), но требует зависимости `langchain4j-embeddings-all-minilm-l6-v2`. Для нас overkill: nomic через Ollama работает, менять не нужно.

**Не брать:**
- RAG-плагин для IDEA — весь код заточен под IntelliJ SDK.
- Room/Retrofit/Koin — overengineering для CLI.
- LM Studio — Ollama достаточно.

---

## 6. Сводка: что взять / что избегать / что проверить

### Стоит взять в наш `week6/day3` (в порядке приоритета)

1. **Общий retrieval → две генерации на ОДНОМ контексте** (`:compare <вопрос>`). Ядро правильного сравнения. У sergio это `cmpRetrieve` → `chunks` → два `cmpAskForAnswer(chunks, Local|Cloud)`. **Приоритет: критично, 1 час.**
2. **`verbatimRate` как метрика качества** (доля дословных цитат в чанках контекста). Дешёвая, объективная, без LLM. У sergio уже готовая логика `cmpVerifyQuotesStructurally`. **Приоритет: высокий, 1 час.**
3. **`:eval --both` с CmpAggregateStats-подобной таблицей** — 5 вопросов × 2 генерации на модель, колонки: время min/avg/max, insufficient N/M, verbatim %. Строка «где отказы совпадают, где расходятся» — критическая для вывода. **Приоритет: высокий, 1.5 часа.**
4. **`:stability [N]` — повтор ТОЛЬКО генерации, retrieval один раз**. Иначе смешаем нестабильность модели с нестабильностью реранка. **Приоритет: средний, 30 мин.**
5. **Раздельные тайминги** embed / retrieval / generate в `chat.log.jsonl` (у dpmn). Позволит в отчёте сказать «локальный медленный на generation, retrieval быстр». **Приоритет: средний, 30 мин.**
6. **Диспетчер `LlmBackend` (sealed) с `Local`/`Cloud` и единая `callLlm(kind, prompt, system)`** — даже если пока один cloud-провайдер, интерфейс важен. **Приоритет: средний, 30 мин.**
7. **`fully_local_rag: True` в README и в шапке `:eval --report`** — явно фиксирует ответ на требование задания. Копирайт из dgor. **Приоритет: низкий, 15 мин.**

### Проверить перед day28

- **Наш промпт day26/27 — попадает ли `question` в prompt?** dpmn нашёл баг «облачная маскирует, локальная честно говорит «не знаю»». Проверить визуально.
- **`THINKING_DISABLED` для DeepSeek через OpenRouter** — не жрёт ли он токены на thinking-mode. Если DeepSeek-v3 доступен через OpenRouter — вероятно, thinking по умолчанию, добавить `extra_body={"thinking": {"type": "disabled"}}` или аналог для нашего HttpClient.
- **Warm-up первого локального запроса** — при замерах отбрасывать первый вызов (Ollama выгружает модель через ~5 мин). У нас может быть до +28 с.
- **Verify/rerank на 7B недетерминирован** (dpmn) — у нас в day27 нет rerank-этапа, но при добавлении LLM-судьи иметь в виду.

### Точно не брать

- **WebUI/Flask/Avalonia/IDEA-плагин** — CLI сильнее для day28-видео.
- **Retrofit/OkHttp/Koin/Room** — `java.net.http.HttpClient` + Gson работает.
- **SQLite для индекса** — `index.json` достаточно.
- **`qwen2.5:0.5b`** — вечно `weak`. Оставляем `qwen2.5:7b`.
- **CJK-guard** — если наша модель не даёт языкового дрейфа, не добавлять (dead code = maintenance).
- **OpenAI-compat `/v1` endpoint для Ollama** — теряем native тайминги, которые ценны для сравнения.
- **Fallback на LLM без RAG при `insufficient_context`** — ослабляет анти-галлюцинационный контур.

### Что у нас уже сделано лучше

- **Native `/api/chat` с метриками Ollama** — у avalanche OpenAI-compat, теряет тайминги; у dpmn через httpx, тоже без нативных метрик.
- **JSONL-трейс каждого хода уже есть** (`chat.log.jsonl`) — у sergio нет, у dgor только per-verify.
- **Уже есть `:eval`** — sergio его добавил в day27, но у нас с day27 тоже.
- **Минималистичный Main.kt** — sergio 1429 строк (специфика — telegram-корпус + постфактумы), у нас должен уложиться в ~700 строк.

### Реалистичный план day28 (5-6 часов)

- **Фаза 1: `LlmBackend` + Cloud backend** (2 ч) — sealed class, OpenRouter/DeepSeek через HttpClient + Gson, конфиг через env var `OPENROUTER_API_KEY`.
- **Фаза 2: `:compare <вопрос>`** (1 ч) — retrieval один раз, два генератора, per-question сравнительная строка.
- **Фаза 3: `verbatimRate` + расширить JSONL-трейс** (1 ч) — добавить поле `quotes` в наш JSON-ответ, `verify_quotes` функция.
- **Фаза 4: `:eval --both` + `:eval --report path.md`** (1.5 ч) — таблица + агрегаты + автоотчёт.
- **Фаза 5: раздел «Оценка» в README** (30 мин) — итог с реальными цифрами, «где локальная сильна/слабая», `fully_local_rag: True`.

---

## Приложение

Клоны участников: `/tmp/day28_research_repos/{sergio,dgor,mobdev,dpmn,avalanche}/`.
Список авторов JSON: `/tmp/day28_authors.json`.

Прямые ссылки:
- sergio: https://github.com/Sergio-rsd/AI-Challenge/blob/week6/day-3-total-28/src/apps/Week6Day3LocLLMwithRAG.kt
- sergio README: https://github.com/Sergio-rsd/AI-Challenge/blob/week6/day-3-total-28/README_Week6Day3LocLLMwithRAG.md
- dgor: https://github.com/dgoryachkovskiy/AiTgsterBot/blob/codex/day_28_local_llm_rag/day28_local_rag.py
- dgor report: https://github.com/dgoryachkovskiy/AiTgsterBot/blob/codex/day_28_local_llm_rag/DAY28_LOCAL_RAG_REPORT.md
- mobdev: https://github.com/mobdev778/aiadventchallenge8/tree/day_28
- dpmn: https://github.com/dpmn/ai-advent-challenge/blob/day-28/week-06/day-28/README.md
- avalanche: https://github.com/1Avalanche/SmartReminder/tree/week6-day3

Denis Suprun (упомянут как сдавший `week_6_day_28`) — не найден: sheets-reader OAuth просрочен, GitHub search по 'suprun'/'week_6_day_28' не дал совпадений. Если Данил вручную сдёрнет ссылку из свежего комментария — можно добавить в отчёт как п.6.
