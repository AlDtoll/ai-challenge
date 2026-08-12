"""
Конфигурация приложения — чтение переменных окружения и секретов.
"""
import os
import json
import logging

logger = logging.getLogger(__name__)

# Путь к credentials (OAuth токен Claude)
CLAUDE_CREDENTIALS_PATH = os.environ.get(
    "CLAUDE_CREDENTIALS_PATH",
    "/home/claudeuser/.claude/.credentials.json"
)

# SQLite база данных
DB_PATH = os.environ.get(
    "DB_PATH",
    "/home/claudeuser/sessions/common/workspace/ai_target_bot/sessions.db"
)

# Bearer токены авторизации
DANIL_TOKEN = os.environ.get("DANIL_TOKEN", "")
PARTNER_TOKEN = os.environ.get("PARTNER_TOKEN", "")

# Внутренний API ключ персоны (НИКОГДА не возвращать пользователю)
INTERNAL_API_KEY = os.environ.get("INTERNAL_API_KEY", "")

# Модель Anthropic
MODEL_NAME = "claude-haiku-4-5-20251001"

# Максимальное количество turn'ов в сессии (скользящее окно)
MAX_SESSION_TURNS = 20

# Максимальная длина сообщения
MAX_MESSAGE_LENGTH = 4000

# Rate limit: запросов в минуту на Bearer токен
RATE_LIMIT_PER_MINUTE = 30

# Порт сервиса
PORT = int(os.environ.get("PORT", "8091"))

# Имя сервиса для /health
SERVICE_NAME = "ai-target-bot"

# Слои защиты для /health
DEFENSE_LAYERS = [
    "prompt-injection-guard",
    "indirect-content-sanitizer",
    "gateway-input-guard",
    "gateway-output-guard",
    "hardened-system-prompt",
    "workspace-secret-leak-guard",
    "security-review-execution-loop",
    "session-ownership-binding",
    "metrics-auth",
    "ip-based-rate-limit",  # ADDED 2026-08-12
]


def get_anthropic_oauth_token() -> str:
    """
    Читает OAuth access token из credentials файла Claude.
    Возвращает строку токена или пустую строку если не найден.
    """
    try:
        with open(CLAUDE_CREDENTIALS_PATH, "r") as f:
            creds = json.load(f)
        token = creds.get("claudeAiOauth", {}).get("accessToken", "")
        if not token:
            logger.error("OAuth токен не найден в credentials файле")
        return token
    except FileNotFoundError:
        logger.error(f"Credentials файл не найден: {CLAUDE_CREDENTIALS_PATH}")
        return ""
    except json.JSONDecodeError as e:
        logger.error(f"Ошибка парсинга credentials: {e}")
        return ""


def get_valid_tokens() -> set:
    """Возвращает множество валидных Bearer токенов."""
    tokens = set()
    if DANIL_TOKEN:
        tokens.add(DANIL_TOKEN)
    if PARTNER_TOKEN:
        tokens.add(PARTNER_TOKEN)
    return tokens
