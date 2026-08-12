"""LLM client that goes through our Gateway (Day 13) — never bypasses it."""
import requests
import json

GATEWAY_URL = "http://127.0.0.1:8100/v1/chat/completions"
MODEL = "deepseek-chat"


def call(system: str, user: str, temperature: float = 0.3, max_tokens: int = 1200) -> dict:
    """Returns dict with keys:
    - status: "ok" | "gateway_blocked" | "error"
    - content: str (LLM response text, empty if blocked)
    - gateway_hits: list of hit types (from input_guard, if any)
    - gateway_error: str (if status != ok)
    - usage: dict (tokens_in, tokens_out) if available
    - cost_usd: float if available
    """
    payload = {
        "model": MODEL,
        "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": user},
        ],
        "temperature": temperature,
        "max_tokens": max_tokens,
    }
    try:
        r = requests.post(GATEWAY_URL, json=payload, timeout=90)
    except Exception as e:
        return {"status": "error", "content": "", "gateway_hits": [],
                "gateway_error": f"transport: {e}", "usage": {}, "cost_usd": 0.0}

    if r.status_code == 403:
        data = r.json()
        hits = [h.get("type") for h in data.get("hits", [])]
        return {"status": "gateway_blocked", "content": "", "gateway_hits": hits,
                "gateway_error": data.get("error", ""), "usage": {}, "cost_usd": 0.0}
    if r.status_code != 200:
        return {"status": "error", "content": "", "gateway_hits": [],
                "gateway_error": f"http {r.status_code}: {r.text[:200]}",
                "usage": {}, "cost_usd": 0.0}

    data = r.json()
    content = data.get("choices", [{}])[0].get("message", {}).get("content", "")
    usage = data.get("usage", {})
    # X-Gateway-Warning header если input был замаскирован
    warning = r.headers.get("X-Gateway-Warning", "")
    hits = warning.replace("masked:", "").split(",") if warning.startswith("masked:") else []
    return {"status": "ok", "content": content, "gateway_hits": hits,
            "gateway_error": "", "usage": usage, "cost_usd": 0.0}


if __name__ == "__main__":
    import sys
    r = call("You are terse.", sys.argv[1] if len(sys.argv) > 1 else "Hello")
    print(json.dumps(r, indent=2, ensure_ascii=False))
