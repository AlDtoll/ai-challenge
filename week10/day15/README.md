# Week 10 Day 15 — Red Team Match

## Что это

Day 15 AI Challenge Advanced: обмен атаками с партнёром (@oxaexa).
Две задачи параллельно:
1. **Защита** — построить свой target bot с 10 слоями защиты и принять атаку партнёра.
2. **Атака** — запустить red-team против бота партнёра (2 раунда, 112 payload'ов).

Итог дня — `REPORT.md`.

## Структура папок

```
week10/day15/
├── target_bot/          # Наш FastAPI bot (10 guard layers, 58 pytest)
│   ├── app/             # Основной код (main.py, guards.py, llm.py, ...)
│   ├── tests/           # 58 pytest тестов
│   ├── .env.example     # Шаблон переменных окружения
│   ├── requirements.txt
│   └── README.md        # Документация target bot
├── attack/              # Материалы атаки на бота партнёра
│   ├── redteam_partner_v2_2026-08-12.md   # 39 payload'ов v2
│   ├── redteam_partner_v2_run.sh          # Скрипт запуска v2
│   ├── redteam_ai_advent_pipeline_2026-08-10_public.md  # Методология
│   └── redteam_v2_results.log             # Результаты v2 прогона
└── selftest/            # Self-adversarial тесты своего бота
    ├── selftest_target_bot_2026-08-12.md       # Round 1
    ├── selftest_target_bot_round2_2026-08-12.md # Round 2 (фикс 12 уязвимостей)
    ├── final_smoke_2026-08-12.md               # Финальный smoke
    └── night_summary_2026-08-12.md             # Сводка ночной сессии
```

## Как запустить target bot локально

```bash
cd week10/day15/target_bot

# 1. Создать окружение
python3 -m venv venv && source venv/bin/activate
pip install -r requirements.txt

# 2. Настроить переменные окружения
cp .env.example .env
# Отредактировать .env: вставить реальные UUID для токенов

# 3. Запустить
uvicorn app.main:app --port 8091

# 4. Проверить
curl http://localhost:8091/health
```

## Запуск тестов

```bash
cd week10/day15/target_bot
pytest tests/ -v
# Ожидается: 58 passed
```

## Итог дня

Подробный отчёт — `REPORT.md`.
