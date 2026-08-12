"""
Тесты слоя 8: session-ownership-binding.
Проверяет что sessionId биндится к первому Bearer токену и другой токен не может его использовать.
LLM замокан — тесты работают offline без реального Anthropic API.
"""
import pytest
from unittest.mock import patch


class TestSessionOwnership:
    """Тесты привязки сессии к Bearer токену."""

    @patch("app.llm.chat_with_model", return_value=("Hello! How can I help?", 80, 40))
    def test_owner_can_write_to_session(self, mock_chat, client, danil_headers):
        """Владелец сессии может писать в неё повторно."""
        # Создаём сессию первым вызовом
        response1 = client.post(
            "/api/chat",
            json={"sessionId": "ownership-test-1", "message": "Hello, start a session"},
            headers=danil_headers
        )
        assert response1.status_code == 200

        # Тот же токен пишет ещё раз
        response2 = client.post(
            "/api/chat",
            json={"sessionId": "ownership-test-1", "message": "What services do you offer?"},
            headers=danil_headers
        )
        assert response2.status_code == 200

    @patch("app.llm.chat_with_model", return_value=("My private session started.", 80, 40))
    def test_different_bearer_cannot_access_session(self, mock_chat, client, danil_headers, partner_headers):
        """
        Bearer A создал сессию — Bearer B пытается в неё писать → 403.
        Слой 8: session-ownership-binding.
        """
        # Danil создаёт сессию
        response1 = client.post(
            "/api/chat",
            json={"sessionId": "ownership-test-stolen", "message": "My private session"},
            headers=danil_headers
        )
        assert response1.status_code == 200

        # Partner пытается писать в ту же сессию
        response2 = client.post(
            "/api/chat",
            json={"sessionId": "ownership-test-stolen", "message": "Try to hijack"},
            headers=partner_headers
        )
        assert response2.status_code == 403

    @patch("app.llm.chat_with_model", return_value=("Session created.", 60, 30))
    def test_delete_session_by_owner(self, mock_chat, client, danil_headers):
        """Владелец может удалить свою сессию."""
        # Создаём сессию
        client.post(
            "/api/chat",
            json={"sessionId": "delete-test-1", "message": "Create to delete"},
            headers=danil_headers
        )

        # Удаляем
        response = client.delete("/api/sessions/delete-test-1", headers=danil_headers)
        assert response.status_code == 200

    def test_delete_nonexistent_session_returns_404(self, client, danil_headers):
        """Удаление несуществующей сессии → 404."""
        response = client.delete("/api/sessions/nonexistent-session-xyz", headers=danil_headers)
        assert response.status_code == 404

    @patch("app.llm.chat_with_model", return_value=("Danil's session.", 60, 30))
    def test_delete_session_by_wrong_owner_returns_403(self, mock_chat, client, danil_headers, partner_headers):
        """
        Попытка удалить сессию другого пользователя → 403.
        """
        # Danil создаёт сессию
        client.post(
            "/api/chat",
            json={"sessionId": "delete-ownership-1", "message": "Danil's private chat"},
            headers=danil_headers
        )

        # Partner пытается удалить
        response = client.delete("/api/sessions/delete-ownership-1", headers=partner_headers)
        assert response.status_code == 403

    def test_delete_invalid_session_id_format_returns_400(self, client, danil_headers):
        """DELETE /api/sessions с невалидным ID → 400."""
        response = client.delete(
            "/api/sessions/../../etc/passwd",
            headers=danil_headers
        )
        # FastAPI интерпретирует path как /api/sessions/ + resolved path
        # В зависимости от маршрутизации может быть 400 или 404
        assert response.status_code in (400, 404)

    def test_delete_without_auth_returns_401(self, client):
        """DELETE /api/sessions без авторизации → 401."""
        response = client.delete("/api/sessions/some-session")
        assert response.status_code == 401
