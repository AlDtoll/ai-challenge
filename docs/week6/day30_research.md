# Day 30 — Локальная LLM как приватный сервис. Разбор решений участников

Дата: 2026-07-11
Наш день: `week6/day5` (Day 30 «Разверните локальную LLM как сервис: VPS/домашний сервер, HTTP API, чат; проверьте доступ по сети, стабильность при нескольких запросах, базовые ограничения — rate limit / max context. Формат: видео + код»). База — `week6/day4` (Day 29): 3 пресета (BASELINE/OPTIMIZED/QUANT), `:compare-presets`, `parse_failure`, warmup.

Источник авторов: sheets-reader токен свежий (обновлён 2026-07-11), 2360 комментариев прочитаны. Явные day30-паттерны в URL (`day-30/day30/task-30/task30/week6-day5/d30`) — 7 подтверждённых сдач, из них 4 полезных (pechatkin, dpmn, swanden, mrsmith113), 1 не про задание (avalanche — Telegram-бот на облачном DeepSeek), 2 ложных срабатывания (karpiuk PR#18 = day27, nikbrik `coding_writer` = ранние дни). Список: `/tmp/day30_authors.json`. Локальные копии: `/tmp/day30_research_repos/{pechatkin,dpmn,swanden,mrsmith113,avalanche,karpiuk,nikbrik}/`.

**Наш эталон sergio (Kotlin) day30 ЕЩЁ НЕ СДАЛ** — в таблице у него самая свежая запись `week6/day-4-total-29`. То же для nikolay (ShirobokovNE), kaa, soziev, yavits — все застряли на day29. Это разбор без топ-Kotlin-референса.

| Ник | Автор | Репо | Стек | HTTP-сервер | API-формат | Auth | Rate limit | Стрим | Реальный сервер |
|---|---|---|---|---|---|---|---|---|---|
| **swanden** | Den | `swanden/ai-challenge/tree/main/week-6/task-30` | **Go stdlib** | `net/http` (stdlib, `http.ServeMux`) | **OpenAI-compat** `/v1/chat/completions` + `/v1/models` | **Bearer** | **Token bucket** на IP | нет (400 на `stream:true`) | локально + инструкция VPS/Caddy |
| **pechatkin** | Viktor Pechatkin | `vapechatkin/ai_ac/tree/main/d30` | Python FastAPI | FastAPI/uvicorn | Свой `/chat` + сессии | **Bearer** | Sliding window (deque) | нет | **Yandex Cloud VM 4CPU/8GB + Caddy + sslip.io HTTPS** |
| **dpmn** | Олег Ионов | `dpmn/ai-advent-challenge/tree/main/week-06/day-30` | Python Flask | Flask + Docker Compose | Свой `/api/excuse`+`/api/chat` (llama.cpp `/v1` внутри) | нет | Sliding window (deque) | нет | **VPS 1vCPU/2GB + swap 2GB, публичный IP 2.27.253.165:8080** |
| **mrsmith113** | Stas Kuznetsov | `mrsmith113/deepseek-chat/tree/master/task30` | Python FastAPI | FastAPI/uvicorn | Свой `/chat` + `/chat/stream` (SSE) | нет | Sliding window (deque) | **SSE** | WSL2 в LAN |
| avalanche | Anastasia Anisimova | `1Avalanche/SmartReminder/tree/week6-day5` | Kotlin JVM | Telegram long-polling | Telegram Bot API | Telegram token | — | — | облачный DeepSeek (**не локальная модель — задание не закрыто**) |

Итог для нашего кода: **swanden — главный эталон** (единственный, кто сделал OpenAI-compat + Bearer + token bucket + семафор + `max_ctx` через `num_ctx` — это буквально план Данила, но на Go stdlib). pechatkin — **живой публичный HTTPS через Caddy+sslip.io** — единственный, кто закрыл «доступ к модели по сети из интернета». dpmn — **VPS на 2GB RAM** доказывает, что 1.5B-модель + swap + Docker compose реально работают; можно упомянуть как «а если Данилу дадут слабый VPS». mrsmith113 — единственный со **streaming (SSE)**, отдельный test_stability.py. avalanche трактовала задание неверно (Telegram-бот с облачной моделью — не локальный сервис).

---

## 1. swanden (Go, `serve30.go`) — прямой аналог плана Данила

Репо: `swanden/ai-challenge`, `week-6/task-30/`.
Файлы: `serve30.go` (**521 строка**), `readme.md` (**279 строк** — таблица «Выбрано vs Альтернатива» по 8 пунктам, mermaid-схема, честные пределы, FAQ; читать полностью — это референс, как оформлять отчёт day30).

### 1.1 Архитектура — 1-в-1 план Данила, но на Go stdlib

Один файл, ноль сторонних зависимостей на HTTP (stdlib `net/http`, `sync`, `time`). Именно то, что Данил хочет сделать на JDK `com.sun.net.httpserver.HttpServer` — на Go это ещё компактнее.

```go
func buildService(llm *LocalLLM, cfg serveConfig) (*server, *http.Server) {
    srv := &server{
        llm: llm, cfg: cfg,
        lim: newIPLimiter(cfg.RateRPM),
        sem: make(chan struct{}, max1(cfg.MaxConcurrent)),
    }
    mux := http.NewServeMux()
    mux.HandleFunc("/health", srv.handleHealth)                      // без ключа: liveness
    mux.HandleFunc("/v1/models", srv.auth(srv.handleModels))         // OpenAI: список моделей
    mux.HandleFunc("/v1/chat/completions", srv.auth(srv.handleChat)) // OpenAI: чат
    return srv, &http.Server{Addr: cfg.Addr, Handler: mux, ReadHeaderTimeout: 10 * time.Second}
}
```

**Прямой урок**: точно так же — три handler'а, один `HttpServer`, middleware auth, semaphore на генерацию.

### 1.2 API-формат: OpenAI-совместимый (не свой)

Хороший аргумент в README:
> К сервису сразу подходят готовые клиенты (OpenAI SDK, LM Studio-совместимые тулы). Свой JSON нагляднее и меньше кода, но не стыкуется с экосистемой.

Точки, где Данил может выбрать иначе: план говорит `/v1/chat` (не `/v1/chat/completions`) + `/v1/rag`. То есть план Данила — **свой формат**, не OpenAI-compat. У swanden — OpenAI-compat + отдельный `/health` + `/v1/models`. Разница: swanden отдаёт `chat.completion` объект (`id`, `object`, `choices[0].message.content`, `usage`). Данил в плане — свой JSON `{ answer, sources, quotes, elapsed_ms }` (день 28 наследие).

**Прямой урок**: OpenAI-compat даёт бонус — с ним можно ткнуть в сервис любым OpenAI-клиентом (Python `from openai import OpenAI; c = OpenAI(base_url="http://vps:8080/v1", api_key="...")` — работает без изменений). При этом наша день-28-структура `sources`/`quotes` в `chat.completions` не влезает — только в `content` строкой. **Идея на подумать**: сделать оба — `/v1/chat/completions` (OpenAI, для внешних клиентов, `content` = склеенный ответ), и `/v1/rag` (свой, для нашего собственного клиента, с `sources`/`quotes` в JSON). Первый — под требование «доступ к модели по сети готовыми клиентами», второй — под наш кейс. Или один `/v1/chat` со своим форматом — тогда бонуса совместимости нет.

### 1.3 Rate limit — token bucket на IP, stdlib

```go
type ipLimiter struct {
    mu   sync.Mutex
    rpm  int
    rate float64 // токенов в секунду
    m    map[string]*tokenBucket
}

type tokenBucket struct {
    tokens float64
    last   time.Time
}

func (l *ipLimiter) allow(ip string) bool {
    l.mu.Lock(); defer l.mu.Unlock()
    now := time.Now()
    b, ok := l.m[ip]
    if !ok {
        l.m[ip] = &tokenBucket{tokens: float64(l.rpm) - 1, last: now}
        return true
    }
    b.tokens += now.Sub(b.last).Seconds() * l.rate
    if b.tokens > float64(l.rpm) { b.tokens = float64(l.rpm) }
    b.last = now
    if b.tokens < 1 { return false }
    b.tokens--
    return true
}
```

Классический токен-бакет: `rate = rpm/60` токенов в секунду, максимум `rpm` в бакете. `mu` — на весь мап (для 1-2 клиентов норм; для тысяч — нужен shard-map, но day30-сервис такого не увидит). Первый запрос от нового IP → создаём бакет со «стартовым капиталом» `rpm-1`. `Retry-After: 60` в заголовке ответа 429.

**Прямой урок**: это ровно то, что Данил планировал (token bucket). Kotlin-версия ещё короче — `ConcurrentHashMap<String, TokenBucket>`, `synchronized` внутри `allow`. Не нужен OkHttp/Retrofit/rate-limiter-lib — 50 строк stdlib.

### 1.4 Клиентский IP через X-Forwarded-For

```go
func clientIP(r *http.Request) string {
    if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
        if i := strings.IndexByte(xff, ','); i >= 0 {
            return strings.TrimSpace(xff[:i])
        }
        return strings.TrimSpace(xff)
    }
    host, _, err := net.SplitHostPort(r.RemoteAddr)
    if err != nil { return r.RemoteAddr }
    return host
}
```

Если Данил встанет за reverse-proxy (Caddy/nginx) — надо читать `X-Forwarded-For` (первый IP, если запятая). Иначе rate limit будет считать все запросы одного IP proxy → мгновенный 429.

**Прямой урок**: сразу добавить `resolveClientIp(exchange)` при переходе за reverse-proxy — иначе rate limit ничего не защитит.

### 1.5 Семафор конкурентности отдельно от rate limit

```go
sem: make(chan struct{}, max1(cfg.MaxConcurrent)),
// ...
select {
case s.sem <- struct{}{}:
    defer func() { <-s.sem }()
case <-r.Context().Done():
    return
}
```

`sem` — буферизированный channel размера K (по умолчанию 2). Запрос ждёт свободного слота или отваливается по клиентскому таймауту. Rate limit срабатывает раньше — если IP шпарит, он получит 429 до входа в семафор.

Обоснование в README: «Ollama сам сериализует тяжёлую генерацию (одна модель — один слот llama.cpp); семафор бережёт память и даёт предсказуемость». То есть на 6GB VRAM параллельно вторая генерация всё равно не пойдёт (или её выкинет OOM), поэтому K=2 — это скорее «два клиента в короткой очереди», а не «две генерации одновременно».

**Прямой урок**: Java-аналог — `Semaphore sem = new Semaphore(K, true)`; `sem.tryAcquire(timeoutMs, MS)` перед вызовом Ollama, `sem.release()` в `finally`. Для нас K=1-2. С таймаутом клиентский curl не подвиснет навечно.

### 1.6 Стриминг: честно 400, а не молчание

```go
if req.Stream {
    writeOAIError(w, http.StatusBadRequest, "unsupported",
        "stream=true не поддерживается; используй stream=false")
    return
}
```

Клиенты, читающие поле `stream: true`, получат явный отказ, а не «пустой ответ через 60 сек». Хорошая практика для видео — показать этот 400 отдельным примером.

**Прямой урок**: Данил в плане пишет «стрим не делаем». Так же явно — 400 на `stream=true`.

### 1.7 max-context через `num_ctx` (наследие day29)

```go
llm := *s.llm // копия, чтобы параметры запроса не текли между клиентами
llm.NumCtx = s.cfg.MaxCtx
if req.Temperature != nil { llm.Temp = *req.Temperature }
if req.MaxTokens != nil && *req.MaxTokens > 0 { llm.NumPredict = *req.MaxTokens }
```

**Внимание — критический паттерн**: копия `LocalLLM` перед мутацией. Иначе `temperature` из одного запроса протечёт в следующий. У нас в day29 будет та же ловушка (`OllamaLocal` — mutable data class?): при добавлении per-request параметров нужна копия `.copy(numCtx = req.maxCtx)`, не мутация.

Обрати внимание: `max-ctx` тут — потолок, применяется к каждому запросу как `num_ctx=cfg.MaxCtx`. Это НЕ обрезает вход, а задаёт `context_length` модели. Умное усечение истории (при слишком длинном диалоге) swanden оставил на потом — честно написано в «пределах».

### 1.8 Порядок middleware: auth → rate limit → semaphore

```go
func (s *server) auth(next http.HandlerFunc) http.HandlerFunc {
    return func(w http.ResponseWriter, r *http.Request) {
        // 1. Auth
        if s.cfg.APIKey != "" {
            got := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
            if strings.TrimSpace(got) != s.cfg.APIKey {
                writeOAIError(w, 401, "invalid_api_key", "...")
                return
            }
        }
        // 2. Rate limit (после auth: чтобы неавторизованные не жгли лимит легитимных)
        if !s.lim.allow(clientIP(r)) {
            w.Header().Set("Retry-After", "60")
            writeOAIError(w, 429, "rate_limit_exceeded", "...")
            return
        }
        next(w, r)  // 3. Handler → semaphore внутри handleChat
    }
}
```

**Прямой урок**: комментарий swanden — «rate limit после auth, чтобы неавторизованные не жгли лимит легитимных» — это неочевидная, но правильная последовательность. Иначе левый скрипт со случайным IP забьёт бакет легитимного клиента.

### 1.9 Демо-режим `-serve30-demo` — одна команда для видео

Отдельная функция `runServe30Demo(ctx, llm, cfg)`, которая:
1. Поднимает сервис в фоне (`go func() { errCh <- httpSrv.ListenAndServe() }()`)
2. Ждёт готовности через опрос `/health` (`waitReady(base, 3s)`)
3. Прогоняет 6 проверок последовательно (health / chat без ключа → 401 / chat с ключом → 200 / models / stream → 400 / burst → 429)
4. Печатает отчёт с рамочками
5. **Оставляет сервис живым** (`select { case <-ctx.Done(): return; case err := <-errCh: return err }`)

Идея — снять видео одной командой без ручных curl'ов между кадрами. Данил может сделать то же самое: `--demo` flag, и `main()` сам делает все curl'ы к своему сервису и печатает результат — а потом остаётся ждать Ctrl+C, чтобы показать доступ по сети с телефона.

**Прямой урок**: сделать `--demo` в плане Данила. Все проверки задания (сеть + стабильность + rate limit + max context) в одной команде.

### 1.10 Флаги через `flag.Parse` + `SERVICE_API_KEY` env fallback

```go
serveKey := flag.String("api-key", os.Getenv("SERVICE_API_KEY"), "...")
```

Ключ можно задать флагом (для видео — виден в кадре) или через env (для реального деплоя — не логируется). У Данила в плане — `X-API-Key` header, ключ в env. Хорошо бы поддержать оба варианта запуска (`-key ai2026` для видео, `SERVICE_API_KEY=...` для скрытого).

---

## 2. pechatkin (Python FastAPI, публичный HTTPS) — сессии + токен + реальный VPS

Репо: `vapechatkin/ai_ac/tree/main/d30`. README — 55 строк, `server.py` — 259 строк, `deploy.sh` — 45 строк, `Dockerfile` — 7 строк.

Публичный URL: **`https://89.169.140.120.sslip.io/ai`** (реально работает; токен в README открытым текстом — специально для проверки: `c068430753f3e77f6a079b5df87e115a4701887c70d8b72363f9fa53a93fde8a`).

### 2.1 Уникальное: sslip.io + Caddy = бесплатный HTTPS без домена

**`sslip.io` — DNS-сервис, который резолвит `<IP>.sslip.io` → `<IP>`.** Caddy автоматически получает Let's Encrypt сертификат для `89.169.140.120.sslip.io` — HTTPS без покупки домена. Идеально для day30-демо: реальный `https://`, без «а купите домен».

**Прямой урок для Данила**: если хочется публичный HTTPS без покупки домена — `<VPS_IP>.sslip.io` + Caddy автопул + `reverse_proxy 127.0.0.1:8080`. VPS у Данила уже есть (89.124.67.135), Caddy можно поставить рядом с nginx (или заменить). Единственная тонкость — порт 443 у Данила занят amnezia-xray (см. память `project_vps_port_443.md`), значит либо на 8443, либо на другом IP, либо на sitebystro.ru — но идея sslip.io работает только с чистым IP-hostname.

### 2.2 API-формат: свой `/chat` с сессиями

```python
class ChatRequest(BaseModel):
    message: str
    session_id: Optional[str] = None

class ChatResponse(BaseModel):
    reply: str
    session_id: str
    history_len: int
    response_time: float
    books_found: int
```

Не OpenAI-compat. Взамен — **сессии на сервере** (`sessions: dict[str, list] = {}` in-memory). Первый запрос — новый `session_id` (uuid4), последующие — тот же `session_id`, чтобы модель помнила историю. `MAX_HISTORY = 20` сообщений в сессии; при переполнении — `history[-MAX_HISTORY:]`.

Плюс: клиент не таскает историю в каждом запросе (экономит трафик, чище протокол). Минус: сервер держит состояние (при рестарте — всё пропадёт; при мультиинстансе — нужен shared storage).

**Прямой урок**: подумать — Данилу нужны сессии? В плане нет. Но у нас day27 — REPL с историей, значит логика уже есть, только перенести. С другой стороны, для стрессового `curl` в видео без сессий проще. Совет: **клиент шлёт полную историю** (как в OpenAI-формате `messages: []`), сервер stateless — так и до OpenAI-compat один шаг.

### 2.3 Bearer + опциональная auth

```python
API_TOKEN = os.getenv("AI_TOKEN")   # если не задан — auth отключена (dev-режим)

def check_auth(credentials: HTTPAuthorizationCredentials):
    if not API_TOKEN:
        return
    if not credentials or credentials.credentials != API_TOKEN:
        raise HTTPException(status_code=401, detail="Invalid or missing token")
```

Ровно как у swanden: пустой ключ = открытый режим (dev). У Данила план — жёсткий `X-API-Key`; лучше сделать опциональным (пустой → warning в лог + доступ без auth), чтобы можно было быстро локально ткнуть без headers.

### 2.4 Rate limit — sliding window через `defaultdict(list)`

```python
rate_store: dict[str, list] = defaultdict(list)

def check_rate_limit(ip: str) -> bool:
    now = time.time()
    rate_store[ip] = [t for t in rate_store[ip] if now - t < RATE_WINDOW]
    if len(rate_store[ip]) >= RATE_LIMIT:
        return False
    rate_store[ip].append(now)
    return True
```

Проще token bucket, но чуть менее аккуратно на burst: скользящее окно 60 сек, лимит 10 запросов. Разница с swanden token bucket: при `rpm=10` swanden позволит 10 запросов подряд (полный бакет) + пополнение 1/6 сек, а pechatkin — 10 запросов в любые 60 сек (более строго, никакого burst allowance).

**Прямой урок**: token bucket (swanden) — более «дружелюбный» к всплескам, sliding window (pechatkin/dpmn/mrsmith113) — строже. Данил в плане говорит «token bucket» — swanden паттерн.

### 2.5 Warmup на старте (лечит first-request latency)

```python
@app.on_event("startup")
def warmup():
    try:
        requests.post(f"{OLLAMA_URL}/api/embed",
                      json={"model": EMBED_MODEL, "input": ["warmup"]}, timeout=60)
        requests.post(f"{OLLAMA_URL}/api/chat", json={
            "model": MODEL, "messages": [{"role": "user", "content": "hi"}],
            "stream": False, "options": {"num_predict": 1},
        }, timeout=120)
    except Exception:
        pass
```

Прогрев модели при старте сервиса — первый пользовательский запрос не будет ждать 30-40 сек cold-load. **Точно как в day29 `ensure_loaded` sergio/soziev** — только для сервиса. Стоит взять.

### 2.6 Timeout на генерацию + возврат `504`

```python
try:
    resp = requests.post(f"{OLLAMA_URL}/api/chat", json={...}, timeout=180)
    resp.raise_for_status()
except requests.Timeout:
    history.pop()
    raise HTTPException(status_code=504, detail="Model timeout (>180s)")
```

Обрати внимание на **`history.pop()`** — если генерация не удалась, из истории сессии выбрасывается только что добавленный user-message (иначе на следующем запросе история будет с «висящим» user без ответа). Мелочь, но правильная.

### 2.7 systemd-сервис через deploy.sh

45 строк bash: `apt`-инсталл Ollama, `ollama pull qwen2.5:3b`, `pip install`, генерация `/etc/systemd/system/ai-service.service` (WorkingDirectory / ExecStart / Restart=always), `systemctl enable+start`. Одноразовый скрипт для VPS — можно копипастнуть.

**Прямой урок**: у Данила уже есть шаблоны systemd (twilights-tech, fuel-map-api), не надо изобретать; просто скопировать `.service` файл, поменять пути.

### 2.8 Метрики в ответе

```python
return ChatResponse(
    reply=reply,
    session_id=sid,
    history_len=len(history),
    response_time=elapsed,
    books_found=len(books),
)
```

Каждый ответ содержит **response_time**, **history_len**, **books_found** (сколько чанков нашлось в RAG). Наглядно для видео — сразу видно, что RAG сработал, что сессия жива, сколько заняло. У Данила план — `/stats` отдельным endpoint. Можно и то и то (в каждом ответе — короткие метрики этого запроса, `/stats` — общая сводка).

---

## 3. dpmn (Python Flask + Docker Compose + llama.cpp) — VPS 2GB под нагрузкой

Репо: `dpmn/ai-advent-challenge/tree/main/week-06/day-30`. README — 99 строк с подробным сценарием проверки; `app.py` — 175 строк; `docker-compose.yml` — llm + app.

Публичный URL был: **`http://2.27.253.165:8080`** (после видео сервис остановлен `docker compose down`).

### 3.1 Двухконтейнерная архитектура: llama.cpp:server + Flask

```yaml
services:
  llm:
    image: ghcr.io/ggml-org/llama.cpp:server
    command: >
      -hf Qwen/Qwen2.5-1.5B-Instruct-GGUF:Q4_K_M
      --host 0.0.0.0 --port 8081
      --ctx-size 2048 --threads 1 --parallel 1
    # порт наружу не торчит — только внутренняя сеть compose
    healthcheck:
      test: ["CMD", "curl", "-fsS", "http://127.0.0.1:8081/health"]
  app:
    build: ./app
    ports: ["8080:8080"]
    environment: { LLM_URL: http://llm:8081 }
    depends_on: { llm: { condition: service_healthy } }
```

**llama.cpp:server** вместо Ollama — прямая работа с GGUF, --hf-download при старте, ниже overhead. `--parallel 1` — один слот генерации (стабильность на 1 vCPU). Порт LLM внутри compose network, наружу торчит только Flask 8080 (безопасность: модель не даст напрямую задать любые параметры).

**Прямой урок для Данила**: если использовать llama.cpp server напрямую — можно **не нужен Ollama-сервис на VPS** (одним контейнером тянет модель, отдаёт `/v1/chat/completions`). Но: у Данила план опирается на Ollama (уже day26-29 через него). Не менять. Просто знать альтернативу.

### 3.2 Swap 2GB — обязательное условие для VPS 2GB RAM

Из README:
> Подготовка сервера: добавлен swap 2GB (файл + fstab) — без него 2GB RAM впритык.

Классический трюк для дешёвых VDS. Модель qwen2.5-1.5b-Q4_K_M весит ~1GB, llama.cpp резервирует ~500MB на KV-cache при ctx=2048, Flask+Python — ещё 100MB. Итого 1.6GB на 2GB — работает, но малейший скачок → OOM. Swap 2GB (файл `/swapfile` + `fstab`) → безопасность.

Команды (стандарт):
```bash
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

**Прямой урок для Данила**: у нас VPS с уже настроенным swap 4GB? Проверить (`swapon --show`). Инцидент с OOM common-бота (`project_common_bot_systemd_todo.md`) — из-за отсутствия swap. Если для demo нужен маленький VPS без swap — сначала swap.

### 3.3 Stress test: 12 параллельных → 429 + timeout, сервис жив

Из README дословно:
> Нагрузка 12 параллельных запросов: часть обработана по очереди, лишние отбиты 429 (rate limit), хвост очереди сброшен 503 по таймауту 120 с — сервис не падает, `/health` после нагрузки ok.

Скрипт из readme (взять для нашего теста):
```bash
for i in $(seq 1 12); do curl -s -o /dev/null -w "%{http_code}\n" --max-time 5 \
  -X POST http://<vps>:8080/api/excuse \
  -H "Content-Type: application/json" \
  -d '{"role":"аналитик","genre":"драма"}' & done; wait
```

Проверяет: (а) 429 срабатывает как ожидается, (б) сервис не падает, (в) `/health` живёт после нагрузки. Идеальный test-case для видео.

**Прямой урок**: у Данила план — `test_stability.py` (не написан). Скрипт выше в bash — 3 строки, ничего не тестирующее, кроме stability под burst. Стоит скопировать в `README.md` как «команда для видео».

### 3.4 few-shot как отдельные сообщения, не в system

```python
# Few-shot отдельными репликами: 0.5B-модель копирует структуру текстового
# примера из system-промпта («Запрос:/Ответ:»), а диалоговый пример — нет.
FEW_SHOT = [
    {"role": "user", "content": "..."},
    {"role": "assistant", "content": "..."},
]
messages = [{"role": "system", ...}] + FEW_SHOT + [{"role": "user", ...}]
```

Комментарий важный: если засунуть `Пример:\nQ: ...\nA: ...` в system-промпт, маленькая модель копирует формат буквально (`Q:/A:`), портя ответ. А отдельные user/assistant сообщения она воспринимает как «была история диалога» и продолжает так же. У Данила qwen2.5:7b — модель побольше, менее подвержена, но принцип полезен.

### 3.5 `repeat_penalty=1.3` для маленьких моделей

```python
"repeat_penalty": 1.3,
"repeat_last_n": 128,
```

Без `repeat_penalty` 0.5B-1.5B зацикливаются на повторах одной фразы. Для 7B обычно не нужно, но если пробовать fallback на маленькую модель (для быстрых ответов) — знать про этот параметр. У нас не в плане, но заметка на память.

### 3.6 Обрезка длины полей на входе

```python
MAX_FIELD_LEN = 120        # обрезка полей формы
MAX_MSG_LEN = 400          # обрезка сообщения чата
MAX_HISTORY = 6            # сколько последних реплик чата уходит в модель

role = str(data.get("role", "IT-специалист"))[:MAX_FIELD_LEN]
```

Простая защита от «отправить в модель мегабайт текста». У Данила план — `max_context` через `num_ctx`. Это защита на выходе (модель обрежет). А тут — на входе (сервер не пропустит). Оба нужны: `num_ctx` — от честных «длинных» диалогов, вход-обрезка — от намеренных атак.

**Прямой урок**: добавить `MAX_INPUT_LEN` (например 8000 символов на message) в handler `/v1/chat` — простая защита, 2 строки.

---

## 4. mrsmith113 (Python FastAPI + SSE) — единственный со стримингом

Репо: `mrsmith113/deepseek-chat/tree/master/task30`. `server.py` — 173 строки, `test_stability.py` — 163 строки, `run.sh` — 25 строк.

### 4.1 SSE-streaming endpoint

```python
@app.post("/chat/stream")
def chat_stream(req: ChatRequest, request: Request):
    ...
    def generate():
        with _http.post(f"{OLLAMA_URL}/api/chat", json={...,"stream": True,...}, stream=True) as r:
            for line in r.iter_lines():
                if line:
                    chunk = json.loads(line)
                    token = chunk.get("message", {}).get("content", "")
                    if token:
                        yield f"data: {json.dumps({'token': token})}\n\n"
                    if chunk.get("done"):
                        yield f"data: {json.dumps({'done': True, 'tokens': chunk.get('eval_count', 0)})}\n\n"
    return StreamingResponse(generate(), media_type="text/event-stream")
```

Ollama `/api/chat` со `stream: True` возвращает NDJSON (строки JSON, разделённые `\n`). mrsmith113 читает построчно, пересобирает в SSE-формат (`data: {...}\n\n`) и стримит клиенту. Клиент (браузер) читает `EventSource`.

**Прямой урок**: если Данил передумает и захочет стриминг — Java-аналог: response headers `Content-Type: text/event-stream`, `OutputStream out = exchange.getResponseBody()`, читать `HttpClient.send(...BodyHandlers.ofInputStream())` и по строкам писать `out.write(("data: " + line + "\n\n").getBytes())`. Не сильно сложнее, но: у Данила в плане stream=no. swanden честно отвечает 400.

### 4.2 test_stability.py — отдельный скрипт-нагрузчик

```python
QUESTIONS = ["Что такое нотификация ФСБ?", "Как рассчитать таможенную пошлину...", ...]

def test_health(url): ...
def test_info(url): ...           # /info endpoint с лимитами
def test_sequential(url): ...     # 5 вопросов подряд, замер latency
def test_concurrent(url): ...     # threading, 5 параллельных
def test_rate_limit(url): ...     # burst до 429
```

Замеряет: total, mean, median, min/max latency; count 200/429/error; tokens. Печатает таблицу. Для видео — прогнал `python test_stability.py http://<ip>:8030` и в кадре все проверки задания за 30 секунд.

**Прямой урок**: точно такое написать под наш Kotlin-сервис (можно на Python — не привязано к языку). Задание прямо просит «проверьте: доступ по сети, стабильность при нескольких запросах, базовые ограничения». Три теста, один файл, один запуск.

### 4.3 `_http.trust_env = False` — отключение системного прокси

```python
_http = requests.Session()
_http.trust_env = False
# Отключаем прокси (v2rayN в WSL2)
```

Мелочь, но важная в WSL2/через VPN — иначе запросы уходят через прокси, ollama на localhost не отвечает. Для Windows-разработки Данила (за AmneziaVPN) — знать этот трюк на случай проблем «клиент не может достучаться до сервиса на localhost».

### 4.4 БЕЗ auth — задание не закрыто по «приватности»

У mrsmith113 нет ни ключа, ни basic-auth. Сервис открыт для всех. Задание требует «приватный сервис» — Murad в чате прямо сказал «+ авторизация по ключу». mrsmith113 — контр-пример: **работает, но небезопасно**. Стоит держать в голове как «а вот так делать НЕ надо».

---

## 5. avalanche — Telegram-бот на облачном DeepSeek (задание НЕ закрыто)

Репо: `1Avalanche/SmartReminder/tree/week6-day5`. Ветка называется `week6-day5`, но реализация — Telegram-бот, использующий `ModelConfig.DEEPSEEK` (облачный!). Из `smartagent/documentation/telegram.md`:

> Жёстко задан `ModelConfig.DEEPSEEK`. Модель нельзя переключить через Telegram — нет REPL-команд.

**Задание Day 30 требует локальную LLM.** Telegram-бот с облачным DeepSeek — это не «локальный сервис по сети», а «интерфейс к облаку». Формально avalanche задание не закрыла. Идея интересная (Telegram = универсальный интерфейс без своего HTTP-endpoint), но неверная реализация под задание.

Взять нечего, но контр-пример: у Данила план — HTTP-сервис + локальная модель. Правильно. Не соблазняться заменой «сервис по сети» на «бот».

---

## 6. karpiuk, nikbrik — ложные срабатывания, не day30

- **karpiuk** — `ivstka95/agent-cli/pull/18` — это PR feature/day27-local-llm. День 27 (локальная LLM в его CLI-агенте), НЕ 30. В комментариях день не проставлен, но по коду и заголовку PR — day27. Пропускаем.
- **nikbrik** — `nikbrik/coding_writer/tree/8fa07f18...` — свежий репо, но docs/ содержит только `day21-day26` (RAG + local LLM demo), day30-relevant кода нет. Возможно, сдал не то, что нужно, или ссылка не на тот коммит. Пропускаем.

---

## Сводка: чек-лист для нашего day30 (`week6/day5`)

### Обязательно взять (у нас в плане уже есть)

1. **`HttpServer` stdlib + 4 endpoint** — план Данила один-в-один совпадает с swanden. Ноль сторонних зависимостей (не Ktor, не SparkJava — вообще stdlib). Java-эквивалент: `HttpServer.create(new InetSocketAddress(8080), 0)`, `.createContext("/v1/chat", handler)`, `.setExecutor(Executors.newFixedThreadPool(K))`, `.start()`. K = 2-4 потоков.
2. **Bearer-auth через `X-API-Key`** (Данил в плане) или `Authorization: Bearer` (swanden/pechatkin) — оба ок; **опциональный ключ** (пустой env = warn + open). Это удобнее для локальной отладки.
3. **Token bucket rate limiter на IP** — 50 строк stdlib, `ConcurrentHashMap<String, TokenBucket>`, `synchronized` внутри `allow`. Именно как у swanden — не sliding window (менее дружелюбен к burst).
4. **Клиентский IP через `X-Forwarded-For`** — если Данил ставит nginx/Caddy перед сервисом, забыть про это = rate limit не работает. Хелпер `resolveClientIp(exchange)`.
5. **Семафор конкурентности (K=1-2)** отдельно от rate limit — `Semaphore sem = new Semaphore(K)`; `sem.tryAcquire(timeoutMs, MS)` перед вызовом Ollama, `sem.release()` в `finally`. Порядок middleware: **auth → rate limit → semaphore**.
6. **max_context через `num_ctx`** — у нас уже есть с day29. **Копия `OllamaLocal.copy(numCtx = ...)`** на per-request, НЕ мутация. Иначе параметры одного клиента протекут в другой.
7. **Честный 400 на `stream:true`** — не молчать, вернуть `{"error":{"type":"unsupported","message":"stream=true не поддерживается"}}`. В плане Данила — no stream, так и делать.
8. **`/health` без ключа** — liveness + проверка что Ollama жива (`GET http://localhost:11434/api/tags`). Всё, что нужно для мониторинга/systemd healthcheck.
9. **`--demo`-режим одной командой** (swanden): поднять сервис в отдельном потоке, дождаться `/health`, прогнать 6 curl'ов (все проверки задания), напечатать отчёт, оставить работать. Идеально для видео — один запуск, весь день 30 на экране.
10. **Warmup на старте** (pechatkin) — при `main()` до `.start()` дёрнуть Ollama `/api/chat {num_predict:1}` чтобы модель загрузилась. Первый пользовательский запрос не будет 30-40 сек cold-load.
11. **Timeout на генерацию + 504** (pechatkin) — если Ollama не ответила за 180 сек, отдать `504 Gateway Timeout`, не висеть до бесконечности.
12. **Обрезка длины входа** (dpmn) — `MAX_INPUT_LEN` на body/message, простая защита от «отправить мегабайт». 2 строки.
13. **stress-test bash-скрипт** (dpmn) — `for i in $(seq 1 12); do curl -s ... & done; wait` — 3 строки в README как «команда для видео», проверяет 429 + `/health` после burst. Плюс отдельный `test_stability.py` (mrsmith113) — если хочется таблицу latency percentiles.

### Взять с осторожностью

14. **OpenAI-совместимый `/v1/chat/completions`** (swanden) vs свой `/v1/chat` (план Данила). OpenAI-compat даёт бонус — любой OpenAI SDK-клиент подключается. **Компромисс**: `/v1/chat` (свой формат, `{answer, sources[], quotes[]}`) — под наш RAG (нужны `sources`/`quotes`); плюс дополнительный `/v1/chat/completions` (OpenAI-compat, `content` — склеенный ответ строкой) — под внешних клиентов. Но: два handler'а — плюс код. Решение: **начать с одного `/v1/chat` (свой формат)**, OpenAI-compat вынести в TODO/следующий день.
15. **Сессии на сервере** (pechatkin) — `sessions: dict[str, list]`. Проще: **клиент шлёт полную историю** в `messages: []` (как OpenAI, mrsmith113, swanden). Сервер stateless. Для нашего сценария (день 27 REPL — клиент помнит) — не надо на сервере. Наш RAG-CLI останется stateful на клиенте.
16. **HTTPS через Caddy + sslip.io** (pechatkin) — если хочется публичный HTTPS без домена. Но у Данила порт 443 занят amnezia-xray (см. память). Варианты: (а) на порту 8443 (тогда HTTPS от Caddy, `89.124.67.135.sslip.io:8443` — но sslip не выдаст сертификат на нестандартный порт напрямую, нужен ACME DNS-01 challenge, не HTTP-01); (б) hostname `ai.sitebystro.ru` через reg.ru DNS (наш домен уже есть); (в) LAN-only (без публичного HTTPS) + для видео показать доступ с телефона по LAN. **Рекомендую (в)** — проще, задание не требует HTTPS.
17. **systemd-сервис** — у Данила уже есть шаблоны (twilights-tech, fuel-map-api). Скопировать. Restart=always, WantedBy=multi-user.target.

### НЕ брать

18. **SSE streaming** (mrsmith113) — план Данила без стрима. swanden — 400 на `stream:true`. Не тратить время.
19. **Docker compose с llama.cpp:server** (dpmn) — переход с Ollama на llama.cpp:server ломает day26-29 совместимость. Оставить Ollama.
20. **Sliding window rate limit** (pechatkin, dpmn, mrsmith113) — строже token bucket, менее дружелюбен к burst. Данил в плане уже выбрал token bucket — swanden реализация.
21. **Модель qwen2.5:1.5b-Q4_K_M** (dpmn) — только если VPS слабый. У Данила demo — на его домашнем железе (RTX 3060 6GB) с qwen2.5:7b (наш day26+). Не менять модель ради day30.
22. **Telegram-бот как «сервис по сети»** (avalanche) — некорректная интерпретация задания. HTTP-сервис по сети — это HTTP-сервис.
23. **Каскадная обработка (`few-shot` как отдельные сообщения, `repeat_penalty=1.3`)** (dpmn) — только для маленьких моделей (0.5B-1.5B). qwen2.5:7b — не нужно.

### Возможные подводные камни

- **Порт 443 у Данила занят amnezia-xray** — новые HTTPS-сервисы только на 8443 (см. `project_vps_port_443.md`). Для day30-демо: LAN-only (без публичного HTTPS) или `<ip>:8443` через Caddy на нестандартном порту.
- **Ollama на Windows слушает `127.0.0.1:11434` по умолчанию** — если сервис Данила запускается на VPS, а Ollama на его Windows-машине, нужно `OLLAMA_HOST=0.0.0.0:11434` + firewall + VPN/туннель. **Проще**: запускать и сервис, и Ollama на одной машине (Windows Данила), для «доступа по сети» показать с телефона по LAN. Или всё разом на VPS (но у VPS нет GPU → CPU inference qwen2.5:7b очень медленно).
- **AmneziaVPN на компе Данила** — если клиентский curl из его же Windows идёт «наружу-через-VPN», он не увидит `localhost:8080` соседа. Проверять доступ с телефона в той же WiFi-сети (VPN только на компьютере).
- **`OllamaLocal` mutable → протечка параметров между запросами** (swanden урок 1.7) — при добавлении per-request `numCtx`/`temperature` в `/v1/chat`, обязательно `.copy(numCtx = req.maxCtx)`, НЕ мутация singleton'а.
- **Rate limit после auth, не до** (swanden урок 1.8) — иначе неавторизованные IP забьют лимит легитимного. Порядок: auth → rate limit → semaphore.

---

## Ссылки

- **swanden**: https://github.com/swanden/ai-challenge/tree/main/week-6/task-30 (Go stdlib, эталон архитектуры, OpenAI-compat + Bearer + token bucket + semaphore)
- **pechatkin**: https://github.com/vapechatkin/ai_ac/tree/main/d30 (Python FastAPI, публичный HTTPS через Caddy + sslip.io, сессии на сервере)
- **dpmn**: https://github.com/dpmn/ai-advent-challenge/tree/main/week-06/day-30 (Python Flask + Docker Compose + llama.cpp:server, VPS 1vCPU/2GB + swap, stress-test bash)
- **mrsmith113**: https://github.com/mrsmith113/deepseek-chat/tree/master/task30 (Python FastAPI + SSE streaming + test_stability.py, БЕЗ auth — контр-пример)
- avalanche: https://github.com/1Avalanche/SmartReminder/tree/week6-day5 (Kotlin Telegram-бот на облачном DeepSeek — задание НЕ закрыто, локальная модель не используется)
- karpiuk: https://github.com/ivstka95/agent-cli/pull/18 (Kotlin, но PR = day27, не day30)
- nikbrik: https://github.com/nikbrik/coding_writer (Go, но docs — days 21-26, не day30)

**Не сдали day30 (застряли на day29):**
- sergio (Ssh sh) — `week6/day-4-total-29`
- Николай (ShirobokovNE) — `day29`
- kaa (Круглов Андрей) — `day29` (C#)
- soziev (Denis Soziev) — `deepseek-cli-day-29`
- yavits (Evgeny Yavits) — `day29`

Значит для day30 у нас нет эталонного Kotlin-разбора. Ближайший образец — **swanden на Go stdlib**: архитектура 1-в-1 совпадает с планом Данила, порт на JDK `HttpServer` прямолинейный (та же логика: ServeMux → HttpContext, tokenBucket → synchronized HashMap, chan struct{} → Semaphore).
