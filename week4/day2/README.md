# День 17 — Первый инструмент MCP (week4/day2)

Свой MCP-сервер с инструментом `get_forecast` (обёртка над Open-Meteo) + агент,
который этот инструмент находит, вызывает и использует результат. Всё в одной программе.

## Что демонстрирует задание
- **Регистрация инструмента** — `mcpServer.addTool("get_forecast", …)`
- **Описание входных параметров** — `inputSchema = ToolSchema(properties = { latitude, longitude }, required = …)`
- **Возврат результата** — `CallToolResult(content = listOf(TextContent(...)))`
- **Вызов из агента** — `mcp.callTool(CallToolRequest(...))` и использование ответа

## Архитектура (один запуск)
1. Поднимаем MCP-сервер по Streamable HTTP на `http://localhost:3001/mcp`.
2. Агент (MCP-клиент) подключается к нему — тем же `StreamableHttpClientTransport`/`mcpStreamableHttp`, что и в дне 16, но против НАШЕГО сервера.
3. Агент: `listTools()` → `callTool(get_forecast, {lat, lon})` → берёт `TextContent` и формирует ответ.

## Запуск
```bash
./gradlew :week4:day2:run --console=plain -q
```

## Ожидаемый вывод
```
MCP-сервер поднят: http://localhost:3001/mcp
Агент подключился к серверу
Инструменты сервера: get_forecast
Вызываю get_forecast(latitude=55.03, longitude=82.92)
Результат инструмента: температура 12.3°C, ветер 8.5 км/ч
Ответ агента: сейчас в Новосибирске — температура 12.3°C, ветер 8.5 км/ч
```
(числа меняются — это реальная текущая погода из Open-Meteo)

## Стек
- Kotlin 2.2.21 (задан в корневом build.gradle.kts)
- `io.modelcontextprotocol:kotlin-sdk-server:0.13.0` + `kotlin-sdk-client:0.13.0`
- `io.ktor:ktor-server-cio:3.2.3` + `ktor-client-cio:3.2.3`
- API подтверждён по офиц. README SDK и рабочим решениям участников (uncolorrboy, ArtemBotnev).

> Open-Meteo не требует ключа. Логи Ktor приглушены до warn (resources/simplelogger.properties), чтобы консоль была чистой для видео.
