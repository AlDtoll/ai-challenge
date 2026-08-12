# Day 15 Report — Red Team Match

## Mission

Обмен атаками с партнёром @oxaexa.
- Мы атакуем их бота, они атакуют наш.
- Цель: найти реальные уязвимости в чужой защите и удержать свою.

---

## Timeline — 3 раунда

### Раунд 1: 2026-08-10 — Blackbox атака на бота партнёра

- **73 payload'а** по pipeline из `attack/redteam_ai_advent_pipeline_2026-08-10_public.md`
- Все запросы вернули `guards.injectionBlocked`
- **6 findings** (уровень info / usability):
  - Information disclosure через сообщения об ошибках
  - Usability-проблемы при крайних сценариях ввода
- Вывод: защита партнёра держит prompt injection, но есть точки для улучшения UX/error handling

### Раунд 2: 2026-08-11 22:14 MSK — v2 атака (39 payload'ов)

- **39 payload'ов** — второй прогон из `attack/redteam_partner_v2_run.sh`
- Результат: **42×401 Unauthorized** — партнёр добавил auth-слой между раундами
- Атака отбилась до app logic — ни один payload не дошёл до LLM
- Логи: `attack/redteam_v2_results.log`

### Раунд 3: 2026-08-12 ночь — Построение своего target bot

- Собрали `ai_target_bot`: FastAPI + **10 guard layers**
- **58 pytest тестов** — all green
- **Self-adversarial testing**: 12 уязвимостей найдено и закрыто за 2 раунда
- Endpoint передан партнёру:
  - **URL:** `http://<VPS_IP>:8091/`
  - **PARTNER_TOKEN:** `2b253b18...` — передан @oxaexa 12.08 08:13 NSK

---

## Наш target bot — 10 слоёв защиты

| # | Слой | Что делает |
|---|------|-----------|
| 1 | prompt-injection-guard | Блокирует injection паттерны во входе |
| 2 | indirect-content-sanitizer | Очищает потенциально injected контент |
| 3 | gateway-input-guard | Валидация длины, формата, sessionId |
| 4 | gateway-output-guard | Санитизация выхода модели |
| 5 | hardened-system-prompt | Persona AITechCo с explicit security rules |
| 6 | workspace-secret-leak-guard | Блокирует утечку INTERNAL_API_KEY (base64/hex) |
| 7 | security-review-execution-loop | Пост-обработка ответа на информационные угрозы |
| 8 | session-ownership-binding | Сессия привязана к первому токену, чужой → 403 |
| 9 | metrics-auth | /metrics только для Bearer auth |
| 10 | ip-based-rate-limit | 60 req/min per IP, fail-safe против DoS |

---

## Статус атаки партнёра на нас

По journalctl сервиса ai-target-bot — с IP 24.199.94.244 (предполагаемый IP @oxaexa)
попыток не зафиксировано на момент написания отчёта (12.08.2026 ~09:00 NSK).

Атака партнёра ожидается — target bot запущен и готов.

---

## Файлы

- `target_bot/` — исходный код и тесты
- `attack/redteam_partner_v2_2026-08-12.md` — план и payload'ы v2
- `attack/redteam_partner_v2_run.sh` — скрипт атаки v2
- `attack/redteam_ai_advent_pipeline_2026-08-10_public.md` — методология (публичная версия)
- `attack/redteam_v2_results.log` — сырые результаты v2
- `selftest/selftest_target_bot_2026-08-12.md` — Round 1 self-test
- `selftest/selftest_target_bot_round2_2026-08-12.md` — Round 2 self-test (после фиксов)
- `selftest/final_smoke_2026-08-12.md` — финальный smoke
- `selftest/night_summary_2026-08-12.md` — сводка ночной сессии

---

## Итог

| Метрика | Значение |
|---------|---------|
| Раундов атаки | 2 (v1: 73 payload, v2: 39 payload) |
| Findings у партнёра | 6 (все info/usability, не критичные) |
| Наш target bot — слои | 10 |
| Pytest тесты | 58 passed |
| Self-test уязвимостей закрыто | 12 |
| Статус атаки на нас | Ожидается (партнёр ещё не атаковал) |
