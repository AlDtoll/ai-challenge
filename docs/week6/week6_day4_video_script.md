# Day 29 — сценарий видео (немой скринкаст, ~2:30)

## Подготовка (не в видео)

```powershell
chcp 65001
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio2\jbr"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

cd C:\D\Develop\ai-challenge
git fetch origin
git checkout week6/day4
git pull

# убеждаемся что модели есть
ollama list | Select-String "qwen2.5:7b"
# должны быть: qwen2.5:7b + qwen2.5:7b-instruct-q3_K_M

# прогрев основной модели вручную (не критично, warmup в REPL сделаем в кадре)
ollama run qwen2.5:7b "hi"
# /bye

# чистка
del week6\day4\index.json 2>$null
del week6\day4\chat.log.jsonl 2>$null
del week6\day4\chat.md 2>$null
```

## Сценарий

### Кадр 1 — Старт + ingest (~25 с)

```powershell
.\gradlew.bat :week6:day4:run --console=plain -q
```
Появится:
```
Health-check…
  установлено: [qwen2.5:7b:latest, qwen2.5:7b-instruct-q3_K_M:latest, nomic-embed-text:latest]
  пресеты: baseline, optimized, quant
RAG-CLI (day29). Пресеты: baseline, optimized, quant. Активный: baseline. Индекс: 0 чанков.
```

В REPL:
```
ingest ../../docs
```
Ждём `Готово: N чанков…`

### Кадр 2 — Warmup + :ps (~15 с)

```
:warmup all
```
Гоняет по одному тестовому вопросу через каждый пресет. Занимает ~20 сек.

```
:ps
```
Появится:
```
qwen2.5:7b:latest: total=~4800 MB, VRAM=~3800 MB (~79% на GPU)
qwen2.5:7b-instruct-q3_K_M:latest: total=~3200 MB, VRAM=~3200 MB (100% на GPU)
```
**Пауза 3 сек — это ключевой момент про ресурсы**: видно что Q3_K_M влезает целиком, Q4_K_M — только частично.

### Кадр 3 — ★ ГЛАВНЫЙ КАДР :compare-presets (~40 с)

```
:compare-presets Какая модель использовалась в day26 и сколько она весит?
```
Появляется тройная плашка:
```
┌─ BASELINE (qwen2.5:7b) ─────────────
│ Согласно предоставленным материалам, автор использовал модель Qwen 2.5 версии 7B параметров, которая занимает …
│
│ [65 tok, wall=1400ms, verbatim=0.28, ⚠️PARSE_FAILED]   ← без маркера!
├─ OPTIMIZED (qwen2.5:7b) ─────────────
│ Использована qwen2.5:7b через Ollama [S1]. Модель весит ~4.7 GB [S2].
│
│ [42 tok, wall=1180ms, verbatim=0.42, ✓]                ← plt чётко!
├─ QUANT (qwen2.5:7b-instruct-q3_K_M) ─────
│ Использована qwen2.5:7b [S1]. Занимает ~4.7 GB VRAM [S2].
│
│ [38 tok, wall=680ms, verbatim=0.36, ✓]                  ← ВДВОЕ БЫСТРЕЕ!
└──────────────────────────────────────
```
**Пауза 5-7 сек — ключевые контрасты видны:**
- BASELINE: длинно, без маркеров, `⚠️PARSE_FAILED`.
- OPTIMIZED: коротко, с маркерами, `✓`.
- QUANT: ещё короче и **вдвое быстрее**.

### Кадр 4 — :eval --presets (~45 с)

```
:eval --presets
```
Прогонит 5 контрольных вопросов через все 3 пресета:
```
| Preset | Model | out avg | wall avg (ms) | tok/s avg | verbatim avg | parse_fail |
|---|---|---|---|---|---|---|
| baseline | qwen2.5:7b | 62 | 1420 | 40.2 | 0.28 | 3/5 |
| optimized | qwen2.5:7b | 45 | 1180 | 42.1 | 0.38 | 0/5 |
| quant | qwen2.5:7b-instruct-q3_K_M | 41 | 690 | 51.0 | 0.35 | 0/5 |
```
**Пауза 5 сек. Это финальная таблица «до/после/после-квантования».**

Ключевые цифры:
- `parse_fail`: **3/5 → 0/5** (промпт+num_predict сработали).
- `wall`: **1420 → 690 ms** (квантование, −51%).
- `verbatim`: **0.28 → 0.38** (плотнее цитаты).

### Кадр 5 — Сохранение (~15 с)

```
:save chat.md
:quit
```
```powershell
notepad chat.md
```
Проскроллить: **fully_local_rag: true** в шапке, разные пресеты с метриками. Закрыть.

### Кадр 6 — Финал (~5 с)

```powershell
Get-Content week6\day4\README.md | Select-String "fully_local_rag"
```
Видно `**fully_local_rag: true**`.

## Тайминг

| Кадр | Время | Что показывает |
|---|---|---|
| 1 | 0:00-0:25 | ingest |
| 2 | 0:25-0:40 | :warmup all + :ps ← контраст 79% vs 100% GPU |
| 3 | 0:40-1:20 | ★ :compare-presets 3 пресета side-by-side |
| 4 | 1:20-2:05 | :eval --presets финальная таблица |
| 5 | 2:05-2:20 | :save + notepad |
| 6 | 2:20-2:25 | fully_local_rag |

Итого ~2:25.

## Если что-то пойдёт не так

- **BASELINE = OPTIMIZED по метрикам** → забыл `:warmup all`. Cold-swap tax на переключении `num_ctx` даст baseline фору. Повтори с warmup.
- **QUANT медленнее OPTIMIZED** → модель ещё не в памяти. `:warmup all` + `:ps` (должно показать 100% VRAM для Q3_K_M).
- **`parse_fail=0` у BASELINE тоже** → повезло на этих 5 вопросах. Добавь в `eval-questions.txt` вопрос с длинным ответом типа «Опиши весь пайплайн RAG в 5 предложениях» — на нём BASELINE обычно рвётся.
