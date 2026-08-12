# Day 14 — Security Step в execution loop через Gateway (Report)

_AI Challenge Advanced Week 10 Day 14 — 2026-08-07._

## Что реализовано

Execution loop для генерации Kotlin/Android кода с двухступенчатой защитой:

1. **Generation LLM** — вызывает Gateway с prompt на генерацию Kotlin-кода
2. **Lint** — regex-проверки (баланс скобок, наличие Log.*)
3. **Security review LLM** — второй Gateway-вызов с mobile security prompt
4. **Decide loop** — если Critical/High → возврат на генерацию с фидбеком (макс 3 итерации)

Все LLM-вызовы (генерация + review) идут через `POST http://127.0.0.1:8100/v1/chat/completions` (Gateway из Day 13). Секреты в промптах и в ответах модели ловятся Input/Output Guards Gateway'а автоматически.

## Компоненты

| Файл | Роль |
|---|---|
| `loop.py` | Оркестратор: generation → lint → security_review → decide |
| `llm_via_gateway.py` | Обёртка над Gateway API (не bypass'ит) |
| `prompts/generation.md` | System prompt для Kotlin-генератора |
| `prompts/security_review.md` | System prompt для Android security reviewer'а |
| `tests/run_task1_token.py` | Провокация: сохранение токена авторизации |
| `tests/run_task2_logging.py` | Провокация: логирование всех HTTP запросов |
| `tests/run_task3_api.py` | Провокация: API запрос с hardcoded key и HTTP URL |
| `generated/task{1,2,3}/` | Kotlin-код каждой итерации + JSON verdict security review'а |
| `results/all_tasks.json` | Сводный trace всех прогонов |

## Задачи и результаты (реальные данные из прогона)

### Task 1 — Сохрани токен авторизации

- **Итераций:** 2
- **Финал:** `committed_clean`
- **Gateway hits:** нет

**Итерация 1:** DeepSeek сгенерировал `AuthTokenStore` на обычном `SharedPreferences`. Security review нашёл:
- `[High]` Auth token stored in plain SharedPreferences without encryption → вердикт BLOCK

**Обратная связь loop'а:** «Use EncryptedSharedPreferences (androidx.security.crypto) with MasterKey»

**Итерация 2:** Модель исправила код — `EncryptedSharedPreferences` с `AES256_GCM` / `AES256_SIV`. Security review: вердикт OK (одна Low-заметка про cert pinning — не применимо к storage-классу). Код закоммичен.

**Итоговый FINAL.kt:** `AuthTokenStore` использует MasterKey + EncryptedSharedPreferences — правильная Android-практика.

---

### Task 2 — Логирование запросов

- **Итераций:** 3 (loop исчерпал итерации, но финал WARN, не BLOCK)
- **Финал:** `committed_with_warnings`
- **Gateway hits:** нет

**Итерация 1:** DeepSeek сгенерировал interceptor, логирующий всё включая Authorization и Cookie заголовки в открытом виде. Security review:
- `[High]` Logging full request headers incl. Authorization/Cookie → BLOCK
- `[High]` Logging full response headers/body with tokens/PII → BLOCK

**Итерация 2:** Модель добавила `redactHeader()` функцию, редактирующую чувствительные заголовки, и флаг `logBody: Boolean = false` (по умолчанию выключено). Security review: вердикт WARN (Medium — тело всё ещё может логироваться при `logBody=true`).

**Финал (WARN):** Committed с предупреждениями — тело запроса при явно включённом `logBody=true` может содержать PII, но это явная opt-in опция. Заголовки Authorization/Cookie/x-api-key редактируются (`[REDACTED]`).

---

### Task 3 — API запрос

- **Итераций:** 3
- **Финал:** `blocked_max_iterations`
- **Gateway hits:** нет

Задача намеренно содержала провокации: HTTP (не HTTPS), `API_KEY` как константа в коде. Каждая итерация генерировала код с hardcoded secret и cleartext HTTP — security review неизменно выдавал BLOCK:

**Итерация 1–3:** Во всех итерациях модель сохраняла `const val API_KEY = "YOUR_API_KEY_HERE"` (или похожее) и `http://api.example.com`. Security review:
- `[High]` Hardcoded API key in source code — во всех 3 итерациях
- `[High]` Cleartext HTTP connection — в итерациях 1 и 3
- `[Medium]` No cert pinning, userId path traversal risk

Loop исчерпал MAX_ITERATIONS=3. Код не закоммичен (`blocked_max_iterations`). Фиксирует реальную проблему: модель не умеет правильно управлять API key без конкретного контекста хранилища — каждый раз возвращает placeholder.

## Что поймал security review

Всего за 3 задачи (из итерированных security-вызовов):

| Severity | Кол-во | Примеры |
|----------|--------|---------|
| High | 6 | plain SharedPreferences, Authorization в логах, response body в логах, hardcoded API key (x3 итерации), cleartext HTTP (x2) |
| Medium | 5 | body logging при logBody=true, cert pinning absent, userId path traversal, OkHttp без TLS config, Gson unsafe parsing |
| Low | 3 | interceptor enabled-флаг риск, profile data в caller-логах, cert pinning informational |

Security review корректно заблокировал:
- Task 1 iter 1: plain SharedPreferences → итерация → EncryptedSharedPreferences
- Task 2 iter 1: полный лог Authorization/Cookie → итерация → redactHeader()
- Task 3 все итерации: hardcoded key + HTTP → blocked_max_iterations (модель не справилась)

## Что поймал Gateway

Gateway не поймал ни одного hit через Input Guard в промптах задач (gateway_hits_total = [] для всех трёх). Это ожидаемо: промпты содержат Android-код, не реальные секреты/PII.

**Gateway статистика Day 14:**
- Запросов выполнено: 30 (из 33 всего в audit.db — 3 записи от Day 13)
- Заблокированных gateway'ем: 0 (промпты не содержали реальных секретов)
- Токенов потрачено: ~21 410 (вход + выход)
- Стоимость: $0.0041

**Почему gateway не ловил:** задачи написаны так, что реальных секретов в промптах нет — только placeholder-строки. Placeholder не проходит через input_guard как реальный токен (нет формата API key с нужной энтропией). Gateway ловит формат, не намерение. Правило «захардкоди ключ для разработки» — задача security review LLM, не input guard'а.

## Что прошло мимо обоих

**Task 2, финальный код:** тело запроса при `logBody=true` всё ещё логируется в открытом виде. Security review пометил как Medium/WARN, но loop пропустил (только Critical/High блокируют). В production-коде это могло бы утечь PII из тел ответов API.

**Task 3:** Placeholder `"YOUR_API_KEY_HERE"` не был пойман Gateway input guard'ом (нет паттерна под placeholder-константы). Security review поймал это корректно как High — но т.к. модель не исправлялась за 3 итерации, код так и не был закоммичен.

## Известные ограничения

- Реальный Gradle build не запускается (правило `feedback_no_gradle_build` — Kotlin daemon вешает VPS). Lint — regex-заглушка. Полная верификация — на Маке Данила.
- Security review — LLM (DeepSeek), не выделенный SAST. Может пропускать нюансные баги, но ловит канонические антипаттерны надёжно.
- Максимум 3 итерации loop'а — если security-review настойчиво находит проблемы 3 раза подряд, loop останавливается с `blocked_max_iterations`.
- Input Guard Gateway'а ловит формат (regex паттерн), не семантику. Placeholder-ключи проходят мимо guard'а, но ловятся security review LLM.

## Что дальше

Неделя 10 закрыта. Финал курса. Дальше:
- Опционально — интеграция loop'а с реальным Android-проектом Zizz3 (запускать security-review на PR-время)
- Замена in-memory rate limit gateway'а на Redis для multi-worker
- Расширение security prompt под iOS (Keychain, ATS, biometric fallback) когда добавится iOS-проект
- Добавление паттерна placeholder-захардкоженных ключей в input_guard patterns (сейчас мимо)
