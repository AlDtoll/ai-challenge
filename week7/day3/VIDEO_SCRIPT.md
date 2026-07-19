# Немой скринкаст — День 33

## Сцены

1. Заставка «День 33. Ассистент поддержки».
2. PowerShell → `chcp 65001` → `git branch --show-current` (week7/day3) → `cat week7/day3/data/tickets.json | head -20`.
3. `.\gradlew.bat :week7:day3:run`:
   - «RAG режим: bm25 (offline)» или «ollama» (в зависимости)
   - «FAQ: нашёл 4 md-файлов»
   - «MCP support-сервер поднят на localhost:3003»
   - «MCP tools: get_user, get_ticket, list_open_tickets»
4. `/list` — показать 4 тикета.
5. `/ask t_501 почему не приходит письмо для подтверждения email?` — LLM отвечает по данным тикета (Данил, Gmail, PRO) и FAQ auth.md ссылками [F1]/[F2].
6. `/ask t_502 могу ли я использовать SSO на своем плане?` — по данным (Саша, FREE, ждёт SSO) LLM отвечает «SSO только TEAM+, нужен апгрейд». Показать [F1] из billing.md.
7. `/ask t_503 почему падает MCP-tool в GitHub Actions?` — LLM по контексту (Юрий, TEAM, приоритет urgent) отвечает про service-account token и scope `mcp:invoke`. Ссылка [F1] на mcp.md.
8. `/quit`.
9. Конец.

## Что подсветить в описании

- Свой MCP-сервер с 3 tool'ами (не готовый filesystem-MCP).
- «CRM» — JSON, но контракт как у настоящей: `get_ticket`, `get_user`, `list_open`.
- RAG с двухрежимной работой: Ollama + BM25 fallback (у меня в CI/на VPS работает второй).
- Приоритет источников в system prompt — тикет > пользователь > FAQ > догадки.
