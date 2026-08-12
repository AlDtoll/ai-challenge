"""
AI Target Bot — FastAPI приложение.
Red Team target для AI Advent Day 15 challenge.

Эндпоинты:
  GET  /health                      — без авторизации
  POST /api/chat                    — Bearer auth
  DELETE /api/sessions/{sessionId} — Bearer auth + ownership
  GET  /metrics                     — Bearer auth
"""
import logging
import time
from collections import defaultdict, deque
from threading import Lock
from typing import Optional

from fastapi import FastAPI, HTTPException, Request, Depends
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, HTMLResponse
from pydantic import BaseModel, field_validator

from . import config
from . import sessions
from . import metrics as metrics_module
from . import guards
from . import llm

# Настройка логирования
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s — %(message)s"
)
logger = logging.getLogger(__name__)

# ─── Инициализация FastAPI ────────────────────────────────────────────────────
# openapi_url=None + docs_url=None + redoc_url=None — отключаем публичные docs.
# Схема эндпоинтов — information disclosure, помогает атакующим в разведке.
app = FastAPI(
    title="AI Target Bot",
    description="Red Team target for AI Advent Day 15 challenge",
    version="1.0.0",
    openapi_url=None,
    docs_url=None,
    redoc_url=None,
)

# Инициализируем БД при старте
sessions.init_db()
logger.info(f"AI Target Bot запущен на порту {config.PORT}")

# ─── Rate Limiter (in-memory sliding window) ──────────────────────────────────
_rate_lock = Lock()
# Для каждого токена хранится дек временных меток запросов
_request_timestamps: dict[str, deque] = defaultdict(lambda: deque())

# IP-based rate limiter — вторичный fail-safe cap (60 req/min per IP)
_ip_rate_lock = Lock()
_ip_requests: dict[str, list[float]] = {}

# Whitelist IP-адресов — лимит не применяется
# 127.0.0.1 — localhost (тесты), <VPS_IP> — наш VPS
_IP_RATE_LIMIT_WHITELIST = {"127.0.0.1", "<VPS_IP>"}
_IP_RATE_LIMIT_PER_MINUTE = 60


def check_rate_limit(token: str) -> bool:
    """
    Проверяет rate limit для Bearer токена.
    Скользящее окно 60 секунд, максимум RATE_LIMIT_PER_MINUTE запросов.
    Возвращает True если запрос разрешён, False если превышен лимит.
    """
    now = time.time()
    window_start = now - 60.0

    with _rate_lock:
        dq = _request_timestamps[token]
        # Удаляем старые метки за пределами окна
        while dq and dq[0] < window_start:
            dq.popleft()
        # Проверяем лимит
        if len(dq) >= config.RATE_LIMIT_PER_MINUTE:
            return False
        # Добавляем текущую метку
        dq.append(now)
        return True


def check_ip_rate_limit(request: Request) -> None:
    """
    Dependency: вторичный IP-based rate limit (60 req/min per client IP).
    Fail-safe cap против DoS через множество разных токенов.
    Whitelist: 127.0.0.1 (localhost/тесты), <VPS_IP> (VPS).
    Выбрасывает 429 при превышении, логирует IP в audit log.
    """
    client_ip = request.client.host if request.client else "unknown"

    # Whitelisted IP — пропускаем без счётчика
    if client_ip in _IP_RATE_LIMIT_WHITELIST:
        return

    now = time.time()
    window_start = now - 60.0

    with _ip_rate_lock:
        timestamps = _ip_requests.get(client_ip, [])
        # Удаляем метки за пределами скользящего окна
        timestamps = [t for t in timestamps if t >= window_start]
        if len(timestamps) >= _IP_RATE_LIMIT_PER_MINUTE:
            logger.warning(
                f"[AUDIT] IP rate limit exceeded: ip={client_ip} "
                f"requests={len(timestamps)} window=60s"
            )
            raise HTTPException(
                status_code=429,
                detail="rate_limit_ip"
            )
        timestamps.append(now)
        _ip_requests[client_ip] = timestamps


# ─── Авторизация ──────────────────────────────────────────────────────────────
def get_bearer_token(request: Request) -> str:
    """
    Извлекает Bearer токен из заголовка Authorization.
    Выбрасывает 401 если заголовок отсутствует или формат неверный.
    """
    auth_header = request.headers.get("Authorization", "")
    if not auth_header.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Missing or invalid Authorization header")
    token = auth_header[7:]  # убираем "Bearer "
    if not token:
        raise HTTPException(status_code=401, detail="Bearer token is empty")
    return token


def require_auth(request: Request) -> str:
    """
    Dependency: проверяет Bearer токен + rate limit.
    Возвращает токен при успехе, иначе выбрасывает 401/429.
    """
    token = get_bearer_token(request)
    valid_tokens = config.get_valid_tokens()

    if token not in valid_tokens:
        raise HTTPException(status_code=401, detail="Invalid Bearer token")

    if not check_rate_limit(token):
        raise HTTPException(
            status_code=429,
            detail="Rate limit exceeded: max 30 requests per minute per token"
        )

    return token


# ─── Схемы запросов/ответов ───────────────────────────────────────────────────
class ChatRequest(BaseModel):
    sessionId: str
    message: str

    @field_validator("sessionId")
    @classmethod
    def validate_session_id(cls, v: str) -> str:
        if not guards.validate_session_id(v):
            raise ValueError("Invalid sessionId: must be 1-64 chars [a-zA-Z0-9_-]")
        return v

    @field_validator("message")
    @classmethod
    def validate_message_length(cls, v: str) -> str:
        if len(v) == 0:
            raise ValueError("Message must not be empty")
        if len(v) > config.MAX_MESSAGE_LENGTH:
            raise ValueError(f"Message too long: max {config.MAX_MESSAGE_LENGTH} chars")
        return v


class ChatResponse(BaseModel):
    reply: str
    sessionId: str
    model: str


# ─── Generic error handler (предотвращает утечку stack trace) ─────────────────
@app.exception_handler(Exception)
async def generic_exception_handler(request: Request, exc: Exception):
    """Слой generic error handler: любые 500 → обезличенный JSON без traceback."""
    logger.error(f"Unhandled exception: {type(exc).__name__}: {exc}", exc_info=True)
    return JSONResponse(
        status_code=500,
        content={"status": 500, "error": "internal_error"}
    )


@app.exception_handler(HTTPException)
async def http_exception_handler(request: Request, exc: HTTPException):
    """Единый формат HTTP ошибок."""
    return JSONResponse(
        status_code=exc.status_code,
        content={"status": exc.status_code, "error": exc.detail}
    )


@app.exception_handler(RequestValidationError)
async def validation_exception_handler(request: Request, exc: RequestValidationError):
    """
    Pydantic validation errors → generic 400 без раскрытия внутренней структуры.
    По умолчанию FastAPI возвращает 422 с полным описанием (type, loc, input) —
    это information disclosure: атакующий видит формат ожидаемых полей.
    """
    fields_with_issues = [str(e['loc']) for e in exc.errors()]
    logger.warning(f"Validation error on fields: {fields_with_issues}")
    return JSONResponse(
        status_code=400,
        content={"status": 400, "error": "invalid_request"}
    )


# ─── Эндпоинты ───────────────────────────────────────────────────────────────
@app.get("/", response_class=HTMLResponse, include_in_schema=False)
async def root():
    """GET / — landing page для браузера (без авторизации)."""
    return """<!DOCTYPE html>
<html><head><meta charset="utf-8"><title>AI Target Bot — AITechCo</title>
<style>
body { font-family: -apple-system, sans-serif; max-width: 700px; margin: 40px auto; padding: 0 20px; color: #e2e8f0; background: #0f172a; }
h1 { color: #60a5fa; }
h2 { color: #94a3b8; border-bottom: 1px solid #334155; padding-bottom: 4px; margin-top: 24px; }
code { background: #1e293b; padding: 2px 6px; border-radius: 4px; color: #fbbf24; }
pre { background: #1e293b; padding: 12px; border-radius: 6px; overflow-x: auto; }
</style></head>
<body>
<h1>🤖 AI Target Bot</h1>
<p>AITechCo — AI-помощник для клиентов. Red Team target для AI Advent Day 15 challenge.</p>

<h2>Endpoints</h2>
<ul>
  <li><code>GET /health</code> — статус, модель, количество слоёв защиты (no auth)</li>
  <li><code>POST /api/chat</code> — основной чат (Bearer)</li>
  <li><code>DELETE /api/sessions/{id}</code> — удалить сессию (Bearer + ownership)</li>
  <li><code>GET /metrics</code> — счётчики (Bearer)</li>
</ul>

<h2>Пример</h2>
<pre>curl -H "Authorization: Bearer &lt;TOKEN&gt;" \\
     -H "Content-Type: application/json" \\
     -d '{"sessionId":"test","message":"hello"}' \\
     http://<VPS_IP>:8091/api/chat</pre>

<h2>Rate limits</h2>
<ul>
  <li>30 requests/min per Bearer token</li>
  <li>60 requests/min per client IP</li>
  <li>Max message length: 4000 chars</li>
</ul>

<h2>10 layers of defense</h2>
<p>prompt-injection-guard, indirect-content-sanitizer, gateway-input-guard, gateway-output-guard, hardened-system-prompt, workspace-secret-leak-guard, security-review-execution-loop, session-ownership-binding, metrics-auth, ip-based-rate-limit</p>

<p style="margin-top: 40px; color: #64748b; font-size: 13px;">Model: claude-haiku-4-5-20251001 · Version: v3 (post-adversarial round 2)</p>
</body></html>
"""


@app.get("/health")
async def health_check():
    """
    GET /health — без авторизации.
    Возвращает краткий статус сервиса без перечисления слоёв защиты.
    Полный список слоёв доступен только через /metrics (Bearer auth).
    """
    return {
        "status": "ok",
        "service": config.SERVICE_NAME,
        "model": config.MODEL_NAME,
        "layers_count": len(config.DEFENSE_LAYERS),
    }


@app.post("/api/chat", response_model=ChatResponse)
async def chat(
    request: ChatRequest,
    token: str = Depends(require_auth),
    _ip_check: None = Depends(check_ip_rate_limit),
):
    """
    POST /api/chat — Bearer auth обязателен.
    Обрабатывает сообщение через 9 слоёв защиты и возвращает ответ модели.
    """
    # Обновляем счётчик запросов
    metrics_module.increment_request()

    session_id = request.sessionId
    raw_message = request.message

    # ─── Слой 8: session-ownership-binding ────────────────────────────────────
    existing_owner = sessions.get_session_owner(session_id)
    if existing_owner is not None and existing_owner != token:
        logger.warning(
            f"Попытка доступа к сессии {session_id} чужим токеном "
            f"(владелец: {existing_owner[:8]}..., запрос от: {token[:8]}...)"
        )
        raise HTTPException(
            status_code=403,
            detail="Session belongs to a different token"
        )

    # ─── Слои 1, 2, 3: входные защиты ─────────────────────────────────────────
    ok, error_reason, sanitized_message = guards.apply_all_input_guards(raw_message)
    if not ok:
        raise HTTPException(status_code=400, detail=error_reason)

    # ─── Получаем историю сессии ───────────────────────────────────────────────
    history = sessions.get_session_history(session_id)

    # ─── Слои 4, 5, 7: вызов модели + выходные защиты ─────────────────────────
    # Слой 5 (hardened-system-prompt) встроен в llm.SYSTEM_PROMPT
    reply_text, prompt_tokens, completion_tokens = llm.chat_with_model(history, sanitized_message)

    if reply_text is None:
        raise HTTPException(status_code=503, detail="AI service temporarily unavailable")

    # Слои 4 + 7: санитизация выхода
    sanitized_reply, was_modified = guards.apply_all_output_guards(reply_text)
    if was_modified:
        logger.warning(f"Ответ модели был модифицирован выходной защитой для сессии {session_id}")

    # Дополнительная проверка output guard на утечку internal_key (base64/hex/обфускация)
    leaked, leak_pattern = guards.check_output_for_secrets(sanitized_reply, config.INTERNAL_API_KEY)
    if leaked:
        logger.warning(
            f"Output guard заблокировал утечку ключа (pattern={leak_pattern}) для сессии {session_id}"
        )
        sanitized_reply = "[Response blocked by security review: sensitive data detected]"
        was_modified = True

    # ─── Сохраняем turn в сессию ──────────────────────────────────────────────
    success = sessions.add_turn_to_session(session_id, token, sanitized_message, sanitized_reply)
    if not success:
        raise HTTPException(status_code=403, detail="session_owner_mismatch")

    return ChatResponse(
        reply=sanitized_reply,
        sessionId=session_id,
        model=config.MODEL_NAME,
    )


@app.delete("/api/sessions/{session_id}")
async def delete_session(
    session_id: str,
    token: str = Depends(require_auth),
    _ip_check: None = Depends(check_ip_rate_limit),
):
    """
    DELETE /api/sessions/{sessionId} — Bearer auth + ownership check.
    Только владелец сессии (первый использовавший Bearer) может удалить.
    """
    # Валидация session_id из пути
    if not guards.validate_session_id(session_id):
        raise HTTPException(status_code=400, detail="Invalid sessionId format")

    # Проверяем существование сессии
    existing_owner = sessions.get_session_owner(session_id)
    if existing_owner is None:
        raise HTTPException(status_code=404, detail="Session not found")

    if existing_owner != token:
        raise HTTPException(
            status_code=403,
            detail="You don't own this session"
        )

    # Атомарное удаление: WHERE session_id=? AND owner_token=? — устраняет TOCTOU
    deleted = sessions.delete_session(session_id, owner_token=token)
    if not deleted:
        raise HTTPException(status_code=404, detail="Session not found")

    return {"status": "ok", "sessionId": session_id, "message": "Session deleted"}


@app.get("/metrics")
async def get_metrics(
    token: str = Depends(require_auth),
    _ip_check: None = Depends(check_ip_rate_limit),
):
    """
    GET /metrics — Bearer auth обязателен (слой 9: metrics-auth).
    Возвращает статистику использования сервиса.
    """
    active = sessions.count_active_sessions()
    return metrics_module.get_metrics(active)
