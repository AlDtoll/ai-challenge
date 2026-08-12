# Week 10 Day 13 — LLM Gateway (defense in depth)

## Задание

Построить единую точку входа для LLM-запросов с 4 слоями защиты + audit + cost tracking. FastAPI-прокси перед DeepSeek API (OpenAI-compatible).

## Компоненты

| Слой | Файл | Что делает |
|------|------|-----------|
| Rate limit | `gateway.py` (slowapi) | 60 req/min per IP |
| Input Guard | `guards/input_guard.py` + `guards/patterns.py` | 10+ regex-детектов: AWS/GCP/Anthropic/OpenAI keys, JWT, credit card + Luhn, email, phone, SSN, RU passport. Режимы **block** и **mask** |
| LLM call | `gateway.py` → `httpx.post` к DeepSeek | Реальный API-вызов только если пройдены оба гварда |
| Output Guard | `guards/output_guard.py` | 5 проверок: secrets echo, system prompt extraction, suspicious URLs, dangerous commands, email leak |
| Audit | `audit.py` (SQLite) | ts, ip, model, tokens, cost, hits, blocked, latency |
| Cost tracking | `cost.py` | Актуальный DeepSeek pricing ($0.14/$0.28 per 1M input/output) |

## Endpoints

| Endpoint | Метод | Auth | Описание |
|----------|-------|------|----------|
| `POST /v1/chat/completions` | POST | нет (демо) | OpenAI-compatible proxy с 4 гвардами |
| `GET /audit/log?n=50` | GET | нет | Последние N audit-записей |
| `GET /stats/cost` | GET | нет | Sum cost + top-10 IP |
| `GET /health` | GET | нет | Статус gateway |

**Note:** в проде добавить Bearer auth на все endpoints (для демо оставлено open).

## Как запустить

```bash
cd week10/day13
pip install -r requirements.txt
export DEEPSEEK_API_KEY=sk-...
bash run.sh                    # запуск через nohup на 127.0.0.1:8080
curl http://127.0.0.1:8080/health
```

Остановка: `bash stop.sh`

## Тесты

```bash
pytest tests/ -v               # 19 pytest тестов
bash tests/e2e_test.sh         # E2E против живого gateway
```

Результаты (2026-08-07):
- **19/19 pytest passed** (12 InputGuard + 7 OutputGuard)
- E2E — attack (`AKIAIOSFODNN7EXAMPLE` в message) → InputGuard блокирует до LLM

## Video demo

[youtube-ссылка после записи]
