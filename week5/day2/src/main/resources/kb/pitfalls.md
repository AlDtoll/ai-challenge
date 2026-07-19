# Известные подводные камни (уроки челленджа)

## Open-Meteo из РФ не работает

В day17 (week4/day2) первоначальная реализация тянула прогноз с Open-Meteo (`api.open-meteo.com`). С VPS из РФ подключение падает по connect timeout. Заменили на **wttr.in** (`https://wttr.in/{lat},{lon}?format=j1`) — доступен из РФ, но требует User-Agent `curl` (иначе отдаёт HTML вместо JSON).

## Кракозябры в PowerShell

При запуске Kotlin-модулей из PowerShell русский текст в выводе портится. Одного `chcp 65001` недостаточно — PowerShell читает вывод дочернего процесса по `[Console]::OutputEncoding`, которое остаётся Windows-1251. Правильно: сначала `chcp 65001`, потом `[Console]::OutputEncoding = [System.Text.Encoding]::UTF8` — только вдвоём чинят.

## Kotlin 2.1.0 не резолвит MCP SDK

На неделе 4 при добавлении зависимости `io.modelcontextprotocol:kotlin-sdk:0.9.0` IDE стала показывать «unsupported binary format». Причина: MCP SDK 0.9.0 собран для Kotlin 2.1.20, а мы были на 2.1.0. Фикс — бамп до 2.1.20 в root `build.gradle.kts`. Ошибка коварная: IDE предлагает Downgrade Kotlin, а надо ровно наоборот — вверх.

## `./gradlew` на Windows не запускает Gradle

На Windows форма `./gradlew` пытается открыть файл ассоциированным приложением (обычно ничем) и не выполняет Gradle. Единственно правильно — `.\gradlew.bat`. Для будущих дней челленджа во всех инструкциях команды должны быть в виде `.\gradlew.bat :weekN:dayN:run ...`.

## `gradlew.bat` изначально отсутствовал

В git был только Unix `gradlew`. Пришлось добавить стандартный `gradlew.bat` (Gradle 8.x) в ветку `week4/day2` (коммит `59eff7a`). Так как между ветками мёрджей нет, при создании каждой новой ветки дня `gradlew.bat` копируется явно.
