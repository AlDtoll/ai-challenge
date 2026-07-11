# Day 30 — сценарий видео (немой скринкаст, ~3 мин)

## Подготовка (не в видео)

```powershell
chcp 65001
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio2\jbr"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

cd C:\D\Develop\ai-challenge
git fetch origin
git checkout week6/day5
git pull

# убеждаемся что модели есть
ollama list | Select-String "qwen2.5:7b"
ollama list | Select-String "nomic-embed-text"

# 1) построить индекс (один раз)
.\gradlew.bat :week6:day5:run --console=plain -q --args="--ingest ../../docs"
# должно: "Saved N chunks → index.json"

# чистка предыдущих логов если были
del week6\day5\chat.log.jsonl 2>$null
```

## Сценарий

### Кадр 1 — Запуск сервиса (~20 с)

```powershell
.\gradlew.bat :week6:day5:run --console=plain -q --args="--api-key demo-2026 --port 7788"
```

Появится:
```
== Day 30 local LLM service ==
Config(port=7788, apiKey=<set,len=9>, rateRpm=60, maxConcurrent=2, maxCtx=4096, ...)
Health-check Ollama … OK (3 models)
Index: 47 chunks from index.json
Warmup … done
Serving on http://0.0.0.0:7788
Endpoints: GET /health, POST /v1/chat, POST /v1/rag, GET /stats (auth)
Ctrl+C to stop.
```

**Пауза 3 сек. Главное — «Serving on http://0.0.0.0:7788» + список endpoints.**

### Кадр 2 — /health в браузере (~10 с)

Открыть в Chrome: `http://localhost:7788/health`

```json
{
  "status": "ok",
  "ollama": true,
  "model": "qwen2.5:7b",
  "index_chunks": 47,
  "uptime_ms": 8432
}
```

### Кадр 3 — /v1/chat БЕЗ ключа → 401 (~15 с)

Открыть второе окно PowerShell:
```powershell
curl.exe -s -X POST http://localhost:7788/v1/chat `
  -H "Content-Type: application/json" `
  -d '{\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}]}'
```
Ответ: `{"error":{"type":"invalid_api_key",...}}`, status 401.

### Кадр 4 — /v1/chat С ключом → 200 (~20 с)

```powershell
curl.exe -s -X POST http://localhost:7788/v1/chat `
  -H "X-API-Key: demo-2026" `
  -H "Content-Type: application/json" `
  -d '{\"messages\":[{\"role\":\"user\",\"content\":\"Скажи одно слово: OK.\"}],\"max_tokens\":16}'
```
Ответ:
```json
{"answer":"OK","model":"qwen2.5:7b","wall_ms":420,"eval_ms":140,"tokens_out":2}
```
Пауза 3 сек. Обрати внимание на `wall_ms` — прошёл через все filters + Ollama.

### Кадр 5 — /v1/rag с ключом (~30 с) ★ ключевой

```powershell
curl.exe -s -X POST http://localhost:7788/v1/rag `
  -H "X-API-Key: demo-2026" `
  -H "Content-Type: application/json" `
  -d '{\"question\":\"Что такое RAG в двух предложениях?\",\"k\":3}'
```
Ответ:
```json
{
  "answer":"RAG — это подход, где ответ модели опирается на найденные чанки контекста [S1]. Retrieval даёт эмбеддинги, генерация склеивает [S2].",
  "sources":[
    {"marker":"[S1]","source":"day26_research.md","similarity":"0.612"},
    {"marker":"[S2]","source":"day27_research.md","similarity":"0.587"},
    {"marker":"[S3]","source":"day28_research.md","similarity":"0.541"}
  ],
  "wall_ms":1080,"eval_ms":760,"tokens_out":38
}
```
Пауза 5 сек — видно маркеры + similarity.

### Кадр 6 — stream=true → 400 (~10 с)

```powershell
curl.exe -s -X POST http://localhost:7788/v1/chat `
  -H "X-API-Key: demo-2026" -H "Content-Type: application/json" `
  -d '{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":true}'
```
Ответ: `{"error":{"type":"unsupported","message":"stream=true is not supported; use stream=false"}}`.

### Кадр 7 — ★ Burst нагрузка → 429 (~25 с)

```powershell
1..30 | ForEach-Object {
  curl.exe -s -o NUL -w "%{http_code}`n" -H "X-API-Key: demo-2026" http://localhost:7788/v1/chat -X POST `
    -H "Content-Type: application/json" `
    -d '{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"max_tokens\":8}'
} | Group-Object | Format-Table Name, Count
```
Появится:
```
Name  Count
----  -----
200      60
429      40    ← rate limit сработал
```
(Числа зависят от `rate-rpm` — при 60 будет ~60 первых 200, остаток 429.)

Прямо в другом окне сразу:
```powershell
curl.exe -s http://localhost:7788/health
```
Ответ `{"status":"ok",...}` — **сервис жив после нагрузки** (ключевой критерий задания).

### Кадр 8 — /stats (~15 с)

```powershell
curl.exe -s -H "X-API-Key: demo-2026" http://localhost:7788/stats
```
```json
{
  "uptime_ms":128432,
  "total_requests":95,
  "ok":62,
  "chat_ok":58,
  "rag_ok":3,
  "err400":1,
  "err401":1,
  "err429":33,
  "avg_wall_ms":540
}
```
Пауза 5 сек — видна вся статистика.

### Кадр 9 — Доступ по сети (телефон) (~20 с)

Найти LAN-IP:
```powershell
ipconfig | Select-String IPv4
# → 192.168.1.42
```

**Взять телефон**, открыть в Chrome: `http://192.168.1.42:7788/health`.

Показать телефон в кадре — тот же JSON что и в браузере на ПК. **«Доступ по сети» задания закрыт.**

### Кадр 10 — Финал (~5 с)

```powershell
Get-Content week6\day5\README.md | Select-String "fully_local_rag"
```
Видно `**fully_local_rag: true**`. Конец.

## Тайминг

| Кадр | Время | Что показывает |
|---|---|---|
| 1 | 0:00-0:20 | Запуск сервиса, endpoints |
| 2 | 0:20-0:30 | /health в браузере |
| 3 | 0:30-0:45 | 401 без ключа |
| 4 | 0:45-1:05 | 200 с ключом (/v1/chat) |
| 5 | 1:05-1:35 | ★ /v1/rag с sources+similarity |
| 6 | 1:35-1:45 | stream=true → 400 |
| 7 | 1:45-2:10 | ★ Burst → 429 + /health живой |
| 8 | 2:10-2:25 | /stats с полной сводкой |
| 9 | 2:25-2:45 | ★ Доступ по сети с телефона |
| 10 | 2:45-2:50 | fully_local_rag |

Итого ~2:50.

## Альтернатива — одной командой (быстрое видео ~1:30)

```powershell
.\gradlew.bat :week6:day5:run --console=plain -q --args="--demo --api-key demo-2026"
```

Сервис сам прогоняет 6 сценариев (health / 401 / 200 / stream=400 / burst 429 / rag / stats) и печатает отчёт с рамками. После — остаётся живым, можно ткнуть с телефона. Идеально если ленивый вечер и нужно быстро — но для оценки жюри лучше полный сценарий выше (наглядно все стадии).

## Если что-то пойдёт не так

- **`Ollama not reachable`** при старте → Ollama не запущен. `ollama serve` в отдельном окне (или Ollama Desktop). Проверить `curl http://localhost:11434/api/tags`.
- **Индекс = 0 chunks** → `--ingest ../../docs` не выполнялся. Прогнать перед запуском сервиса.
- **Burst 30 запросов, все 200 (429 не сработал)** → `rate-rpm` слишком большой. Запустить с `--rate-rpm 10` чтобы точно уложиться в лимит.
- **Телефон не видит `http://<lan-ip>:7788`** — Windows Firewall блокирует. Разрешить: `New-NetFirewallRule -DisplayName "AI Challenge day30" -Direction Inbound -Protocol TCP -LocalPort 7788 -Action Allow` (в PS от админа). Или временно выключить фаервол для теста.
- **AmneziaVPN на компе включён** — курлы **изнутри компа** пойдут через тунель и localhost:7788 может не отвечать. Отключить VPN на время демо, либо тестировать с телефона по LAN (VPN только на ПК).
