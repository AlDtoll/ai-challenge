# Agent Profiles — специализированные профили агентов в `.claude/agents/`

## What it improves

Один universal-агент — компромисс везде: слишком осторожен для баг-фикса, слишком вольный для review. Специализированные профили в `.claude/agents/*.md` дают каждому субагенту свой system prompt + tool subset + output-контракт. Bug-fix агент: читает только нужные файлы, пишет точечный патч, возвращает JSON с diff. Research агент: читает широко, пишет отчёт. Screenshot-baseline агент: запускает Paparazzi, сохраняет скриншоты. Из week8/day2: bug-fix профиль нашёл реальную prod-регрессию в `NotificationScheduler`.

## When to use

- Разные типы задач: баги, исследование, тестирование, документация — у каждого своя «роль»
- Параллельный batch: 15 issues — 5 идут в bug-fix агент, 5 в feature агент, 5 в refactoring агент
- Нужен строгий output-контракт: «bug-fix всегда возвращает JSON с diff», «research всегда возвращает Markdown-отчёт»
- Экономия токенов: специализированный агент видит только нужные tool'ы (не полный список)

**Когда НЕ надо:** одна-две задачи без повторяемости; задачи слишком разнородны чтобы типизировать; прототип — создание профилей требует времени на отточку.

## How to integrate

1. Создай папку `.claude/agents/` в корне проекта.
2. Для каждого профиля — файл `<role>.md` с секциями: Description, System Prompt, Allowed Tools, Output Contract.
3. Description — одна строка: что делает этот агент.
4. System Prompt — конкретная роль + запреты + стиль. Наследует правила из `CLAUDE.md` проекта.
5. Output Contract — строгий формат вывода (JSON-схема, Markdown-структура) — главный агент парсит.

## Working example (Kotlin)

```markdown
# bug-fix.md — Bug Fix Agent

## Description
Специализированный агент для точечного исправления одного бага без побочных изменений.

## System Prompt
Ты — Senior Kotlin/Android разработчик специализирующийся на исправлении багов.

**Твоя задача:** исправить ТОЛЬКО описанный баг, не трогая ничего рядом.
**Запрещено:** рефакторинг попутно, изменение тестов не связанных с багом, добавление фич.
**Обязательно:** читай CLAUDE.md проекта, соблюдай все правила (запрет !!, KDoc на русском).

При анализе:
1. Прочитай файл с багом целиком
2. Найди точную причину (не симптом)
3. Исправь минимально возможно
4. Проверь что тесты для этого модуля пройдут

## Allowed Tools
- Read (файлы проекта)
- Edit (только файл с багом)
- Bash: только `grep`, `find`, `cat` — никаких `gradle`, `npm build`

## Output Contract
Верни JSON строго в формате:
{"file": "path/to/File.kt", "lines_changed": 3, "diff_summary": "...", "root_cause": "...", "tests_to_run": ["TestClass#method"]}
```

```markdown
# research.md — Research Agent

## Description  
Агент для исследования кодовой базы и создания отчётов — не вносит изменений.

## System Prompt
Ты — технический аналитик. Твоя задача: исследовать и описать, НЕ изменять.
Read-only режим. Пиши отчёт по шаблону: Overview → Key Files → Dependencies → Findings → Recommendations.

## Allowed Tools
- Read (любые файлы)
- Bash: только `grep`, `find`, `wc`, `ls`, `git log`

## Output Contract
Markdown-отчёт с заголовками: ## Overview, ## Key Files, ## Findings, ## Recommendations
```

```kotlin
// Запуск из main-агента через Agent tool:
// Agent(
//     subagentType = "bug-fix",  // имя файла без .md
//     prompt = "Исправь баг: ${issue.description}\n\nФайл: ${issue.file}\nСтрока: ${issue.line}",
//     runInBackground = true
// )
// 
// Результат парсится из JSON-контракта → main-агент принимает решение о commit
```

## Metrics

- **Profile usage distribution** — % задач по каждому профилю; если bug-fix 80% → возможно research и refactoring недоиспользованы
- **Output contract compliance rate** — % субагентов вернувших ответ в ожидаемом формате; при < 90% — ужесточить Output Contract или добавить retry
- **Bug detection rate** — сколько реальных bagов нашёл bug-fix профиль на batch прогоне; из week8/day2: 1/3 задач = реальный prod-баг
- **Tool violation rate** — % попыток использовать запрещённый tool (Gradle, git commit); если > 0 → добавить явный запрет в System Prompt

## Source

- **AI Challenge:** week8/day2 — Profiles (агентные профили)
- **Артефакты:** `AlDtoll/zizz3:main/.claude/agents/bug-fix.md`, `research.md`, `screenshot-baseline.md`; `week8/day2/bugfix_test_notifications.md`; `week8/day2/research_test_active_sleep.md`
- **Связано:** [`claude_md_v2_rules.md`](claude_md_v2_rules.md) — правила проекта которые каждый профиль наследует; [`../agentic_loop/subagents_parallel.md`](../agentic_loop/subagents_parallel.md) — параллельный запуск нескольких профилей на batch задачах
