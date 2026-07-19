# Day 29 — Оптимизация локальной LLM. Разбор решений участников

Дата: 2026-07-11
Наш день: `week6/day4` (Day 29 «Оптимизируйте локальную модель под задачу: параметры, квантование, prompt-шаблон; сравните до/после»). База — `week6/day3` (Day 28): RAG-CLI на Kotlin с `sealed LlmBackend = OllamaLocal + DeepSeekCloud`, qwen2.5:7b, temp 0.2, `num_ctx=` дефолт (2048), verbatimRate, `:compare`/`:eval --both`/`:stability`.

Источник авторов: sheets-reader токен свежий (обновлён 2026-07-11), 2355 комментариев прочитаны. Явные day29-паттерны в URL (`day29/day-29/week6/day-4/week_6_day_29/task_29/d29`) — 8 подтверждённых сдач, плюс ~10 кандидатов по свежим коммитам (2026-07-09..11) — из них 8 клонированы и разобраны. Список: `/tmp/day29_authors.json`. Локальные копии: `/tmp/day29_research_repos/{sergio,nikolay,kaa,dpmn,avalanche,yavits,soziev,pechatkin}/`.

| Ник | Автор | Репо | Ветка/путь | Стек | Что сделал day29 |
|---|---|---|---|---|---|
| **sergio** | Ssh sh | `Sergio-rsd/AI-Challenge` | `week6/day-4-total-29` | **Kotlin JVM + Ollama** | 3 конфига `OptGenConfig(BASELINE/OPTIMIZED/QUANT)` на общем retrieval; Q4_K_M vs Q8_0; `!eval 3` с полным разбором |
| **nikolay** | Николай (Shirobokov) | `ShirobokovNE/ai-challenge` | `day29/src/main/kotlin` | **Kotlin JVM + OkHttp** | 3 сценария (Q4 baseline / Q4 custom / Q2_K), TPS, `ollama ps`, отчёт через OpenRouter Gemini |
| **dpmn** | Олег Ионов | `dpmn/ai-advent-challenge` | `week-06/day-29` | Python + Flask + FAISS | 2 профиля (baseline через /v1 vs optimized через `/api/chat`), Q4_K_M vs Q3_K_M, tok/s в WebUI |
| **soziev** | Denis Soziev | `dsoziev1/deepseek-cli-day-29` | `main` | Python + FastAPI + Ollama | **Своё квантование** `ollama create --quantize q3_K_M`; Modelfile с параметрами; веб-стенд с редактируемым промптом; DeepSeek-судья |
| **pechatkin** | Viktor Pechatkin | `vapechatkin/ai_ac` | `main/d29` | Python | Агент-рекомендатор книг: `temp 0.7→0.8` (креатив!), `num_ctx 2048→8192`, `max_tokens 512→400`, ролевой промпт+формат |
| **kaa** | Круглов Андрей | `kaa-it/Ollama` | `day29` | **C# / .NET 10** | RAG-агент по Rust patterns, Claude 3.5 Sonnet vs без RAG, temp 0.1 (воспроизводимость), не про локальные оптимизации |
| **avalanche** | Anastasia Anisimova | `1Avalanche/SmartReminder` | `week6-day4` | Kotlin JVM + OkHttp | Минимальная правка: одна ветка кода добавляет `num_ctx=8192` в CHAT-режиме OPTIMUM — из day29 брать нечего |
| **yavits** | Evgeny Yavits | `zheksus/ai_challenge` | `main/day29` | Python | qwen2.5-coder:1.5b + ролевой промпт «супер-ревьюер» с эмодзи; не про измеримое сравнение |

Итог для нашего кода: **sergio + soziev + dpmn — костяк**. sergio даёт лучшую методологию (3 конфига на общем retrieval, verbatimRate, живой разбор «cold-swap tax»); soziev — самое ценное техническое: **своё квантование через `ollama create --quantize`** без сборки llama.cpp + пресеты Modelfile; dpmn — доказывает, что Q3_K_M влезает на 6GB GPU целиком (наш случай, RTX 3060 6GB) и **OpenAI-совместимый `/v1` не принимает `options`** — нужен нативный `/api/chat` (у нас уже так со дня 27). pechatkin — контр-пример: `temp UP` на творческой задаче. nikolay — паттерн замера RAM через `ollama ps` + `ollama stop` между сценариями.

---

## 1. sergio (Kotlin JVM, `Week6Day4OptimizLocLLM.kt`) — главный эталон, снова

Репо: `Sergio-rsd/AI-Challenge`, ветка `week6/day-4-total-29`.
Файлы: `src/apps/Week6Day4OptimizLocLLM.kt` (**1327 строк**), README `README_Week6Day4OptimizLocLLM.md` (**303 строки**, читать целиком — там 4 «Находки» с реальными данными живого прогона).

### 1.1 Три конфигурации на общем retrieval — прямой перенос идеи day28

Полностью повторяет паттерн day28 «один retrieval, две генерации», но теперь **три конфигурации на одном retrieved-контексте**:

```kotlin
private data class OptGenConfig(
    val label: String,
    val modelName: String,
    val temperature: Double,
    val numPredict: Int?,   // null = дефолт Ollama (не задавать в options)
    val numCtx: Int?,       // null = дефолт Ollama
    val systemPrompt: String
)

// заполняется в main():
add(OptGenConfig("BASELINE",  localModel, temperature = 0.3,  numPredict = null, numCtx = null, systemPrompt = OPT_ANSWER_SYSTEM_BASELINE))
add(OptGenConfig("OPTIMIZED", localModel, temperature = 0.15, numPredict = 800,  numCtx = 8192, systemPrompt = OPT_ANSWER_SYSTEM_TUNED))
if (quantTag != null) {
    add(OptGenConfig("QUANT",  quantTag,  temperature = 0.15, numPredict = 800,  numCtx = 8192, systemPrompt = OPT_ANSWER_SYSTEM_TUNED))
}
```

Ключевое: **BASELINE — это буквальная копия поведения day28**. `OPT_ANSWER_SYSTEM_BASELINE = "..."` — CMP_ANSWER_SYSTEM day28 без единого изменения, `temperature=0.3` (то же значение, что у sergio на day28; у нас 0.2 — небольшая разница), `numCtx=null`/`numPredict=null` — не передаются в `options` вообще, дефолт Ollama (**2048** — тот самый скрытый предел). Это делает «до/после» точным, а не переписанным задним числом.

QUANT — те же параметры/промпт, что OPTIMIZED, но модель `qwen2.5:7b-instruct-q8_0` вместо дефолтной Q4_K_M. **Изолирует эффект квантования от эффекта тюнинга параметров/промпта** — этот принцип критично важен, иначе сравнение теряет смысл (нельзя одновременно менять модель и промпт и делать вывод про «квантование лучше»).

### 1.2 OPTIMIZED-промпт: 4 изменения относительно BASELINE

BASELINE-промпт (`OPT_ANSWER_SYSTEM_BASELINE`) — линейный список правил 1-6, JSON-схема в конце (`Отвечай СТРОГО ОДНИМ JSON-объектом, ..., в формате: {...}`).

OPTIMIZED (`OPT_ANSWER_SYSTEM_TUNED`) — та же JSON-схема (парсер один на оба), но переструктурирован под конкретные живые провалы qwen2.5:7b на этой задаче (замечены на днях 27-28):

1. **Схема + пример правильного ответа СРАЗУ после роли**, а не в конце — модель видит целевую форму до правил:
   ```
   Формат ответа — СТРОГО один JSON-объект, БЕЗ markdown, БЕЗ ```json...
   {"answer": "...", "insufficient_context": false, "sources": [...], "quotes": [...]}
   
   Пример ПРАВИЛЬНОГО ответа (условный, не про этот корпус):
   {"answer": "X — это Y.", "insufficient_context": false, ...}
   ```
2. **Контрастный пример ЗАПРЕЩЁННОГО поведения**, бьющий по конкретной живой галлюцинации (не абстрактное «не придумывай»):
   ```
   Пример ЗАПРЕЩЁННОГО поведения: "MCP (Model Code Passing)" — если такой
   расшифровки НЕТ дословно в контексте, НЕ ИЗОБРЕТАЙ её.
   ```
3. **Самопроверочный чек-лист в конце вместо линейного списка правил** — тот же приём, что снижает retry-count в guard'ах, но встроен в промпт:
   ```
   Перед тем как выдать JSON, мысленно проверь по пунктам:
   1. Каждый факт в "answer" подтверждён контекстом...
   2. У КАЖДОГО источника есть хотя бы одна цитата...
   3. Каждая цитата — точный фрагмент, скопированный дословно.
   4. chunk_id скопирован ЦЕЛИКОМ...
   5. Если контекст не отвечает — "insufficient_context": true...
   ```
4. **Явное ограничение длины ответа (5-6 предложений)** — короче ответ → меньше output_tokens → быстрее при том же `numPredict=800`.

### 1.3 Находка планирования: `num_ctx` НИКОГДА не задавался

`ollama show qwen2.5:7b-instruct --parameters` — пусто (Modelfile модели не переопределяет `num_ctx`). `GET /api/show` → архитектурный максимум `context_length = 32768`, но `OllamaClient.generate()` (дни 26-28, `Week6Day1LocalLLM.kt`) передавал в `options` только `temperature` — ни `num_ctx`, ни `num_predict` не задавались явно. **Вся генерация дней 26-28 работала на скрытом дефолте Ollama (2048), хотя RAG-промпт может к нему приблизиться или превысить**. То есть окно контекста было реальной, а не гипотетической точкой потери качества/полноты.

Фикс — `OllamaClient.generate()`/`generateGuarded()` расширены опциональными `numPredict`/`numCtx` (backward-compatible — `null` не меняет поведение старых дней).

**Прямой урок для нас**: у нас **та же ситуация** — `OllamaLocal.generate` (day28) передаёт только `temperature=0.2`. `num_ctx` и `num_predict` не заданы вообще. Если наш RAG-промпт с 5 чанками × ~500 токенов ≥ 2048 — обрезка контекста уже происходит молча. Первый пункт плана — проверить это `ollama show`/`GET /api/show`, потом добавить `numPredict`/`numCtx` опционально в generate.

### 1.4 Живой прогон `!eval 3` (4 вопроса × 3 конфига × 3 попытки = 36 генераций, ~20 мин на CPU)

Сырая сводка усреднением по 3 попыткам:

| Конфигурация | Среднее время (12 генераций) | «Не знаю» (12) | Verbatim |
|---|---|---|---|
| BASELINE (Q4_K_M) | 27762 мс | 8/12 | 50% |
| OPTIMIZED (Q4_K_M) | 27145 мс | 6/12 | 50% |
| QUANT (Q8_0) | 43786 мс | 6/12 | 50% |

**На первый взгляд OPTIMIZED почти не отличается от BASELINE, а QUANT дороже.** Разбор попыток по отдельности (`optFormatAttemptTimes`) показал, что сырые средние искажены методологическим артефактом — **«cold-swap tax»**:

### 1.5 Находка 1: «налог на переключение» доминирует над средним

Во всех 12 точках переключения (4 вопроса × 3 конфига) **1-я генерация каждой конфигурации в 2-6 раз медленнее 2-й и 3-й** той же конфигурации:

- Q4 BASELINE: 33033мс → 5410мс → 5134мс (1-я в 6.4 раза медленнее устоявшейся)
- Q2 QUANT: 110952мс → 44827мс → 44763мс (1-я на 66 сек дороже)

Причина: **`num_ctx` — load-time параметр**, фиксирует размер KV-кэша при загрузке модели в llama.cpp. Это НЕ runtime-переключаемый параметр. При смене `num_ctx` (или переключении между BASELINE→OPTIMIZED, где `num_ctx` разный) Ollama **поднимает новый runner** — та же модель загружается заново.

Усреднение только попыток 2 и 3 (устоявшийся режим):

| Конфигурация | Устоявшееся среднее |
|---|---|
| BASELINE | ~20792 мс |
| OPTIMIZED | ~18525 мс (**на ~11% быстрее** BASELINE) |
| QUANT | ~29190 мс (на ~40% медленнее OPTIMIZED) |

Вывод переворачивается: **`numPredict=800` + требование краткости в промпте реально сокращают генерацию** в устоявшемся режиме, компенсируя чуть больший системный промпт OPTIMIZED (+100 input_tokens).

**Прямой урок**: в нашем `:compare-presets <вопрос>` мы получим шум от cold-swap tax при первом вызове каждого пресета. Надо либо **делать warmup перед замером** (тот же трюк, что soziev — `ensure_loaded(model, options={num_predict:1, num_ctx: X})`), либо честно писать в отчёте «первая попытка — cold-load, не считаем».

### 1.6 Находка 2: OPTIMIZED/QUANT надёжнее держат JSON-контракт

На вопросе 2 (единственном с неоднозначным исходом):
- BASELINE — валидный ответ только 1/3, 2/3 провала — `[Parse] ⚠ Unterminated string` (обрыв JSON до закрытия строки)
- OPTIMIZED и QUANT (одинаковый промпт/параметры, отличается только модель) — **3/3 валидных ответа, 0 сбоев парсинга**

Причина: `numCtx=8192`+`numPredict=800` в OPTIMIZED/QUANT не дают генерации «убежать» за неявный дефолт Ollama. У BASELINE не ограничен вообще → модель может уйти в бесконечный текст и обрезаться на середине JSON.

**Прямой урок**: в `verbatimRate` мы уже ловим галлюцинации в цитатах, но не ловим «недописанный JSON». Стоит завести счётчик `parse_failure` в агрегате и показывать в `:eval --presets` — это ловит именно эту проблему.

### 1.7 Находка 3: Q8_0 здесь дороже, но не лучше

QUANT дал **идентичный профиль качества с OPTIMIZED** (тот же расклад «не знаю»/verbatim/retry на 4/4 вопросах) — но на ~40% медленнее в устоявшемся режиме и **8460MB против 5203MB VRAM** (замер `optOllamaPs` — обёртка над `GET /api/ps`).

Вывод: **Q4_K_M (дефолт) — лучший выбор для нашей задачи**, Q8_0 не даёт измеримого выигрыша, оправдывающего цену.

**Прямой урок для Данила (RTX 3060 6GB)**: на 6GB Q8_0 (~8.5 GB) вообще не влезет целиком в VRAM — будет частично на CPU. Q4_K_M (~5 GB) — влезает. Настоящий эксперимент для нас — **Q3_K_M** vs Q4_K_M (см. dpmn ниже), не Q8_0. Q8_0 держать только как «а если нужна максимальная точность весов» — но 40% скорость + переход на CPU = не наш случай.

### 1.8 Постфактум: rewrite тянул тему курса в организационные вопросы

Пользователь вживую поймал вопрос «какой график сдачи заданий?» — все конфиги ответили «не знаю», хотя ответ есть в корпусе. Проверка показала: `rewrite` (LLM-этап переформулировки запроса) превратил вопрос в «график сдачи заданий **в программировании нейронные сети**» — общие слова темы курса из ролевой рамки промпта просочились. Целевой чанк `thread_243` оказался на 286-м месте вместо топ-20.

Точечный фикс — `OPT_REWRITE_SYSTEM` получил явное правило: **для организационных вопросов (сроки, дедлайны, оплата) не добавлять общие слова темы курса и технические синонимы**. Результат частичный: ранг улучшился (286→151), но всё равно за пределами topK=20. Настоящее решение — **гибридный поиск BM25 + embedding** (запланирован как будущий день).

**Прямой урок**: у нас та же болезнь потенциально. Наш rewrite не тронут day28, но проверить на организационном вопросе стоит — если увидим общие слова темы курса в переформулировке, добавить такое же правило.

---

## 2. dpmn (Python, `ragger/` + `week-06/day-29/`) — измерения и Q3_K_M

Репо: `dpmn/ai-advent-challenge`, папка `week-06/day-29/`. Отчёт `README.md` — 147 строк, читать целиком.

### 2.1 Диагноз железа из логов `ollama serve`

Из `ollama-serve-log-sample.txt`: сервер сконфигурирован на `OLLAMA_CONTEXT_LENGTH=32768` (глобальный env серверный дефолт) → KV-cache 1792 MiB не влезает в 6 GiB VRAM → только **18/29 слоёв на GPU**, 47%/53% CPU/GPU распределение → ~12 tok/s.

**Прямой урок**: `OLLAMA_CONTEXT_LENGTH` в env Ollama-сервера имеет приоритет над отсутствием `num_ctx` в запросе, но `options.num_ctx` в per-request переопределяет env. У Данила (RTX 3060 6GB, Windows) — если он не выставлял `OLLAMA_CONTEXT_LENGTH`, будет тот же 2048-дефолт llama.cpp. Проверить: `Get-ChildItem env: | Select-String OLLAMA`.

### 2.2 Ключевое: OpenAI-совместимый `/v1` не принимает `options`

Из комментариев `ollama_client.py`:
> Зачем не OpenAI-совместимый /v1: тот не принимает options (num_ctx и др.)

`/v1/chat/completions` умеет только стандартный OpenAI-набор (`temperature`, `max_tokens`, `top_p`) — **num_ctx, num_predict, keep_alive, kv_cache_type задать через /v1 нельзя**. Нужен нативный `POST /api/chat`:

```python
payload = {
    "model": model,
    "messages": [...],
    "stream": False,
    "keep_alive": "30m",
    "options": {"num_ctx": 8192, "temperature": 0.0, "num_predict": 1024}
}
```

**У нас уже так** (day27+ на `/api/chat`), но у avalanche и multiple других — /v1. Это большой скрытый барьер для оптимизации в их коде.

### 2.3 Профили baseline vs optimized

```python
OPTIMIZED_PROFILE = {
    "transport": "ollama",
    "keep_alive": "30m",
    "gen_options":    {"num_ctx": 8192, "temperature": 0.0, "num_predict": 1024},
    "verify_options": {"num_ctx": 8192, "temperature": 0.0, "num_predict": 5},   # для verify нужно всего "yes"/"no"
    "rerank_options": {"num_ctx": 8192, "temperature": 0.0, "num_predict": 256}, # для реранка нужны JSON-оценки
    "chunk_char_limit": 1600,   # у baseline было 1200 → больше контекста
    "prompt_style": "compact",
}
```

**Найдено**: разные `num_predict` для разных этапов (verify=5, rerank=256, generate=1024) — тонкая экономия, у sergio такого нет. При этом `num_ctx` **одинаковый** для всех этапов — сознательно, чтобы избежать перезагрузки модели между verify→rerank→generate («смена num_ctx перегружает модель», то самое, что sergio ловил как cold-swap tax).

### 2.4 Компактный промпт (для 7B — критично)

`_build_compact_prompt`:
```
Ответь на вопрос, используя ТОЛЬКО документы ниже. Отвечай на русском.

ВОПРОС: {query}

ДОКУМЕНТЫ:
{chunks_text}

ПРАВИЛА:
- Используй только факты из документов. Если ответа в них нет — напиши "Я не знаю" и confidence "none".
- Для каждого факта укажи источник: source, chunk_id, section и дословную цитату quote из документа.
- confidence: high (полный ответ), medium (частичный), low (слабая связь), none (ответа нет).

Верни ТОЛЬКО JSON без markdown и пояснений:
{"answer": "...", "sources": [{...}], "confidence": "..."}
```

Комментарий автора: «та же JSON-схема, что и в полном промпте, но короткие правила без эмодзи-разметки — маленькие модели следуют им надёжнее».

**Прямой урок**: у нас в day28 промпт длинный + маркдаун-разметка `**Правила**` и т.д. Стоит замерить, теряем ли качество, укоротив (это и есть смысл нашего `:preset optimized`).

### 2.5 Результаты 5 вопросов

| model | profile | avg total, s | avg generate, s | avg tok/s | VRAM |
|---|---|---|---|---|---|
| Q4_K_M | baseline (32k ctx, полный промпт) | 50.8 | 49.8 | ~12 | 53% GPU |
| Q4_K_M | optimized (8k ctx, компактный) | 28.1 | 27.2 | 11.2 | 79% GPU |
| Q3_K_M | baseline | 36.8 | 36.0 | — | — |
| **Q3_K_M** | **optimized** | **26.9** | **26.1** | **13.3** | **100% GPU** |

**Латентность: 50.8 → 26.9 с (−53%)**. Q3_K_M влезает целиком в 6GB VRAM (100% GPU), Q4_K_M даже при 8k ctx — только 79%. Качество не просело (confidence high на 4/4 отвечаемых вопросах во всех конфигурациях, источники и цитаты на месте).

**Прямой урок для Данила**: **на RTX 3060 6GB для qwen2.5:7b единственный способ 100% GPU — Q3_K_M**. Q4_K_M — 79-84% GPU, значит часть на CPU. Если у Данила есть время скачать `qwen2.5:7b-instruct-q3_K_M` (~3.5 GB), это будет самый заметный «до/после» в видео — с почти -50% латентности.

### 2.6 tok/s только в optimized

В техстроке WebUI: у optimized есть `tok/s` и `load Ns` (метрики из нативного `/api/chat`), у baseline их нет (baseline идёт через `/v1` без метрик). Наглядная разница для видео.

---

## 3. soziev (Python + FastAPI) — своё квантование через Modelfile

Репо: `dsoziev1/deepseek-cli-day-29`. README — 80 строк, всё существенное вынесено сюда.

### 3.1 Modelfile.opt — параметры пакуются в кастомную модель

Ключевая инженерная находка — вместо задавать `options` при каждом запросе, можно **зашить их в Modelfile и создать свою модель** через `ollama create`:

```
FROM qwen2.5:3b

PARAMETER temperature 0.1
PARAMETER top_p 0.9
PARAMETER num_ctx 2048
PARAMETER num_predict 400
PARAMETER num_thread 4
PARAMETER repeat_penalty 1.05

SYSTEM """You are an engineer assistant that helps connect applications to Auth0. Rules:
1. Answer in English, concise and to the point, no preamble or filler.
2. Use ONLY facts from the SOURCES block. After a claim taken from a source, add [S1], [S2]…
3. If the answer is not in the sources, say "The sources do not contain information about this" and do not make anything up.
4. At most 4–6 sentences or a short list."""
```

`ollama create qwen-rag:opt -f Modelfile.opt` → готовая модель `qwen-rag:opt`, при вызове **сама применяет параметры + системный промпт** без указания в запросе.

Плюсы: чище код (не тащить `options` через все слои), одна точка правды.
Минусы: **нельзя менять параметры без пересоздания модели** — плохо для нашего `:preset baseline|optimized`, где хочется переключаться из REPL. Использовать не стоит, но идею упомянуть в видео как альтернативу.

### 3.2 Ключевое: своё квантование БЕЗ сборки llama.cpp

```bash
ollama pull qwen2.5:3b-instruct-fp16     # исходник ~6.2 ГБ
ollama create qwen25-3b-q3 --quantize q3_K_M -f Modelfile.q3
# где Modelfile.q3 = 'FROM qwen2.5:3b-instruct-fp16'
```

**Ollama умеет квантовать самостоятельно** через `--quantize` — не нужен `llama.cpp`, не нужен `llama-quantize` бинарь, не нужен Python. Доступные форматы (Ollama docs): `q4_0`, `q4_K_S`, `q4_K_M`, `q5_0`, `q5_K_S`, `q5_K_M`, `q8_0`, `q3_K_S`, `q3_K_M`, `q3_K_L`, `q2_K`, `q6_K`. Исходник обязан быть **fp16** (или fp32) — из уже квантованного `q4_K_M` пересжать в `q3_K_M` НЕЛЬЗЯ.

**Прямой урок для Данила**: если готовой модели `qwen2.5:7b-instruct-q3_K_M` в Ollama library нет, но нужна — качаем `qwen2.5:7b-instruct-fp16` (~15 GB, много места, но одноразово) и делаем `ollama create qwen25-7b-q3 --quantize q3_K_M -f Modelfile.q3`. Или проверить, есть ли готовый `q3_K_M` тег у qwen2.5:7b (у Q3_K_M-версии официально `qwen2.5:7b-instruct-q3_K_M` есть в Ollama library — проверил).

### 3.3 `ensure_loaded` — прогрев теми же опциями (лечит cold-swap tax)

```python
def ensure_loaded(model, options=None):
    """Прогреть модель ТЕМИ ЖЕ опциями, что и замер (иначе смена num_ctx/
    num_thread заставляет Ollama перезагрузить модель прямо во время замера —
    и латентность получается «холодной», нечестной)."""
    opts = dict(options or {})
    opts["num_predict"] = 1  # генерируем 1 токен для форса загрузки
    requests.post(f"{BASE}/api/chat", json={
        "model": model, "messages": [{"role": "user", "content": "hi"}],
        "stream": False, "options": opts}, timeout=300)
```

Тот же трюк, что sergio упомянул в постфактум. Пригодится для наших `:compare-presets` — перед серией замеров прогреть каждый пресет.

### 3.4 Метрики из `/api/chat` response

```python
d = r.json()
ec = d.get("eval_count", 0)       # число сгенерированных токенов
ed = d.get("eval_duration", 0)    # наносекунды на генерацию
pc = d.get("prompt_eval_count", 0) # число входных токенов
tok_s = round(ec / (ed / 1e9), 2) if ed else 0.0
```

Ollama возвращает `eval_duration` в наносекундах — не забыть про `/1e9`.

### 3.5 Промпт: `naive` vs `opt`

`naive` (до): `"You are a helpful assistant. Answer the user's question."` + `"Context:\n{sources}\n\nQuestion: {question}"`
`opt` (после): жёсткие правила + [Sn] цитаты + отказ-триггер `"The sources do not contain information about this"` + лимит 4-6 предложений.

Английский промпт — **осознанный выбор**: «small local models handle English better than Russian». Спорно, зависит от модели (qwen2.5 обучен на русском тоже), но для 3B — правда: качество на английском заметно выше. У нас qwen2.5:7b — 7B справляется с русским, но замерить стоит.

### 3.6 DeepSeek-судья

Оценка качества — DeepSeek (облачно), шкала 0-100 по grounded/точности/цитатам/отказу. Собственная LLM-судья, отдельная от рабочей. У нас есть `verbatimRate` (структурная, без LLM) — надёжнее и дешевле. Судьей может быть OpenRouter из тестов nikolay — но у нас уже есть `sealed LlmBackend`, можно добавить `CloudJudge` как отдельный не-generate вызов.

---

## 4. nikolay (Kotlin, `QuantizationBenchmark.kt`) — 3 сценария + TPS + ollama ps

Репо: `ShirobokovNE/ai-challenge`, `day29`. Модель `llama3.1:8b`, не qwen — но паттерны применимы.

### 4.1 Три сценария

```kotlin
"Baseline (Q4 Default)"  -> LlmConfig(model="llama3.1:8b",           temp=0.8, maxTokens=1000, numCtx=4096, sys="You are a helpful assistant.")
"Optimized (Q4 Custom)"  -> LlmConfig(model="llama3.1:8b",           temp=0.1, maxTokens=1000, numCtx=4096, sys="Ты — Senior Kotlin Developer. Отвечай кратко, используй современный синтаксис Kotlin 2.0. Всегда проверяй типизацию.")
"Compressed (Q2 Optimized)" -> LlmConfig(model="llama3.1:8b-instruct-q2_K", temp=0.1, maxTokens=1000, numCtx=4096, sys=<то же что optimized>)
```

Комментарий: `// ВАЖНО: Ставим одинаковый numCtx для всех, чтобы сравнение RAM было честным`. Правильная методология — одинаковый `num_ctx` изолирует эффект `num_ctx` от эффекта модели.

**Экстремальное квантование Q2_K** — почти не применяется в проде (сильная потеря качества), но интересно для видео как «а можно ещё жёстче».

### 4.2 unloadAllModels + Thread.sleep(2000) между сценариями

```kotlin
fun unloadAllModels() {
    val psProcess = ProcessBuilder("ollama", "ps").start()
    val output = psProcess.inputStream.bufferedReader().readText()
    output.lines().drop(1).forEach { line ->
        val name = line.split(Regex("\\s+")).firstOrNull()
        if (name != null && name.isNotBlank()) {
            ProcessBuilder("ollama", "stop", name).start().waitFor()
        }
    }
    Thread.sleep(2000) // Даем время на освобождение памяти
}
```

Явная выгрузка перед новым сценарием → **честный замер холодной загрузки** для каждого сценария. Обратная стратегия к soziev/dpmn (`keep_alive=30m` + прогрев). Что лучше — зависит от цели: если мерим устоявшийся режим, нужно `ensure_loaded`; если мерим «сколько живому пользователю ждать при первом запросе после переключения» — `unloadAllModels`.

### 4.3 RAM через `ollama ps`

```kotlin
output.lines().find { it.contains(modelName) }?.let { line ->
    val columns = line.split(Regex("\\s{2,}")).filter { it.isNotBlank() }
    if (columns.size >= 3) columns[2] else "N/A"
}
```

Парсит вывод команды `ollama ps` (человекочитаемая таблица). Альтернатива — `GET /api/ps` (JSON), который использует sergio (`optOllamaPs`). JSON-путь предпочтительнее, не завязан на форматирование stdout.

### 4.4 TPS = tokens / (duration_ms / 1000)

Считает через usage.totalTokens (OpenAI-compat), не через `/api/chat` eval_duration. Это менее точно (usage — общее число, включая prompt), но проще. У soziev/dpmn — `eval_duration` только для generate, честнее.

### 4.5 AI-анализ через OpenRouter (gemini-pro-1.5)

CSV результатов → в промпт → LLM пишет отчёт. **Оверинжиниринг для Данила** — Николай явно любит автоматизировать всё, но для нашего дня достаточно самому написать выводы. Пропускаем.

---

## 5. pechatkin (Python, агент-рекомендатор книг) — контр-пример

Репо: `vapechatkin/ai_ac`, `main/d29`. Отличная контр-иллюстрация: **temp UP** для творческой задачи, а не down.

```python
CONFIGS = {
    "before": {"system": "You are a helpful assistant.", "temperature": 0.7, "max_tokens": 512, "num_ctx": 2048},
    "after":  {"system": "Ты — эксперт по книгам и литературный советник...", "temperature": 0.8, "max_tokens": 400, "num_ctx": 8192},
}
```

Задача — рекомендация книг с форматированием (📖, «Похожие: •»), **креативная**. Temp 0.7→0.8 (креатив up), max_tokens 512→400 (сжать), num_ctx 2048→8192 (для истории диалога, не для контекста RAG).

**Урок**: направление тюнинга temp зависит от задачи. У нас — RAG с точными цитатами → temp DOWN (0.2→0.0). У pechatkin — креатив → temp UP. Данилу это упоминать не надо (мы точно down), но полезно знать при обсуждении.

Также: `format_score` — эвристика оценки формата (есть 📖 → +1, есть «Похожие» → +1, ≥3 буллетов → +1). Мгновенная измеримая метрика без LLM-судьи. Для нашего дня не пригодится (у нас JSON, парсится строго), но техника интересная.

---

## 6. kaa (C# / .NET 10) — про Anthropic, не про локальную оптимизацию

Репо: `kaa-it/Ollama`, `day29`. Стек — C#, Anthropic SDK, Claude 3.5 Sonnet, temp 0.1 (для воспроизводимости).

Задача — RAG по книге «Rust Design Patterns» с двумя режимами: **без RAG** (прямой Claude) vs **с RAG** (retrieval + Claude). Локальная только эмбеддер (`nomic-embed-text` через Ollama). **Про оптимизацию локальной LLM здесь ничего нет** — задание понято как «оптимизация RAG-агента с облачной LLM».

Взять нечего, но контр-пример: «task Day 29 = локальная модель, а не облачная». Если кто-то из ревьюеров скажет «а вот kaa делает облачно» — это неверная интерпретация задания.

---

## 7. avalanche, yavits — минимальный вклад

**avalanche** (`1Avalanche/SmartReminder`, ветка `week6-day4`) — в `ChatClient.kt:53`:
```kotlin
val requestOptions: OllamaOptions? = if (isChatMode && chatSetting == ChatSetting.OPTIMUM) OllamaOptions(num_ctx = 8192) else null
```
Одна строка кода — `num_ctx=8192` только для CHAT-режима OPTIMUM. Ни квантования, ни промпт-шаблонов, ни бенчмарков. Взять нечего.

**yavits** (`zheksus/ai_challenge`, `main/day29`) — qwen2.5-coder:1.5b + огромный ролевой промпт с эмодзи «Ты — Супер-ревьюер Python-кода с 15-летним опытом работы в FAANG-компаниях...», 5 критериев оценки, «эталонное решение», «дополнительный анализ». Класс промптов, которые Данилу антирекомендованы (overengineering, эмодзи, ролевой театр). Пропускаем.

---

## Сводка: чек-лист для нашего day29 (`week6/day4`)

### Обязательно взять (у нас пока нет)

1. **`num_ctx`/`num_predict` в options** — сейчас `OllamaLocal.generate` передаёт только `temperature`. Проверить `ollama show qwen2.5:7b` — если Modelfile не задаёт `num_ctx`, у нас скрытый предел 2048.
2. **Три пресета на одном retrieval**: BASELINE (текущий day28-код без изменений: temp=0.2, num_ctx=null) / OPTIMIZED (temp=0.0, num_ctx=8192, num_predict=800, tuned prompt) / **опционально** QUANT (тот же OPTIMIZED, но модель другая — **не Q8_0** для 6GB, а `qwen2.5:7b-instruct-q3_K_M` — единственная влезающая в 6GB целиком по данным dpmn).
3. **`ensure_loaded(model, options)` warmup** — прогреть каждый пресет ДО серии замеров, иначе cold-swap tax искажает средние. У sergio это стало главной методологической находкой поста фактум.
4. **`parse_failure` в агрегате** `:eval` — сколько раз JSON не распарсился (`Unterminated string` и т.п.). У sergio BASELINE = 2/12 сбоев, OPTIMIZED = 0/12. Главное измеримое преимущество явного `num_predict`.
5. **Компактный промпт**: короткие правила без markdown-разметки, самопроверочный чек-лист вместо линейного списка, contrastive example для конкретной галлюцинации (у нас скорее «выдумывание источников», а не «MCP = Model Code Passing»).
6. **RAM/VRAM из `GET /api/ps`** (JSON-путь, не parsing `ollama ps`-таблицы): `size` (общий) и `size_vram` (VRAM). Показать в `:compare-presets` и `:eval --presets`.
7. **tok/s из response.eval_duration** (наносекунды!): `tok_s = eval_count / (eval_duration / 1e9)`. Наглядная разница для видео.

### Взять с осторожностью

8. **Своё квантование через `ollama create --quantize`** — упомянуть в отчёте, но если готовый тег `qwen2.5:7b-instruct-q3_K_M` есть в Ollama library, брать его (не квантовать самим). Проверить: `ollama pull qwen2.5:7b-instruct-q3_K_M` — если качается ок, готовый.
9. **`keep_alive="30m"`** — стоит проставить, но эффект скажется только на серии подряд идущих запросов (в чистом REPL из одного вопроса — не влияет). Не для видео.
10. **Отдельные `num_predict` для verify/rerank/generate** (dpmn: verify=5, rerank=256, generate=1024) — тонкая экономия, у нас retrieval уже сделан день 28, только генерация нового не потребует. Но идея: для guard-повторов можно поставить меньший `num_predict`.

### НЕ брать

11. **Q8_0 квантование** — sergio показал: на его железе (тоже CPU) 40% медленнее, тот же verbatim. Для нашего 6GB GPU **не влезет целиком** → всё равно частично на CPU → ещё хуже. Не тратить время.
12. **kv_cache_type** (`f16`/`q8_0`/`q4_0`) — задание упоминает, но никто из разобранных участников не менял. Это server-side env (`OLLAMA_KV_CACHE_TYPE`), не per-request option. Для видео стоит **проверить**, что стоит по умолчанию (`f16`), и упомянуть, что `q8_0` — потенциальная экономия KV-cache VRAM ~на 25%, но никто не мерил. Не делать в этот день — оставить как «направление на будущее».
13. **Modelfile-пресеты** (soziev) — противоречит нашему `:preset baseline|optimized` из REPL: пришлось бы пересоздавать модель для смены пресета.
14. **DeepSeek-судья / OpenRouter Gemini-анализ** (soziev, nikolay) — у нас `verbatimRate` (структурная метрика, без LLM). Дополнительный судья — оверинжиниринг для этого дня.
15. **Ролевой промпт с эмодзи и «Ты — Senior XXX Developer»** (nikolay, yavits) — противоречит нашему стилю «короткие правила без театра».

### Возможные подводные камни

- **cold-swap tax при `num_ctx` меняется между пресетами** — обязательно warmup. Иначе первый вызов OPTIMIZED будет медленнее последнего вызова BASELINE и вывод перевернётся неправильно (как у sergio до перепроверки).
- **`OLLAMA_CONTEXT_LENGTH` env** — если у Данила выставлен в 32768 (dpmn-случай), поведение baseline будет другое, чем ожидается (не 2048, а 32768, но не влезет). Проверить в env Windows.
- **Rewrite тянет тему курса** (sergio-постфактум) — у нас может быть та же болезнь. Проверить рефером на организационном вопросе перед видео.

---

## Ссылки

- sergio: https://github.com/Sergio-rsd/AI-Challenge/tree/week6/day-4-total-29 (Kotlin, эталон)
- dpmn: https://github.com/dpmn/ai-advent-challenge/tree/main/week-06/day-29 (Python, Q3_K_M на 6GB)
- soziev: https://github.com/dsoziev1/deepseek-cli-day-29 (Python, `ollama create --quantize`)
- nikolay: https://github.com/ShirobokovNE/ai-challenge/tree/day29 (Kotlin, TPS/RAM)
- pechatkin: https://github.com/vapechatkin/ai_ac/tree/main/d29 (Python, temp UP как контр-пример)
- kaa: https://github.com/kaa-it/Ollama/tree/day29 (C#, задание понято как облако)
- avalanche: https://github.com/1Avalanche/SmartReminder/tree/week6-day4 (Kotlin, одна строка)
- yavits: https://github.com/zheksus/ai_challenge/tree/main/day29 (Python, ролевой промпт с эмодзи)
