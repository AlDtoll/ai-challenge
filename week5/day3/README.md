# Day 23 (week5/day3) — Реранкинг, query rewrite, фильтрация

Расширение naive-RAG из дня 22 двумя этапами: **query rewrite** (3 переформулировки) и
**LLM-реранкер** (DeepSeek-судья) + быстрый similarity-порог перед реранком.

## Что нового поверх дня 22

| Этап | День 22 | День 23 |
|------|---------|---------|
| Ретрив | эмбед → top-3 | эмбед → top-10 |
| Query rewrite | нет | 3 варианта (Rephrasing / Conceptual Expansion / Search Engine Style) → union top-10 |
| Similarity-порог | нет | cos ≥ 0.28 перед реранком |
| Реранкер | нет | DeepSeek-судья, плоский JSON `[0.9, 0.4, ...]` шкалой 0..1, порог 0.45 |
| Финальный контекст | 3 чанка | 3 чанка |

## Режимы работы

| Режим | Описание | Стоимость (LLM calls на 1 вопрос) |
|-------|----------|-----------------------------------|
| `naive` | как день 22: эмбед → top-3 → ответ | 1 |
| `threshold` | эмбед top-10 → cos ≥ 0.28 → top-3 → ответ | 1 |
| `rerank` | эмбед top-10 → LLM-rerank → top-3 → ответ | 2 |
| `rewrite` | 3 rewrite → union top-10 → top-3 → ответ | 2 |
| `full` | rewrite → union → threshold → rerank → top-3 → ответ | 3 |

## Что нужно на машине

- **JDK 21+** (у меня `C:\Program Files\Android\Android Studio2\jbr`, JBR 17.0.10 подходит).
- **Ollama** на `localhost:11434`, модель `nomic-embed-text` (`ollama pull nomic-embed-text`).
- **`.env`** в корне репо с `DEEPSEEK_API_KEY=...`.

## Сборка и запуск

```powershell
chcp 65001
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio2\jbr"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

# 1. Построить индекс (эмбеддинги через Ollama, ~15 сек на 40+ чанков)
.\gradlew.bat :week5:day3:run --console=plain -q --args="--build"

# 2. Интерактивный REPL (по умолчанию режим `full`)
.\gradlew.bat :week5:day3:run --console=plain -q

# 3. Прогон 10 контрольных × 5 режимов + метрики
.\gradlew.bat :week5:day3:run --console=plain -q --args="--compare"

# 4. Прогон только выбранных режимов
.\gradlew.bat :week5:day3:run --console=plain -q --args="--compare --modes naive,full"
```

## В REPL

- Вводишь вопрос → печатается ответ без RAG (для сравнения) + ответ выбранного режима + топ-10
  кандидатов с отметкой ★ у попавших в финальный контекст + счётчик LLM calls / latency.
- Смена режима на лету: `:mode naive|threshold|rerank|rewrite|full`.
- Пустая строка / Ctrl+C — выход.

## Как устроено (кратко)

`Main.kt` содержит всё:

- `runRag(question, index, mode)` — общий диспетчер: rewrite → retrieve → threshold → rerank → answer.
- `rewriteQuery(q)` → DeepSeek возвращает JSON `{"rewritten_queries":[...]}` (3 варианта).
- `topKMulti(queries, idx, k)` — эмбеддит каждый запрос, объединяет по chunkId с максимальным cosine
  (дедупликация обязательна: один чанк часто находят несколько вариантов).
- `rerankLLM(q, candidates)` → DeepSeek возвращает плоский `[0.9, 0.4, ...]` (шкала 0..1). Regex-fallback
  на битый JSON, паддинг недостающих значений 0.5.
- `runCompare(idx, modes)` — прогон вопросов × режимов с матрицей: R@3, R@10, MRR@3, Grounded, LLM/q,
  latency + ΔMRR относительно naive.

## Уроки при разработке

- **Формат ответа LLM-судьи — плоский массив 0..1**, не `[{id, score:0-10}]`: (а) LLM залипает на «7»
  в шкале 0-10, (б) плоский массив дешевле и легче парсится, (в) regex-fallback простой.
- **Обрезка чанка в rerank-промпте до 800 символов** — иначе токены на compare взлетают втрое.
- **Similarity-порог мягкий (0.28)**: жёсткий 0.4 выкидывает нормальных кандидатов на разговорных
  вопросах («что нам делать / куда мержим»).
- **HYDE (генерация псевдоответа для поиска) не даёт заметного выигрыша** на нашем маленьком
  доменном корпусе — оставил только 3 «настоящих» переформулировки.
- **Проверка префиксов nomic** — `search_query:` для вопросов, `search_document:` для чанков.
  Отсутствие префиксов — самая частая ошибка, ломает retrieval незаметно.
