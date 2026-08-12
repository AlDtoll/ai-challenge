"""FastAPI LLM Gateway — proxies to DeepSeek with input/output guards, rate limit, audit."""
import os
import time
import httpx
from fastapi import FastAPI, Request, HTTPException
from fastapi.responses import JSONResponse
from slowapi import Limiter, _rate_limit_exceeded_handler
from slowapi.util import get_remote_address
from slowapi.errors import RateLimitExceeded

from guards.input_guard import InputGuard
from guards.output_guard import OutputGuard
from guards.patterns import PATTERNS
from audit import AuditLogger
from cost import calculate

app = FastAPI(title="LLM Gateway", version="1.0")

# Rate limit: 60 req/min per IP (in-memory)
limiter = Limiter(key_func=get_remote_address)
app.state.limiter = limiter
app.add_exception_handler(RateLimitExceeded, _rate_limit_exceeded_handler)

# Guards & infra
GUARD_MODE = os.getenv("GATEWAY_GUARD_MODE", "block")  # "block" | "mask"
input_guard = InputGuard(mode=GUARD_MODE)
output_guard = OutputGuard()
audit = AuditLogger()

DEEPSEEK_KEY = os.getenv("DEEPSEEK_API_KEY", "")
DEEPSEEK_URL = "https://api.deepseek.com/chat/completions"


@app.get("/health")
async def health():
    return {"status": "ok", "model": "deepseek-chat", "guard_mode": GUARD_MODE, "key_loaded": bool(DEEPSEEK_KEY)}


@app.get("/audit/log")
async def audit_log(n: int = 50):
    return {"records": audit.recent(n)}


@app.get("/stats/cost")
async def stats_cost():
    return audit.cost_summary()


@app.post("/v1/chat/completions")
@limiter.limit("60/minute")
async def chat_completions(request: Request):
    body = await request.json()
    messages = body.get("messages", [])
    model = body.get("model", "deepseek-chat")
    ip = get_remote_address(request)
    t_start = time.time()

    # Извлекаем emails из input (для проверки email-leak в output guard)
    input_text = "\n".join(m.get("content", "") for m in messages if isinstance(m.get("content"), str))
    input_emails = set(PATTERNS["EMAIL"][0].findall(input_text))

    # Input guard
    ig = input_guard.check(messages)
    if ig.blocked:
        latency_ms = int((time.time() - t_start) * 1000)
        audit.log(ip, model, 0, 0, 0.0,
                  [h.pattern_name for h in ig.hits], [], True, latency_ms)
        return JSONResponse(
            status_code=403,
            content={
                "error": "input_guard_blocked",
                "hits": [{"type": h.pattern_name, "snippet": h.snippet} for h in ig.hits],
                "guidance": "Sensitive data detected in prompt. Remove secrets/PII before retry.",
            },
        )

    # Проксируем к DeepSeek
    proxied_body = dict(body)
    proxied_body["messages"] = ig.masked_messages
    proxied_body["model"] = model

    if not DEEPSEEK_KEY:
        latency_ms = int((time.time() - t_start) * 1000)
        audit.log(ip, model, 0, 0, 0.0, [h.pattern_name for h in ig.hits], [], True, latency_ms)
        raise HTTPException(500, "DEEPSEEK_API_KEY not configured")

    try:
        async with httpx.AsyncClient(timeout=60) as client:
            resp = await client.post(
                DEEPSEEK_URL,
                headers={"Authorization": f"Bearer {DEEPSEEK_KEY}", "Content-Type": "application/json"},
                json=proxied_body,
            )
            resp.raise_for_status()
            data = resp.json()
    except Exception as e:
        latency_ms = int((time.time() - t_start) * 1000)
        audit.log(ip, model, 0, 0, 0.0, [h.pattern_name for h in ig.hits], [], True, latency_ms)
        raise HTTPException(502, f"Upstream error: {e}")

    # Output guard
    response_text = ""
    if data.get("choices"):
        response_text = data["choices"][0].get("message", {}).get("content", "") or ""
    og = output_guard.check(response_text, input_emails=input_emails)

    usage = data.get("usage", {})
    tokens_in = usage.get("prompt_tokens", 0)
    tokens_out = usage.get("completion_tokens", 0)
    cost_usd = calculate(model, tokens_in, tokens_out)
    latency_ms = int((time.time() - t_start) * 1000)

    if og.blocked:
        audit.log(ip, model, tokens_in, tokens_out, cost_usd,
                  [h.pattern_name for h in ig.hits], og.hits, True, latency_ms)
        return JSONResponse(
            status_code=500,
            content={
                "error": "output_guard_blocked",
                "reason": og.reason,
                "cost_usd": cost_usd,  # пользователь уже потратил токены, даже при блоке
            },
        )

    audit.log(ip, model, tokens_in, tokens_out, cost_usd,
              [h.pattern_name for h in ig.hits], [], False, latency_ms)

    # Добавляем предупреждение в заголовок если input был замаскирован
    resp_headers = {}
    if ig.hits and ig.masked_messages != messages:
        resp_headers["X-Gateway-Warning"] = f"masked:{','.join(sorted({h.pattern_name for h in ig.hits}))}"

    return JSONResponse(content=data, headers=resp_headers)
