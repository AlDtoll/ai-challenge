# День 16 — Подключение к MCP

Минимальный MCP-клиент на Kotlin: подключается к удалённому MCP-серверу по
**Streamable HTTP**, делает `initialize` и через `tools/list` выводит список
доступных инструментов. Сами инструменты не вызываются (это следующие дни).

## Что внутри
- `src/main/kotlin/Main.kt` — весь код (коннект + discovery + обработка ошибок).
- Сервер по умолчанию — **DeepWiki MCP** (`https://mcp.deepwiki.com/mcp`), публичный, без авторизации.

## Запуск
```bash
./gradlew :week4:day1:run
```
Другой сервер можно задать переменной окружения:
```bash
MCP_SERVER_URL="https://mcp.deepwiki.com/mcp" ./gradlew :week4:day1:run
```

## Ожидаемый вывод
```
Подключаюсь к MCP-серверу: https://mcp.deepwiki.com/mcp
✅ Соединение установлено. Доступно инструментов: 3

1. read_wiki_structure
   описание: Get a list of documentation topics for a GitHub repository
   input schema: ...

2. read_wiki_contents
   описание: View documentation about a GitHub repository
   input schema: ...

3. ask_question
   описание: Ask any question about a GitHub repository
   input schema: ...
```

## Зависимости (build.gradle.kts)
- `io.modelcontextprotocol:kotlin-sdk:0.9.0` — официальный MCP Kotlin SDK.
- `io.ktor:ktor-client-cio:3.2.3` — HTTP-клиент (движок CIO) для транспорта.
- `org.slf4j:slf4j-simple:2.0.13` — тихий логгер.

> Если IDE/сборка ругнётся на версии: SDK требует Kotlin 2.x (в проекте 2.1.0).
> При желании можно поднять корневой `kotlin("jvm")` до 2.1.20 — на нём SDK 0.9.0
> и Ktor 3.2.3 точно совместимы (так у эталонных решений участников).

## Проверка задания
- ✅ соединение устанавливается → строка «Соединение установлено».
- ✅ список инструментов возвращается → пронумерованный список с name/description/schema.
- ✅ обработка ошибок → при недоступном сервере печатается «❌ Не удалось…» без падения стектрейсом.
