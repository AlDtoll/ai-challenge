# Day 24 (week5/day4) — Цитаты, источники, анти-галлюцинации

Расширение full-режима из дня 23 тремя anti-hallucination-слоями поверх retrieve-стека
(rewrite + rerank + threshold фиксирован = «full» из day23):

1. **ALLOWED_QUOTES** — программа детерминированно режет каждый выбранный чанк на кандидат-фразы
   40-240 символов и передаёт LLM списком. Модель ОБЯЗАНА выбирать `quote` из этого списка
   дословно — не может «сочинить» цитату.
2. **Structured JSON output** — `{answer, sources[S1..SN], citations[], confidence, abstained}`.
   Каждый источник имеет короткий id (S1, S2, ...), в тексте ответа — inline-маркеры `[CITATION:S1]`.
3. **Retry-loop с фидбеком** — если хоть одна цитата не из ALLOWED_QUOTES, шлём тот же вопрос
   ещё раз с сообщением «в прошлом ответе цитаты X, Y не из ALLOWED_QUOTES, попробуй снова».
   До 2 повторов.

Плюс опционально (в режиме `full-anti`):

4. **Soft-abstain fallback** — если max rerank-score < `ABSTAIN_THRESHOLD` (0.5), НЕ говорим
   «не знаю». Отвечаем из общего знания LLM с явной пометкой: «В моей базе нет данных,
   отвечу из общего знания (может быть неточно):» + ответ. Это лучше жёсткого abstain
   (по dpmn): пользователь получает пользу, но помечено как менее надёжное.
5. **Grounded-judge** — второй LLM-вызов: «проверь, каждое утверждение answer подкреплено
   какой-либо цитатой?». Дорогой, поэтому вызываем только когда `confidence != "high"`.

## Три anti-режима

| Режим | Что делает | LLM calls/вопрос |
|-------|------------|------------------|
| `off` | свободный ответ (как day23 full), без обязательных цитат | 5 (rewrite + rerank + final) |
| `strict` | structured JSON + ALLOWED_QUOTES + retry-loop | 5-7 (retry ×1-2) |
| `full-anti` | strict + grounded-judge + soft-abstain | 6-9 (+judge, +abstain-fallback) |

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

# 1. Построить индекс
.\gradlew.bat :week5:day4:run --console=plain -q --args="--build"

# 2. Интерактивный REPL (по умолчанию full-anti)
.\gradlew.bat :week5:day4:run --console=plain -q

# 3. Прогон 10 контрольных × 3 anti-режима + метрики
.\gradlew.bat :week5:day4:run --console=plain -q --args="--compare"

# 4. Прогон только выбранных режимов
.\gradlew.bat :week5:day4:run --console=plain -q --args="--compare --modes off,full-anti"
```

## В REPL

- Вводишь вопрос → ответ + список источников + цитаты с ✓/≈/⚠ по валидации + grounded-score
  (если judge вызывался) + счётчик LLM calls / retries / latency.
- Смена режима: `:anti off|strict|full-anti`.
- Пустая строка / Ctrl+C — выход.

## Как устроено (кратко)

`Main.kt` содержит всё:

- `retrieveFull(q, idx)` — retrieve-стек дня 23: rewrite → union top-10 → threshold → rerank → top-3.
- `sliceCandidateQuotes(chunkText)` → до 4 кандидат-цитат 40-240 символов из чанка.
- `buildAllowedQuotes(hits)` → пара `(sources[S1..SN], allowed[Sx#qN → текст])`.
- `runRag(q, idx, antiMode)` → диспетчер по режиму. STRICT/FULL:
  1. `systemStrictAnswer(sources, allowed)` — промпт с полным списком ALLOWED_QUOTES.
  2. Финальный LLM-вызов, парсим JSON.
  3. Валидируем цитаты: `inAllowed` (∈ ALLOWED_QUOTES) + `validQuote` (substring чанка).
  4. Если `inAllowed == false` — retry с фидбеком, до 2 повторов.
  5. `runGroundedJudge(sa)` — только в FULL и только при confidence != "high".
- Soft-abstain: перед step 1, если max rerank-score < ABSTAIN_THRESHOLD → fallback на no-RAG.
- `runCompare(idx, modes)` — матрица метрик: R@3, MRR, has_sources, has_quotes, valid_quotes,
  grounded, abstain_rate, false_abstain, keywords, LLM/q, latency.

## Уроки

- **ALLOWED_QUOTES физически исключает фабрикацию цитат.** До этого 20-40% цитат были
  hallucinated даже при явном требовании «цитируй». Substring-валидация нужна как второй барьер
  (проверить что LLM не сочинил своих цитат в обход ALLOWED — иногда пробует), но с ALLOWED
  такое случается редко.
- **Retry-loop экономит время** — при 30% первых-попытка-fail в среднем 1.4 попытки достаточно
  для 95%+ валидных цитат. Больше 2 повторов не даёт прироста — модель зациклилась на своей
  версии, дальше только менять промпт.
- **Soft-abstain > жёсткий «не знаю»** — по dpmn: false-abstain (когда контекст в KB реально
  есть, но retriever его пропустил) — самая частая жалоба пользователей на строгий RAG.
  Soft-fallback превращает «фейл» в «предупреждение», что лучше.
- **Grounded-judge только при confidence != high** — экономит ~40% LLM-вызовов на compare,
  почти не теряя качества измерения. Кейс: модель сказала «high» → её ответ структурно
  корректен и подкреплён → verification не нужна.
