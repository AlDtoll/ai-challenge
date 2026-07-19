#!/usr/bin/env bash
#
# Обёртка для cron: собирает недельный VPS-отчёт и шлёт Данилу в Telegram.
# Запуск раз в неделю, воскресенье 20:00 NSK = 13:00 UTC.
#
# Cron-запись:
#   0 13 * * 0  /home/claudeuser/ai-challenge/week7/day5/deploy/weekly-vps-report.sh >> /tmp/vps-report.log 2>&1
#
# Секреты:
#   TELEGRAM_BOT_TOKEN и DEEPSEEK_API_KEY подхватятся из ~/.claude/env/secrets.env
#   (читает сам Kotlin-модуль, см. Config.kt).
#
set -euo pipefail

REPO="${REPO:-/home/claudeuser/ai-challenge}"
cd "$REPO"

# На всякий случай подтянуть свежие данные (мало ли).
git fetch --quiet origin || true

# Собственно запуск. --send реально отправит; без него — только stdout в лог.
./gradlew :week7:day5:run --args="--send" --console=plain --quiet
