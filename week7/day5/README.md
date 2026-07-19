# День 35 — Weekly VPS Report Bot (реальная задача)

## Какую задачу решаю

У меня (Данила) на одном VPS живёт 15+ Telegram-ботов + 5 активных репозиториев (ai-challenge, fuel-map, zizz3, antivoice-bot, twiligihts). Раньше каждое воскресенье я вручную:

1. Пробегал `git log` по каждому проекту, чтобы понять что закоммитил за неделю.
2. Лез в `~/sessions/*/bot.log` и смотрел `grep 'exited with code'` — не падал ли какой бот от OOM.
3. Открывал quickai (`~/.local/bin/quickai stats`) — сколько денег/токенов ушло на Claude.
4. Проверял `df -h` / `free -h` — не забился ли диск, не в трэшинг ли ушёл swap.

Итого 10-15 минут ручной работы каждое воскресенье, часто пропускал и потом кусал локти. Теперь это делает сам сервис: собирает данные, отдаёт LLM, шлёт мне готовый краткий отчёт в Telegram.

## Как AI участвует

- Свой **MCP-сервер** (Streamable HTTP `:3005`) с 4 tools над VPS: `vps_git_activity(days)`, `vps_bots_health(days)`, `vps_token_spend(days)`, `vps_system()`. Каждый — типизированный, не bash-инъекция.
- **MCP-клиент внутри же процесса** дёргает все 4 tools.
- **DeepSeek** получает system prompt с чётким шаблоном отчёта (5 секций: активность/боты/токены/система/на что обратить внимание) и данные. Форматирует в человеческий текст.
- Отчёт отправляется в Telegram bot API как одно короткое сообщение.

Побочная выгода: MCP-сервер можно подключить к Claude Desktop — тогда я могу с ноутбука спросить «покажи git-активность за 3 дня» и получить те же данные через MCP.

## Файлы

- `Config.kt` — конфиг, читает секреты из `~/.claude/env/secrets.env`, разумные default'ы для VPS-путей.
- `DataSources.kt` — реальные команды (git log, grep, df, free, SQLite quickai.db).
- `ReportMcp.kt` — MCP-сервер с 4 tools.
- `Telegram.kt` — тонкий клиент Bot API (chunked на 3900 символов, лимит Telegram).
- `Deepseek.kt` — тот же клиент что day31-34.
- `Main.kt` — оркестратор: поднимает MCP, тянет данные, зовёт LLM, шлёт в TG.
- `deploy/weekly-vps-report.sh` — обёртка для cron.

## Как запустить

Локально в dry-run (без отправки, только stdout):
```powershell
$env:DEEPSEEK_API_KEY = "sk-…"
.\gradlew.bat :week7:day5:run
```

На VPS с отправкой (секреты подхватятся из `~/.claude/env/secrets.env`):
```bash
cd /home/claudeuser/ai-challenge
./gradlew :week7:day5:run --args="--send"
```

## Cron

```cron
# Weekly VPS report — воскресенье 20:00 NSK = 13:00 UTC
0 13 * * 0  /home/claudeuser/ai-challenge/week7/day5/deploy/weekly-vps-report.sh >> /tmp/vps-report.log 2>&1
```

## Что реалистично не работает

- **Sandbox токенов** — использует TG-токен common-бота. Если завтра общий common-бот закрою — сломается. Правильнее — свой отдельный `@aldtollReportBot` через managed bots API, но для минимального рабочего варианта пока хватает.
- **Секцию «на что обратить внимание»** LLM иногда галлюцинирует, если данных мало. Реально важное я жёстко бы правил-эвристиками, а LLM использовал только для форматирования; можно докрутить.
- **`quickai.db` timestamps в миллисекундах** (проверял руками, memory `quickai-stats` подтверждает) — учтено в SQL WHERE.
- **Диск на 91%** — сам себе флаг: скоро надо чистить бэкапы.

## Ограничения / что дальше

- Отдельно от MCP-tool'ов можно добавить `vps_backup_status` (последний git commit в vps-backup репо) и `vps_reminders_summary` (что стоит в reminders.json на следующей неделе).
- В идеале — не только отчёт, но и **action-items**: LLM говорит «предлагаю рестартнуть бот X, там 12 падений» и я одобряю кнопкой в TG.
- Ещё дальше — сам сервис можно подключить в общий common-бот как MCP-инструмент, и я в чате спрашиваю «what's up on VPS?» — приходит live-отчёт.
