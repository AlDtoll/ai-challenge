# День 20 — Оркестрация MCP (week4/day5)

Несколько MCP-серверов + агент, который через DeepSeek (function-calling) сам выбирает
инструменты, маршрутизирует вызовы на нужный сервер и выполняет длинный флоу.

## Три MCP-сервера (на разных портах)
- `knowledge` (3010): `search` (Wikipedia), `summarize` (DeepSeek)
- `weather` (3011): `get_weather` (wttr.in)
- `files` (3012): `save_to_file`

## Как работает оркестрация
1. Агент подключается ко ВСЕМ трём серверам, собирает общий реестр инструментов
   (имена namespace-ятся по серверу: `weather__get_weather`, `knowledge__search`, …).
2. Все инструменты отдаются DeepSeek как `tools` (function-calling).
3. DeepSeek сам решает, какой инструмент вызвать (`tool_calls`).
4. Агент по namespace МАРШРУТИЗИРУЕТ вызов на правильный сервер, возвращает результат модели.
5. Цикл повторяется (длинный флоу) до финального ответа.

## Что закрывает задание
- ✅ несколько MCP-серверов
- ✅ агент сам выбирает нужный инструмент
- ✅ корректная маршрутизация запросов (по server+tool)
- ✅ длинный флоу взаимодействия
- ✅ сценарий с инструментами с разных серверов, правильный порядок вызовов

## Запуск
Цель задаётся через --args (по умолчанию — сценарий про Новосибирск):

Windows (PowerShell):
```
chcp 65001
.\gradlew.bat :week4:day5:run --console=plain -q
```
Linux/Mac:
```
./gradlew :week4:day5:run --console=plain -q
```
Своя цель:
```
.\gradlew.bat :week4:day5:run --args="Узнай погоду в Berlin и справку о Берлине, сделай сводку и сохрани в berlin.txt" --console=plain -q
```

> Нужен DEEPSEEK_API_KEY в корневом `.env` (он там есть) — run-task сам подхватывает.

## Ожидаемый вывод (фрагмент)
```
Подняты 3 MCP-сервера: knowledge:3010, weather:3011, files:3012
  сервер «knowledge» (порт 3010): search, summarize
  сервер «weather» (порт 3011): get_weather
  сервер «files» (порт 3012): save_to_file
Общий реестр (4): knowledge__search, knowledge__summarize, weather__get_weather, files__save_to_file

=== ЦЕЛЬ: Узнай погоду в Novosibirsk и справку... ===
[шаг 1 · роутинг] сервер «weather» → get_weather({"city":"Novosibirsk"})
    → температура 19°C, ветер 9 км/ч, Clear
[шаг 2 · роутинг] сервер «knowledge» → search({"query":"Новосибирск"})
    → Новосибирск — город в России, административный центр...
[шаг 3 · роутинг] сервер «knowledge» → summarize({"text":"..."})
    → Новосибирск — третий по величине город России...
[шаг 4 · роутинг] сервер «files» → save_to_file({"content":"...","filename":"day20_novosibirsk.txt"})
    → Сохранено: ...\day20_novosibirsk.txt (...байт)

=== ФИНАЛЬНЫЙ ОТВЕТ АГЕНТА (шаг 5) ===
Готово: погода и справка собраны, сводка сохранена в файл.
```
(порядок и точные вызовы выбирает модель — могут немного отличаться)

## Стек
Kotlin 2.2.21 + kotlin-sdk 0.13.0 (server+client) + Ktor 3.2.3 + DeepSeek function-calling.
