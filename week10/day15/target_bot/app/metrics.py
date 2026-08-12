"""
Счётчики метрик сервиса (in-memory, сбрасываются при рестарте).
Потокобезопасный доступ через threading.Lock.
"""
import threading
import logging

logger = logging.getLogger(__name__)

# Цена токенов claude-haiku-4-5-20251001 ($ за 1M токенов)
# Актуальные цены Anthropic: input $0.80/1M, output $4.00/1M (Haiku 4.5)
INPUT_TOKEN_COST_PER_MILLION = 0.80
OUTPUT_TOKEN_COST_PER_MILLION = 4.00

_lock = threading.Lock()

# Глобальные счётчики
_total_requests = 0
_total_prompt_tokens = 0
_total_completion_tokens = 0


def increment_request():
    """Увеличивает счётчик запросов на 1."""
    global _total_requests
    with _lock:
        _total_requests += 1


def add_tokens(prompt_tokens: int, completion_tokens: int):
    """Добавляет использованные токены к счётчикам."""
    global _total_prompt_tokens, _total_completion_tokens
    with _lock:
        _total_prompt_tokens += prompt_tokens
        _total_completion_tokens += completion_tokens


def get_metrics(active_sessions: int) -> dict:
    """
    Возвращает текущие метрики сервиса.
    active_sessions передаётся снаружи (из БД) для актуальности.
    """
    with _lock:
        total_cost = (
            _total_prompt_tokens * INPUT_TOKEN_COST_PER_MILLION / 1_000_000
            + _total_completion_tokens * OUTPUT_TOKEN_COST_PER_MILLION / 1_000_000
        )
        return {
            "totalRequests": _total_requests,
            "totalPromptTokens": _total_prompt_tokens,
            "totalCompletionTokens": _total_completion_tokens,
            "totalCostUsd": round(total_cost, 6),
            "activeSessions": active_sessions,
        }
