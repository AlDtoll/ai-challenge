# Day 3 — Reasoning Strategies

One task, four ways to solve it:
1. **Direct** — plain question
2. **Step-by-step** — "solve step by step"
3. **Meta-prompting** — model writes the prompt, then solves with it (2 API calls)
4. **Panel of experts** — Analyst, Engineer, Critic via system prompt

## Setup

Create `.env` in the project root (`ai-challenge/.env`):
```
DEEPSEEK_API_KEY=sk-...
```

## Run

Open `ai-challenge/` in Android Studio, select `:week1:day3` in Run Configurations and click Run.

Or via terminal:
```bash
./gradlew :week1:day3:run
```
