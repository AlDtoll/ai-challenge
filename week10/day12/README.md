# Week 10 Day 12 — Indirect Prompt Injection Lab

## Задание

3 вектора **indirect** injection (через контент, не через prompt пользователя) + 3 защиты + Copilot-style reproduce.

## Структура

```
day12/
├── REPORT.md          — итоги и таблица (attack vs defense × 3)
├── attacks/           — payload файлы, имитирующие полученный контент
├── agents/            — 3 агента (summarizer/analyzer/searcher) на DeepSeek + mock fallback
├── defenses/          — 3 защитных слоя
├── tests/             — runners: без защиты и с защитой
├── reproduce/         — Copilot-style: poisoned_code + code_reviewer
└── results/           — JSON и log-файлы прошлых прогонов
```

## 3 вектора атаки

| # | Вектор | Метод injection | Целевой агент |
|---|--------|-----------------|---------------|
| 1 | Email | HTML-comment + `color:#fff` (скрытый текст в тегах) | summarizer |
| 2 | Document | Zero-width chars + markdown link `title` атрибут | analyzer |
| 3 | Webpage | `display:none` div с фейковым фактом | searcher |

## 3 защиты

| # | Защита | Метод |
|---|--------|-------|
| 1 | `sanitize_html.py` | Strip HTML-comments, `display:none`, `color:#fff`, zero-width chars |
| 2 | `content_boundary.py` | Обрамление untrusted-контента delimiter'ами перед LLM |
| 3 | `output_validator.py` | LLM+regex post-check output модели на маркеры leak'а |

## Reproduce (Copilot-style)

`reproduce/` — воспроизводит real-world кейс когда poisoned dependency (`poisoned_code.py`) даёт code-reviewer'у промпт с injection'ом. `code_reviewer.py` — уязвимая версия и защищённая.

## Как запустить

```bash
cd week10/day12

# Атаки без защиты (2 провайдера: реальный DeepSeek + детерминированный mock)
python3 tests/run_attacks.py

# Атаки с защитой
python3 tests/run_defended.py

# Copilot-style reproduce
python3 reproduce/code_reviewer.py
```

DeepSeek API ключ через `export DEEPSEEK_API_KEY=xxx`. Без ключа автоматически fallback на mock LLM.

## Результаты (см. REPORT.md подробно)

- **DeepSeek без защиты** — все 3 вектора SAFE (реальный LLM устоял против naive injection).
- **Mock LLM без защиты** — все 3 BREACHED (доказывает что защиты действительно нужны, а не полагаемся на "умную модель").
- **С защитами** — все 3 DEFENDED. Санитайзеры блокируют payload до передачи в LLM, output validator ловит утечки.

## Video demo

[youtube-ссылка после записи]
