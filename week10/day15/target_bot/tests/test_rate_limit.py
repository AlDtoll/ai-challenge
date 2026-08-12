"""
Тесты rate limiter — 30 req/min per Bearer токен.
Используем mock LLM чтобы запросы выполнялись мгновенно,
иначе 30 реальных API вызовов > 60 секунд → sliding window сбрасывается.
"""
import pytest
import time
from unittest.mock import patch


def test_rate_limit_exceeded_on_31st_request(client, danil_headers):
    """
    31-й запрос в течение минуты → 429.
    LLM замокан чтобы запросы были мгновенными.
    """
    from app.main import _request_timestamps
    _request_timestamps.clear()

    # Мокируем LLM чтобы не тратить реальное время и токены
    with patch("app.llm.chat_with_model", return_value=("Mocked reply", 10, 5)):
        # 30 запросов должны пройти
        success_count = 0
        for i in range(30):
            response = client.post(
                "/api/chat",
                json={"sessionId": f"rate-limit-sess-{i}", "message": "Hi"},
                headers=danil_headers
            )
            if response.status_code != 429:
                success_count += 1

        assert success_count == 30, f"Ожидали 30 успешных запросов, получили {success_count}"

        # 31-й запрос должен вернуть 429
        response = client.post(
            "/api/chat",
            json={"sessionId": "rate-limit-sess-31", "message": "This should fail"},
            headers=danil_headers
        )
        assert response.status_code == 429


def test_rate_limit_is_per_token(client, danil_headers, partner_headers):
    """
    Rate limit раздельный для каждого токена.
    Partner токен не ограничен лимитами Danil токена.
    """
    from app.main import _request_timestamps
    _request_timestamps.clear()

    with patch("app.llm.chat_with_model", return_value=("Mocked reply", 10, 5)):
        # Исчерпываем лимит Danil токена
        for i in range(30):
            client.post(
                "/api/chat",
                json={"sessionId": f"rl-danil-{i}", "message": "Hi"},
                headers=danil_headers
            )

        # Danil заблокирован на 31-м запросе
        danil_31 = client.post(
            "/api/chat",
            json={"sessionId": "rl-danil-31", "message": "Overflow"},
            headers=danil_headers
        )
        assert danil_31.status_code == 429

        # Partner всё ещё работает (своя очередь)
        partner_response = client.post(
            "/api/chat",
            json={"sessionId": "rl-partner-1", "message": "Hi from partner"},
            headers=partner_headers
        )
        assert partner_response.status_code != 429


def test_rate_limit_resets_after_window(client, danil_headers):
    """
    После истечения sliding window запросы снова проходят.
    Заполняем очередь старыми метками (65 секунд назад).
    """
    from app.main import _request_timestamps, _rate_lock
    import time as time_module

    _request_timestamps.clear()

    # Добавляем 30 старых меток (65 секунд назад — за пределами 60-секундного окна)
    old_time = time_module.time() - 65
    with _rate_lock:
        for _ in range(30):
            _request_timestamps[danil_headers["Authorization"][7:]].append(old_time)

    # Мокируем LLM — нам важна только логика rate limit
    with patch("app.llm.chat_with_model", return_value=("Mocked reply", 10, 5)):
        response = client.post(
            "/api/chat",
            json={"sessionId": "rl-reset-1", "message": "After window reset"},
            headers=danil_headers
        )
        # Должно пройти — все старые метки вышли из окна
        assert response.status_code != 429


def test_ip_rate_limit_exceeded_on_61st_request(client, danil_headers):
    """
    61-й запрос с одного IP (не из whitelist) → 429 с detail='rate_limit_ip'.
    LLM замокан. TestClient по умолчанию передаёт IP 'testclient',
    который не входит в whitelist (127.0.0.1, <VPS_IP>).
    """
    from app.main import _ip_requests, _ip_rate_lock, _request_timestamps

    # Сбрасываем счётчики обоих лимитеров
    _request_timestamps.clear()
    with _ip_rate_lock:
        _ip_requests.clear()

    # Напрямую заполняем IP-счётчик — ставим 60 меток для IP 'testclient'
    # (именно такой host подставляет httpx TestClient при raise_server_exceptions=False)
    import time as time_module
    now = time_module.time()
    with _ip_rate_lock:
        _ip_requests["testclient"] = [now] * 60

    # Следующий запрос — 61-й с этого IP → должен вернуть 429
    with patch("app.llm.chat_with_model", return_value=("Mocked reply", 10, 5)):
        response = client.post(
            "/api/chat",
            json={"sessionId": "ip-rl-test-1", "message": "Should be blocked by IP limit"},
            headers=danil_headers
        )

    assert response.status_code == 429
    data = response.json()
    assert data.get("error") == "rate_limit_ip"
