# Day 13 — LLM Gateway (Report)

_AI Challenge Advanced Week 10 Day 13 — 2026-08-07._

## Что реализовано

FastAPI-прокси между user и LLM API (DeepSeek). Слои защиты:
- **Rate limit** — slowapi, 60 req/min per IP (in-memory)
- **Input Guard** — regex-детекция 10+ типов секретов/PII, режим block или mask
- **Output Guard** — validation ответа модели: secrets, system prompt extraction, suspicious URLs, dangerous commands, email leak
- **Audit** — SQLite `~/tools/llm-gateway/audit.db` (ts / ip / model / tokens / cost / hits / blocked / latency)
- **Cost tracking** — актуальный DeepSeek прайсинг ($0.14 / $0.28 per 1M input/output)

## Endpoints

| Endpoint | Метод | Описание |
|---|---|---|
| `POST /v1/chat/completions` | POST | OpenAI-compatible proxy с guards |
| `GET /audit/log?n=50` | GET | Последние N записей audit |
| `GET /stats/cost` | GET | Суммарный cost + top-10 per IP |
| `GET /health` | GET | Статус gateway |

## Структура файлов

| Файл | Описание |
|---|---|
| `gateway.py` | FastAPI приложение, точка входа |
| `guards/__init__.py` | Пустой init |
| `guards/patterns.py` | Regex-словарь (10+ паттернов) + luhn_check() |
| `guards/input_guard.py` | Класс InputGuard: block/mask режимы |
| `guards/output_guard.py` | Класс OutputGuard: 5 типов проверок |
| `audit.py` | AuditLogger (SQLite) |
| `cost.py` | PRICING dict + calculate() |
| `requirements.txt` | fastapi, uvicorn, slowapi, httpx |
| `run.sh` | Запуск через nohup, проверка /health |
| `stop.sh` | Kill по PID-файлу |
| `tests/__init__.py` | Пустой init |
| `tests/test_input_guard.py` | 12 unit-тестов InputGuard |
| `tests/test_output_guard.py` | 7 unit-тестов OutputGuard |
| `tests/e2e_test.sh` | E2E curl против живого gateway |
| `audit.db` | SQLite БД (создаётся при первом запуске) |

## Результаты unit-тестов (pytest)

**19 passed, 0 failed** — Python 3.12.3, pytest-9.1.1

| Тест | Статус | Описание |
|---|---|---|
| test_aws_access_key | PASS | AKIAIOSFODNN7EXAMPLE блокируется |
| test_aws_secret_key | PASS | aws_secret_access_key=VALUE блокируется |
| test_github_pat | PASS | ghp_AAAAAA... блокируется |
| test_openai_key | PASS | sk-proj-aaa... блокируется |
| test_email | PASS | someone@example.com блокируется |
| test_credit_card_luhn_valid | PASS | 4111 1111 1111 1111 блокируется (Luhn OK) |
| test_credit_card_luhn_invalid | PASS | 4111 1111 1111 1112 — НЕ блокируется (Luhn fail) |
| test_base64_secret | PASS | secret=BASE64_VALUE блокируется |
| test_clean_prompt | PASS | Чистый prompt — не блокируется |
| test_private_key_header | PASS | BEGIN RSA PRIVATE KEY блокируется |
| test_split_secret_across_messages | PASS | Задокументированное ограничение |
| test_mask_mode | PASS | Режим mask: email заменяется на [REDACTED_EMAIL] |
| test_secret_in_output | PASS | AWS key в ответе LLM — output blocked |
| test_system_prompt_leak | PASS | Детектируется попытка извлечь system prompt |
| test_suspicious_url | PASS | URL с параметром data= — blocked |
| test_dangerous_command | PASS | rm -rf / — blocked |
| test_clean_response | PASS | Чистый ответ — пропускается |
| test_email_leak | PASS | Email в output не из input — blocked |
| test_email_ok_if_in_input | PASS | Email в output совпадает с input — пропускается |

### Исправление в процессе

AWS_SECRET_KEY паттерн из плана не матчил aws_secret_access_key=VALUE (разделитель _access_key перебивал [^A-Za-z0-9]{1,20}). Исправлен на более широкий паттерн: захватывает полный env-var формат aws_secret_access_key=VALUE.

## Результаты E2E (curl против live gateway)

| Тест | Ожидание | Результат | Латентность |
|---|---|---|---|
| 1. /health | 200 | status ok, key_loaded true | ~5ms |
| 2. Block AWS key | 403 | input_guard_blocked, hits=AWS_ACCESS_KEY | ~0ms |
| 3. Block OpenAI key | 403 | input_guard_blocked, hits=OPENAI_KEY | ~0ms |
| 4. Clean prompt -> DeepSeek | 200 | BEACON (ответ модели), tokens: in=14/out=3 | 1932ms |
| 5. Rate limit smoke (5 pings) | 200 | 5/5 = 200 (60/min не нарушен) | <10ms/ping |
| 6. /audit/log?n=10 | 3 записи | 2 blocked + 1 success | — |
| 7. /stats/cost | Summary | total_usd=0.000003 (3e-06), reqs=3 | — |

DeepSeek ответил: модель deepseek-v4-flash, word "BEACON", 14 input / 3 output tokens.

## Cost per request (наблюдения)

Из /stats/cost после E2E:
- total_usd: 0.000003 (3e-06)
- reqs: 3
- tokens_in: 14, tokens_out: 3

Один реальный запрос (14 in + 3 out tokens): $0.000003. При прайсинге $0.14/$0.28 per 1M — соответствует ожиданиям.

## Rate limit demo

5 быстрых пингов /health: все вернули 200. 429 не триггерился — правильно, т.к. лимит 60 req/min, а 5 пингов за ~1 сек не превышают порог.

## Известные ограничения

1. Split-secret атака через \n: паттерн sk-(?:proj-)?[A-Za-z0-9_-]{30,} не матчит ключ разбитый через newline между сообщениями. Задокументировано в test_split_secret_across_messages.

2. AWS_SECRET_KEY контекстный: требует aws_secret или secret_access в тексте рядом со значением.

3. EMAIL в output: детектируется только если НЕ был в input. Легитимный forwarding не блокируется.

4. In-memory rate limit: при рестарте gateway счётчик сбрасывается. Для production — Redis backend.

5. Single worker: uvicorn --workers 1. Для production — несколько воркеров + Redis.

## Выводы

Gateway готов как pluggable layer перед любым OpenAI-compatible LLM API. Прайсинг захардкожен под DeepSeek, но легко расширяется через PRICING dict в cost.py.

Ключевые security-свойства:
- Lethal Trifecta break: gateway разрывает связь между untrusted input и sensitive data через input guard
- Defense in depth: 2 независимых слоя (input + output), каждый независимо блокирует
- Audit trail: каждый запрос логируется с hits, cost, IP

## Что дальше

Day 14 — Security Step в execution loop через этот Gateway. Автогенерация Kotlin/Android кода с security-review вторым LLM-вызовом; все вызовы проходят через POST /v1/chat/completions gateway'а, cost и hits логируются.
