# Day 5 — Model Comparison

Same prompt, three models + a judge:

| Level | Model | Price |
|-------|-------|-------|
| Weak | `meta-llama/llama-4-scout:free` | free |
| Medium | `meta-llama/llama-3.3-70b-instruct:free` | free |
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
