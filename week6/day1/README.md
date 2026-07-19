# Day 26 (week6/day1) — Запуск локальной LLM через Ollama

Первый день недели **Локальные LLM** AI Challenge. Задание организаторов:

> Установите и запустите любую локальную LLM (Ollama, LM Studio, llama.cpp, Mistral, Qwen, Phi и т.п.).
> Проверьте: модель запускается локально; к ней можно обратиться через CLI или HTTP API; модель отвечает.
> Сделайте минимум 3 запроса разной сложности.
> **Результат:** локальная LLM запущена и отвечает. Формат: видео + код.

## Что реализовано

- Минимальный Kotlin-клиент **Ollama** через `java.net.http.HttpClient` + Gson.
- **Endpoint `/api/chat`** (нативный, не OpenAI-compat `/v1` — иначе Ollama не отдаёт метрики).
- **Health-check** перед прогоном: `/api/version` + `/api/tags` (падаем с внятным сообщением если модель не установлена).
- **`temperature: 0`** — для воспроизводимости результатов.
- **Метрики из ответа Ollama** (не `System.currentTimeMillis`):
  - `prompt_eval_count` / `eval_count` — токены (in→out).
  - `load_duration` / `prompt_eval_duration` / `eval_duration` / `total_duration` — в наносекундах, конвертирую в мс.
  - `tok/s = eval_count * 1000 / eval_duration_ms` — честный throughput.
- **3 промпта с автопроверкой** (`✓/✗`, не «читать глазами»):
  1. **Простой факт с ловушкой** — «столица Австралии» (Сидней — интуитивная ловушка, правильно Канберра). Проверка: `contains("Канберра")`.
  2. **Логика (задача Канемана)** — «мяч+бита=110, бита на 100 дороже мяча, сколько мяч?» (правильно 5, интуитивная ловушка — 10). Проверка: regex `\b5\s*(руб|₽)` и НЕТ «10 руб».
  3. **JSON extraction** — «извлеки name+date_iso из текста». Проверка: парсится, `name` содержит «Алексей», `date_iso == "1990-03-15"`.
- Хелпер `stripCodeFence()` — снимает ```` ```json ```` перед `JSON.parse` (модели любят обёртки).
- Автоотчёт `day26_report.md` с таблицей + расшифровкой каждого промпта.

## Что нужно на машине

- **JDK 17+** (у меня `C:\Program Files\Android\Android Studio2\jbr`).
- **Ollama** запущена на `localhost:11434`.
- Модель установлена: `ollama pull qwen2.5:7b` (~4.7 GB).

## Запуск

Windows PowerShell:

```powershell
chcp 65001
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio2\jbr"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

.\gradlew.bat :week6:day1:run --console=plain -q
```

Другая модель:

```powershell
.\gradlew.bat :week6:day1:run --console=plain -q --args="llama3.1:8b"
```

## Что видно в консоли

```
Health-check…
  ollama 0.5.7, models=[qwen2.5:7b, nomic-embed-text:latest]

── 1_capital_trap: Простой факт с ловушкой (столица Австралии) ──
Q: Назови столицу Австралии. Ответь одним словом.
A: Канберра.
[✓] eval=290ms, 41.4 tok/s, 12 out tokens, wall=1240ms

── 2_bat_and_ball: Логика (задача Канемана про биту и мяч) ──
…

=== ИТОГОВАЯ ТАБЛИЦА ===
prompt               |  pass | in→out | eval ms |  tok/s |  wall
1_capital_trap       |     ✓ |  25→12 |     290 |   41.4 |  1240
2_bat_and_ball       |     ✓ |  92→68 |    1620 |   42.0 |  2650
3_json_extract       |     ✓ |  86→45 |    1080 |   41.7 |  1980

3 / 3 прошли автопроверку.

Отчёт: day26_report.md
```

## Проверка что GPU работает (не CPU-режим)

В отдельном терминале:

```powershell
nvidia-smi
```

Во время генерации `Memory-Usage` должен подняться с ~76 MiB до ~4700 MiB, `GPU-Util` скакать 40–90%. Если 0% — модель на CPU (в 10× медленнее), проверить `ollama serve` и версию драйвера CUDA.

## Как устроено

- `Main.kt` — весь код (~220 строк):
  - `OllamaClient` — HTTP-обёртка + `healthCheck()` + `chat(system, user)`.
  - `stripCodeFence()` — снимает ```` ``` ```` перед JSON.
  - `buildPrompts()` — три `Prompt` с `checker: (String) -> Boolean` для автопроверки.
  - `writeReport()` — markdown-отчёт с таблицей и деталями.

## Уроки

- **Ollama `/v1` (OpenAI-compat) — не отдаёт метрики.** Только нативный `/api/chat` возвращает `eval_count`, `eval_duration` и т.д. Для day26, где нужны реальные цифры throughput, только нативный.
- **`System.currentTimeMillis` показывает `wall` — включая HTTP-транспорт, JSON serialize/deserialize.** Для чистого throughput модели брать `eval_duration` из ответа.
- **Автопроверка > «читать глазами».** Ловушки в промптах (Сидней, «10 руб») сразу отсекают модели, которые «уверенно ошибаются». В отчёте видно `✓/✗` без ручного просмотра.
- **Health-check дёшев** — не тратим 5-10 сек на первый запрос чтобы выяснить что модель не установлена. `GET /api/tags` → 20 ms.
