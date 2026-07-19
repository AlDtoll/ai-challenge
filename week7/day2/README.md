# День 32 — Автоматизация ревью PR

Модуль `:week7:day2`. GitHub Action на `pull_request` запускает Kotlin-приложение, которое читает diff + список изменённых файлов, поднимает BM25-индекс над README/docs, ходит в DeepSeek и постит ревью-комментарий на PR.

## Из чего он состоит

1. **`.github/workflows/pr-review.yml`** — CI-триггер на `opened/synchronize/reopened` PR.
   - Через `gh pr diff` берёт полный diff, через `gh pr view` — список изменённых файлов.
   - Запускает `gradle :week7:day2:run` с этими файлами как аргументами.
   - `gh pr comment --body-file` постит вывод ассистента как отдельный комментарий на PR.
2. **`Bm25Index.kt`** — свой BM25 индекс над `README.md` + `docs/**/*.md` + верхнеуровневыми `CLAUDE.md/MEMORY.md`. Простая токенизация (lowercase → `[^\p{L}\p{Nd}]+` → фильтр коротких), k1=1.5, b=0.75. Работает offline, детерминированно.
3. **`Main.kt`** — оркестратор: читает diff, собирает RAG-запрос (список файлов + первые 2 KB diff), забирает top-K чанков, отдаёт DeepSeek с системным промптом «старший инженер».
4. **`DeepSeek.kt`** — тонкий HTTP-клиент, тот же что в day31.

## Почему BM25, а не эмбеддинги

В day31 использовал Ollama nomic-embed-text — но CI-раннер GitHub Actions Ollama не крутит. Пробовать поднимать её на каждом ране — минуты холодного старта. Использовать DeepSeek embeddings — дорого (много токенов на весь корпус) и медленно (сеть на каждый чанк).

BM25 всё это решает:
- offline, никаких дополнительных зависимостей;
- работает за миллисекунды на десятках чанков;
- детерминирован (в CI одинаковый вход → одинаковый выход, полезно для дебага).

Единственная жертва — семантика: BM25 не поймёт «переименовал `getUser` в `fetchUser`» как связь с документацией про `getUser`. Но для day31/day32-класса задач BM25 достаточно (мы ищем документацию по путям файлов и именам классов, а не парафразы).

## Как это работает шаг за шагом

1. Разработчик открывает PR в `main`.
2. Workflow срабатывает, чекаутит HEAD PR, ставит JDK 21 и Gradle.
3. `gh pr diff` → `pr.diff` (полный unified diff PR).
4. `gh pr view --json files` → `changed_files.txt` (по одному пути в строке).
5. `gradle :week7:day2:run --args="--diff pr.diff --changed changed_files.txt"`.
6. Внутри main.kt:
   - собирается `Bm25Index` над репом (~секунда на средних репах);
   - строится запрос из имён файлов + первых ~2 KB diff'а;
   - top-3 чанков → в промпт;
   - system=«старший инженер, три секции: Баги / Архитектура / Рекомендации»;
   - user=diff + RAG;
   - DeepSeek chat → ответ на stdout.
7. Workflow забирает stdout как `ai_review.md` и постит через `gh pr comment --body-file`.

## Настройка

Один секрет в GitHub Settings → Secrets:
```
DEEPSEEK_API_KEY = sk-…
```

Всё. `GITHUB_TOKEN` доступен автоматически.

## Локальный тест

Из корня репо:
```bash
git diff main..HEAD > pr.diff
git diff --name-only main..HEAD > changed_files.txt
DEEPSEEK_API_KEY=... ./gradlew :week7:day2:run \
  --args="--diff pr.diff --changed changed_files.txt"
```

## Флаги

- `--diff <path>` (обязательно) — файл с diff'ом.
- `--changed <path>` — файл со списком путей.
- Env: `DEEPSEEK_API_KEY`, `DEEPSEEK_MODEL` (default `deepseek-chat`), `TOP_K` (default 3), `MAX_DIFF_CHARS` (default 20000).

## Ограничения / что дальше

- Diff > 20 KB обрезается (защита от кв. tokens): для больших PR можно инкрементально по файлам.
- BM25 не понимает переименования — TODO подмешать имена символов из `git log --name-status` за N коммитов.
- Ревью — один общий комментарий, не построчный. Настоящий inline review-comment требует REST-запрос с line numbers по каждому пункту — это отдельная итерация.
