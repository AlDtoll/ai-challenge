# Day 27 — сценарий видео (немой скринкаст)

Формат как всегда: **что печатаю → что получаю → следующий шаг**. Звука нет. Целевая длина ~2.5 мин.

## Подготовка перед записью

1. **Прогреть обе модели**:
   ```powershell
   ollama run qwen2.5:7b "hi"       # /bye
   curl.exe -X POST http://localhost:11434/api/embed -H "Content-Type: application/json" -d '{"model":"nomic-embed-text","input":"hi"}' | Out-Null
   ```
2. **Одно широкое окно PowerShell** (проще чем 3, режим RAG-CLI визуально плотный):
   ```powershell
   chcp 65001
   [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
   $env:JAVA_HOME = "C:\Program Files\Android\Android Studio2\jbr"
   $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
   cd C:\D\Develop\ai-challenge
   git checkout week6/day2
   git pull
   del week6\day2\index.json 2>$null
   del week6\day2\chat.log.jsonl 2>$null
   del week6\day2\chat.md 2>$null
   ```
3. Второе (узкое) окно рядом — под `Get-Content chat.log.jsonl -Wait`, чтобы в кадре был live-JSONL стрим ходов.

---

## Сценарий

### Кадр 1 — Health-check + ingest (25 сек)

Окно A:
```powershell
.\gradlew.bat :week6:day2:run --console=plain -q --args="ingest ../../docs"
```

Ожидаемый вывод:
```
Health-check…
  chat=qwen2.5:7b, embed=nomic-embed-text. Всего моделей: 3
Ingest: '../../docs' → 8 файлов
  [1/8] week5/day23_research.md — 12 чанков
  [2/8] week5/day24_research.md — 10 чанков
  ...
  [8/8] week6/week6_day2_video_script.md — 4 чанка
Готово: 47 чанков из 8 файлов за 15.2 с.
Индекс: index.json
```

Пауза 2 сек — прочитать «47 чанков из 8 файлов».

### Кадр 2 — Запуск чата (5 сек)

Окно A:
```powershell
.\gradlew.bat :week6:day2:run --console=plain -q
```

Появляется:
```
Health-check…
  chat=qwen2.5:7b, embed=nomic-embed-text. Всего моделей: 3
RAG-CLI: chat=qwen2.5:7b, embed=nomic-embed-text. Индекс: 47 чанков.

Команды:
  ingest <папка>         построить индекс…
  <вопрос>               спросить…
  :history / :sources / :stats / :save / :eval / :clear / :help / :quit

>>>
```

### Кадр 3 — Первый вопрос + multi-turn (40 сек)

Окно A, ввод:
```
Какая модель использовалась в day26?
```

Ожидаемый ответ (2-3 сек):
```
Ты запустил qwen2.5:7b через Ollama [S1]. Модель весит около 4.7 GB
и работает на RTX 3060 Laptop с 6 GB VRAM [S2].

[источники: week6/day1/README.md, docs/week6/day26_research.md | 42 tokens, 42.8 tok/s, wall=1180 ms]
```

Ввод (мультитурн — модель должна помнить контекст):
```
А как я мерил её скорость?
```

Ответ:
```
Метрики брались из ответа Ollama напрямую [S1]: prompt_eval_count / eval_count
для токенов, eval_duration в наносекундах (÷ 1e9 → мс), tok/s = out * 1000 / eval_ms.

[источники: docs/week6/day26_research.md, week6/day1/README.md | 51 tokens, 43.0 tok/s, wall=1420 ms]
```

Пауза 1 сек — видно что модель поняла «её» = qwen2.5:7b из предыдущего хода.

### Кадр 4 — JSONL-трейс в live-режиме (15 сек)

Открыть **окно B** параллельно:
```powershell
Get-Content chat.log.jsonl -Wait
```

Показать что в окне B видно 2 JSON-строки (по одной на каждый turn). Каждая строка — `{ts, question, answer, sources, prompt_tokens, out_tokens, eval_ms, wall_ms, tokens_per_sec}`.

### Кадр 5 — `:sources` и `:stats` (10 сек)

Окно A:
```
:sources
```
Ответ — 8 файлов из `docs/`:
```
  week5/day23_research.md
  week5/day24_research.md
  week5/day25_research.md
  week5/week5_day1_video_script.md
  week5/week5_day2_video_script.md
  week5/week5_transcript.md
  week6/day26_research.md
  week6/week6_day2_video_script.md
```

```
:stats
```

Ответ — метрики последнего ответа:
```
Последний ответ: prompt=1240 → out=51 tokens, 1420 ms, 43.0 tok/s, wall=1420 ms
Источники: docs/week6/day26_research.md, week6/day1/README.md
```

### Кадр 6 — `:eval` регресс-тест (30 сек)

Окно A:
```
:eval
```

Ожидаемый вывод — таблица прогона `eval-questions.txt` (5 вопросов):
```
Eval: 5 вопросов (индекс: 47 чанков, чистая история)

| # | Вопрос | Источники | out | tok/s | wall |
|---|---|---|---|---|---|
| 1 | Какая модель использовалась в day26? | week6/day1/README.md,docs/week6/day26_research.md | 42 | 42.8 | 1180 |
| 2 | Как я мерил метрики скорости в day26? | docs/week6/day26_research.md,week6/day1/README.md | 51 | 43.0 | 1420 |
| 3 | Что такое RAG в двух предложениях? | week5/day23_research.md,week5/day24_research.md | 38 | 42.5 | 1080 |
| 4 | Какие промпты я проверял в day26? | week6/day1/README.md,docs/week6/day26_research.md | 45 | 42.9 | 1250 |
| 5 | Что такое nomic-embed-text и почему нужны префиксы? | docs/week6/day26_research.md | 62 | 43.1 | 1580 |

Средняя tok/s: 42.9, суммарный wall: 6510 ms
```

Пауза 2 сек — видно что 5/5 отвечает с правильными источниками.

### Кадр 7 — `:save` и `:quit` (10 сек)

Окно A:
```
:save chat_demo.md
```
Ответ: `Сохранено: chat_demo.md`

```
:quit
```

Ответ: `> _` (возврат в PowerShell).

Окно A:
```powershell
notepad chat_demo.md
```
Проскроллить сгенерированный markdown с двумя turn'ами + eval-прогоном (упоминания метрик и источников). Закрыть.

### Кадр 8 — cloud=false (5 сек)

Окно A:
```powershell
Get-Content week6\day2\README.md | Select-String "cloud_models_used"
```
Ответ: `**cloud_models_used: false** — приложение работает **полностью локально**...`

Пауза 1 сек — сдача формально закрыта.

---

## Тайминг

| Кадр | Время | Что показывает |
|---|---|---|
| 1 | 0:00-0:25 | Health-check + ingest 47 чанков |
| 2 | 0:25-0:30 | Запуск чата, стартовый экран |
| 3 | 0:30-1:10 | Первый вопрос + мультитурн |
| 4 | 1:10-1:25 | JSONL-трейс live в окне B |
| 5 | 1:25-1:35 | :sources + :stats |
| 6 | 1:35-2:05 | :eval — таблица из 5 вопросов |
| 7 | 2:05-2:15 | :save + notepad chat_demo.md |
| 8 | 2:15-2:20 | cloud_models_used: false в README |

**Итого:** ~2 мин 20 сек.

## Если что-то пойдёт не так

- **Ingest падает** — забыл `ollama pull nomic-embed-text`. Ставим и повторяем.
- **Health-check падает** — Ollama-сервер не запущен. `curl http://localhost:11434` → должен ответить `Ollama is running`.
- **Ответы бредовые / без ссылок на источники** — модель не поняла инструкцию. Обычно виноват маленький индекс — проверь `:sources`. Если пусто — `ingest` не отработал.
- **`:eval` даёт разные результаты на повторных запусках** — вопросы неудачные или `temperature > 0`. Норма: разброс до 5-10% tok/s, но источники должны быть **одни и те же** (deterministic retrieval).

## Что подавать

- Код: коммит `week6/day2`, ссылка на GitHub.
- Видео: 2.3 мин по этому сценарию.
- Дополнительно: `week6/day2/chat_demo.md`, `week6/day2/chat.log.jsonl` — артефакты с реальным запуском.
