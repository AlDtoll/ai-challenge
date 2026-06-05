# AI Challenge

6-week AI development challenge.

## Structure

```
ai-challenge/
  .env              ← API keys (not committed)
  .env.example      ← template
  week1/
    day1/  ← First LLM API call (CLI)
    day2/  ← Response format control
    day3/  ← Reasoning strategies (direct / step-by-step / meta-prompting / experts)
    ...
  week2/
    ...
```

## Setup (once)

1. Get a free API key at [platform.deepseek.com](https://platform.deepseek.com)
2. Create `.env` in the project root:
   ```
   DEEPSEEK_API_KEY=sk-...
   ```
3. Open the root `ai-challenge/` folder in Android Studio

## Run

Select the module (`:week1:day1`, `:week1:day2`, etc.) in Run Configurations and click Run.
No environment variables needed — the key is loaded from `.env` automatically.
