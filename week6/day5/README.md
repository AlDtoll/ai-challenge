# Day 30 (week6/day5) — Локальная LLM как приватный HTTP-сервис

**fully_local_rag: true** — всё локально (Ollama), сервис поднят на JDK stdlib, без Ktor.

## Задание организаторов

> Разверните локальную LLM как сервис: VPS/домашний сервер, HTTP API, чат;
> проверьте доступ по сети, стабильность при нескольких запросах,
> базовые ограничения — rate limit / max context.
> Формат: видео + код.

## Что сделано

HTTP-сервис на **`com.sun.net.httpserver.HttpServer`** (JDK stdlib, ноль сторонних зависимостей на HTTP).

### Endpoints

| Method + Path | Auth | Rate | Concurrency | Что делает |
|---|---|---|---|---|
| `GET /health` | нет | нет | нет | Живой ли сервис + Ollama up + сколько чанков в индексе |
| `POST /v1/chat` | ✅ | ✅ | ✅ | Чат: `{messages:[{role,content}], temperature?, max_tokens?}` |
| `POST /v1/rag` | ✅ | ✅ | ✅ | RAG над индексом day26-28: `{question, k?}` |
| `GET /stats` | ✅ | нет | нет | Сводка: uptime, total, ok, 4xx/5xx, avg_wall |

### Middleware (порядок важен: **auth → rate limit → semaphore**)

1. **Auth** — `X-API-Key: <key>` **или** `Authorization: Bearer <key>`. Если ключ пуст (`--api-key ""`) — open mode с предупреждением в лог.
2. **Rate limit** — token bucket per-IP через `ConcurrentHashMap<String, State>` + `synchronized`. По умолчанию `60 rpm` (1 req/сек в среднем + burst до 60). `Retry-After: 60` в 429.
3. **Concurrency** — `Semaphore(K)` (по умолчанию `K=2`). Больше — очередь, если совсем не пускает за 30 сек — `503 busy`.

### Ограничения (пределы, честно указаны)

- **`num_ctx`** (максимальный контекст модели) — передаётся в Ollama при каждом запросе (`--max-ctx 4096` по умолчанию). Модель не глотает больше.
- **`max_tokens`** (`num_predict`) — по умолчанию 256, клиент может увеличить, но не больше физического потолка модели.
- **`MAX_INPUT_LEN`** — вход обрезается до 8000 символов на body (защита от «мегабайта»).
- **timeout 180 сек** на генерацию → `504 Gateway Timeout`.
- **stream=true** → `400 unsupported` (в этом дне не делаем стрим — как у эталона swanden).

## Как запускать

**Требуется:**
- JDK 17+
- Ollama на `localhost:11434`
- Модели: `qwen2.5:7b`, `nomic-embed-text` (`ollama pull qwen2.5:7b && ollama pull nomic-embed-text`)

### 1. Собрать индекс из docs (один раз)

```powershell
.\gradlew.bat :week6:day5:run --console=plain -q --args="--ingest ../../docs"
```
Появится `Saved N chunks → index.json` в `week6/day5/`.

### 2. Запустить сервис

```powershell
# Открытый режим (для локального теста)
.\gradlew.bat :week6:day5:run --console=plain -q

# С ключом
.\gradlew.bat :week6:day5:run --console=plain -q --args="--api-key demo-2026"

# Кастомный порт + жесткий лимит
.\gradlew.bat :week6:day5:run --console=plain -q --args="--api-key demo-2026 --port 8080 --rate-rpm 30 --max-concurrent 1"
```

### 3. Демо-режим (для видео) — одной командой

```powershell
.\gradlew.bat :week6:day5:run --console=plain -q --args="--demo --api-key demo-2026"
```
Сервис **сам** прогоняет 6 сценариев (health / 401 / 200 / stream=400 / burst 429 / rag / stats) и печатает отчёт. Сервер остаётся живым — можно ткнуть с телефона по LAN.

## Флаги

| Флаг | Env | Default | Что |
|---|---|---|---|
| `--port` | `LLM_SERVICE_PORT` | 7788 | HTTP порт |
| `--api-key` | `LLM_API_KEY` / `SERVICE_API_KEY` | `""` (open) | Ключ клиента |
| `--rate-rpm` | `LLM_RATE_RPM` | 60 | Requests-per-minute per IP |
| `--max-concurrent` | `LLM_MAX_CONCURRENT` | 2 | Одновременных генераций |
| `--max-ctx` | `LLM_MAX_CTX` | 4096 | num_ctx для Ollama |
| `--model` | `LLM_MODEL` | qwen2.5:7b | Модель чата |
| `--ingest <dir>` | — | — | Построить index.json и выйти |
| `--demo` | — | off | Демо-режим |

## Примеры curl

```bash
# /health — без ключа
curl -s http://localhost:7788/health | jq

# /v1/chat — с ключом
curl -s -X POST http://localhost:7788/v1/chat \
  -H "X-API-Key: demo-2026" \
  -H "Content-Type: application/json" \
  -d '{"messages":[{"role":"user","content":"Скажи одно слово: OK."}],"max_tokens":16}' | jq

# /v1/rag — с ключом
curl -s -X POST http://localhost:7788/v1/rag \
  -H "X-API-Key: demo-2026" \
  -H "Content-Type: application/json" \
  -d '{"question":"Что такое RAG в двух предложениях?","k":3}' | jq

# stream=true → 400
curl -s -X POST http://localhost:7788/v1/chat \
  -H "X-API-Key: demo-2026" -H "Content-Type: application/json" \
  -d '{"messages":[{"role":"user","content":"hi"}],"stream":true}'
# {"error":{"type":"unsupported","message":"stream=true is not supported; ..."}}

# burst → 429
for i in $(seq 1 30); do
  curl -s -o /dev/null -w "%{http_code}\n" \
    -H "X-API-Key: demo-2026" http://localhost:7788/health
done | sort | uniq -c
# должно быть N × 200 + M × 429
```

## Проверка «доступа по сети»

**С другой машины в LAN** (телефон, ноут):
```bash
# найти LAN-IP компьютера, где крутится сервис (Windows: `ipconfig | findstr IPv4`)
curl http://192.168.1.42:7788/health
```

**Если хочется публично (VPS)** — под nginx/Caddy как reverse-proxy на порту 8443 (не 443 — там AmneziaVPN). `X-Forwarded-For` уже правильно резолвится в `resolveClientIp()`.

## Архитектура

```
┌─ HTTP request ───────────────────┐
│                                  │
│  Filter chain (в этом порядке):  │
│  1. AuthFilter                   │  → 401 если ключ не совпал
│  2. RateFilter (TokenBucket/IP)  │  → 429 если бакет пуст (Retry-After: 60)
│  3. ConcurrencyFilter (Semaphore)│  → 503 если >30с в очереди
│                                  │
│  Handler:                        │
│  4. ChatHandler / RagHandler     │
│     → OllamaClient.chat()        │  → 504 если >180с
│     → sendJson(200, {...})       │
└──────────────────────────────────┘
```

Почему **auth перед rate limit**: чтобы неавторизованные IP не жгли бакет легитимного клиента (урок 1.8 из day30_research).

Почему **rate перед semaphore**: rate — дешёвый (in-memory), semaphore держит слоты — не хочется занимать слот тем, кого мы всё равно отобьём 429.

## Метрики (в отличие от day 26-29 — про **устойчивость**, не про качество)

- **Пропускная способность**: `avg_wall_ms` в `/stats`.
- **Отказы**: `err429`, `err504` в `/stats`.
- **Живость под нагрузкой**: burst 20 запросов → 429 срабатывает, **`/health` после нагрузки жив**.

## Ссылки

- Разбор чужих решений: `docs/week6/day30_research.md` (главный эталон — **swanden** на Go stdlib; **pechatkin** — публичный HTTPS через Caddy+sslip.io; **dpmn** — VPS 2GB под нагрузкой).
- Сценарий видео: `docs/week6/week6_day5_video_script.md`.
