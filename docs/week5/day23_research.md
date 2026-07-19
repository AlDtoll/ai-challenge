# Day 23 — RAG + rerank + query rewrite: разбор эталонов

Дата: 2026-07-05
Ветка нашего дня: `week5/day2` (день 23 = продолжение). Стек: Kotlin JVM + Java HttpClient + Gson, Ollama (nomic-embed-text 768d) + DeepSeek. Без Retrofit/OkHttp, без тяжёлых локальных моделей.

Разобраны 4 репо. Итог: единственный по-настоящему релевантный эталон — **mobdev778 (Kotlin)**. У **kaa-it** проект вообще на C# (не Kotlin, как заявлено в брифе), но у него есть проектный prompt-документ `rag.md`, откуда взято описание архитектуры «сравнения 4 режимов». **ShirobokovNE day23** — не про реранкинг вовсе (у него трёхуровневая память STM/WM/LTM, никакого rerank/rewrite). **dpmn** — короткий Python-модуль LLM-судьи, справочно.

---

## 1. mobdev778 (Kotlin, наш стек) — главный эталон

Репо: `mobdev778/aiadventchallenge8`, ветка `day_23`.
Пакет RAG: `src/main/kotlin/com/github/mobdev778/aiadventchallenge/domain/rag/`.

### 1.1 Структура пакета RAG

```
domain/rag/
├── RagSearcher.kt                  # интерфейс: suspend search(query): List<RagSearchResult>
├── RagSearchResult.kt              # data class(source, section, text, vector)
├── SimpleRagSearcher.kt            # naive top-K через PriorityQueue (baseline)
├── RankedRagSearcher.kt            # пайплайн rewrite → search → rank → topK → threshold
├── RagChunkGenerator.kt            # генерит эмбеддинг через ONNX embedding
├── filechunker/                    # FileChunker, FixedSize, Paragraphs + Factory
├── queryrewriter/
│   ├── QueryRewriter.kt            # LLM-based query rewrite, 3 варианта
│   └── RewrittenQueriesResponse.kt # data class {rewritten_queries: List<String>}
├── ranker/
│   ├── Ranker.kt                   # интерфейс: init(query) + rank(text, vector): Double
│   ├── SimilarityRanker.kt         # cosine-similarity ранкер (bug-free и предсказуемый)
│   ├── HeuristicRanker.kt          # rolling-hash подстрок (BM25-подобная эвристика)
│   ├── ReRanker.kt                 # ONNX cross-encoder scoring model
│   ├── RankerFactory.kt            # DI-фабрика: возвращает нужную реализацию
│   └── CosineSimilarityExt.kt      # утилита
└── model/
    ├── RagConfig.kt / RagFilterType.kt (Similarity/Heuristic/Reranker)
```

Ключевые архитектурные решения:
- Отдельные абстракции `RagSearcher` (retrieval) и `Ranker` (переоценка кандидатов).
- `RankedRagSearcher` = композиция `SimpleRagSearcher` + `QueryRewriter` + `RankerFactory`.
- Три взаимозаменяемых ранкера через enum `RagFilterType`.
- Конфиг persistent в SQLite (RagConfigEntity) — можно перегружать «на лету».

### 1.2 Reranker — как реализован (СТРАТЕГИЧЕСКИЙ момент)

У mobdev778 есть **три** ранкера, включаемых через enum:

```kotlin
// RankerFactory.kt
fun create(filterType: RagFilterType): Ranker = when (filterType) {
    RagFilterType.Reranker   -> ReRanker(scoringModel)                    // ONNX cross-encoder
    RagFilterType.Heuristic  -> HeuristicRanker()                          // rolling-hash substrings
    RagFilterType.Similarity -> SimilarityRanker(RagChunkGenerator(...))   // cosine повторно
}
```

**ReRanker.kt** — тонкая обёртка над `dev.langchain4j.model.scoring.onnx.OnnxScoringModel`:
```kotlin
override suspend fun rank(found: String, vector: FloatArray): Double =
    scoringModel.score(query, found).content()
```
Это локальная ONNX cross-encoder модель (типа BGE-reranker, MiniLM). Промпта нет — просто модель `(query, doc) → score`.

**Для нас это НЕ подходит:** тяжёлая ONNX runtime, файлы модели, порог 100-500 МБ, ClassLoader-костыли в фабрике (см. код `RankerFactory.embeddingModel`), лишние gradle-зависимости langchain4j.

**HeuristicRanker.kt** — интересный дешёвый бэйзлайн: строит rolling-hash всех подстрок query, считает сколько подстрок встречается в кандидате. Аналог char-n-gram overlap. Всё in-memory, стоимости нет.

**SimilarityRanker.kt** — просто повторно считает cosine на эмбеддингах. Странно, но у него роль «жёсткого финального фильтра» после реранкера (см. `RankedRagSearcher` шаг 4).

**Ключевой инсайт:** LLM-судьи как реранкера в mobdev778 **НЕТ**. Он использует ONNX. То есть наша идея с DeepSeek-судьёй — это уже отход в сторону dpmn.

### 1.3 Query rewriter — реализация (можно копировать почти дословно)

`QueryRewriter.kt` — LLM-based, 3 варианта через structured JSON. Промпт (system, дословно из файла):

> You are an expert search-query optimization assistant for Retrieval-Augmented Generation (RAG) systems. Your task is to perform "query rewriting" to improve search recall and precision.
>
> Analyze the user's input query and generate exactly {count} distinct variations of it.
>
> Follow these rewriting strategies:
> 1. Rephrasing & Synonyms: Use different terminology, technical synonyms, or alternative phrasing while keeping the original intent.
> 2. Conceptual Expansion: Breakdown the query into core concepts or add implicit keywords that a relevant document would likely contain.
> 3. Search Engine Style: Convert the natural language question into a concise, keyword-driven search string (like a Google or vector search query).
>
> Output MUST be a valid JSON object with a single key "rewritten_queries" containing an array of exactly 3 strings. Do not include any explanations or markdown formatting outside the JSON.
>
> Example Output Format (if number of variations is 3):
> {"rewritten_queries": ["Variation 1 here", "Variation 2 here", "Variation 3 here"]}

User prompt: `"Rewrite the following user query into 3 variations for RAG retrieval:\n\nQuery: \"$query\"\n"`

Парсинг:
1. Trim.
2. `removePrefix("\`\`\`json").removeSuffix("\`\`\`")` — защита от markdown-обёртки.
3. `json.decodeFromString<RewrittenQueriesResponse>(clean)`.
4. Фолбек при исключении: `listOf(query)` (использовать оригинал).

Температура НЕ задана (значит default модели). Модель — `appSettings.baseModel` из настроек.

**HYDE (гипотетический документ-ответ) — НЕТ.** Только парафраз/расширение/keyword-style. Это важно: mobdev778 не тратит вызовы LLM на генерацию псевдодокумента.

### 1.4 Как объединяются результаты нескольких запросов (multi-query fusion)

`RankedRagSearcher.search()`:

```kotlin
if (config.useQueryRewriting) {
    val queries = queryRewriter.getQueries(query, 3)
    queries.forEach { result.addAll(searcher.search(it)) }  // ← naive concat, дубликаты не убираются
} else {
    result.addAll(searcher.search(query))
}
```

**Обратите внимание:** union без дедупликации. Дубликаты остаются, потом их «съедает» реранкер + `topKAfter`. RRF (Reciprocal Rank Fusion) не применяется. Это ГРУБО, но работает — сортировка после реранка сама вытолкнет дубли наверх.

### 1.5 Пороги и top-K — как настроены

`RagConfigEntity`:
```kotlin
val topKBefore: Int       // сколько кандидатов оставить ПОСЛЕ retrieval (у SimpleRagSearcher)
val filterType: String    // Similarity | Heuristic | Reranker
val useQueryRewriting: Boolean
val topKAfter: Int        // сколько оставить ПОСЛЕ ранкера
val useMinSimilarity: Boolean
val minSimilarity: Double // абсолютный cos-порог финальной фильтрации
```

Полный конвейер (`RankedRagSearcher.kt`):
1. **Retrieve**: `SimpleRagSearcher` → top-`topKBefore` (PriorityQueue, cosine). Если query rewrite включён — вызывается на всех 3 переформулировках.
2. **Rerank**: выбранный `Ranker` пересчитывает score → `sortedByDescending`.
3. **Cut**: `.take(topKAfter)`.
4. **Threshold (опция)**: если `useMinSimilarity`, для каждого результата берётся **свежий cosine** (через `SimilarityRanker`) и фильтруется по `>= minSimilarity`. Порог сравнивается с cosine, не со score реранкера.

Ключевые уроки:
- **Порог применяется ПОСЛЕ реранка, а не до.** Это противоположно нашему изначальному плану.
- Порог сравнивается **с cosine-similarity**, а не со score cross-encoder-а. Правильно: score реранкера — в своей шкале (может быть sigmoid, может быть логит) и не сопоставим между запусками.
- Два top-K: `topKBefore` (сколько в реранкер) и `topKAfter` (сколько в LLM). Классика: 10-20 → 3-5.

### 1.6 Метрики и compare-режим — ОТСУТСТВУЮТ

В коде mobdev778 нет CompareCommand, нет метрик Retrieval@K, нет A/B прогона. Есть только один `println("!!! RAG searcher. results: $results")` в `SimpleRagSearcher` как дебаг. RAG-результат отдаётся дальше в агенты (`PlannerAgent`, `ExecutorAgent` и т.д.). Это чат-приложение, а не бенчмарк.

**Для нас это значит:** compare-режим с метриками нужно проектировать самим. mobdev778 в этом не помогает.

---

## 2. dpmn — Python reranker.py (справочно, для промпта LLM-судьи)

Файл: `ragger/reranker.py`. Только 2 функции: `threshold_filter()` и `llm_rerank()`.

### 2.1 Промпт LLM-судьи (дословно)

```python
prompt = (
    "Оцени релевантность каждого документа к запросу пользователя.\n\n"
    f"Запрос: {query}\n\n"
    "Документы:\n" + chunks_text + "\n\n"
    "Верни JSON-массив с оценками от 0.0 до 1.0, где 1.0 — идеально релевантен, "
    "0.0 — не релевантен. Индекс элемента в массиве соответствует номеру документа.\n"
    "Формат: [0.1, 0.9, 0.4, ...]"
)
```

Где `chunks_text` = `"\n\n".join(f"[{i}] {c['text'][:800]}" ...)` — обрезание документа до 800 символов (важная деталь: экономит контекст, снижает latency).

### 2.2 Параметры вызова

- Модель: `Qwen/Qwen3-Coder-Next` (default, параметризуется).
- Temperature: **0.1** (низкая — для стабильности оценок).
- max_tokens: **512** (мало! — для JSON-массива много не надо).
- Один батч-запрос на все чанки (НЕ по одному вызову на чанк).

### 2.3 Парсинг — трёхуровневый fallback

```python
def _parse_scores(content, expected):
    # 1) json.loads → list[float] правильной длины
    # 2) regex \d+(?:\.\d+)? → берём все числа
    # 3) padding до expected значением 0.5 (нейтрально)
    return [0.5] * expected  # финальный fallback
```

**Урок:** LLM-судья ЧАСТО ломает JSON. Обязателен regex-fallback и padding нейтральным значением, а не 0.

### 2.4 Порог

В самом `llm_rerank` порога нет — только сортировка. Порог — отдельная функция `threshold_filter(chunks, threshold)`. То есть **rerank и threshold — независимые шаги**, порядок применения в вызывающем коде.

### 2.5 Гочи

- Формат `[0.1, 0.9, ...]` (индекс → id) проще чем `[{id, score}]` — короче ответ, легче парсить, но LLM может пропустить элементы → нужен fallback.
- Не полагаться на `json.loads` — LLM возвращает markdown-обёртки, лишний текст.
- `text[:800]` — обязательный лимит, иначе контекст выстреливает.

---

## 3. kaa-it — проект на C# (НЕ Kotlin) + prompt-документ `rag.md`

Репо `kaa-it/Ollama`, ветка `day23`. По языкам — 100% C# (не Kotlin, как ожидалось). В корне лежат «промпт-документы» `rag.md`, `rag2.md`, `enhanced_rag_prompt.md` — это не код, а **описание архитектуры для OpenCode**, чтобы тот сгенерировал реализацию. Полезны как источник паттернов.

### 3.1 Что автор описывает в `rag.md` — базовый RAG + 10 тест-вопросов

- Индексация markdown-файлов из репозитория Rust design patterns (по 4-х уровневой иерархии idioms/patterns/anti_patterns/functional).
- Chunking strategy: FixedSize + Structural, для RAG использует Structural.
- **10 контрольных вопросов** с полями: `question`, `expected_answer`, `expected_sources` (какие md должны попасть в топ), `difficulty` (easy/medium/hard).
- Метрики compare-режима: длина ответа, «упоминание ожидаемых ключевых концепций (простая эвристика)», источники, время ответа.
- Отчёт — таблица в консоли: без-RAG vs с-RAG для каждого вопроса.

### 3.2 Что описано в `rag2.md` (аудит + улучшения)

Автор явно называет баги предыдущей реализации:
- **Double prefix** в эмбеддингах: `search_document: search_query: ...`. У nomic-embed-text есть префиксы `search_query:` (для запросов) и `search_document:` (для документов). Их нельзя дублировать. **Урок для нас:** для nomic-embed-text query и document эмбеддинги должны иметь РАЗНЫЕ префиксы, ошибка ломает поиск.
- Добавляют поле `key_concepts` в тест-вопросы для авто-скоринга: `ScoreAnswer = matched / total`.

### 3.3 Что описано в `enhanced_rag_prompt.md` — усиленный pipeline с 4 режимами

**Именно это описывает 4-режимный compare, который так удобен для нашего дня 23:**

```
Baseline       — только vector top-3
WithThreshold  — vector top-10 + фильтр по similarity >= 0.5 + take(3)
WithReranker   — vector top-10 + reranker → sort → take(3)
FullPipeline   — QueryRewrite + top-10 + threshold + reranker → take(3)
```

Модель реранкера у kaa-it — **HeuristicReranker** (не LLM, не cross-encoder):
```
FinalScore = 0.6 * cosine_similarity + 0.3 * keyword_match_ratio + 0.1 * length_boost
```
где `length_boost` штрафует слишком короткие (<50 слов) и слишком длинные (>500) чанки.

Query rewriter у него — **два варианта**:
- **HeuristicQueryRewriteService**: тупо добавляет «Rust» и «pattern» если отсутствуют. Zero-cost, offline.
- **LlmQueryRewriteService**: одна LLM-переформулировка (не 3), результат обрезается кавычками.

Env-vars:
- `RAG_TOP_K_PRE=10` (для реранка)
- `RAG_TOP_K_POST=3` (в LLM)
- `RAG_SIMILARITY_THRESHOLD=0.5` (cosine, не rerank-score)
- `RAG_ENABLE_REWRITE=true`, `RAG_ENABLE_RERANK=true`

**Инсайты для нас:**
- Порог 0.5 на cosine — консервативный, часто в RAG-практике используют 0.3-0.4. Наш план `cos<0.4` — норм.
- Heuristic reranker без LLM даёт бесплатное сравнение с baseline: показывает, что даже дешёвый rerank улучшает Retrieval@K. Стоит включить как один из режимов сравнения — покажет ценность LLM-судьи.

### 3.4 Что kaa-it явно называет провалом (rag2.md — «Критические баги»)

- Hardcoded model в LLM-клиенте вместо env var → лишние расходы на неправильную модель.
- Sync-вызовы в async методе → потенциальный deadlock.
- Двойной префикс embeddings → поиск ломается полностью, метрики недостоверны.
- `IndexOf("patterns/")` для парсинга source path → падает на Windows (`\`) и разном регистре.

Общий урок: **валидация pipeline** (единичный тест «известный вопрос → ожидаемый top-1 source») критична, иначе можно долго не замечать сломанный ретривер.

---

## 4. ShirobokovNE — day23 не про реранкинг

Репо `ShirobokovNE/ai-challenge`, ветка `day23`. README описывает трёхуровневую модель памяти (STM/WM/LTM) + SQLite + OpenRouter. Файлы: `HistoryManager.kt`, `LlmAgent.kt`, `MessageAnalysisServer.kt`, `Mcp.kt`, `WeatherAnalyticsHub.kt`. Ни `rerank`, ни `rewrite`, ни `RAG` в структуре нет.

**Для нас — не релевантен**, пропускаем.

---

## 5. Проверка нашего плана против эталонов

Наш начальный план:
> - Query rewrite через DeepSeek: 3 переформулировки + HYDE-псевдоответ → эмбеддим 4 варианта → объединяем top-K.
> - Реранкер через DeepSeek-судью: даём top-10 кандидатов + вопрос, просим JSON `[{id, score:0-10}]`, порог 5.0.
> - Similarity-порог cos<0.4 перед реранкером.
> - Метрики в --compare: Retrieval@3, Retrieval@10, Grounded, LLM calls.

### 5.1 Query rewrite — плюс HYDE или без?

mobdev778 — **3 парафраза, без HYDE**. Union результатов без RRF. Одобрено, работает.

HYDE (генерация псевдо-ответа, потом его эмбеддим) — **никто из эталонов не использует**. Это лишний вызов LLM (+ токены) и в задаче с домашним RAG-датасетом даёт спорную выгоду. **Рекомендация: убрать HYDE из плана**, оставить 3 парафраза. Это сэкономит 1 LLM-call на запрос и не потеряем в качестве.

Формат ответа копируем 1-в-1 у mobdev778: `{"rewritten_queries": [...]}` с system-промптом «Rephrasing / Conceptual Expansion / Search Engine Style». Плюс защита `removePrefix("\`\`\`json")`.

### 5.2 Reranker — LLM-судья или что-то ещё?

Эталоны:
- mobdev778 — ONNX cross-encoder (**нам не подходит**, тяжёлая модель).
- dpmn — LLM-судья (Qwen), батчем, формат `[0.1, 0.9, ...]`.
- kaa-it — эвристика (0.6·cos + 0.3·keyword + 0.1·length_boost), **без LLM**.

Наш выбор — DeepSeek-судья — **оправдан**, но:
- **Формат ответа**: `[{id, score:0-10}]` длиннее и хрупче чем dpmn-style `[0.1, 0.9, ...]`. LLM легко забудет id или потеряет пару. **Рекомендация: использовать dpmn-формат** `[0.7, 0.3, 0.9, ...]` где индекс = порядок в промпте. Короче, надёжнее, легче падать в regex-fallback.
- **Шкала 0-10**: LLM часто прилипает к «7» — низкое разрешение. **Рекомендация: 0.0-1.0** как у dpmn, temperature 0.1, max_tokens 512.
- **Обрезание чанков**: `text[:800]` обязательно, иначе один длинный документ выест контекст DeepSeek.
- **Батч, а не по одному**: один запрос на весь top-10. Экономит и время, и деньги.
- **Порог реранка 5.0**: субъективный, лучше сделать конфигурируемым и подобрать эмпирически. У dpmn отдельный `threshold_filter`, применяется после rerank.

### 5.3 Similarity-порог — до или после реранкера?

Наш план: `cos<0.4` **перед** реранкером.
mobdev778: порог **после** реранкера + top-K cut.
dpmn: порог как отдельный шаг, порядок на усмотрение.

**Аргумент за «до»:** если кандидатов много и они мусорные — не тратим LLM-вызов реранка на них. Экономия.
**Аргумент за «после»:** реранкер видит больше кандидатов, шанс найти скрытый gem выше. Плюс cosine не всегда коррелирует с настоящей релевантностью — можно отсечь хороший чанк, у которого низкий cos из-за плохого эмбеддинга.

**Рекомендация:** ставим порог **ДО** реранка (как в плане), но делаем его мягким — `cos<0.25-0.3`, не `0.4`. И **дополнительно** порог по rerank_score после (для финального cut). Так экономим DeepSeek-вызовы и фильтруем мусор. Это гибрид mobdev778 и dpmn.

### 5.4 Метрики compare — что реально измерять

Наш план: Retrieval@3, Retrieval@10, Grounded, LLM calls.

kaa-it описывает: длина ответа, key-concepts matched, sources_used, response time. mobdev778 — вообще нет compare.

**Рекомендация — расширить метрики**:
- **Retrieval@K** (K=1, 3, 10) — Data-set вопросов должен содержать `expected_source_ids` (какие чанки/файлы должны попасть в топ). Считаем: попал ли хоть один ожидаемый в top-K.
- **MRR** (Mean Reciprocal Rank) — ранг первого ожидаемого. Точнее чем Retrieval@K.
- **Grounded / faithfulness**: не через LLM (дорого и субъективно), а через простую эвристику — процент упомянутых `key_concepts` из вопроса (kaa-it подход). Дёшево и наглядно.
- **Стоимость**: `llm_calls` (rewrite=1, rerank=1, final=1 → 3 на запрос в FullPipeline) и **tokens_in/out** отдельно.
- **Latency**: p50 / p95 в мс.

Режимы сравнения (беру от kaa-it, оптимизирую под наш стек):

| Режим | rewrite | rerank | threshold | LLM calls | Что показывает |
|-------|---------|--------|-----------|-----------|----------------|
| `naive` (baseline) | ✗ | ✗ | ✗ | 1 (final) | базовая точность retrieval |
| `+threshold` | ✗ | ✗ | ✓ | 1 | цена cos-порога |
| `+rerank` | ✗ | ✓ | ✗ | 2 | ценность LLM-судьи |
| `+rewrite` | ✓ | ✗ | ✗ | 2 | ценность многозапросного поиска |
| `full` | ✓ | ✓ | ✓ | 3 | end-to-end |

5 столбцов в таблице метрик. Показать ΔMRR/ΔRetrieval@3 относительно `naive`.

### 5.5 Гочи, которые встают в полный рост

1. **nomic-embed-text префиксы** (kaa-it `rag2.md`): для запросов нужен `search_query:`, для документов `search_document:`. **Проверить в нашем day22 коде**, что не дублируется. Если день 22 индексировал документы без префикса — придётся переиндексировать.
2. **Multi-query fusion**: mobdev778 делает простой union без дедупликации. Работает, но если у одного чанка топ-1 во всех 3 переформулировках, он занимает 3 слота в top-K. **Мягкая дедупликация** по `chunk_id` (оставляем max cosine) — 3 строки кода, значительно улучшает.
3. **JSON от LLM ломается** (dpmn): обязателен regex-fallback `\d+(?:\.\d+)?` и padding нейтральным 0.5. Иначе один плохой вызов ломает весь compare.
4. **`text[:800]` в rerank промпте** — критично для стоимости.
5. **Temperature=0.1** в rerank/rewrite (не 0 — DeepSeek может залипать; не 0.7 — нужна стабильность).
6. **Порог сравнивается со ШКАЛОЙ ТОЙ ЖЕ СИЛЫ**: cos-порог с cos-score, rerank-порог с rerank-score. Не смешивать.
7. **compare-режим не встроен в mobdev778** — это дополнительная инженерная работа только для челленджа. Не забыть про кэширование ответов LLM (kaa-it упоминает: «нет кэширования — 20 API-вызовов каждый запуск»). Простой SQLite-кэш по хэшу (question, mode) сэкономит в разы.

---

## 6. Итоговые рекомендации по нашему дизайну

**Оставить как в плане:**
- 3 переформулировки через DeepSeek + union результатов (mobdev778).
- LLM-судья через DeepSeek на top-10 (dpmn).
- Similarity-порог перед реранкером (наша идея, разумная).
- Compare-режим с несколькими стратегиями (kaa-it описывает лучше всех).

**Изменить:**
- **Убрать HYDE** — эталоны не используют, лишний вызов LLM.
- **Формат ответа реранка**: `[0.1, 0.9, ...]` вместо `[{id, score}]`. Шкала **0.0-1.0** вместо 0-10.
- **Порог реранка** 5.0 → 0.4-0.5 (в новой шкале), сделать env-переменной.
- **Similarity-порог** 0.4 → 0.25-0.3 (мягче — иначе съедает нормальные кандидаты).
- **Метрики**: добавить MRR и tokens, разбить LLM calls по фазам (rewrite/rerank/final).
- **Дедупликация** по chunk_id при multi-query fusion (не в эталонах, но 3 строки кода и заметный выигрыш).

**Добавить (эталоны это делают):**
- **`text[:800]`** обрезание чанка в rerank-промпте.
- **Regex-fallback** парсинга JSON от LLM с padding до `expected`.
- **Кэш ответов LLM** в SQLite по хэшу (question, mode) для повторных прогонов compare.
- **Проверка префиксов nomic** (`search_query:` для запроса, `search_document:` для документов) — глазами в код day22.

**НЕ брать:**
- ONNX cross-encoder / langchain4j — тяжёлое и вне стека.
- Локальный `bge-reranker` через Ollama — Ollama не поставляет reranker-моделей (только embed и chat), пришлось бы вручную ставить ONNX runtime. Не стоит.
- Persistent config в SQLite (mobdev778 RagConfigEntity) — избыточно для челленджа, достаточно env vars + CLI-флагов.

**Список файлов для итогового дизайна (рекомендуемая структура week5/day23):**
```
week5/day23/
├── src/main/kotlin/…/rag/
│   ├── QueryRewriter.kt         # LLM-based, 3 варианта, dpmn+mobdev промпт
│   ├── Reranker.kt              # DeepSeek-судья, batch, [0.0-1.0] scale
│   ├── NaiveRetriever.kt        # cos top-K (уже есть в day22)
│   ├── RankedRetriever.kt       # композиция rewrite + retrieve + threshold + rerank
│   ├── RagPipeline.kt           # финальный интерфейс, режимы
│   └── model/{Chunk, ScoredChunk, RagMode}.kt
├── src/main/kotlin/…/eval/
│   ├── TestQuestions.kt         # JSON-файл с ~10 вопросами (question, expected_sources, key_concepts)
│   ├── Metrics.kt               # Retrieval@K, MRR, groundedness, cost
│   ├── LlmCache.kt              # SQLite кэш вызовов
│   └── CompareRunner.kt         # прогон 5 режимов, отчёт в консоль/md
└── README.md                    # инструкция + таблица результатов
```

---

## Приложение: ссылки на конкретные файлы

### mobdev778 (day_23)

- QueryRewriter: `src/main/kotlin/com/github/mobdev778/aiadventchallenge/domain/rag/queryrewriter/QueryRewriter.kt`
- RankedRagSearcher (главный пайплайн): `src/main/kotlin/com/github/mobdev778/aiadventchallenge/domain/rag/RankedRagSearcher.kt`
- SimpleRagSearcher (baseline): `src/main/kotlin/com/github/mobdev778/aiadventchallenge/domain/rag/SimpleRagSearcher.kt`
- Ranker интерфейс: `src/main/kotlin/com/github/mobdev778/aiadventchallenge/domain/rag/ranker/Ranker.kt`
- ReRanker (ONNX): `src/main/kotlin/com/github/mobdev778/aiadventchallenge/domain/rag/ranker/ReRanker.kt`
- HeuristicRanker: `src/main/kotlin/com/github/mobdev778/aiadventchallenge/domain/rag/ranker/HeuristicRanker.kt`
- SimilarityRanker: `src/main/kotlin/com/github/mobdev778/aiadventchallenge/domain/rag/ranker/SimilarityRanker.kt`
- RankerFactory: `src/main/kotlin/com/github/mobdev778/aiadventchallenge/domain/rag/ranker/RankerFactory.kt`
- Конфиг: `src/main/kotlin/com/github/mobdev778/aiadventchallenge/data/rag/datasource/model/RagConfigEntity.kt`

Raw URL шаблон: `https://raw.githubusercontent.com/mobdev778/aiadventchallenge8/day_23/<path>`.

### dpmn

- Reranker: `ragger/reranker.py` в `https://github.com/dpmn/ai-advent-challenge/blob/main/`.

### kaa-it (проектные документы, не код)

- Базовый RAG: `rag.md` в `https://github.com/kaa-it/Ollama/tree/day23`.
- Аудит + фиксы: `rag2.md`.
- Enhanced pipeline (4 режима, threshold, rerank, rewrite): `enhanced_rag_prompt.md`.

### ShirobokovNE

Не про RAG-rerank. Пропустить.
