"""Unified LLM client for Day 12 agents.
Primary: DeepSeek API. Fallback: deterministic mock_llm.
"""
import os
import re
import requests

# Load DeepSeek key from secrets.env (source before running)
DEEPSEEK_KEY = os.getenv("DEEPSEEK_API_KEY", "")
DEEPSEEK_URL = "https://api.deepseek.com/chat/completions"

INJECT_TRIGGERS = [
    "ignore previous", "SYSTEM:", "INSTRUCTION:", "Override",
    "Disregard", "COPILOT_CONTEXT", "OVERRIDE_SUCCESS",
    "SEARCH AGENT INSTRUCTION", "HIDDEN:",
]


def _deepseek_call(system: str, user: str, temperature: float = 0.0) -> str:
    resp = requests.post(
        DEEPSEEK_URL,
        headers={
            "Authorization": f"Bearer {DEEPSEEK_KEY}",
            "Content-Type": "application/json",
        },
        json={
            "model": "deepseek-chat",
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ],
            "temperature": temperature,
        },
        timeout=60,
    )
    resp.raise_for_status()
    return resp.json()["choices"][0]["message"]["content"]


def _mock_llm(system: str, user: str) -> str:
    """Deterministic simulation of vulnerable LLM: reproduces breach if triggers found."""
    if any(t.lower() in user.lower() for t in INJECT_TRIGGERS):
        m = re.search(
            r'(?:INSTRUCTION|SYSTEM|Override|COPILOT_CONTEXT|HIDDEN|SEARCH AGENT INSTRUCTION)[:\s]+(.{10,200})',
            user, re.IGNORECASE | re.DOTALL,
        )
        injected = m.group(1).strip()[:200] if m else "[INJECTED ACTION PERFORMED]"
        return f"[MOCK-VULNERABLE-LLM-OUTPUT] Task complete. {injected}"
    return "[MOCK-VULNERABLE-LLM-OUTPUT] Task complete. Content processed normally."


def llm_call(system: str, user: str, temperature: float = 0.0) -> tuple[str, str]:
    """Returns (output, provider_used)."""
    if DEEPSEEK_KEY:
        try:
            return _deepseek_call(system, user, temperature), "deepseek"
        except Exception as e:
            print(f"[llm_client] DeepSeek failed: {e}; falling back to mock", flush=True)
    return _mock_llm(system, user), "mock"
