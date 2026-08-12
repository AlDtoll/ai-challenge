"""
Тесты GET /metrics эндпоинта.
"""
import pytest


def test_metrics_without_bearer_returns_401(client):
    """GET /metrics без авторизации → 401 (слой 9: metrics-auth)."""
    response = client.get("/metrics")
    assert response.status_code == 401


def test_metrics_with_invalid_bearer_returns_401(client, invalid_headers):
    """GET /metrics с невалидным токеном → 401."""
    response = client.get("/metrics", headers=invalid_headers)
    assert response.status_code == 401


def test_metrics_with_valid_bearer_returns_200(client, danil_headers):
    """GET /metrics с валидным Bearer → 200."""
    response = client.get("/metrics", headers=danil_headers)
    assert response.status_code == 200


def test_metrics_json_structure(client, danil_headers):
    """GET /metrics возвращает все обязательные поля."""
    response = client.get("/metrics", headers=danil_headers)
    assert response.status_code == 200
    data = response.json()

    required_fields = [
        "totalRequests",
        "totalPromptTokens",
        "totalCompletionTokens",
        "totalCostUsd",
        "activeSessions",
    ]
    for field in required_fields:
        assert field in data, f"Поле {field} отсутствует в /metrics"


def test_metrics_values_are_numeric(client, danil_headers):
    """GET /metrics возвращает числовые значения."""
    response = client.get("/metrics", headers=danil_headers)
    data = response.json()

    assert isinstance(data["totalRequests"], int)
    assert isinstance(data["totalPromptTokens"], int)
    assert isinstance(data["totalCompletionTokens"], int)
    assert isinstance(data["totalCostUsd"], float)
    assert isinstance(data["activeSessions"], int)

    assert data["totalRequests"] >= 0
    assert data["activeSessions"] >= 0
    assert data["totalCostUsd"] >= 0.0


def test_metrics_request_count_increases(client, danil_headers):
    """Счётчик запросов увеличивается после вызова /api/chat."""
    # Сбрасываем счётчики
    from app import metrics as metrics_module
    metrics_module._total_requests = 0

    before = client.get("/metrics", headers=danil_headers).json()["totalRequests"]

    # Делаем 1 запрос к chat
    client.post(
        "/api/chat",
        json={"sessionId": "metrics-count-test", "message": "Hello"},
        headers=danil_headers
    )

    after = client.get("/metrics", headers=danil_headers).json()["totalRequests"]

    # После запроса счётчик должен вырасти
    assert after > before


def test_partner_token_can_access_metrics(client, partner_headers):
    """Partner токен тоже имеет доступ к /metrics."""
    response = client.get("/metrics", headers=partner_headers)
    assert response.status_code == 200
