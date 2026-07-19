# Day 22 (week5/day2) — Первый RAG-запрос

Агент в двух режимах: **без RAG** (модель отвечает по своим знаниям) и **с RAG** (модель отвечает по нашей «вики челленджа» через retrieval).

## Что нужно на машине

- **JDK 21+.**
- **Ollama** установлен, поднят на `localhost:11434`, скачана модель `nomic-embed-text` (`ollama pull nomic-embed-text`). Проверка живучести — см. ниже.
- **`.env` в корне репо** с `DEEPSEEK_API_KEY=...`.

## Быстрая проверка Ollama

```powershell
curl.exe -X POST http://localhost:11434/api/embeddings -H "Content-Type: application/json" -d "{\"model\":\"nomic-embed-text\",\"prompt\":\"тест\"}"
```

Должен вернуться JSON `{"embedding":[…]}` с массивом из 768 чисел.

## Сборка и запуск

```powershell
chcp 65001
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
# опционально — начать с нуля (удалить прошлый индекс)
Remove-Item $env:USERPROFILE\.ai-challenge\day22_index.json -ErrorAction SilentlyContinue

# 1. Построить индекс (эмбеддинги через Ollama, ~10-15 сек на 10 файлов)
.\gradlew.bat :week5:day2:run --console=plain -q --args="--build"

# 2. Задать один вопрос БЕЗ RAG
.\gradlew.bat :week5:day2:run --console=plain -q --args="--ask ""Какая embedding-модель используется в дне 22?"""

# 3. Задать тот же вопрос С RAG
.\gradlew.bat :week5:day2:run --console=plain -q --args="--ask ""Какая embedding-модель используется в дне 22?"" --rag"

# 4. Прогон 10 контрольных вопросов — оба режима + метрики
.\gradlew.bat :week5:day2:run --console=plain -q --args="--compare"
```

## Метрики

Скрипт `--compare` печатает по каждому вопросу:

- **Retrieval@3**: попал ли ожидаемый файл в топ-3 чанков ретривера.
- **Grounded (без RAG)**: доля ключевых слов из `expected_keywords`, встреченных в ответе без RAG.
- **Grounded (с RAG)**: то же, но для ответа с RAG.

В финале — сводка по всем 10 вопросам. Ожидаемый эффект: без RAG модель либо галлюцинирует, либо честно говорит «не знаю»; с RAG — попадает в ожидаемые слова и цитирует источник.

## Как устроено (кратко)

`Main.kt` содержит всё:

- `chunkParagraphs()` — режем MD по абзацам, склеиваем короткие, режем длинные до 700 символов с overlap 100.
- `ollamaEmbedRaw()` / `embedDocument()` / `embedQuery()` — POST на `http://localhost:11434/api/embeddings`, `FloatArray(768)`. Для `nomic-embed-text` перед текстом ставится task-префикс: `search_document:` для чанков базы, `search_query:` для вопросов пользователя — это официальный best practice от Nomic, даёт ~5-15% прирост recall@k.
- `cosine()` + `topK()` — in-memory ретривер.
- `deepseek()` — POST на `https://api.deepseek.com/chat/completions` (`deepseek-chat`).
- `askNoRag()` / `askWithRag()` — два режима.
- `runCompare()` — прогон вопросов из `src/main/resources/questions.json`.

Индекс сохраняется в `~/.ai-challenge/day22_index.json` (кроссплатформенно; на Windows — `%USERPROFILE%\.ai-challenge\`). Пересобрать — `--build`.

## Файлы

```
week5/day2/
├── build.gradle.kts
├── README.md                                  ← ты здесь
├── src/main/kotlin/Main.kt
└── src/main/resources/
    ├── kb/                                    ← 10 MD-файлов «вики челленджа»
    │   ├── days-done.md
    │   ├── env-and-state.md
    │   ├── mcp-week4.md
    │   ├── people.md
    │   ├── pitfalls.md
    │   ├── rag-week5.md
    │   ├── rules.md
    │   ├── stack.md
    │   ├── tools-inventory.md
    │   ├── videos.md
    │   ├── weeks-plan.md
    │   └── windows-setup.md
    └── questions.json                         ← 10 контрольных вопросов
```
