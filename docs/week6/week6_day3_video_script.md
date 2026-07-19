# Day 28 — сценарий видео (немой скринкаст, ~2.5 мин)

## Подготовка (не в видео)

```powershell
chcp 65001
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio2\jbr"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:DEEPSEEK_API_KEY = "sk-..."   # или лежит в ../../.env

cd C:\D\Develop\ai-challenge
git fetch origin
git checkout week6/day3
git pull

# прогрев моделей
ollama run qwen2.5:7b "hi"
# /bye

# чистим артефакты для чистого видео
del week6\day3\index.json 2>$null
del week6\day3\chat.log.jsonl 2>$null
del week6\day3\chat.md 2>$null
```

## Сценарий

### Кадр 1 — Health-check + ingest (25 с)

```powershell
.\gradlew.bat :week6:day3:run --console=plain -q
```
Появится:
```
Health-check…
  local: chat=qwen2.5:7b, embed=nomic-embed-text. Всего моделей: 3
  cloud: cloud:deepseek-chat (DEEPSEEK_API_KEY найден)   ← ключевое!
RAG-CLI (day28). backend=local:qwen2.5:7b (cloud ✓). Индекс: 0 чанков.
```

В REPL:
```
ingest ../../docs
```
Ждём `Готово: N чанков из M файлов`. Пауза 2 с.

### Кадр 2 — Простой вопрос (default local) (15 с)

```
Что такое RAG в двух предложениях?
```
Ответ. Пауза 2 с.

Обрати внимание в конце: `[local:qwen2.5:7b | … | verbatim=0.35]` — новая метрика цитируемости.

### Кадр 3 — Ключевой :compare (30 с)

```
:compare Что такое RAG?
```
Появляется двухпанельная плашка LOCAL vs CLOUD с ответами и метриками. Внизу:
```
Одинаковые источники: ✓
Cloud быстрее по wall: ✓ (560 ms разница)
```
Пауза 5 с — читаешь оба ответа + метрики. **Это главный кадр видео.**

### Кадр 4 — :eval --both (40 с)

```
:eval --both
```
Прогонит `eval-questions.txt` (5 вопросов) через оба бэкенда. Появится сравнительная таблица + средние значения. Пауза 3 с.

### Кадр 5 — Стабильность (20 с)

```
:stability Что такое RAG? 3
```
Один вопрос 3 раза, показывает разброс out/wall и одинаковость источников.

### Кадр 6 — :backend cloud + вопрос через cloud (15 с)

```
:backend cloud
Что такое verbatim-метрика?
```
Тот же чат, но ответ уже от DeepSeek. В конце `[cloud:deepseek-chat | …]`. Обратно:
```
:backend local
```

### Кадр 7 — Сохранение + отчёт (15 с)

```
:save chat.md
:quit
```
```powershell
notepad chat.md
```
Проскроллить: **fully_local_rag: true** в шапке + все turn'ы с бэкендом и verbatim. Закрыть.

### Кадр 8 — Финал: fully_local_rag (5 с)

```powershell
Get-Content week6\day3\README.md | Select-String "fully_local_rag"
```
Видно `**fully_local_rag: true**` — задание закрыто. Конец.

## Тайминг

| Кадр | Время | Что |
|---|---|---|
| 1 | 0:00-0:25 | ingest 47 чанков |
| 2 | 0:25-0:40 | вопрос через local + verbatim |
| 3 | 0:40-1:10 | **:compare local vs cloud** |
| 4 | 1:10-1:50 | :eval --both таблица |
| 5 | 1:50-2:10 | :stability |
| 6 | 2:10-2:25 | :backend cloud + вопрос |
| 7 | 2:25-2:40 | :save + notepad |
| 8 | 2:40-2:45 | fully_local_rag |

Итого ~2:45.

## Если что-то пойдёт не так

- **`cloud: НЕТ`** в health-check → `DEEPSEEK_API_KEY` не найден. Проверь `$env:DEEPSEEK_API_KEY` или `../../.env`.
- **DeepSeek 429/500** → повтори. Мы намеренно НЕ добавляли retry-loop (день 28 не про надёжность, про сравнение).
- **verbatim=0** — модель полностью перефразировала. Норма для больших моделей типа cloud. Норма local ~0.3.
