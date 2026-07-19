# День 34 — Ассистент работы с файлами

Модуль `:week7:day4`. REPL-агент, который **сам** решает какие файлы читать/писать. Использует MCP-tools + agentic loop (function calling у DeepSeek).

## Что внутри

1. **Свой MCP file-сервер** (Streamable HTTP `:3004`) с 6 инструментами:
   - `project_stat()` — краткая сводка проекта
   - `list_files(pattern?, subdir?)` — список файлов с фильтром
   - `read_file(path)` — прочитать (с обрезкой по `MAX_FILE_BYTES`)
   - `search_text(query, glob?, max_results?)` — grep по проекту
   - `write_file(path, content)` — создать/перезаписать
   - `apply_patch(path, find, replace, expected_count?)` — точечная замена с проверкой
2. **Sandboxing** — все пути через `safeResolve`: `..`, абсолютные и выход за `projectDir` блокируются.
3. **Agentic loop** — DeepSeek получает MCP-tools как function-schema, `tool_choice=auto`. LLM возвращает `tool_calls` → мы через MCP вызываем → отдаём обратно `role=tool` → пока LLM не сделает финальный текстовый ответ или не сработает `MAX_TOOL_ITER` (default 12).
4. **`--dry-run`** — `write_file`/`apply_patch` возвращают preview вместо реальной записи. Безопасно проверить план агента без риска сломать проект.

## REPL-команды (2+ сценариев, как требует минимум)

- `/where <symbol>` — найти использования: агент дёргает `search_text` + `read_file` для 2-3 значимых мест, отдаёт карту «где определено, где вызывается».
- `/doc <path>` — сгенерировать/обновить README для директории/файла: `list_files` + `read_file` для контекста → `write_file README.md` со стандартными секциями.
- `/adr <title>` — создать ADR: `project_stat` + `list_files docs/` → `write_file docs/adr/NNNN-<slug>.md` с шапкой Status/Context/Decision/Consequences.
- `/task <любая цель>` — свободный режим для агента.
- `/list_tools` — показать что дал MCP.

## Как запустить

```powershell
chcp 65001
$env:DEEPSEEK_API_KEY = "sk-…"     # или в .env корня
.\gradlew.bat :week7:day4:run --args="--dry-run"
```

Убрать `--dry-run` — агент реально пишет файлы.

## Ограничения / что дальше

- Один загон одной цели — нет long-term памяти между `/task`. Простое расширение — накапливать `messages` между вызовами.
- Нет операций через git (checkout, branch, commit). Хотел добавить git-tools, но раздувать не стал — при необходимости `apply_patch`+ручной `git` покрывает базовый сценарий.
- Слот `expected_count` в `apply_patch` — защита от неверного `find` с широким matching. Настоятельно рекомендую использовать при массовых заменах.
- `search_text` линейно ходит по проекту — на репе >100k файлов будет медленно. Для больших монорепо — вынести grep на ripgrep через ProcessBuilder.
