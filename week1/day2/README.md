# Day 2 — Response Format Control

The same question is sent twice:
- **Without constraints** — free response
- **With constraints** — system prompt with format + `max_tokens` + `stop sequence`

`finish_reason` shows why the model stopped: `stop` = finished naturally, `length` = cut off by token limit.

## Setup

Create `.env` in the project root (`ai-challenge/.env`):
```
DEEPSEEK_API_KEY=sk-...
```

## Run

Open `ai-challenge/` in Android Studio, select `:week1:day2` in Run Configurations and click Run.

Or via terminal:
```bash
./gradlew :week1:day2:run
```
