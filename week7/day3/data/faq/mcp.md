# FAQ — MCP-сервер и интеграции

## Аутентификация к MCP-серверу из CI

MCP-сервер требует Bearer-токен в заголовке `Authorization`. В GitHub Actions обычно ломается на 401, потому что:

1. **Токен из личного PAT** — работает только с интерактивной сессии, в headless CI выдаётся 401. Нужен **service account token**, создаётся в `Settings → API → Service Tokens`.
2. **Токен просрочен** — по умолчанию TTL 90 дней. Проверить в `Settings → API → Active Tokens`.
3. **Токен без scope `mcp:invoke`** — по умолчанию scope только `read`. При выпуске service-token выбрать `mcp:invoke` явно.
4. **IP allowlist аккаунта TEAM/BUSINESS** — если включён, IP-диапазоны GitHub Actions runners нужно занести (`api.github.com/meta` → поле `actions`).

Локально работает потому что PAT + user-context сохраняются в связке `~/.config/aichallenge/credentials.toml`.

## Список MCP-инструментов v1

- `get_ticket(id)` — вернуть тикет по id.
- `get_user(id)` — вернуть карточку пользователя.
- `list_open_tickets(user_id, limit=10)` — открытые тикеты пользователя.
- `create_ticket(user_id, title, description, tags)` — создать тикет (нужен `mcp:invoke`).
- `search_kb(query, top_k=3)` — поиск по FAQ и docs (BM25 top-K).

## Как добавить свой tool

Использовать SDK `kotlin-sdk-server:0.13.0`, вызвать `server.addTool(name, description, inputSchema) { req → CallToolResult(...) }`. Пример — в `week4/day2/src/main/kotlin/Main.kt`.

## Streamable HTTP transport

MCP-сервер по-умолчанию на `127.0.0.1:3001/mcp`. Для внешнего доступа — прокинуть через nginx с TLS. По умолчанию доступ только с localhost.
