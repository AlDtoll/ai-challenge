"""SQLite audit logger for all gateway requests."""
import sqlite3
import json
import os
from datetime import datetime, timezone

DB_PATH = os.path.expanduser("~/tools/llm-gateway/audit.db")

SCHEMA = """
CREATE TABLE IF NOT EXISTS requests (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    ts TEXT NOT NULL,
    ip TEXT NOT NULL,
    model TEXT NOT NULL,
    tokens_in INTEGER,
    tokens_out INTEGER,
    cost_usd REAL,
    input_hits TEXT,
    output_hits TEXT,
    blocked INTEGER,
    latency_ms INTEGER
);
"""


class AuditLogger:
    def __init__(self, path: str = DB_PATH):
        self.path = path
        conn = sqlite3.connect(self.path)
        conn.executescript(SCHEMA)
        conn.close()

    def log(self, ip: str, model: str, tokens_in: int, tokens_out: int, cost_usd: float,
            input_hits: list[str], output_hits: list[str], blocked: bool, latency_ms: int):
        conn = sqlite3.connect(self.path)
        conn.execute(
            "INSERT INTO requests(ts, ip, model, tokens_in, tokens_out, cost_usd, input_hits, output_hits, blocked, latency_ms) VALUES (?,?,?,?,?,?,?,?,?,?)",
            (
                datetime.now(timezone.utc).isoformat(),
                ip, model,
                tokens_in or 0, tokens_out or 0,
                cost_usd or 0.0,
                json.dumps(input_hits),
                json.dumps(output_hits),
                1 if blocked else 0,
                latency_ms or 0,
            ),
        )
        conn.commit()
        conn.close()

    def recent(self, n: int = 50) -> list[dict]:
        conn = sqlite3.connect(self.path)
        conn.row_factory = sqlite3.Row
        rows = conn.execute(
            "SELECT * FROM requests ORDER BY id DESC LIMIT ?", (n,)
        ).fetchall()
        conn.close()
        return [dict(r) for r in rows]

    def cost_summary(self) -> dict:
        conn = sqlite3.connect(self.path)
        conn.row_factory = sqlite3.Row
        total = conn.execute(
            "SELECT COALESCE(SUM(cost_usd),0) as total_usd, COUNT(*) as reqs, "
            "COALESCE(SUM(tokens_in),0) as tokens_in, COALESCE(SUM(tokens_out),0) as tokens_out "
            "FROM requests"
        ).fetchone()
        per_ip = conn.execute(
            "SELECT ip, COUNT(*) as reqs, COALESCE(SUM(cost_usd),0) as usd "
            "FROM requests GROUP BY ip ORDER BY usd DESC LIMIT 10"
        ).fetchall()
        conn.close()
        return {"total": dict(total), "per_ip": [dict(r) for r in per_ip]}
