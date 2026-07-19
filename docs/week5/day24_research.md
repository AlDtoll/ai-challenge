# Day 24 — Цитаты, источники и анти-галлюцинации: исследование 4 эталонных решений

## Эталоны

| # | Автор | Стек | Ссылка |
|---|-------|------|--------|
| 1 | **kaa-it** | **C# / .NET 10** (не Kotlin, вопреки заявке в задании) | https://github.com/kaa-it/Ollama/tree/day24/DocIndexer |
| 2 | **ShirobokovNE** | **Kotlin + Gson** (наш стек) | https://github.com/ShirobokovNE/ai-challenge/tree/day24/src/main/kotlin/ru/myproject/aichat |
| 3 | **dpmn** | Python | https://github.com/dpmn/ai-advent-challenge/blob/main/ragger/answer.py + `agents/jarvis.py` |
| 4 | **dgoryachkovskiy** | Python | https://github.com/dgoryachkovskiy/AiTgsterBot/blob/codex/day_24_rag_citations_antihallucination/day24_rag_citations_agent.py |

Важно: **kaa-it, несмотря на URL `Ollama/`, на day24 — это .NET-проект (`DocIndexer/*.cs`, `PROMPT_RAG_CITATIONS.md`).** Kotlin даёт только Shirobokov.

---

## 1. Формат обязательных источников (JSON-контракт)

### kaa-it — самый строгий и подробный
`DocIndexer/RagAnswerModels.cs` + `PROMPT_RAG_CITATIONS.md:41-56`, `PromptBuilder.cs:17-28`:

```json
{
  "answer": "<comprehensive answer with inline [CITATION:0], [CITATION:1] markers>",
  "confidence": "<high|medium|low|unknown>",
  "sources": [
    { "index": 0, "source": "<file path>", "section": "<section name>",
      "chunk_id": "<uuid>", "score": 0.85 }
  ],
  "citations": [
    { "index": 0, "quote": "<exact text from chunk>", "source_index": 0 }
  ],
  "clarification_request": "<if confidence=unknown, ask user to clarify>"
}
```
- **`sources` и `citations` — раздельные массивы** (citation.source_index → sources[idx]).
- Требование к цитате: **точный substring, 30-200 символов**.
- **Inline-маркеры `[CITATION:N]` в answer обязательны** — это error, а не warning (`CitationValidator.cs:30-34`).
- Структурированный вывод форсится только промптом + пост-парсингом; `response_format=json_object` НЕ используется (`AnthropicLlmService.cs` — обычный Anthropic messages call без tools/json mode).
- **Нет JSON mode ни у кого.** Все живут через "extract JSON из raw string" (`CitationAnswerParser.ExtractJson`).

### dgoryachkovskiy — «ALLOWED_QUOTES»-режим (важный трюк)
`day24_rag_citations_agent.py:311-374`:

```json
{
  "answer": "краткий ответ на русском",
  "sources": [{"source_id": "S1", "source": "...", "section": "...", "chunk_id": "..."}],
  "quotes": [{"source_id": "S1", "source": "...", "section": "...", "chunk_id": "...", "quote": "точная цитата из чанка"}],
  "confidence": "high|medium|low",
  "needs_clarification": false
}
```
- **source_id — синтетический `S1..SN`**, а не UUID; `chunk_id` в отдельном поле.
- `sources` и `quotes` тоже раздельно.
- **Ключевой трюк:** ДО вызова LLM программа сама детерминированно нарезает `ALLOWED_QUOTES` из чанков (`deterministic_quotes` + `best_quote`) и передаёт списком в промпт: *«quote должен быть дословно скопирован из ALLOWED_QUOTES. Не сокращай и не переформатируй»* (`agent.py:356-357`). Это резко снижает hallucinated-quote rate.
- Порог длины цитаты **40-240 символов**.

### dpmn — самый скромный контракт
`ragger/answer.py:20-28, 82-113`:

```json
{
  "answer": "текст ответа",
  "sources": [
    { "source": "docs/example.md", "chunk_id": "struct_00042",
      "section": "Название раздела", "quote": "дословная цитата" }
  ],
  "confidence": "high|medium|low|none"
}
```
- **quote внутри объекта source** — 1 цитата = 1 источник (нельзя привязать несколько цитат к одному чанку). Наивно, но проще.
- `confidence: none` — специальный маркер абстейна.

### Shirobokov — вообще не JSON!
`LlmAgent.kt:598-612` — формат ТЕКСТОВЫЙ:
```
ОТВЕТ: (твой подробный ответ на основе контекста)
ИСТОЧНИКИ:
1. [Название файла] (ID: ..., Секция: ...)
ЦИТАТЫ:
- "(прямая цитата из текста чанка)"
```
- Никакой валидации substring, никакого парсинга — trust the LLM.
- chunk_id из `ChunkMetadata` (`IndexingModels.kt:6-18`): `source, fileName, chunkId, section, msgId, author, date`.

---

## 2. Валидация цитат

| Автор | Метод |
|-------|-------|
| **kaa-it** | `CitationAnswerParser.ValidateQuoteExists` (parser.cs:134-142) + `CitationValidator.cs:16-27`. Нормализация whitespace: `Regex.Replace(text.Trim(), @"\s+", " ")`, затем `Contains(..., StringComparison.OrdinalIgnoreCase)`. Дополнительно: длина цитаты 30-200 chars (warning если вне диапазона). НЕТ fuzzy match. |
| **dgoryachkovskiy** | `quote_matches_chunk` (agent.py:395-400): `re.sub(r"\s+", " ", quote).strip().lower() in re.sub(r"\s+", " ", chunk.text).strip().lower()`. Плюс — сам ЛИМИТИРУЕТ пул допустимых цитат через ALLOWED_QUOTES (`deterministic_quotes` — заранее нарезает лучший фрагмент через `best_quote(chunk, query)`). |
| **dpmn** | Валидации НЕТ. `_normalize_sources` (answer.py:296-314) только приводит поля к формату — не проверяет, что quote есть в тексте чанка. |
| **Shirobokov** | Валидации НЕТ. Только инструкция в промпте. |

**Никто не использует fuzzy/regex — только нормализация whitespace + substring.**

---

## 3. Абстейн ("не знаю")

| Автор | Триггер абстейна | Что говорит вместо ответа |
|-------|------------------|---------------------------|
| **kaa-it** | `UnknownThresholdHandler.cs:21` — `maxSim < 0.45` (env `RAG_UNKNOWN_THRESHOLD`). Считается на `FinalScore` — что это score после rerank (не raw cosine). Дополнительно вводит `_minHighConfidenceSimilarity=0.65` для confidence gradation. | Фиксированный текст с указанием реального score: *«best matching content has a relevance score of X, which is below my threshold. Please rephrase your question or ask about a different topic»* (`ComparisonAgent.cs:23-25`). Наводящих вопросов НЕТ. |
| **dgoryachkovskiy** | `relevance_threshold=0.62` (agent.py:854, env `DAY24_RELEVANCE_THRESHOLD`) — это на макс. context_relevance (композитный из вектора+keyword). Проверка **до** вызова LLM: `low_relevance_answer` (agent.py:324-335). | *«не знаю. Найденный контекст ниже порога релевантности (X < 0.62). Уточните вопрос: файл, класс, функцию или endpoint.»* — фиксированный шаблон с подсказкой типа уточнения (файл/класс/функция/endpoint), НЕ вопросы. |
| **dpmn** | Двойной механизм: 1) `if not chunks: confidence=none` (answer.py:56-66); 2) **pre-verify LLM-проход** дешёвой Qwen3-30B (`_verify_relevance`, answer.py:149-201) — просит бинарный yes/no *«есть ли документ ХОТЯ БЫ КАСАЮЩИЙСЯ темы»*. При no → `confidence=none`. НЕТ порога по score вообще. | *«Я не знаю ответа на этот вопрос. В найденных документах нет информации по данной теме.»* Наводящих вопросов НЕТ. |
| **Shirobokov** | Нет числового порога! `formatContext` (RagService.kt:108) просто ДОБАВЛЯЕТ в конец промпта инструкцию: *«Если информации НЕДОСТАТОЧНО или она НЕ РЕЛЕВАНТНА, ты ОБЯЗАН ответить: К сожалению, я не нашёл информации по вашему вопросу в базе знаний. Пожалуйста, уточните запрос»*. Решение принимает LLM. | Точный фиксированный текст выше. Наводящих вопросов НЕТ, но есть *«уточните запрос»*. |

**Ни один эталон не генерирует персонализированные наводящие вопросы (2+ штуки) — все ограничиваются фиксированной формулировкой с общим призывом «уточнить».**

---

## 4. Grounded verification (второй LLM-проход)

**Ключевой вывод: ни один эталон НЕ делает post-hoc grounded-судьи после генерации ответа.**

- **kaa-it:** post-хок ретраи с фидбеком на LLM (`ComparisonAgent.cs:39-89`, `RAG_MAX_RETRIES=3`), но это НЕ второй судья — это тот же вопрос с добавленным фидбеком *«Previous response had validation errors: ...»* для форс-исправления. Проверка чисто механическая (substring, длина цитат, inline-маркеры), не семантическая. Fallback после N retries — берёт top-chunk и делает синтетический ответ.
- **dpmn:** ДО ответа делает pre-verify (`_verify_relevance`) — дешёвая Qwen3-30B отвечает yes/no. **Это НЕ grounded, а retrieval-consistency check** — «есть ли что-то по теме вообще».
- **dgoryachkovskiy:** только substring + `meaning_matches_quotes` — детерминированный overlap-score, не LLM-судья.
- **Shirobokov:** ничего.

**Никто не использует то, что мы делали в день 23 (LLM-судья) как grounded-verifier для day24.** Это означает, что наш план `grounded-judge` — оригинальная работа, а не копия эталонов. Риск: LLM-судья — самая дорогая по токенам часть, все избегают её на этапе. Но именно она даёт настоящий anti-hallucination signal.

---

## 5. Метрики на 10 вопросах

### kaa-it (`EvaluationEngine.cs:266-280`, СOL `evaluation_citations`)
```
has_sources, has_citations, citations_match_context (validator.IsValid),
answer_consistent_with_citations, correctly_said_unknown,
confidence, is_unknown, max_similarity, chunk_count,
validation_errors[], validation_warnings[]
```
- `answer_consistent_with_citations` (`EvaluationEngine.cs:172-194`) — оригинальная heuristics: разбивает цитаты на слова, фильтрует stopwords (набор из ~30 английских), берёт слова длиной >3, считает какая доля есть в answer.lower(). **Порог consistency: `ratio >= 0.3`**.
- НЕТ MRR/R@k — метрики только *«качество ответа»*, не *«качество retrieval»*.

### dgoryachkovskiy (agent.py:476-491)
Самый полный набор:
```
model_json_valid, has_answer, has_sources, source_fields_ok,
has_quotes, quote_fields_ok, source_ids_valid,
quotes_match_chunks, answer_term_score, quote_term_score,
answer_quote_overlap, meaning_matches_quotes, low_relevance_rule_ok,
passed  # (low_relevance_rule_ok OR normal_answer_ok)
```
- `answer_term_score` / `quote_term_score`: доля ожидаемых терминов (из `expected_terms` control-set) в тексте.
- `answer_quote_overlap` (agent.py:448) — overlap слов ответа и склеенных цитат.
- `meaning_matches_quotes` — консенсусный (agent.py:449-454): цитаты валидные ПЛЮС (answer_term ≥0.25 ИЛИ quote_term ≥0.25 ИЛИ overlap ≥0.2).
- `low_relevance_rule_ok` — валидатор абстейна: сработал он корректно (context < threshold И ответ начинается с *«не знаю»* И needs_clarification=true И нет sources/quotes). Это **false_abstain check в обе стороны**.

### dpmn — метрики в README-таблице, без формального кода
`week-05/day-24/README.md`: только has_sources/has_quotes/confidence + число chars ответа. Проверка совпадения смысла — вручную глазами.

### Shirobokov — метрик нет вообще
Ручная проверка в чате.

---

## 6. «Гочи и уроки»: комментарий про «фильтрация → галлюцинации»

**В 4 репо дословной фразы «C фильтрацией ответы стали получаться более конкретными, но даже при фильтрации появляются странные галлюцинации» я не нашёл** — она, вероятно, из чата участников или другого репо. Но её смысл проявляется в 3-х наблюдаемых паттернах:

### Паттерн A — filter squeezes context too tight → LLM выдумывает связки
Когда мы после rerank оставляем 2-3 чанка вместо 5-8, у LLM недостаточно текста «вокруг». Она пытается склеить факты из соседних чанков (которые она уже НЕ видит) через собственное общее знание — и выдумывает. Симптом: цитата валидная, source валидный, но answer содержит утверждения, отсутствующие в цитате.
- **Как ловит kaa-it:** `answer_consistent_with_citations` — если <30% содержательных слов цитаты есть в ответе, это флаг.
- **Как ловит dgoryachkovskiy:** `answer_quote_overlap` + `meaning_matches_quotes`.

### Паттерн B — false abstain при агрессивной фильтрации
Если threshold задран, retrieval часто отсекает нормальные ответы. dpmn прямо признаёт это в `agents/jarvis.py:319-322`:
> *«confidence == "none" — не переопределяем ответ, оставляем rag_override = None. Код упадёт на основную LLM, у которой есть история диалога и память. Это чинит мета-вопросы («О чём говорили?») и позволяет отвечать на OOD-запросы из общего знания (Гагарин) без галлюцинаций RAG.»*

То есть **dpmn специально не форсит абстейн — при confidence=none даёт fallback на обычный LLM без RAG**, потому что чистый абстейн ломает UX и даёт false negative. Это ключевой инсайт: жёсткий абстейн `< threshold → "не знаю"` — плохо; лучше *«не знаю в базе, отвечаю из общих знаний»*.

### Паттерн C — LLM подгоняет ответ под цитаты
Когда мы жёстко требуем «каждый факт — цитатой», LLM начинает **фабриковать** цитаты, которых нет в чанках, чтобы удовлетворить формат. Решение — dgoryachkovskiy ALLOWED_QUOTES: LLM не «сочиняет» цитаты, а **выбирает из готового списка**, детерминированно нарезанного программой. Это резко снижает hallucinated-quote rate.

### Что явно ловят авторы репо
- **kaa-it** — retry-loop с фидбеком (до 3 попыток) когда substring не валидируется. Fallback на «Based on the retrieved context: {topChunk[:150]}» — признаёт, что честный ответ невозможен.
- **kaa-it** — специальная проверка *«inconsistency»* (ComparisonAgent.cs:49-56): если LLM говорит confidence=unknown, но RAG сказал что можно отвечать, — retry с *«The context IS sufficient. Do NOT output confidence='unknown'»*. Это защита от false_abstain со стороны LLM.
- **dgoryachkovskiy** — весь ALLOWED_QUOTES это осознанная защита от hallucinated quote.
- **dpmn** — сознательный отказ от форсированного абстейна для сохранения UX (см. паттерн B).

---

## 7. Промпты дословно

### kaa-it (PromptBuilder.cs:5-28) — самый информативный
```
You are a precise technical assistant for Rust design patterns documentation.
Your answers MUST be grounded exclusively in the provided context chunks.

STRICT RULES:
1. Answer ONLY using information from the provided context chunks.
2. If the context does not contain sufficient information, output JSON with confidence: "unknown".
3. Every factual claim MUST be backed by a citation from the context.
4. Citations must be EXACT substrings (30-200 chars) from the context — no paraphrasing.
5. NEVER fabricate sources, citations, or facts not present in the context.
6. Respond in the same language as the user's question.
7. Output RAW JSON only. Do NOT wrap in markdown code blocks (no ```json).
```

### dgoryachkovskiy (agent.py:352-359)
```
Ты строгий RAG-ассистент по проекту AstroTarot. Используй только SOURCES.
Верни только JSON без markdown. Обязательные поля: answer, sources, quotes, confidence, needs_clarification.
sources должны содержать source_id, source, section, chunk_id.
quotes должны содержать source_id, source, section, chunk_id, quote.
quote должен быть дословно скопирован из ALLOWED_QUOTES. Не сокращай и не переформатируй quote.
Дай 1-3 sources и 1-3 quotes. Каждая quote должна быть короткой: 40-240 символов.
Если контекст не отвечает на вопрос, answer должен начинаться с 'не знаю' и needs_clarification=true.
```

### Shirobokov (LlmAgent.kt:598-612)
```
ИНСТРУКЦИИ ПО RAG:
- Тебе предоставлен "ДОПОЛНИТЕЛЬНЫЙ КОНТЕКСТ" из базы знаний в виде чанков (CHUNK).
- Ты ОБЯЗАН отвечать строго в следующем формате:

ОТВЕТ: (твой подробный ответ на основе контекста)
ИСТОЧНИКИ:
1. [Название файла] (ID: ..., Секция: ...)
ЦИТАТЫ:
- "(прямая цитата из текста чанка)"

- Если в контексте есть ответ, ты ОБЯЗАН использовать его.
- Если предоставленный контекст НЕ содержит ответа или его релевантность сомнительна,
  ты ОБЯЗАН ответить: "К сожалению, я не нашел информации по вашему вопросу в базе знаний.
  Пожалуйста, уточните запрос."
- НЕ выдумывай ответ от себя, если его нет в контексте.
```

---

## 8. Оценка нашего плана

| Пункт нашего плана | Вердикт | Комментарий |
|---|---|---|
| **Финальный JSON `{answer, sources:[{file, chunk_id, quote, supports}], confidence, abstained}`** | **надо изменить** | Все эталоны разделяют `sources` и `citations/quotes` (kaa-it, dgoryachkovskiy). У нас quote внутри source — это **dpmn-way (упрощение)**, годится для one-quote-per-source, но теряет гибкость (нельзя дать 2 цитаты из одного чанка). Рекомендую: раздельные массивы + `source_id` (S1..SN как у dg — намного проще парсинга, чем UUID). `supports` полезно, но никто не использует — можно ввести как оригинальную фичу. |
| **Substring в тексте чанка после нормализации whitespace** | **валидно, копирует эталоны** | Точно так делают kaa-it и dgoryachkovskiy. Плюс — обязательно `case-insensitive` (StringComparison.OrdinalIgnoreCase / .lower()). Плюс — ограничение длины 30-240 символов. |
| **Абстейн: max rerank score < 0.35** | **надо изменить (порог)** | Эталоны: kaa-it 0.45, dgoryachkovskiy 0.62, Shirobokov 0.3 (но там гибридный score). 0.35 — на пределе низковато для *чистого* cosine после rerank. **Рекомендую эмпирически откалибровать: прогнать 10 вопросов + 5 out-of-domain, замерить distribution max-score и выбрать порог между «фиолетом» и «зелёной зоной»**. Дефолт для старта: 0.5, потом крутить. |
| **Абстейн: «не знаю, уточни: <2 наводящих вопроса>»** | **эталоны делают иначе** | НИКТО не генерирует наводящие вопросы. Все — фиксированные шаблоны. **Наводящие вопросы через LLM — оригинальная фича, стоит попробовать**, но: (а) она дорогая (+1 LLM-вызов), (б) может галлюцинировать сама. Компромисс: **фиксированный шаблон с указанием *«уточните: файл / класс / функция / endpoint»*** (как dgoryachkovskiy) — детерминированный, дешёвый, но менее умный. Для видео эффектнее LLM-вариант. |
| **Grounded-judge: второй LLM-вызов `{grounded: 0..1, unsupported_claims:[]}`** | **оригинально, никто не делает** | Это **best-in-class anti-hallucination**, но: (а) +1 LLM-вызов дорого, (б) может ошибаться сам. **Рекомендую: делать только для confidence!=high** (низкая/средняя уверенность = дополнительная проверка). Формат `{grounded: float, unsupported_claims: [str], reason: str}` — хорош. Модель: тот же DeepSeek с `temperature=0`. |
| **Метрики: has_sources, has_quotes, valid_quotes, grounded, abstain_rate, false_abstain + R@3/MRR** | **валидно, лучше эталонов** | Мы держим и retrieval-метрики (R@3/MRR из дня 23) и answer-метрики. Единственное дополнение — стоит взять у dgoryachkovskiy `answer_quote_overlap` и `meaning_matches_quotes` (композит) — они дают более честный сигнал качества, чем чистый has_quotes. |
| **Режимы `off / strict / strict+verify / strict+abstain / full-anti`** | **валидно** | Хорошая абляция для видео. Kaa-it делает только `CitationEnforced` vs день-23-modes, dgoryachkovskiy — только один режим. Наша сетка богаче — можно показать вклад каждой фичи отдельно. |

---

## 9. Что НЕ брать из эталонов

1. **Не брать текстовый формат Shirobokov** — trust-the-LLM не даёт валидируемых метрик, видео будет слабое.
2. **Не брать pre-verify LLM в dpmn стиле** — он гарантирует лишний LLM-вызов на КАЖДЫЙ вопрос до генерации. Дороже нашего post-hoc grounded-judge, который срабатывает не всегда. Плюс наш post-hoc даёт `unsupported_claims`, чего pre-verify дать не может.
3. **Не брать fallback на «top-chunk quote» kaa-it** — это маскирует падение системы. Если LLM 3 раза подряд не смог — надо честно абстейниться, а не подсовывать пользователю top-chunk.
4. **Не брать pure text-name source_id в UUID-стиле как у kaa-it** — сложнее парсить и матчить. **Брать dgoryachkovskiy-way `S1..SN`** + отдельное поле `chunk_id`.

---

## 10. Что ОБЯЗАТЕЛЬНО взять

1. **ALLOWED_QUOTES-режим от dgoryachkovskiy** (agent.py:311-321, 356-357) — самая сильная защита от hallucinated-цитаты. Программа сама детерминированно нарезает лучший фрагмент из каждого чанка, LLM выбирает из списка. У нас это ляжет в новую функцию `buildAllowedQuotes(chunks, query) -> List<Quote>` и в промпт добавится блок `ALLOWED_QUOTES:` JSON-массивом.
2. **kaa-it inline `[CITATION:N]` маркеры в answer** — даёт грануляр-attribution (какой факт → какая цитата), делает видео эффектнее. Валидируется regex-ом `\[CITATION:(\d+)\]` (validator.cs:32).
3. **kaa-it retry-loop с системным фидбеком в промпт** (compare.cs:39-89) — самокоррекция дешевле grounded-judge и уменьшает количество итоговых fallback'ов.
4. **kaa-it двойная проверка consistency** (compare.cs:49-56): если LLM говорит unknown при достаточном контексте — retry с *«The context IS sufficient. Do NOT output confidence='unknown'»*. Защита от LLM-side false abstain.
5. **dpmn confidence=none-fallback на обычный LLM без RAG** (jarvis.py:319-322) — сознательный отказ от жёсткого абстейна для UX. **У нас это = флаг `--abstain-mode=strict|soft`, где soft = «не знаю в базе, но вот из общего знания: ...»**.
6. **dgoryachkovskiy source_id `S1..SN`** — удобнее парсить, чем UUID.
7. **dgoryachkovskiy composite metric `meaning_matches_quotes`** (agent.py:449-454) — честнее чем чистый has_quotes.

---

## 11. Финальная рекомендация архитектуры

```
1. Retrieval (день 23 pipeline: rerank+rewrite)
   ↓
2. ThresholdGate:
   if max_final_score < ABSTAIN_THRESHOLD:
     → сразу вернуть unknown-response (без вызова LLM)
   ↓
3. buildAllowedQuotes(chunks, query) — детерминированные best-quote фрагменты
   ↓
4. Формируем промпт с CHUNKS + ALLOWED_QUOTES + JSON-schema
   ↓
5. LLM call (DeepSeek, temperature=0) → JSON
   ↓
6. CitationParser.extractJson (kaa-it стиль: markdown → braces → raw)
   ↓
7. CitationValidator:
   - substring для каждой quote (normalize whitespace + case-insensitive)
   - inline [CITATION:N] маркеры в answer
   - source_id ∈ {S1..SN}
   - длина цитаты 40-240 chars
   ↓ (если invalid и attempt < 3)
8. Retry с фидбеком в промпте
   ↓ (если всё ок)
9. GroundedJudge (опционально, только если confidence != "high"):
   второй LLM-вызов {grounded: 0..1, unsupported_claims: []}
   ↓
10. Метрики: has_sources, has_quotes, valid_quotes,
    quote_overlap, meaning_matches, grounded_score,
    abstain_rate, false_abstain, R@3, MRR
```

**Порог абстейна: старт 0.5, откалибровать вручную после первого прогона.**

---

## Ссылки-якори на строки эталонов

- kaa-it PROMPT_RAG_CITATIONS.md — вся архитектура: https://github.com/kaa-it/Ollama/blob/day24/DocIndexer/PROMPT_RAG_CITATIONS.md
- kaa-it CitationValidator.cs — substring + inline markers: https://github.com/kaa-it/Ollama/blob/day24/DocIndexer/CitationValidator.cs
- kaa-it CitationAnswerParser.cs — extract JSON стратегия: https://github.com/kaa-it/Ollama/blob/day24/DocIndexer/CitationAnswerParser.cs
- kaa-it ComparisonAgent.cs — retry-loop, inconsistency check: https://github.com/kaa-it/Ollama/blob/day24/DocIndexer/ComparisonAgent.cs
- kaa-it UnknownThresholdHandler.cs — pre-LLM threshold gate: https://github.com/kaa-it/Ollama/blob/day24/DocIndexer/UnknownThresholdHandler.cs
- kaa-it EvaluationEngine.cs — метрики + SQLite persistence: https://github.com/kaa-it/Ollama/blob/day24/DocIndexer/EvaluationEngine.cs
- kaa-it test-questions.json — control set с expected_sources + key_concepts: https://github.com/kaa-it/Ollama/blob/day24/DocIndexer/test-questions.json
- Shirobokov RagService.kt — hybrid retrieval + abstain-в-контексте: https://github.com/ShirobokovNE/ai-challenge/blob/day24/src/main/kotlin/ru/myproject/aichat/indexing/RagService.kt
- Shirobokov LlmAgent.kt строки 598-612 — RAG-инструкция промпта: https://github.com/ShirobokovNE/ai-challenge/blob/day24/src/main/kotlin/ru/myproject/aichat/LlmAgent.kt
- dpmn answer.py — RagAnswer dataclass + pre-verify: https://github.com/dpmn/ai-advent-challenge/blob/main/ragger/answer.py
- dpmn jarvis.py строки 277-333 — сознательный отказ от абстейна: https://github.com/dpmn/ai-advent-challenge/blob/main/agents/jarvis.py
- dpmn week-05/day-24/README.md — таблица метрик 10/10: https://github.com/dpmn/ai-advent-challenge/blob/main/week-05/day-24/README.md
- dgoryachkovskiy day24_rag_citations_agent.py — ALLOWED_QUOTES-подход: https://github.com/dgoryachkovskiy/AiTgsterBot/blob/codex/day_24_rag_citations_antihallucination/day24_rag_citations_agent.py
- dgoryachkovskiy DAY24 report — детальный вывод validation для каждого q: https://github.com/dgoryachkovskiy/AiTgsterBot/blob/codex/day_24_rag_citations_antihallucination/DAY24_CITATIONS_ANTIHALLUCINATION_REPORT.md
