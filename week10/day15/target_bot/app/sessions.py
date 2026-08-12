"""
Хранилище сессий на SQLite.
Каждая сессия содержит: sessionId, владелец (Bearer токен), историю диалога.
Session ownership binding: sessionId привязан к первому Bearer-токену который его использовал.
"""
import sqlite3
import json
import logging
import time
from typing import Optional
from contextlib import contextmanager

from . import config

logger = logging.getLogger(__name__)


@contextmanager
def get_db():
    """Контекстный менеджер для соединения с SQLite."""
    conn = sqlite3.connect(config.DB_PATH)
    conn.row_factory = sqlite3.Row
    try:
        yield conn
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


def init_db():
    """Инициализирует схему базы данных если не существует."""
    with get_db() as conn:
        conn.execute("""
            CREATE TABLE IF NOT EXISTS sessions (
                session_id TEXT PRIMARY KEY,
                owner_token TEXT NOT NULL,
                history TEXT NOT NULL DEFAULT '[]',
                created_at REAL NOT NULL DEFAULT (strftime('%s', 'now')),
                updated_at REAL NOT NULL DEFAULT (strftime('%s', 'now'))
            )
        """)
        logger.info("База данных сессий инициализирована")


def get_session_owner(session_id: str) -> Optional[str]:
    """
    Возвращает Bearer токен владельца сессии или None если сессия не существует.
    Используется для session-ownership-binding (слой 8).
    """
    with get_db() as conn:
        row = conn.execute(
            "SELECT owner_token FROM sessions WHERE session_id = ?",
            (session_id,)
        ).fetchone()
        return row["owner_token"] if row else None


def get_session_history(session_id: str) -> list:
    """
    Возвращает историю диалога для сессии.
    История — список {"role": "user"|"assistant", "content": "..."}.
    """
    with get_db() as conn:
        row = conn.execute(
            "SELECT history FROM sessions WHERE session_id = ?",
            (session_id,)
        ).fetchone()
        if not row:
            return []
        return json.loads(row["history"])


def add_turn_to_session(
    session_id: str,
    owner_token: str,
    user_message: str,
    assistant_reply: str,
) -> bool:
    """
    Добавляет turn в историю сессии.
    Если сессия не существует — создаёт её и биндит к owner_token (слой 8).
    Применяет скользящее окно: максимум MAX_SESSION_TURNS turn'ов.
    Защита от TOCTOU: INSERT + BEGIN IMMEDIATE гарантируют атомарный owner-check.
    Возвращает True при успехе, False при owner mismatch или потере гонки.
    """
    # isolation_level=None → manual transaction control (autocommit выкл.)
    conn = sqlite3.connect(config.DB_PATH, isolation_level=None)
    try:
        cursor = conn.cursor()
        cursor.execute("BEGIN IMMEDIATE")  # write lock — устраняет TOCTOU

        # Пытаемся INSERT как новую сессию
        try:
            history_init = json.dumps(
                [
                    {"role": "user", "content": user_message},
                    {"role": "assistant", "content": assistant_reply},
                ],
                ensure_ascii=False,
            )
            cursor.execute(
                """INSERT INTO sessions (session_id, owner_token, history, created_at, updated_at)
                   VALUES (?, ?, ?, ?, ?)""",
                (session_id, owner_token, history_init, int(time.time()), int(time.time())),
            )
            conn.commit()
            logger.info(f"Создана сессия {session_id}, владелец: {owner_token[:8]}...")
            return True
        except sqlite3.IntegrityError:
            # Сессия уже существует — UPDATE только если owner_token совпадает
            cursor.execute(
                "SELECT owner_token, history FROM sessions WHERE session_id = ?",
                (session_id,),
            )
            row = cursor.fetchone()
            if row is None or row[0] != owner_token:
                conn.rollback()
                logger.warning(
                    f"TOCTOU owner mismatch для сессии {session_id}: "
                    f"запрос от {owner_token[:8]}..., владелец {(row[0][:8] + '...') if row else 'none'}"
                )
                return False  # чужая сессия — race

            history = json.loads(row[1])
            # Добавляем новый turn
            history.append({"role": "user", "content": user_message})
            history.append({"role": "assistant", "content": assistant_reply})

            # Скользящее окно: оставляем последние MAX_SESSION_TURNS turn'ов
            # Каждый turn = 2 сообщения (user + assistant)
            max_messages = config.MAX_SESSION_TURNS * 2
            if len(history) > max_messages:
                history = history[-max_messages:]

            cursor.execute(
                """UPDATE sessions SET history = ?, updated_at = ?
                   WHERE session_id = ? AND owner_token = ?""",
                (json.dumps(history, ensure_ascii=False), int(time.time()), session_id, owner_token),
            )
            if cursor.rowcount == 0:
                conn.rollback()
                return False  # race window закрылся неожиданно
            conn.commit()
            return True
    finally:
        conn.close()


def delete_session(session_id: str, owner_token: Optional[str] = None) -> bool:
    """
    Удаляет сессию из базы данных.
    Если owner_token передан — удаляет только если владелец совпадает (TOCTOU-safe).
    Возвращает True если сессия была удалена, False если не существовала или owner mismatch.
    """
    with get_db() as conn:
        if owner_token is not None:
            # Атомарное удаление с проверкой владельца — нет отдельного SELECT+DELETE
            cursor = conn.execute(
                "DELETE FROM sessions WHERE session_id = ? AND owner_token = ?",
                (session_id, owner_token),
            )
        else:
            cursor = conn.execute(
                "DELETE FROM sessions WHERE session_id = ?",
                (session_id,),
            )
        deleted = cursor.rowcount > 0
        if deleted:
            logger.info(f"Сессия {session_id} удалена")
        return deleted


def count_active_sessions() -> int:
    """Возвращает количество активных сессий в базе."""
    with get_db() as conn:
        row = conn.execute("SELECT COUNT(*) as cnt FROM sessions").fetchone()
        return row["cnt"] if row else 0
