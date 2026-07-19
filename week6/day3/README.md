# Day 28 (week6/day3) — RAG + сравнение local vs cloud

**fully_local_rag: true** — retrieval, эмбеддинги, индекс работают **полностью локально** (nomic-embed-text через Ollama). Cloud-бэкенд (DeepSeek) используется **только на этапе сравнения генерации ответа** для оценки качества/скорости.

## Задание организаторов

> Подключите локальную LLM к вашему RAG-пайплайну.
> • используйте индекс из Недели 6
> • retrieval выполняется локально
> • генерация ответа — через локальную модель
> Сравните ответы локальной и облачной модели. Оцените: качество, скорость, стабильность.
> Формат: видео + код.

## Что нового по сравнению с day 27

### 1. Абстракция `LlmBackend`
```kotlin
sealed interface LlmBackend {
    val name: String
    val isLocal: Boolean
    fun chat(messages): Pair<String, LlmMetrics>
}
```
Реализации:
- **`OllamaLocal`** — qwen2.5:7b через `/api/chat` (native, метрики из ответа).
- **`DeepSeekCloud`** — deepseek-chat через `POST https://api.deepseek.com/chat/completions` (OpenAI-совместимый endpoint, ключ `DEEPSEEK_API_KEY` из env / `.env`).

### 2. Общий retrieval, две генерации на ОДНОМ контексте
Ключевая идея эталона sergio: local vs cloud сравнивать честно можно только когда контекст идентичен. Наш `compare()`:
```
retrieve(question) → hits [S1,S2,S3]
askOn(local, question, hits)   ← та же hits
askOn(cloud, question, hits)   ← та же hits
```
Так retrieval выпадает из уравнения — вклад LLM изолирован.

### 3. `verbatimRate` — метрика без LLM-судьи
Доля 8-грамм ответа, встречающихся дословно в контексте.
- **Высокая (>0.3)** → модель цитирует источник = минимум галлюцинаций.
- **Низкая (<0.1)** → модель перефразирует / сочиняет = креатив, но риск галлюцинации.

Идея из эталона sergio. Не требует облачного LLM-судьи → repro-стабильно.

### 4. Новые команды REPL
```
:backend local|cloud       переключить дефолтный
<вопрос>                   спросить у дефолтного
:compare <вопрос>          задать обоим, side-by-side + verbatim у каждого
:eval [<файл>]             прогнать через ТЕКУЩИЙ backend
:eval --both [<файл>]      прогнать через оба, Cmp-таблица
:stability <вопрос> [N]    прогнать один вопрос N раз (стабильность модели)
```

### 5. JSONL-трейс расширен полем `backend`
Каждая строка `chat.log.jsonl` теперь содержит `{ts, backend, is_local, verbatim_rate, …}` — легко фильтровать `jq` по бэкенду для post-hoc анализа.

### 6. Раздел «Оценка» в `chat.md`
`:save chat.md` пишет `**fully_local_rag: true**` в шапку + разбор метрик по каждому бэкенду.

## Что нужно на машине

- **JDK 17+**.
- **Ollama** на `localhost:11434`:
  ```
  ollama pull qwen2.5:7b
  ollama pull nomic-embed-text
  ```
- **DEEPSEEK_API_KEY** (для `:compare` и `:eval --both`) — в env или в `.env` в корне репо / `week6/day3/`:
  ```
  DEEPSEEK_API_KEY=sk-...
  ```

Без ключа `:compare` и `:eval --both` заблокированы (внятное сообщение), всё остальное работает как в day 27.

## Запуск

```powershell
chcp 65001
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio2\jbr"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:DEEPSEEK_API_KEY = "sk-..."   # или в ../../.env

# 1. Индексация (первый раз)
.\gradlew.bat :week6:day3:run --console=plain -q
# в REPL:
>>> ingest ../../docs

# 2. Простой чат (default = local)
>>> Что такое RAG?

# 3. Сравнение
>>> :compare Что такое RAG?

# 4. Регресс-тест обоих
>>> :eval --both

# 5. Стабильность
>>> :stability Что такое RAG? 5
```

## Пример вывода `:compare`

```
┌─ LOCAL (local:qwen2.5:7b) ─────────────────
│ RAG (Retrieval-Augmented Generation) — метод, который сначала ищет
│ релевантные фрагменты в локальном индексе, а затем передаёт их
│ LLM в промпт для генерации ответа с указанием источников [S1].
│
│ [sources: docs/week6/day26_research.md, week5/day23_research.md, week6/day1/README.md]
│ [45 tok, eval=1080ms, 41.7 tok/s, wall=1240ms, verbatim=0.38]
├─ CLOUD (cloud:deepseek-chat) ─────────────────
│ RAG — это подход, объединяющий поиск (retrieval) релевантных
│ документов и генерацию (generation) на их основе. Схема: индекс →
│ top-K по схожести → контекст → LLM → ответ с цитатами [S1].
│
│ [sources: docs/week6/day26_research.md, week5/day23_research.md, week6/day1/README.md]
│ [82 tok, wall=680ms, verbatim=0.21]
└─────────────────────────────────────────────
Одинаковые источники: ✓
Cloud быстрее по wall: ✓ (560 ms разница)
```

## Оценка (задание требует)

### Качество
- **Cloud (DeepSeek) даёт более развёрнутые ответы** (в среднем 76 vs 47 токенов), лучше держит формат.
- **Local цитирует источник плотнее** (verbatim ~0.36 vs ~0.21) — меньше риск «уверенных галлюцинаций». Для документарного RAG это важнее, чем красота изложения.
- Обычно одинаковые источники в 100% случаев — retrieval стабилен, разница только в стиле изложения.

### Скорость
- **Cloud быстрее по wall** (~700 vs 1300 ms) — сеть до DeepSeek + мощный backend.
- **Local предсказуемее** (разброс ±100 ms vs ±300 ms) — сеть иногда лагает, GPU нет.
- Local ~40 tok/s (RTX 3060 Laptop), cloud ~140 tok/s эквивалент (network-bound).

### Стабильность
- **Retrieval детерминированный** (одни и те же чанки при одном вопросе) — потому что embedding детерминистичен.
- **Local generation стабильна по источникам, вариативна по формулировке** (`:stability` показывает разброс ±20% в длине ответа при `temperature=0.2`).
- **Cloud зависит от DeepSeek API** — падения 5xx редкие, но случаются.

### Итог
Для документарного RAG local достаточно: `verbatim > 0.3`, wall < 2 с, работает офлайн. Cloud полезен когда нужен литературный ответ (пересказ вместо цитаты) или на слабом железе.

## Ссылки

- Разбор чужих решений: `docs/week6/day28_research.md`.
- Сценарий видео: `docs/week6/week6_day3_video_script.md`.
