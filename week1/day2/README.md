# Day 2 — Формат ответа

Один и тот же вопрос отправляется дважды:
- **Без ограничений** — свободный ответ
- **С ограничениями** — system prompt с форматом + `max_tokens` + `stop sequence`

По `finish_reason` видно почему модель остановилась: `stop` = завершила сама, `length` = обрезали по лимиту.

## Запуск

### Android Studio
1. File → Open → `week1/day2`
2. Run → Edit Configurations → Environment variables: `DEEPSEEK_API_KEY=sk-...`
3. Запусти `main()`

### Терминал
```bash
export DEEPSEEK_API_KEY=sk-...
cd week1/day2
./gradlew run
```
