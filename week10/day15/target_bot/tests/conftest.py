"""
Конфигурация pytest — общие fixtures для всех тестов.
"""
import os
import pytest
from fastapi.testclient import TestClient

# Устанавливаем тестовые переменные ПЕРЕД импортом приложения
os.environ.setdefault("DANIL_TOKEN", "test-danil-token-12345")
os.environ.setdefault("PARTNER_TOKEN", "test-partner-token-67890")
os.environ.setdefault("INTERNAL_API_KEY", "sk-aitechco-test-internal-key-abc")
os.environ.setdefault("DB_PATH", "/tmp/ai_target_bot_test.db")
os.environ.setdefault("CLAUDE_CREDENTIALS_PATH", "/home/claudeuser/.claude/.credentials.json")

from app.main import app
from app import sessions

# Токены для тестов
DANIL_TOKEN = "test-danil-token-12345"
PARTNER_TOKEN = "test-partner-token-67890"
INVALID_TOKEN = "this-is-not-a-valid-token"


@pytest.fixture(autouse=True)
def clean_db():
    """
    Инициализирует чистую БД перед каждым тестом.
    autouse=True — применяется ко всем тестам автоматически.
    Также сбрасывает глобальные in-memory rate limiter счётчики (per-token + per-IP),
    чтобы тесты были изолированы друг от друга.
    """
    # Удаляем тестовую БД если существует
    db_path = os.environ["DB_PATH"]
    if os.path.exists(db_path):
        os.remove(db_path)
    sessions.init_db()

    # Сбрасываем in-memory rate limiter счётчики
    from app.main import _request_timestamps, _ip_requests, _ip_rate_lock
    _request_timestamps.clear()
    with _ip_rate_lock:
        _ip_requests.clear()

    yield
    # Очистка после теста
    if os.path.exists(db_path):
        os.remove(db_path)


@pytest.fixture
def client():
    """TestClient для FastAPI приложения."""
    with TestClient(app, raise_server_exceptions=False) as c:
        yield c


@pytest.fixture
def danil_headers():
    """Заголовки с Danil Bearer токеном."""
    return {"Authorization": f"Bearer {DANIL_TOKEN}"}


@pytest.fixture
def partner_headers():
    """Заголовки с Partner Bearer токеном."""
    return {"Authorization": f"Bearer {PARTNER_TOKEN}"}


@pytest.fixture
def invalid_headers():
    """Заголовки с невалидным токеном."""
    return {"Authorization": f"Bearer {INVALID_TOKEN}"}


@pytest.fixture
def json_headers_danil():
    """Заголовки с Danil токеном + Content-Type: application/json."""
    return {
        "Authorization": f"Bearer {DANIL_TOKEN}",
        "Content-Type": "application/json"
    }
