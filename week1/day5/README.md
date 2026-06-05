# Day 5 — Model Comparison

Same prompt, three models + a judge:

| Level | Model | Price |
|-------|-------|-------|
| Weak | `meta-llama/llama-3.1-8b-instruct:free` | free |
| Medium | `deepseek/deepseek-chat` | $0.14/$0.28 per 1M tokens |
| Strong | `deepseek/deepseek-r1:free` | free |
| Judge | `deepseek/deepseek-r1:free` | free |

Measures: response time, token count, cost.

## Setup

Add to `.env` in project root:
```
OPENROUTER_API_KEY=sk-or-...
```

## Run

Open `ai-challenge/` in Android Studio, select `:week1:day5` and click Run.

Or via terminal:
```bash
./gradlew :week1:day5:run
```
