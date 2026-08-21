# Harness Kit — Указатель по темам

## context_management/ — управление контекстным окном

- [context_compression.md](context_management/context_compression.md) — сжатие истории через incremental LLM-summary при переполнении контекста
- [sliding_window.md](context_management/sliding_window.md) — скользящее окно последних N сообщений; дешевле compression для коротких сессий
- [sticky_facts.md](context_management/sticky_facts.md) — ключевые факты (профиль, ограничения) вне rolling-истории; никогда не вытесняются
- [persistent_context.md](context_management/persistent_context.md) — история в JSON по chatId + мониторинг заполнения окна + friendly overflow-обработка

## memory_layers/ — трёхслойная память

- [three_layer_memory.md](memory_layers/three_layer_memory.md) — Short (L1) / Working (L2) / Long (L3) по аналогии CPU-кэша; онбординг при первом запуске
- [profile_extractor.md](memory_layers/profile_extractor.md) — LLM-экстрактор профиля из диалога; SystemPromptBuilder из профиля; implicit learning

## state_machine/ — управление состоянием задачи

- [task_state_machine.md](state_machine/task_state_machine.md) — конечный автомат IDLE→PLANNING→EXECUTION→VALIDATION→DONE + JSONL audit log
- [invariant_guard.md](state_machine/invariant_guard.md) — двухслойный guard (regex + LLM-судья) с silent rollback; плохой ответ не отравляет историю
- [gate_transitions.md](state_machine/gate_transitions.md) — таблица переходов с условиями (planApproved, executionComplete); детерминированный workflow

## mcp/ — MCP-серверы и оркестрация

- [mcp_client_basics.md](mcp/mcp_client_basics.md) — минимальный клиент (initialize + tools/list + callTool) без библиотек + первый собственный сервер
- [mcp_background_tasks.md](mcp/mcp_background_tasks.md) — start_watch/get_summary/stop_watch; персистентный JSONL журнал для 24/7 агента
- [mcp_orchestration_namespace.md](mcp/mcp_orchestration_namespace.md) — N серверов с namespace-префиксом; роутер без коллизий имён

## rag/ — Retrieval-Augmented Generation

- [rag_chunking.md](rag/rag_chunking.md) — fixed-size vs structural chunking (по Markdown-заголовкам); метрики Recall@3 и MRR
- [rag_reranker_and_rewrite.md](rag/rag_reranker_and_rewrite.md) — threshold фильтр + LLM-реранкер (JSON scores) + query rewrite + LLM-as-judge паттерн
- [rag_anti_hallucination.md](rag/rag_anti_hallucination.md) — ALLOWED_QUOTES + structured JSON output + retry-loop + soft-abstain
- [rag_task_state_enrichment.md](rag/rag_task_state_enrichment.md) — TaskState (5 полей) обогащает retrieval; агент «помнит цель» сессии
- [bm25_offline_index.md](rag/bm25_offline_index.md) — BM25 без Ollama для CI/CD; dual-fallback Ollama→BM25 для graceful degradation

## local_llm/ — локальные LLM через Ollama

- [ollama_local_setup.md](local_llm/ollama_local_setup.md) — Kotlin-клиент /api/chat + RAG-CLI + LlmBackend абстракция local↔cloud
- [ollama_rag_tuning.md](local_llm/ollama_rag_tuning.md) — критический тюнинг: num_ctx 2048→8192 (silent bug!), temperature 0, stop-sequences, prompt-шаблон
- [local_llm_http_service.md](local_llm/local_llm_http_service.md) — JDK stdlib HTTP-сервис: /health, /v1/chat, /v1/rag, /stats; Bearer auth, rate limit

## agentic_loop/ — автономные агентные циклы

- [subagents_parallel.md](agentic_loop/subagents_parallel.md) — параллельные субагенты для batch-задач через Task tool
- [agentic_loop_tool_calls.md](agentic_loop/agentic_loop_tool_calls.md) — LLM tool_choice=auto → MCP → обратно, до MAX_TOOL_ITER=12; apply_patch; safeResolve sandbox

## rules_system/ — правила и профили агентов

- [claude_md_v2_rules.md](rules_system/claude_md_v2_rules.md) — CLAUDE.md v2: точечные правила с примерами «плохо/хорошо»; 100% compliance на Zizz3
- [agent_profiles.md](rules_system/agent_profiles.md) — специализированные профили .claude/agents/*.md (bug-fix, research, screenshot-baseline)

## security/ — безопасность LLM-агентов

- [prompt_injection_defense.md](security/prompt_injection_defense.md) — 3-слойная защита: skill-фильтр + CLAUDE.md правила + output-guard; Lethal Trifecta
- [indirect_injection_defenses.md](security/indirect_injection_defenses.md) — 3 вектора (HTML/zero-width/display:none) + sanitize_html + content_boundary + output_validator
- [llm_gateway.md](security/llm_gateway.md) — FastAPI-прокси: input guard (10 regex) + output guard + rate limit + SQLite audit + cost tracking
- [security_execution_loop.md](security/security_execution_loop.md) — generate → lint → security_review → decide (BLOCK/WARN/OK, max 3 iter) через Gateway
