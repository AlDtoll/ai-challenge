# AI Challenge — 10 недель от LLM-вызова до Security Testing

Репозиторий учебного AI-челленджа [AlDtoll](https://github.com/AlDtoll) (Данил Толмачёв).
Каждый день — работающий код, практика, разбор техник.

## Содержание

- [Навигация](#навигация)
- [Обзор недель](#обзор-недель)
- [Стек](#стек)
- [Структура репо](#структура-репо)
- [Как запустить](#как-запустить)
- [Связанные репозитории](#связанные-репозитории)

---

## Навигация

| Что нужно | Куда идти |
|-----------|-----------|
| Что было сделано в каждый день | [INDEX.md](INDEX.md) |
| Готовые техники для своего агента | [harness_kit/README.md](harness_kit/README.md) |
| Код конкретного дня | `git checkout weekN/dayM` → папка `weekN/dayM/` |

---

## Обзор недель

| Неделя | Трек | Тема |
|--------|------|------|
| Week 1 | base | Основы LLM API: промпты, параметры, модели, LLM-as-judge |
| Week 2 | base | Диалоговые агенты: история, персистентность, context compression, sticky facts |
| Week 3 | base | State machine: инварианты, gates, silent rollback, персонализация |
| Week 4 | base | MCP (Model Context Protocol): tools, оркестрация серверов |
| Week 5 | base | RAG: chunking, retrieval, реранкинг, anti-hallucination |
| Week 6 | base | Локальные LLM: Ollama, quantization, offline RAG |
| Week 7 | base | Production-кейсы: dev-ассистент, PR review, support, file agent, VPS report |
| Week 8 | advanced | CLAUDE.md v2, профили агентов, execution loop |
| Week 9 | advanced | Fine tuning: Q-LoRA, датасет, стратифицированный split |
| Week 10 | advanced | Security для LLM: Prompt Injection, Gateway, Red-team |

Полная таблица со ссылками на каждый день → [INDEX.md](INDEX.md)

---

## Стек

- **Kotlin (JVM)** — основной язык, Gradle, каждый день — отдельный подпроект
- **DeepSeek API** — главный LLM-провайдер
- **OpenRouter** — мультимодельный роутинг (GPT-4o, Claude, Mistral и др.)
- **Ollama** — локальный LLM (Week 6 и далее)
- **MCP** — Model Context Protocol для инструментов агентов (Week 4+)
- **FastAPI (Python)** — вспомогательные инструменты Week 10 (Gateway, Red-team)

---

## Структура репо

```
ai-challenge/
├── INDEX.md               # Оглавление: все недели, все дни, статусы
├── harness_kit/           # Actionable-справочник техник для агентов-харнессов
│   └── README.md
├── docs/                  # Исторические артефакты и заметки
├── week1/ … week7/        # Исторические папки base-трека (Week 1–7 на main)
└── (ветки weekN/dayM)     # Каждая ветка = один день, папка weekN/dayM/
```

**Ветки:** каждый день живёт в своей git-ветке `weekN/dayM`.
Например, код дня 3 недели 5: `git checkout week5/day3` → папка `week5/day3/`.

**[harness_kit/](harness_kit/README.md)** — выжимка практических техник из всех уроков:
паттерны промптинга, retry-логика, контроль контекста, RAG-пайплайны, MCP-интеграция.
Создан как быстрый справочник без необходимости читать весь INDEX.

---

## Как запустить

```bash
git clone git@github.com:AlDtoll/ai-challenge.git
cd ai-challenge

# Переключиться на нужный день
git checkout weekN/dayM

# Скопировать шаблон переменных
cp .env.example .env
# Вписать ключи:
#   DEEPSEEK_API_KEY=...
#   OPENROUTER_API_KEY=...
#   OLLAMA_ENDPOINT=http://localhost:11434  (Week 6+)

# Запустить
./gradlew run
```

> Gradle wrapper есть начиная с ветки `week4/day2`.
> Для Week 1–3 — запускать через `kotlinc` или IDE.

---

## Связанные репозитории

- **[AlDtoll/zizz3](https://github.com/AlDtoll/zizz3)** — Android-приложение, где техники
  advanced-трека (Week 8: execution loop, профили агентов) применялись к реальному production-коду.

---

## Автор

[AlDtoll](https://github.com/AlDtoll) — Данил Толмачёв
