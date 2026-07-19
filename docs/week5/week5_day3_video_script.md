# Сценарий видео — День 23: Реранкинг и query rewrite (week5/day3)

Немой скринкаст консоли. Без озвучки. Формат: что печатаю → что получаю.
Ветка: `week5/day3`, длительность видео ~2 мин, 3 шага.

Смысл дня: показать что двухэтапный RAG (rewrite + rerank) даёт заметный прирост
качества по сравнению с naive top-3 (день 22) — на матрице метрик и на конкретном вопросе.

---

## ШАГ 0 — подготовка (не в кадре)

Перед записью:
```powershell
git fetch
git checkout week5/day3
git pull
ollama list       # nomic-embed-text должен быть
Remove-Item $env:USERPROFILE\.ai-challenge\day23_index.json -ErrorAction SilentlyContinue
```
Прогоняем один раз `--build` заранее, чтобы Gradle скачал зависимости и Ollama прогрелся.

Затем удаляем индекс снова, чтобы шаг 1 показал построение с нуля:
```powershell
Remove-Item $env:USERPROFILE\.ai-challenge\day23_index.json
```

---

## ШАГ 1 — UTF-8, JAVA_HOME, build индекса

Печатаю:
```powershell
chcp 65001
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio2\jbr"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat :week5:day3:run --console=plain -q --args="--build"
```

Получаю:
```
Читаю базу знаний из src\main\resources\kb — 12 MD-файлов.
Эмбединг: 42  (файл windows-setup.md, чанк 3)
Собрано 42 чанков, размерность вектора = 768.
Индекс сохранён: C:\Users\<user>\.ai-challenge\day23_index.json
```

---

## ШАГ 2 — REPL: сравнение naive vs full на живом вопросе

Печатаю:
```powershell
.\gradlew.bat :week5:day3:run --console=plain -q
```

Появляется приглашение:
```
Загружен готовый индекс: 42 чанков (dim=768) из day23_index.json

Интерактивный RAG (день 23). Режим по умолчанию: full (rewrite + threshold + rerank)
Смена режима:  :mode naive|threshold|rerank|rewrite|full
Пустая строка / Ctrl+C — выход.

[full] >
```

Печатаю сложный вопрос (специально с обобщёнными формулировками, где naive часто мажет):

```
[full] > Что нам нужно проверить в Ollama-эмбеддере чтобы retrieval не сломался незаметно?
```

Получаю (сокращённо):
```
--- БЕЗ RAG (для сравнения) ---
Проверить: скачана ли модель, доступен ли Ollama на 11434, размерность эмбеддингов...

--- С RAG [full (rewrite + threshold + rerank)] ---
Rewrite-варианты:
  1. Как убедиться что эмбеддинг-модель Ollama правильно интегрирована в наш RAG?
  2. Проверка nomic-embed-text: task-префиксы search_query/search_document...
  3. Ollama nomic embeddings проверка префиксов интеграция

В нашем коде для nomic-embed-text обязательно ставим task-префиксы:
`search_query:` для вопросов, `search_document:` для чанков базы...
Источники: rag-week5.md, pitfalls.md

Кандидаты top-10 до финального отбора:
  ★ rag-week5.md#2  cos=0.712
    pitfalls.md#1  cos=0.641
  ★ pitfalls.md#3  cos=0.633
  ★ rag-week5.md#0  cos=0.617
    stack.md#3     cos=0.581
    ...
★ — попал в финальный контекст.
LLM calls: rewrite=1 rerank=1 final=1 (всего 3), latency 4123ms
```

Меняю режим на naive для контраста:
```
[full] > :mode naive
→ naive (эмбед top-3)
[naive] > Что нам нужно проверить в Ollama-эмбеддере чтобы retrieval не сломался незаметно?
```

Получаю (обычно хуже — naive часто мажет мимо `rag-week5.md#2` из-за отсутствия rewrite):
```
--- С RAG [naive] ---
Проверить, что nomic-embed-text скачана в Ollama и сервис поднят на 11434.
Источники: stack.md

Кандидаты top-3 до финального отбора:
  ★ stack.md#3  cos=0.605
  ★ pitfalls.md#1  cos=0.598
  ★ people.md#0  cos=0.512
LLM calls: rewrite=0 rerank=0 final=1 (всего 1), latency 1876ms
```

Виден чёткий контраст: `full` вытащил `rag-week5.md#2` (наш ключевой источник), `naive` — нет.

Enter на пустую строку — выход.

---

## ШАГ 3 — прогон 10 контрольных × 5 режимов + метрики

Печатаю:
```powershell
.\gradlew.bat :week5:day3:run --console=plain -q --args="--compare"
```

Идёт ~2-3 минуты (10 вопросов × 5 режимов ~= 50 итераций, ~150 LLM-вызовов). В кадре
можно ускорить в монтаже. Финальная таблица должна быть чётко читаемой:

```
== Итог по режимам (n=10) ==
Режим       | R@3    | R@10   | MRR@3 | Grounded | LLM/q | avg lat
------------|--------|--------|-------|----------|-------|--------
naive       | 7/10   | 7/10   | 0.60  | 6/10     | 1.0   | 1800ms
threshold   | 7/10   | 10/10  | 0.63  | 6/10     | 1.0   | 2200ms
rerank      | 9/10   | 10/10  | 0.81  | 8/10     | 2.0   | 3600ms
rewrite     | 9/10   | 10/10  | 0.78  | 8/10     | 2.0   | 5100ms
full        | 10/10  | 10/10  | 0.88  | 10/10    | 3.0   | 6400ms

ΔMRR относительно naive:
  threshold   +0.030
  rerank      +0.210
  rewrite     +0.180
  full        +0.280
```

Виден вклад каждого шага:
- **threshold** почти ничего не меняет (наш корпус чистый, все хорошие кандидаты и так проходят).
- **rerank** — главный винтик, +0.21 MRR за один лишний LLM-вызов.
- **rewrite** — второй по силе, помогает когда вопрос обобщённый.
- **full** — 10/10 R@3 и 10/10 Grounded на нашей вики.

⚠️ Числа выше — примерные. Реальные значения могут отличаться на ±0.1 из-за случайности DeepSeek.

---

## Заметки для съёмки

- **Между шагами 2/3 не закрывать PowerShell** — иначе JAVA_HOME/UTF8 надо снова прописать.
- Шаг 2 живой, шаг 3 длинный (~2-3 мин) — в монтаже ускорить середину 4x, финальную таблицу оставить в оригинале.
- Если Ollama прогрелся до записи — rewrite и rerank идут быстро (~1-2 сек каждый DeepSeek-вызов).
- В конце можно показать `docs/week5/day23_research.md` (мельком, как «а вот эталоны других участников») — необязательно.
