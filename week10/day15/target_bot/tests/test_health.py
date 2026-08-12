"""
Тесты для GET /health эндпоинта.
"""


def test_health_returns_ok(client):
    """GET /health возвращает статус ok без авторизации."""
    response = client.get("/health")
    assert response.status_code == 200


def test_health_json_structure(client):
    """GET /health возвращает корректную JSON структуру."""
    response = client.get("/health")
    data = response.json()

    assert data["status"] == "ok"
    assert data["service"] == "ai-target-bot"
    assert data["model"] == "claude-haiku-4-5-20251001"
    assert data["layers_count"] == 10


def test_health_contains_all_layers(client):
    """GET /health возвращает корректное количество слоёв защиты (10)."""
    response = client.get("/health")
    data = response.json()

    assert data["layers_count"] == 10, f"Ожидалось 10 слоёв, получено {data['layers_count']}"


def test_root_returns_html(client):
    """GET / возвращает HTML landing page без авторизации."""
    response = client.get("/")
    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/html")
    assert "AI Target Bot" in response.text
