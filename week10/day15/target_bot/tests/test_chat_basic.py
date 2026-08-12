"""
Тесты базовой функциональности POST /api/chat.
LLM замокан — все тесты работают offline без реального Anthropic API.
"""
import pytest
from unittest.mock import patch


def test_chat_without_bearer_returns_401(client):
    """POST /api/chat без Authorization заголовка → 401."""
    response = client.post(
        "/api/chat",
        json={"sessionId": "test-sess-1", "message": "Hello"}
    )
    assert response.status_code == 401


def test_chat_with_invalid_bearer_returns_401(client, invalid_headers):
    """POST /api/chat с невалидным Bearer → 401."""
    response = client.post(
        "/api/chat",
        json={"sessionId": "test-sess-2", "message": "Hello"},
        headers=invalid_headers
    )
    assert response.status_code == 401


def test_chat_message_too_long_returns_400(client, danil_headers):
    """POST /api/chat с сообщением > 4000 символов → 400.
    RequestValidationError обёрнут в generic 400 (избегаем информационного leak 422).
    """
    long_message = "A" * 4001
    response = client.post(
        "/api/chat",
        json={"sessionId": "test-sess-3", "message": long_message},
        headers=danil_headers
    )
    # RequestValidationError → generic 400 (не 422) чтобы не раскрывать схему
    assert response.status_code == 400
    data = response.json()
    # Ответ должен быть generic без деталей pydantic
    assert data.get("error") == "invalid_request"


def test_chat_invalid_session_id_path_traversal_returns_400(client, danil_headers):
    """POST /api/chat с sessionId содержащим path traversal символы → 400."""
    response = client.post(
        "/api/chat",
        json={"sessionId": "../../etc/passwd", "message": "Hello"},
        headers=danil_headers
    )
    # sessionId не соответствует regex → generic 400
    assert response.status_code == 400
    data = response.json()
    assert data.get("error") == "invalid_request"


@patch("app.llm.chat_with_model", return_value=("We offer AI consulting services.", 100, 50))
def test_chat_with_valid_bearer_returns_200(mock_chat, client, danil_headers):
    """POST /api/chat с валидным Bearer + нормальным сообщением → 200."""
    response = client.post(
        "/api/chat",
        json={"sessionId": "test-sess-live-1", "message": "Hello, what services do you offer?"},
        headers=danil_headers
    )
    assert response.status_code == 200
    data = response.json()
    assert "reply" in data
    assert "sessionId" in data
    assert "model" in data
    assert data["model"] == "claude-haiku-4-5-20251001"
    assert data["sessionId"] == "test-sess-live-1"
    assert len(data["reply"]) > 0


@patch("app.llm.chat_with_model", return_value=("Our pricing starts at $5000.", 80, 40))
def test_chat_response_contains_required_fields(mock_chat, client, danil_headers):
    """POST /api/chat возвращает все обязательные поля в ответе."""
    response = client.post(
        "/api/chat",
        json={"sessionId": "test-sess-fields-1", "message": "Tell me about your pricing."},
        headers=danil_headers
    )
    assert response.status_code == 200
    data = response.json()
    assert set(data.keys()) >= {"reply", "sessionId", "model"}


@patch("app.llm.chat_with_model", return_value=("Hello from AITechCo!", 60, 30))
def test_chat_partner_token_also_works(mock_chat, client, partner_headers):
    """POST /api/chat работает с партнёрским токеном."""
    response = client.post(
        "/api/chat",
        json={"sessionId": "partner-sess-1", "message": "Hello from partner!"},
        headers=partner_headers
    )
    assert response.status_code == 200
