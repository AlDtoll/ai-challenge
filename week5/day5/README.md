# Day 25 (week5/day5) — Мини-чат с RAG + память задачи (task state)

Расширение стека дней 22–24 памятью задачи (`TaskState`), которая живёт **дольше одной реплики**
и удерживает цель диалога даже на 10-15 сообщениях. Наследует полностью:

- День 22 — базовый RAG (Ollama `nomic-embed-text` + DeepSeek).
- День 23 — rewrite + rerank + threshold («full» retrieve).
- День 24 — ALLOWED_QUOTES + structured JSON + retry-loop + soft-abstain.

## Что нового

**1. `TaskState` — 5 полей памяти задачи**
```
{
  "goal": "текущая цель диалога",
  "constraints": ["стек Kotlin+DeepSeek", "работает на Windows"],
  "clarifications": ["юзер знает про день 23", ...],
  "fixed_terms": {"ALLOWED_QUOTES": "нарезка чанков...", ...},
  "open_questions": [...]
}
```
LLM обновляет state ОДНИМ вызовом вместе с ответом (поле `task_state_updates`) — экономит
второй LLM-вызов и держит state консистентным с только что данным ответом.

**2. `build_retrieval_query` — обогащение retrieve контекстом сессии**
Retrieve делаем не по чистому вопросу, а по: `question + goal + fixed_terms + last 3 user messages`.
Без этого на 10-й реплике модель теряет цель — эмбеддинг чистого «а что дальше?» ничего
не находит.

**3. `task_state_chunk` — state как псевдо-источник**
Текущий state превращаем в «псевдо-чанк» и добавляем в список источников для retrieval.
Тогда на мета-вопросы «какая наша цель?» ассистент отвечает **из state**, а не гадает.

**4. `ensure_citations` — программный fallback**
Если retry-loop не смог получить валидные цитаты, программа сама подставляет топ-1 чанк
как источник. **100% sources_ratio** даже в худших случаях.

**5. Персистентные сессии на диске (2 файла)**
```
~/.ai-challenge/day25_sessions/<session_id>/
  messages.jsonl    # append-only, история сообщений
  state.json        # текущий task_state + метаданные
```

**6. Replay сценариев + метрики**
Флаг `--replay <name>` прогоняет заготовленный сценарий из 10-15 сообщений с финальным
тестом на удержание цели (LLM-судья: `same` vs `drifted`).

## Что нужно на машине

- **JDK 21+** (у меня `C:\Program Files\Android\Android Studio2\jbr`).
- **Ollama** на `localhost:11434`, модель `nomic-embed-text` (`ollama pull nomic-embed-text`).
- **`.env`** в корне репо с `DEEPSEEK_API_KEY=...`.

## Сборка и запуск

```powershell
chcp 65001
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio2\jbr"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

# 1. Построить индекс
.\gradlew.bat :week5:day5:run --console=plain -q --args="--build"

# 2. Новая сессия мини-чата
.\gradlew.bat :week5:day5:run --console=plain -q

# 3. Прогон сценария (для видео)
.\gradlew.bat :week5:day5:run --console=plain -q --args="--replay overview"
.\gradlew.bat :week5:day5:run --console=plain -q --args="--replay planning"

# 4. Список сессий на диске
.\gradlew.bat :week5:day5:run --console=plain -q --args="--list-sessions"

# 5. Продолжить существующую сессию
.\gradlew.bat :week5:day5:run --console=plain -q --args="--session <session_id>"
```

## В REPL мини-чата

Каждая реплика показывает:
- ответ ассистента с inline-маркерами `[CITATION:S1]`
- список источников `S1..SN` (включая `task_state.session` если state непустой)
- валидные цитаты (✓ = в ALLOWED_QUOTES + substring чанка)
- **STATE Δ** — что изменилось в task_state (diff, не полный state)
- счётчик LLM calls / latency / retries

Команды:
- `:state` — полный task_state
- `:history` — последние 10 сообщений
- `:new` — новая сессия
- `:session <id>` — переключиться на другую сессию

## Метрики после сценария

- **sources_ratio** — доля реплик с ≥1 источником (должно 100% благодаря ensure_citations)
- **citations_ratio** — доля реплик с ≥1 валидной цитатой
- **valid_quote_ratio** — цитат прошло substring-валидацию / всего
- **goal_retained** — LLM-судья: `same` или `drifted` относительно `initialGoal`
- **final_keywords_hit** — сколько ожидаемых терминов реально в финальной сводке
- **terms_accumulated / constraints_accumulated** — рост state
- **abstain_rate** — сколько раз soft-abstain
- **avg / p95 latency**, **total_llm_calls**

## Как устроено (кратко)

`Main.kt` содержит всё:

- `TaskState.apply(updates)` + `trim()` (лимиты роста: 30/40/20/80).
- `buildRetrievalQuery(q, session)` → композитный запрос для retrieve.
- `retrieveForTurn(q, session, idx)` → day23-full retrieve + task_state_chunk.
- `buildAllowedQuotes(hits)` → детерминированная нарезка чанков (40-240 символов).
- `chatTurn(userMsg, session, idx)` → один turn: retrieve → LLM с retry-loop →
  `ensureCitationsFallback` → merge task_state_updates → append to messages.jsonl + save state.json.
- `runReplay(name, idx)` → прогон сценария с финальным `checkGoalRetained`.

## Уроки

- **Один LLM-вызов на update+answer** — критично для скорости и консистентности. Отдельный
  вызов на update state = 2× цена и рассинхрон между «что модель только что сказала» и
  «что запомнилось».
- **build_retrieval_query — единственная защита от потери цели на длинных диалогах.**
  Без обогащения retrieve на 10-15 реплике модель уходит в тему последнего вопроса и
  забывает изначальный goal, потому что эмбеддинг короткой реплики теряет контекст.
- **task_state_chunk решает мета-вопросы.** Без него на «какая была наша цель?» модель отвечает
  из общих знаний / галлюцинирует. С ним — цитирует state.
- **ensure_citations выигрывает надёжность за счёт «читтинга»** — программно подставленный
  источник менее осмысленный, чем выбранный моделью, но 100% источников > 90% с редкими провалами.
- **Лимиты роста state** — обязательны. Без них на 30-й реплике list clarifications вырастает
  в 200 строк, retrieve становится шумным, а промпт LLM — переполненным.
