# Немой скринкаст — День 34

## Сцены

1. Заставка «День 34. Ассистент работы с файлами».
2. PowerShell → `chcp 65001` → `git branch --show-current` (week7/day4) → показать `week7/day4/README.md`.
3. Запуск: `.\gradlew.bat :week7:day4:run --args="--dry-run"` — dry-run на первый прогон.
   - «MCP file-сервер поднят»
   - «MCP tools: project_stat, list_files, read_file, search_text, write_file, apply_patch»
4. **Сценарий 1** — `/where MCP`. Показать в консоли строки `→ tool #1: search_text(...)`, `→ tool #2: read_file(...)`, а потом финальный ответ агента с картой использований.
5. **Сценарий 2** — `/adr Использование BM25 вместо эмбеддингов в CI-задачах`. Агент сам вызывает project_stat, list_files, потом write_file на `docs/adr/0001-bm25-in-ci.md`. Поскольку `--dry-run` — показывается preview.
6. Выйти `/quit`. Запустить снова БЕЗ `--dry-run`. Повторить `/adr Использование BM25...` — теперь файл реально появляется.
7. `cat docs/adr/0001-bm25-in-ci.md` — показать содержимое.
8. Конец.

## Что подсветить в описании

- Свой MCP file-сервер + agentic loop с function-calling у DeepSeek.
- LLM САМА выбирает какие tools дёрнуть, минуя команды пользователя типа «прочитай X».
- `--dry-run` — безопасный режим предпросмотра.
- `apply_patch` с `expected_count` — anti-footgun против массовых замен.
- Sandboxing через `safeResolve` — нельзя выйти из projectDir.
