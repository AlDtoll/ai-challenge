# Day 25 — Мини-чат с RAG + task memory. Исследование 4 эталонов

Дата: 2026-07-05. Задача дня 25: RAG-чат с историей + «памятью задачи» (goal / constraints / clarifications / terms / open_questions), обязательные источники в каждом ответе, проверка на 2 длинных сценариях 10-15 сообщений.

Эталоны, реально изученные:
1. **kaa-it/Ollama@day25** — C# (.NET), а не Kotlin (был неверный якорь в запросе). Компактный, продуманный.
2. **ShirobokovNE/ai-challenge@day25** — Kotlin, OkHttp+Gson+SQLite, трехслойная память STM/WM/LTM + task_state.
3. **dgoryachkovskiy/AiTgsterBot@codex/day_25_rag_chat_task_memory** — Python. Самый близкий к нашему плану, включая ALLOWED_QUOTES + task_state_update в JSON ответа.
4. **dpmn/ai-advent-challenge:week-05/day-25** — Python + Flask web-UI. Отдельный дешёвый LLM-экстрактор task_state, detect_topic_change.

Локальные копии для быстрого перечитывания: `/tmp/day25refs/{kaa,shiro,dgor,dpmn}/`.

---

## 1. Формат task_state

### kaa-it (`DocIndexer/TaskState.cs`, 9 строк)
```csharp
public record TaskState {
    public string? Goal { get; set; }
    public List<string> Constraints { get; set; } = [];
    public Dictionary<string, string> Terms { get; set; } = [];
    public string? ActiveTopic { get; set; }
    public ConfidenceLevel ConfidenceInGoal { get; set; } = Unknown;   // Unknown/Low/Medium/High
    public DateTime LastUpdated { get; set; } = DateTime.UtcNow;
}
```
Плоская, минимальная. НЕТ `clarifications` и `open_questions`. Ключевое отличие — `ConfidenceInGoal` как маркер «пора уточнять цель у пользователя».

### ShirobokovNE (`LlmAgent.kt` L107)
```kotlin
data class TaskState(
    val stage: TaskStage,                          // INITIAL/PLANNING/EXECUTION/VALIDATION/DONE
    @SerializedName("current_step") val currentStep: String,
    @SerializedName("expected_action") val expectedAction: String,
    @SerializedName("sub_tasks") val subTasks: List<SubTask> = emptyList(),
    val clarifications: List<String> = emptyList(),
    val constraints: List<String> = emptyList(),
    val goal: String? = null
)
```
Плоская, но с state-machine (stage + explicit transition rules в `TaskStage.canTransitionTo`, L81-90). У них task_state — часть общей «трехуровневой памяти» (STM=history, WM=map<key,value>, LTM=map<key,value>, TaskState — отдельная сущность). Наследие дня 13 (state machine) переиспользовано.

### dgoryachkovskiy (`day25_rag_memory_chat.py` L155)
```python
def default_task_state(goal: str = "") -> dict[str, Any]:
    return {
        "goal": goal.strip(),
        "clarifications": [],
        "constraints": [
            "отвечать только на основании найденных RAG-источников",
            "в каждом обычном ответе выводить источники и цитаты",
        ],
        "fixed_terms": {},                # dict[str, str]
        "open_questions": [],
        "updated_at": now_iso(),
    }
```
Плоская. Точное совпадение с нашим планом (goal / constraints / clarifications / terms / open_questions). Отличие: `constraints` инициализируется двумя жёстко зашитыми правилами про «отвечать только по источникам» — это гарантирует, что даже пустая сессия проносит через диалог «политику дня 24».

### dpmn (`agents/jarvis_memory.py` L12)
```python
TASK_STATE_KEYS = {
    "goal": "цель диалога",
    "constraints": "ограничения",
    "terms": "уточнённые термины",
    "last_focus": "последняя тема",
    "progress": "что сделано / что осталось",
}
```
Плоская. Уникальные поля: `last_focus` (что было темой последнего оборота) и `progress` (протокол шагов, обрезается до последних 3 предложений — `_trim_progress`, L97).

### Как модель обновляет state — сравнение
| Эталон | Кто обновляет | Когда |
|---|---|---|
| kaa-it | **Отдельный LLM-вызов** (`TaskMemoryService.UpdateStateAsync`, `TaskMemoryService.cs` L43), pipe-delimited вывод, парсится regex-ом | ПОСЛЕ основного ответа, каждый ход |
| ShirobokovNE | **Отдельный LLM-вызов** (`updateMemories`, `LlmAgent.kt` L384), большой JSON `{intent, wm, ltm, profile, task, new_invariants}` | ПОСЛЕ основного ответа, каждый ход |
| dgoryachkovskiy | **В ОДНОМ ответе** — модель возвращает `task_state_update: {goal, clarifications_add, constraints_add, fixed_terms_update, open_questions_add, open_questions_resolved}` (`day25_rag_memory_chat.py` L503-510), мерджится дет.-но в `update_task_state` (L574) | Единый LLM-вызов ответа + update |
| dpmn | **Отдельный LLM-вызов** дешёвой моделью `Qwen3-30B-A3B` (`extract_and_update`, `jarvis_memory.py` L108), температура 0.1, max_tokens=512 | ПОСЛЕ основного ответа, каждый ход, `detect_topic_change` до этого может пропустить update |

### Вывод для нашего плана
Наш план «один LLM-вызов = ответ + citations + task_state_updates» **совпадает с dgoryachkovskiy** и это, IMO, самый эффективный вариант для нашего стека (DeepSeek не бесплатный, экономим на втором раунд-трипе). Другие три делают **два вызова**, что дороже, но проще отладить и надёжнее (провал экстрактора не портит ответ).

**Мержёвая семантика dgoryachkovskiy** (важно скопировать):
- `clarifications_add` / `constraints_add` — только добавление (через `unique_append` с case-insensitive dedup, L206), капается лимитом (40 clarifications, 20 open_questions).
- `open_questions_resolved` — список строк, удаляемых из open_questions (case-insensitive matching). Есть и разрешение вопросов, не только добавление.
- `fixed_terms_update` — dict merge, капается 80 ключами.
- `goal` — перезаписывается только если непустой; иначе сохраняется прежний.

Плюс детерминированные добавки помимо LLM:
- Regex-экстрактор `extract_terms` (L218) находит `/v1/*` endpoints, `*Client|*Service|*Repository|*ViewModel`, `*.kt`/`*.md` в вопросе и автоматически кладёт их в `fixed_terms` — модель не может «забыть».
- Если в вопросе есть «только по источникам» — добавляется `constraint: пользователь требует отвечать только с источниками`.
- Если в вопросе есть «запомни»/«цель»/«ограничение» — вопрос сохраняется в `clarifications`.

**Рекомендую для нас:** `{goal, constraints[], clarifications[], terms{}, open_questions[]}` (наш план) + детерминированный regex-экстрактор поверх LLM-обновлений. Плюс `updated_at` для метрик роста.

---

## 2. История диалога

| Эталон | Хранение | В промпт |
|---|---|---|
| kaa-it | Полный `List<ChatMessage>` в `ChatSession` (`ChatSession.cs`), не персистится между запусками | Последние 6 сообщений (`GetHistoryContext(maxMessages=6)`), формат `User: … / Assistant: …`, обрезая текущий вопрос (L36-37) |
| ShirobokovNE | SQLite таблица `messages`, полная история загружается на старт (`loadHistory`, `HistoryManager.kt` L235) | `messages.takeLast(maxRecentMessages=10)` (`LlmAgent.kt` L673-674). Никакой суммаризации. Просто скользящее окно. |
| dgoryachkovskiy | JSON-файл `sessions/{id}/messages.json` (весь диалог) + `turns.jsonl` (полный RAG-трейс на ход) | Последние `--recent-messages=6` реплик, каждая `compact`-нута до 700 символов (`recent_messages_for_prompt`, L476) |
| dpmn | БД через `HistoryManager` (агент `jarvis_session.py`); полная история persist | Полная история в промпт + `compression` при переполнении контекста (модуль `jarvis_compression.py`, из day 9) |

**Ни один не суммирует историю через LLM** для этой задачи — все используют скользящее окно 6-10 реплик + компактизация текста. Это принципиально: суммаризация «съедает» точные факты, важные для сцены «модель должна помнить что уже спрашивали».

**Наш план:** окно 6 последних реплик + `compact` (обрезка до 700 символов на реплику) — идентично dgoryachkovskiy. Валидно, ничего менять не надо.

---

## 3. Интеграция RAG

**Все 4 эталона делают retrieve при КАЖДОМ вопросе** (не через tool-call). Никто не пытается «умный роутинг retrieve vs no-retrieve».

Различия — как обогащают запрос историей/state:

### kaa-it — не обогащает
Retrieve по сырому вопросу (`_rag.ExecuteAsync(userMessage, ...)`). Task state вставляется в system prompt отдельно (`BuildContextPrompt`, `TaskMemoryService.cs` L128). История — отдельный блок в system.

### ShirobokovNE — обогащает
`refineQuery(prompt)` (day 22, HyDE-подобная переформулировка) → `findRelevantChunks(refinedQuery)`. Task state и WM/LTM вставляются как отдельные system-message'ы (`LlmAgent.kt` L657-671).

### dgoryachkovskiy — самое интересное
`build_retrieval_query` (L253) собирает **композитный retrieval-запрос** из:
- Вопроса
- `task_state.goal`
- `domain_query_hints` (жёстко закодированные ключевые слова по темам — auth/navigation/tarot/backend)
- Последних 6 clarifications
- Последних 6 constraints
- fixed_terms
- Последних 4 user-реплик

Всё склеивается и `compact`-ится до 1800 символов. Это **прямо решает** «модель на 10-м обороте не помнит цель» — цель ФИЗИЧЕСКИ включена в retrieval-query.

Плюс есть `pinned_source_chunks` (L313) — если вопрос содержит триггерное слово (например «signup», «TarotDao»), принудительно достаются чанки из конкретных файлов. Это анти-fallback: даже если embedding-поиск промахнулся, важные файлы всегда в контексте.

Плюс есть `task_state_chunk` (L400) — если вопрос про сам task_state («какая цель?», «какие ограничения?»), сам task_state конвертируется в псевдо-чанк и передаётся в контекст с source_id=`day25_task_state`. То есть task_state работает и как контекст для ответа, и как источник, на который модель может ссылаться.

### dpmn — обычный retrieve + hybrid/threshold, но с pre-verification
Pre-verification фильтрует out-of-domain вопросы: если релевантность низкая, RAG не срабатывает, отвечает main-LLM без источников. Это то, что мы делаем в day24 через soft-abstain.

### Вывод для нас
**НАШ ПЛАН НЕДОРАБОТАН по retrieval-query.** Мы наследуем rewrite день 23, но не обогащаем запрос task_state.goal. На 10-15 репликах retrieval будет плыть — модель может сохранить goal в state, но embedding-поиск в этот момент искать не про goal, а про последнюю реплику пользователя.

**Рекомендация: добавить `build_retrieval_query`** в стиле dgoryachkovskiy: rewrite(вопрос) + goal + top-5 fixed_terms + top-4 recent user-msgs. Это дешевле и надёжнее, чем tool-call.

---

## 4. Обязательные источники

### kaa-it — retry-loop + strict validator
1. Модель возвращает JSON `{answer, confidence, sources[], citations[], clarification_request}` (`PromptBuilder.cs` L18-28).
2. `CitationValidator.Validate` (`CitationValidator.cs` L5):
   - если confidence != Unknown, но sources.Count == 0 → error
   - если confidence != Unknown, но citations.Count == 0 → error
   - каждая citation.quote — проверяется как substring контента чанка (`ValidateQuoteExists`, `CitationAnswerParser.cs` L134, whitespace-normalization)
   - `[CITATION:N]` inline-markers обязательны, если confidence != Unknown
   - длина цитаты 30-200 chars (warnings)
3. При провале — **до 3 retry** с фидбеком «Previous response had validation errors: X» (`ChatService.cs` L94).
4. Если все 3 retry провалились — **fallback**: детерминированно берётся top-chunk, извлекается safe-quote (`CitationAnswerParser.cs` L147), собирается ответ типа «Based on the retrieved context [CITATION:0]: <quote>» (`ChatService.cs` L244).

### ShirobokovNE — soft-инструкция, без валидатора
Prompt L626-640 просто требует формат «ОТВЕТ / ИСТОЧНИКИ / ЦИТАТЫ» текстом. Никакой JSON-валидации нет. Это слабее.

### dgoryachkovskiy — идентично нашему day24 + JSON + ensure_citations
1. Строится `allowed_quotes` = точные квоты из 3 top-чанков + task_state-цитаты, если task_state-вопрос (`chat_allowed_quotes`, L428).
2. Модель должна вернуть JSON с `sources[]` и `quotes[]`, где `quote` — дословно из ALLOWED_QUOTES.
3. `validate_chat_answer` (L617) проверяет: sources непусты, поля source/section/chunk_id заполнены, source_id входит в chunks-by-id, quotes непусты, каждая quote matches chunk.
4. **`ensure_citations` (L685) — детерминированно** дозаполняет пустые sources/quotes из top-3 чанков (без retry, просто программно). Это гарантирует 100% source-rate.
5. Есть отдельное правило `low_relevance_blocked`: при плохой релевантности разрешается ответить «не знаю…» с needs_clarification=true и пустыми sources/quotes (soft-abstain как у нас).

### dpmn — доверяет ragger
Основной ответ идёт напрямую из `ragger.answer.generate_answer()`, который уже возвращает форматированный текст с cite-block'ами (source, chunk_id, quote). Отдельного валидатора нет.

### Вывод для нас
Наш подход — ALLOWED_QUOTES + JSON + retry — **эталон**, ровно то, что делает kaa-it и dgoryachkovskiy. **Продолжаем ALLOWED_QUOTES**, ничего не выбрасываем. Но добавляем **`ensure_citations`-fallback** в стиле dgoryachkovskiy: если модель прислала пустые sources/quotes — детерминированно достраиваем из top-3 чанков. Это дешевле retry и гарантирует «в каждом ответе есть источники».

---

## 5. Персистентность

| Эталон | Что и куда |
|---|---|
| kaa-it | Ничего не персистит между запусками. Session = in-memory, `SessionId = Guid[..12]` (`ChatSession.cs` L8). |
| ShirobokovNE | **SQLite** (`db/chat.db`), таблицы: `profiles`, `messages`, `working_memory`, `long_term_memory`, `task_states`, `invariants`. Полностью transactional, ALTER TABLE-миграции при старте (`HistoryManager.kt` L48-90). |
| dgoryachkovskiy | Три отдельных файла на сессию: `sessions/{session_id}/messages.json`, `task_state.json`, `turns.jsonl` (append-only trace). |
| dpmn | БД через агентский `HistoryManager` (наследие day 8/13/23). |

**Наш план: один JSON-файл `~/.ai-challenge/day25_session_<id>.json`** содержащий history + state + meta.

Сравнение с эталонами:
- Против ShirobokovNE (SQLite) — оверкилл для нашего сценария (домашняя игра, 2 сценария), можно позже мигрировать.
- Против dgoryachkovskiy (3 файла) — я бы **разделил на 2 файла**: `messages.jsonl` (append-only, история) + `state.json` (перезаписывается, task_state + meta). Плюсы: history append безопасен (crash не портит), state отдельно можно легко просмотреть/диффнуть.

**Рекомендация: 2 файла** `messages.jsonl` + `state.json` вместо одного. Причины:
1. append-only jsonl безопаснее одиночного файла.
2. state.json глазами читать проще (при `/state` команде тупо cat).
3. Совместимо с CLAUDE Md-правилом «не хранить в одном файле разнородные вещи».

---

## 6. 2 сценария

### kaa-it (`test-chat-scenarios.json`)
JSON-файл в корне, 2 сценария по 15 вопросов, оба про Rust design patterns:
- «Rust Design Patterns Deep Dive» (Builder → RAII → Strategy → Newtype → Visitor → summary)
- «Rust Anti-patterns Analysis» (clone→borrow-checker, deny(warnings), Drop, unwrap, shadowing → summary)

Каждый сценарий имеет `initialGoal` — если задан, task_state.Goal предустанавливается перед прогоном (`ChatScenarioTest.cs` L46-50). Прогонщик `RunScenariosAsync` вызывает `_taskMemory.Reset()` между сценариями (L37) — чистая изоляция.

Метрики после сценария (`ChatScenarioTest.cs` L108-125):
- `goalPreserved` = `Goal != null && ConfidenceInGoal != Unknown`
- `sourcesAlwaysShown` = во всех non-unknown ответах count > 0
- `citationsAlwaysShown` = аналогично
- `unknownCount`
- `avgLength`

### ShirobokovNE
Сценариев в файлах не нашёл — README показывает интерактивные команды. Возможно проверяли руками. Слабо, я бы не копировал.

### dgoryachkovskiy (`day25_rag_memory_chat.py` L875)
2 сценария в коде (`scenario_definitions()`), по 10 вопросов каждый (не 15). Темы — по проекту AstroTarot: «auth_backend_review» и «navigation_tarot_review». Каждый начинается с реплики-«запомни цель», потом 8 конкретных вопросов, финал — «сделай сводку с учётом ограничений».

Отчёт (см. `DAY25_RAG_MEMORY_CHAT_REPORT.md`) — **все 20/20 turns passed**, sources+quotes во всех ответах, все quotes matches chunk. `context_relevance` в диапазоне 0.68-1.00 — ни разу не свалился в low_relevance_blocked.

Прогонщик — subcommand `demo` (L1138), пишет `store_dir/last_demo.json` + markdown-отчёт.

### dpmn
Мануальный тест-скрипт `test_chat.py` не смотрел (файл был указан в README, я не вытянул), но README-план имеет 13 шагов, включая «сменить тему → goal обновился», «out-of-domain → нет ложных источников».

### Вывод для нас
- **Формат сценариев: JSON в `resources/scenarios/*.json`** с полями `name`, `initialGoal`, `messages[]`. Это самое чистое (kaa-it, dgoryachkovskiy).
- **Метрики goal_retained**: у kaa-it это тупо «Goal!=null && Confidence!=Unknown». Я бы усложнил: **LLM-judge** (мы уже такое делали в day 24 grounded-judge) с вопросом «в этом финальном ответе всё ещё чувствуется исходный goal Х?». Это ловит случай «goal формально хранится, но модель на 15-м вопросе отвечает мимо».
- **2 сценария по 12-15 вопросов** — согласовано с задачей. Наш план валиден.

---

## 7. UX / CLI

### kaa-it (`ChatService.cs` L125)
```
Commands: /exit /reset /state /goal /help
```
Каждый ответ выводит:
- Ответ
- `--- Sources ---` (chunk_id, source, section, score)
- `--- Citations ---` (source_index, quote-preview 100 chars)
- Если goal обновился vs предыдущего — `[Task Goal updated: X]` (L226-233)

Отдельного «показывать task_state после каждой реплики» нет — вызывается `/state` вручную.

### ShirobokovNE
Есть `stats`, `memory`, task-state — вручную по командам. Также `rag on/off/status/config`.

### dgoryachkovskiy
Команды: `new`, `ask`, `chat`, `show`, `reset`, `demo`, `verify`. `chat` — REPL с `/exit`, `/quit`. `print_turn` (L850) **после каждой реплики выводит**:
- session_id, turn_id, context_relevance, tokens
- validation flags (has_sources, has_quotes, quotes_match_chunks, passed)
- Ответ
- Источники (source | section | chunk_id)
- Цитаты (source | section | chunk_id: quote)
- **Task State полностью** (goal, constraints[-6:], clarifications[-6:], fixed_terms[-10:], open_questions[-6:])

### dpmn — web UI (`/api/sessions`), нет CLI как такового.

### Вывод для нас
**Наш план — «REPL показывает task_state после каждой реплики»** — согласуется с dgoryachkovskiy (на который мы больше всего похожи). ХОРОШО, но при 15 репликах и debug-выводе state лог станет очень шумным. **Рекомендация**: показывать только **diff task_state** (что добавилось за этот ход) + компактно top-3 sources. Полный state — по команде `/state`.

Тайминги: dgoryachkovskiy показывает `tokens=` (сколько DeepSeek потратил), но не latency. Наш план с p50/p95 latency — **сильнее эталонов**, стоит оставить.

---

## 8. Гочи и уроки

### Явно упомянуты эталонами:

**kaa-it — «модель может залипнуть в confidence=unknown»**: retry-loop с добавлением к user-prompt «[SYSTEM: The context IS sufficient. Do NOT output confidence='unknown'. Provide a concrete answer with citations.]» (`ChatService.cs` L80). Проблема **есть**, лечится retry.

**ShirobokovNE — «модель перепрыгивает stage TASK-lifecycle без approval пользователя»**: явное правило в промпте `approved: ПРИНЦИПИАЛЬНО ВАЖНО. Ставь true ТОЛЬКО если пользователь ЯВНО сказал "Да", "Поехали", "Одобряю"` (LlmAgent.kt L404). Это специфично для их stage-machine, у нас такого нет.

**dgoryachkovskiy — «task_state становится источником»**: elegant трюк — если вопрос про сам state (маркеры «какая цель», «ограничения», «зафикс», «уточнил», `is_task_state_question`, L382), state конвертируется в чанк с source_id=`day25_task_state`, и модель отвечает **с указанием этого источника**. Иначе модель на вопрос про свою же память говорила бы «в источниках нет». **Стоит скопировать.**

**dgoryachkovskiy — «модель забывает цель на длинном контексте»**: решено, но НЕ через раздутый прошлый-контекст, а через **retrieval-query, обогащённый goal+fixed_terms+recent** (`build_retrieval_query`, L253) — цель ретривится вместе с контентом. В **отчёте все 20/20 turns прошли**, goal сохранён во всех, sources во всех. То есть **проблема решаема при таком подходе**.

**dpmn — «goal пропадал при отвлекающих темах» + «progress перезаписывался вместо дополнения»**: явно в README «Что требует доработки» (L236-241). Первое лечится через `detect_topic_change` (`jarvis_memory.py` L47) — до `extract_and_update` вызывается лёгкая LLM «связан ли новый запрос с текущим goal? yes/no», при no — extract_and_update пропускается, goal сохраняется. Второе — программная защита «если новый progress короче старого на 30%+ — не перезаписываем» (L189).

**dpmn — «FAISS-ранжирование по русским запросам плохое»**: у нас Ollama nomic-embed-text — на русском примерно так же слабо. **Возможная гочу для нас — но не нова**, мы это уже видели на day 22.

### «task_state раздувается»?
Только dgoryachkovskiy об этом думает — `unique_append` капается лимитами (40 clarifications, 20 open_questions, 80 fixed_terms), `compact(text, 1800)` при формировании retrieval-query. Явных жалоб на раздутие нет. Наш план **должен добавить лимиты** — иначе за 15 реплик constraints/clarifications могут разрастись до сотен строк.

### «конфликт state ↔ RAG»?
Никто явно не упоминает. Все просто вставляют state и RAG-context как разные system-message'ы или разные секции.

---

## 9. Ответ на прямой вопрос: «модель теряет цель на 10-15 репликах — реально?»

**У эталонов НЕ теряет** — все 20/20 turns у dgoryachkovskiy прошли с sources и с сохранённым goal. Но у него есть **три конкретные механики против этого**:
1. Retrieval-query обогащается `task_state.goal` + fixed_terms → цель попадает в контекст через RAG-канал даже когда пользователь спрашивает про частность.
2. TaskState рендерится отдельным system-message'ем в промпте, компактно (`TASK_STATE:\n{json}`) и жёстко.
3. Финальный вопрос сценария — «сделай сводку с учётом зафиксированных ограничений» — модель проверяется на facts из state, и работает.

**У нас будет ок ТОЛЬКО ЕСЛИ:**
- Обогатим retrieval-query goal+terms (сейчас в плане нет).
- Task state вставим отдельным system-message, а не в user-prompt (как ShirobokovNE и dgoryachkovskiy).
- Будем валидировать «goal_retained» через LLM-judge, а не тупо `goal != null`.

**Иначе есть риск** — DeepSeek на 10+ реплике может дрейфовать (у нас окно 6, значит первые реплики со «Zапомни цель X» вываливаются из истории). Если goal лежит только в task_state и в промпте его нет — модель «формально» помнит, но может отвечать по последней теме без учёта goal. У dgoryachkovskiy этого не было именно потому что goal в 3 местах: state prompt, retrieval-query, allowed_quotes для task_state-вопросов.

---

## 10. Резюме по нашему плану — по пунктам

| # | Пункт нашего плана | Вердикт | Что делать |
|---|---|---|---|
| A | Ветка week5/day5, копия day4 | **валидно** | Продолжаем: rewrite + rerank + ALLOWED_QUOTES + soft-abstain — база у нас лучше kaa-it/Shirobokov. |
| B | task_state = {goal, constraints[], clarifications[], terms{}, open_questions[]} | **валидно**, точно совпадает с dgoryachkovskiy | Добавить: `updated_at`, лимиты (40/20/80/40), детерминированный regex-extractor для terms из вопроса. |
| C | Один LLM-вызов = ответ + citations + task_state_updates | **валидно, но рискованно** | Совпадает с dgoryachkovskiy (у него работает). Kaa-it и ShirobokovNE делают 2 вызова — надёжнее, но дороже. Мой совет: **делать в 1 вызов**, но добавить `ensure_citations`-fallback и regex-extractor терминов — чтобы даже если модель не прислала update, state обновляется программно. |
| D | Один JSON-файл `~/.ai-challenge/day25_session_<id>.json` | **надо изменить** | **2 файла**: `messages.jsonl` (append-only) + `state.json` (перезаписывается). Плюс `turns.jsonl` для RAG-трейса (как у dgoryachkovskiy) — очень полезно для отладки. |
| E | 2 сценария в `src/main/resources/scenarios/*.json`, replay-режим | **валидно** | Формат `{name, initialGoal, messages[]}` (kaa-it). Prime task_state.goal из initialGoal (`ChatScenarioTest.cs` L46). Reset между сценариями. |
| F | REPL показывает task_state после каждой реплики | **надо изменить** | Показывать **diff state** (что добавилось за ход) + топ-3 sources compact. Полный state — команда `/state`. |
| G | Метрики sources_ratio, citations_ratio, goal_retained (LLM-judge), task_state_growth, latency p50/p95 | **валидно + сильнее эталонов** | Все нужны. Дополнительно из эталонов: `quotes_match_chunks_ratio` (dgor), `unknown_ratio` / `abstain_ratio` (kaa/наш день24), `retrieval_query_had_goal_ratio` (наш новый). |

### Обязательные дополнения к нашему плану (без них риск потери цели реален):

**Add-1. Обогащённый retrieval-query.** Функция `buildRetrievalQuery(question, taskState, recentMessages)` — конкатенирует rewrite(question) + goal + top-5 fixed_terms + top-4 recent user-msgs, `compact` до 1800 chars. **Это критически важно**, без этого retrieve уплывёт от цели на 10-й реплике.

**Add-2. TaskState как источник.** Если вопрос содержит маркеры «какая цель», «ограничения», «что мы уже уточнили», «зафикс» — конвертируем текущий state в pseudo-chunk с source_id=`task_state:<sessionId>` и подмешиваем в контекст. Модель отвечает на «какая цель?» с указанным источником, а не «в источниках нет».

**Add-3. Ensure-citations fallback.** Если модель вернула пустые sources/quotes — детерминированно достраиваем из top-3 чанков (у нас уже есть `sliceCandidateQuotes` в day24). Гарантия 100% source-rate.

**Add-4. Детерминированный regex-extractor терминов.** В обработке каждого вопроса regex-ит имена классов Kotlin (`*ViewModel`, `*Service`), файлы `.kt`, endpoints — и кидает в `fixed_terms`. Это страховка от «модель забыла записать термин в state».

**Add-5. Лимиты роста state.** clarifications[-40:], open_questions[-20:], fixed_terms first 80 keys, constraints[-40:]. Иначе на 15 реплик state распухнет.

### Что НЕ брать у эталонов:
- **SQLite (ShirobokovNE)** — оверкилл, дискурс дня 25 не требует персистентности между запусками; JSON достаточно.
- **State machine INITIAL/PLANNING/EXECUTION (ShirobokovNE)** — это про делегирование задач, не про RAG-чат. Не наш кейс.
- **Отдельный LLM-вызов для update (kaa-it, ShirobokovNE, dpmn)** — дороже в 2x, для нас неоптимально. Одного вызова + regex-фолбэка достаточно.
- **`ActiveTopic` (kaa-it)** — избыточно, покрывается `last_focus`-ом или просто последним вопросом. Не тащим.
- **`stage` / `sub_tasks` / `expected_action` (ShirobokovNE)** — не для нашего сценария.
- **`progress` (dpmn)** — тоже избыточно, у нас про RAG, а не про исполнение workflow.

---

## Приложение — сырые артефакты промптов

### System prompt kaa-it (`PromptBuilder.cs` L5-28)
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

OUTPUT FORMAT — strict JSON with these exact keys:
{
  "answer": "<comprehensive answer with inline [CITATION:0], [CITATION:1] markers>",
  "confidence": "<high|medium|low|unknown>",
  "sources": [{ "index": 0, "source": "<file path>", "section": "<section name>", "chunk_id": "<uuid>", "score": 0.85 }],
  "citations": [{ "index": 0, "quote": "<exact text from chunk>", "source_index": 0 }],
  "clarification_request": "<if confidence=unknown, ask user to clarify>"
}
```

### System prompt dgoryachkovskiy (`day25_rag_memory_chat.py` L513-527)
```
Ты production-like RAG-ассистент по проекту AstroTarot.
Используй только SOURCES для фактов о коде и архитектуре.
Учитывай TASK_STATE и RECENT_MESSAGES, чтобы не терять цель диалога.
Верни только JSON без markdown. Обязательные поля: answer, sources, quotes, confidence,
needs_clarification, task_state_update.
sources должны содержать source_id, source, section, chunk_id.
quotes должны содержать source_id, source, section, chunk_id, quote.
quote должен быть дословно скопирован из ALLOWED_QUOTES. Не сокращай и не переформатируй quote.
Если SOURCES не отвечают на вопрос, answer начинается с 'не знаю', needs_clarification=true,
и попроси уточнить файл, класс, функцию или endpoint.
```

### User-prompt shape dgoryachkovskiy (L528-539)
```
TASK_STATE:
{json}

RECENT_MESSAGES:
{json}

QUESTION:
{question}

RETRIEVAL_QUERY:
{compact_retrieval_query}

REQUIRED_JSON_SHAPE:
{schema_hint}

ALLOWED_QUOTES:
{json}

SOURCES:
[S1] source=... section=... chunk_id=... relevance=... text: ...
---
[S2] ...
```

### JSON schema task_state_update (dgoryachkovskiy L503-510)
```json
{
  "goal": null,
  "clarifications_add": [],
  "constraints_add": [],
  "fixed_terms_update": {},
  "open_questions_add": [],
  "open_questions_resolved": []
}
```

### Промпт для обновления state — ShirobokovNE (`LlmAgent.kt` L388-434)
Отдельный LLM-вызов после ответа, JSON шейп:
```json
{
  "intent": "CHAT/TASK",
  "wm": {},
  "ltm": {},
  "profile": {},
  "task": null | {
    "stage": "INITIAL/PLANNING/EXECUTION/VALIDATION/DONE",
    "current_step": "...",
    "expected_action": "...",
    "approved": true/false,
    "clarifications": [...],
    "constraints": [...],
    "goal": "...",
    "sub_tasks": [...]
  },
  "new_invariants": []
}
```

### 2 сценария kaa-it (`test-chat-scenarios.json`)
- Deep Dive (15 msgs): Builder → Factory сравнение → RAII → RAII vs manual → Strategy без traits → замыкания-strategy perf → Newtype → use cases → когда избегать → Visitor → альтернативы → struct decomposition для borrowing → сводка всех паттернов.
- Anti-patterns (15 msgs): Clone-to-satisfy-borrow → почему anti → когда clone ок → deny(warnings) → alternatives → struct decomposition → когда overkill → Drop anti-patterns → как избежать → unwrap() → unwrap_or → shadowing in match → eager cloning perf → сводка anti-patterns.

### 2 сценария dgoryachkovskiy (L879-905)
- auth_backend_review (10 msgs): «запомни цель» → signup endpoint → resend verification → SignUpUseCase → refresh 401 → README endpoints → backend stack → зафиксируй term refreshSession → порядок signup→verify→auth → финальная сводка с учётом ограничений.
- navigation_tarot_review (10 msgs): «запомни цель» → Screen routes → BottomNavigationBar → TarotSpreadHistoryDto → DAO functions → AiService prompt → зафиксируй term tarot_spread_history → источники tarot flow → user flow → финальный checklist по цели.

Обратите внимание — оба сценария начинаются с «запомни цель: …» и заканчиваются «финальная сводка с учётом зафиксированных ограничений». Это простая, но действенная схема: **первая реплика фиксирует цель, последняя проверяет что она сохранена**.

---

## Файлы наших локальных копий (для дальнейших сессий)

- kaa-it C#: `/tmp/day25refs/kaa/` (12 файлов, 1.3k строк) — ключевые: `TaskState.cs`, `TaskMemoryService.cs`, `ChatService.cs`, `ChatScenarioTest.cs`, `test-chat-scenarios.json`, `PromptBuilder.cs`, `CitationValidator.cs`.
- Shirobokov Kotlin: `/tmp/day25refs/shiro/` (6 файлов, 2.9k строк) — `LlmAgent.kt` (LlmAgent, TaskState, updateMemories), `HistoryManager.kt` (SQLite), `RagService.kt`, `Main.kt` (CLI).
- dgoryachkovskiy Python: `/tmp/day25refs/dgor/` (2 файла кода + 1 отчёт 686k) — `day25_rag_memory_chat.py`, `DAY25_RAG_MEMORY_CHAT_REPORT.md`.
- dpmn Python: `/tmp/day25refs/dpmn/` (10 файлов) — `jarvis_memory.py`, `jarvis.py`, `README.md`, `app.py`, `ragger/*.py`.
