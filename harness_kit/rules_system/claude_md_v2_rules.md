# CLAUDE.md v2 — точечные правила с примерами для 100% compliance

## What it improves

`CLAUDE.md v1` — общие пожелания: «используй Kotlin best practices», «соблюдай архитектуру». LLM выполняет их частично и непоследовательно. `v2` — конкретные правила с явными примерами «плохо/хорошо»: `!!` запрещён (с контр-примером), `Pair<A,B>` в public API запрещён (с data class альтернативой), KDoc строго на русском. На кейсе Zizz3: 3 точечные правки дали 100% compliance (0 `!!`, 0 `Pair` в контрактах, полный русский KDoc), плюс агент пошёл дальше — создал `data class PendingAwakening` с 8 полями вместо минимального `WakeupInterval`.

## When to use

- Проект с устоявшимися конвенциями которые LLM нарушает (!! в Kotlin, var вместо val, неверный пакет)
- Team codestyle: правила как docs → LLM соблюдает автоматически без review замечаний
- Domain-specific ограничения: только `stringResource` для строк в Compose, только `StateFlow` не `LiveData`
- Несколько агентов работают над одним проектом — единый CLAUDE.md как source of truth

**Когда НЕ надо:** прототип или одноразовый скрипт — overhead на написание правил не окупится; проект без устоявшейся архитектуры — правила будут тормозить экспериментирование.

## How to integrate

1. Создай `.claude/CLAUDE.md` (или `CLAUDE.md` в корне проекта) — Claude Code читает автоматически.
2. Структура: секции «Стек», «Инварианты», «Запрещено», «Форматирование», «Примеры».
3. Для каждого запрета: явный пример ПЛОХО → ХОРОШО (это ключ к 100% compliance).
4. Добавь «Ключевые идиомы» — как писать domain-объекты, что использовать для строк, какой пакет для какого слоя.
5. Запусти сравнение: сгенерируй одну и ту же фичу без CLAUDE.md и с ним — сравни compliance-метрики.

## Working example (Kotlin)

```markdown
# CLAUDE.md — Правила для агентов в проекте Zizz3

## Стек
- Kotlin, Jetpack Compose, Material 3
- Минимальный Android SDK: 26
- Архитектура: Clean Architecture (domain / data / presentation)

## Инварианты (строго соблюдать)

### 1. Запрет на !! (null-force-unwrap)
**Запрещено:**
```kotlin
val result = repository.load()!!.items
```
**Правильно:**
```kotlin
val result = repository.load()?.items ?: emptyList()
// Или через requireNotNull с понятным сообщением:
val result = requireNotNull(repository.load()) { "Репозиторий вернул null при старте" }
```

### 2. Запрет Pair<A,B> в public API
**Запрещено:**
```kotlin
fun getInterval(): Pair<LocalTime, LocalTime>
```
**Правильно:**
```kotlin
data class SleepInterval(val start: LocalTime, val end: LocalTime)
fun getInterval(): SleepInterval
```

### 3. KDoc строго на русском
**Запрещено:**
```kotlin
/** Returns list of sleep records for given date */
```
**Правильно:**
```kotlin
/** Возвращает список записей сна за указанную дату */
```

## Строки в Compose
Только через `stringResource(R.string.*)` — никаких hardcoded строк в @Composable.

## Форматирование
- Отступы: 4 пробела (не табы)
- Максимальная длина строки: 120 символов
- Именование: camelCase для функций/переменных, PascalCase для классов/объектов
```

// Эффект v1 vs v2 (из week8/day1):
// v1: генерация WakeupInterval → data class с 2 полями (минимальный).
// v2: генерация WakeupInterval → data class PendingAwakening с 8 именованными полями + KDoc + require-инварианты.
// «Богаче domain» = агент не просто соблюдает правила, но экстраполирует намерение.
//
// Ключевой вывод из week8/day4 (Qwen vs Cursor):
// Qwen без правил — 2/5 (даже стек неверный: React Native вместо Compose).
// Qwen с правилами v2 — 3/5 (стек правильный, но UI и пакеты — нет).
// Cursor Sonnet — 4/5 (читает CLAUDE.md, понимает конвенции пакетов).
// → Правила помогают Qwen, но не догоняют Cursor. На лучшей модели эффект сильнее.
```

## Metrics

- **!! occurrence rate** — `grep -r "!!" src/ | wc -l` до и после v2; целевой: 0
- **Pair in public API** — `grep -r "Pair<" src/domain src/data | wc -l`; целевой: 0 в public interfaces
- **KDoc language compliance** — доля KDoc на русском языке (regex); после v2: 100%
- **Spontaneous domain richness** — субъективно: среднее число полей в генерируемых data class; v2 должен создавать более полные модели

## Source

- **AI Challenge:** week8/day1 — Rules (CLAUDE.md v1→v2)
- **Артефакты:** `AlDtoll/zizz3` ветки `advanced-day1-v1` (коммит `5a92646`) и `advanced-day1-v2` (коммит `4e7245e`); `docs/ai_challenge_advanced_day1_postmortem.md`
- **Связано:** [`agent_profiles.md`](agent_profiles.md) — специализированные профили которые наследуют правила CLAUDE.md; [`../agentic_loop/subagents_parallel.md`](../agentic_loop/subagents_parallel.md) — каждый субагент читает тот же CLAUDE.md как source of truth
