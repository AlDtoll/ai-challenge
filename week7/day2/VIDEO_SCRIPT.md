# Немой скринкаст — День 32

Формат тот же что дни 29-31: экран без голоса, ~1.5-2 мин.

## Сцены

1. **Заставка (2 сек)** — «День 32. AI-ревью PR».
2. **PowerShell**: `git checkout week7/day2` → `cat .github/workflows/pr-review.yml` (промотать) → `cat week7/day2/README.md | head -30`.
3. **GitHub UI**: перейти на github.com/AlDtoll/ai-challenge, показать что в Settings → Secrets есть DEEPSEEK_API_KEY.
4. **Сделать тестовый PR**: локально создать ветку `demo/pr-review-test`, добавить один намеренно проблемный файл (например, короткий Kotlin с забытой NPE-проверкой). `git push -u origin demo/pr-review-test`.
5. **На GitHub**: открыть PR, показать в Files changed что там.
6. **Actions tab**: увидеть как запустился workflow «AI PR Review», развернуть job → показать шаги «Extract diff» → «Run AI reviewer» → «Post review comment».
7. **Conversation tab**: показать сгенерированный ревью-комментарий от @github-actions[bot] — три секции 🐛/🏗️/💡 + сноска с источниками RAG.
8. **Закрыть PR** (без merge).
9. **Конец (2 сек)** — «Готово».

## Что подсвечивается в описании ролика

- Свой Kotlin-скрипт, никакой готовой Action-обёртки.
- BM25 RAG над проектной документацией — offline, детерминированно.
- DeepSeek chat, структурированный system prompt (3 секции).
- Обрезка diff'а по MAX_DIFF_CHARS — экономия токенов.
- Пост через `gh pr comment --body-file`.
