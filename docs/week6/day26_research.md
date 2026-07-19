# Day 26 — Локальная LLM: разбор решений участников

Дата: 2026-07-10
Наш день: `week6/day1` (Day 26 «Запуск локальной LLM»). Стек: Kotlin JVM, Java HttpClient + Gson (без Retrofit/OkHttp), Windows/JDK 17, Ollama + qwen2.5:7b на RTX 3060 Laptop.

Источник авторов: OAuth-токен sheets-reader протух → работал по GitHub-кэшу `day1_links.json` + прямой опрос веток/поддиректорий 73 репо через GitHub API. Найдено 11 решений day26, из которых 3 напрямую релевантны (Kotlin + метрики + локальная LLM), ещё 2 полезны как контр-примеры.

Список авторов и репо — в `/tmp/day26_authors.json`.

Итог: самый ценный эталон — **kirilltkachev-92** (стек 1-в-1 наш, включая `java.net.http.HttpClient`, только сериализация другая). Полезные добавки — **Sergio-rsd** (guard против CJK-дрейфа + REPL), **Polurival** (Modelfile-трюк, но у нас его не нужно). Ссылка на LLM-судью для оценки правильности ответов — уместно взять у kirilltkachev.

---

## 1. kirilltkachev-92 (Kotlin, наш стек) — главный эталон

Репо: `kirilltkachev-92/AI-Advent-Challenge-8`, каталог `day 26/` в `main`.
Файлы: `Config.kt`, `OllamaClient.kt`, `Prompts.kt`, `Main.kt` (все в `src/main/kotlin/`).

### 1.1 Стек

- **HTTP-клиент:** `java.net.http.HttpClient` (JDK, без Retrofit/OkHttp/Ktor) — это тот же выбор, что у нас.
- **Сериализация:** `kotlinx.serialization` (json DSL: `buildJsonObject`, `putJsonArray`). У нас Gson — эквивалентно, стилистика чуть отличается.
- **Модель:** `qwen2.5:14b` (основная) + `qwen2.5:0.5b` (для контраста).
- **Ollama endpoint:** нативный `/api/chat` (не OpenAI-compat `/v1`), `stream=false`.

### 1.2 OllamaClient — что копировать

Клиент — 111 строк, три метода: `version()`, `localModels()`, `chat(model, prompt, system)`. Возвращает `ChatResult` со всеми метриками, которые Ollama сама отдаёт:

```kotlin
data class ChatResult(
    val answer: String,
    val promptTokens: Long,
    val answerTokens: Long,
    val loadMs: Long,   // подгрузка модели (0, если она уже в памяти)
    val totalMs: Long,  // весь запрос
    val evalMs: Long,   // чистая генерация ответа
) {
    val tokensPerSec: Double
        get() = if (evalMs > 0) answerTokens * 1000.0 / evalMs else 0.0
}
```

Ключевой инсайт: **latency и tok/s не надо мерить секундомером снаружи** — Ollama в ответе `/api/chat` отдаёт:
- `prompt_eval_count` — токены промпта.
- `eval_count` — токены ответа.
- `load_duration`, `total_duration`, `eval_duration` — в **наносекундах**, делить на 1_000_000 для мс.

Мы в плане собирались мерить через `System.currentTimeMillis()` — это будет включать сетевой оверхед и IO, что для локального сервера не критично, но менее чисто. **Рекомендация: взять оба замера** — `wallMs` (наш) и `evalMs` (из ответа), в отчёте показать оба и объяснить разницу («wall включает IO, eval — только генерация»).

### 1.3 Prompts — программная проверка ответов

Это **лучшая идея из всех разобранных**. У kirilltkachev каждый промпт — не «модель что-то сказала», а `data class Task` с полем `check: (String) -> String?`. Возвращает `null` если ответ ОК, или текст проблемы. Прогон ставит ✓ или ✗ автоматически, без глазами читать.

Их 4 задачи (мы можем взять 3, задание требует минимум):

1. **Простой факт с подвохом**: «Столица Австралии?» → ожидание Канберра, не Сидней. Проверка: `contains("Канберра", ignoreCase=true)`.
2. **Генерация кода**: `isPalindrome(s: String): Boolean` на Kotlin. Проверка: `code.contains("fun isPalindrome") && code.contains("Boolean")` (shallow — они сами это отмечают: 0.5B прошёл проверку, но код был неверный).
3. **Логическая ловушка (bat-and-ball)**: ручка+карандаш = 110₽, ручка дороже на 100₽ — сколько стоит карандаш? Проверка: `Regex("Ответ:\\s*(\\d+)").find(answer)?.groupValues[1] == "5"`. Промпт заканчивается инструкцией «в конце напиши строку “Ответ: N рублей”» — чтобы автопроверка была легкой.
4. **Структурированное извлечение JSON**: 5 полей включая нормализацию даты «12 марта 2026» → ISO 8601. Проверка парсит JSON (после `stripCodeFence`), убеждается что все поля есть, дата начинается с `2026-03-12`.

`stripCodeFence()` — обязательный хелпер для JSON-промптов, снимает ```` ```json ... ``` ```` обёртку:
```kotlin
fun stripCodeFence(text: String): String {
    val trimmed = text.trim()
    if (!trimmed.startsWith("```")) return trimmed
    return trimmed.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
}
```
(Мы в day23 разборе уже видели тот же паттерн у mobdev778.)

### 1.4 Repro-настройки

- `temperature = 0` в `options` — прогоны воспроизводимы, отчёт «честно повторяется». Мы планировали default; **лучше взять 0**, иначе таблица метрик будет плыть между запусками.
- `Duration.ofMinutes(10)` таймаут запроса — 14B на CPU может думать долго. У нас 7B на GPU, но всё равно 60-120с ставить надёжно.
- `Duration.ofSeconds(5)` connect timeout — быстро отвалиться, если сервер не запущен.

### 1.5 Health-check перед прогоном

Первые 15 строк `main()`:
1. `client.version()` — если null, печатает `Ollama serve` подсказку и выходит. Полезно.
2. `client.localModels()` — сравнивает с ожидаемым списком через `it == model || it.startsWith("$model:")` (учёт версий типа `qwen2.5:7b-instruct`).

Это две проверки, которые мы не планировали, но стоит добавить — иначе первая ошибка будет «Connection refused» на середине прогона.

### 1.6 Формат отчёта — можно копировать 1-в-1

`output/report.md`:
- Шапка: версия Ollama, endpoint, список моделей, temperature.
- Сводная таблица: Запрос | Модель | Проверка | Токены ответа | Генерация, мс | Ток/с | Всего, мс.
- Полные ответы — каждый в своём разделе с промптом и code-block ответа.

Финальный print: `"Итог: $passed/${runs.size} проверок пройдено, отчёт: $reportPath"`.

Файл: `/tmp/day26_research_repos/kirilltkachev_main/day 26/output/report.md` — есть реальный пример прогона на 14B + 0.5B.

---

## 2. Sergio-rsd (Kotlin, HttpURLConnection) — идеи, которые стоит унести

Репо: `Sergio-rsd/AI-Challenge`, ветка `week6/day-1-total-26`.
Файл: `src/apps/Week6Day1LocalLLM.kt` (~250 строк, single-file).

### 2.1 Стек

- **HTTP:** `java.net.HttpURLConnection` (тоже JDK, но старый API — HttpClient чище). Не берём.
- **JSON:** `org.json.JSONObject` (не Gson, не kotlinx.serialization). Не берём.
- **Модель:** `qwen2.5:7b-instruct` + `llama3.1:8b` (сравнение).

### 2.2 Что унести (2 идеи)

**A. Language drift guard.** Sergio явно ловит реальный дефект `qwen2.5:7b-instruct` — модель посреди длинного ответа съезжает на китайский/японский. Решение:
```kotlin
private val CJK_REGEX = Regex("[一-鿿぀-ヿ가-힯]")

private fun generateGuarded(modelName: String, question: String): LocalGenResult {
    val client = OllamaClient(modelName)
    val first = client.generate(question, system = RUSSIAN_LANGUAGE_GUARD)
    if (first.error != null || !CJK_REGEX.containsMatchIn(first.text)) return first
    // retry с усиленным напоминанием
    val retryPrompt = "$question\n\n(!) Твой предыдущий ответ ошибочно содержал китайские/японские/" +
        "корейские символы. Ответь заново СТРОГО на русском языке..."
    return client.generate(retryPrompt, system = RUSSIAN_LANGUAGE_GUARD)
}
```
Systemprompt: «Отвечай строго на русском языке от начала до конца. Даже если тебе покажется уместным переключиться на другой язык — не делай этого, это ошибка».

Нам это нужно, если увидим CJK-символы в 7b-ответах. **Рекомендация: не добавлять upfront, но держать в голове** — если в первом прогоне такое случится, добавим guard. Не осложнять код заранее.

**B. Понижение temperature до 0.3.** Sergio пишет: «понижена по умолчанию (не дефолт модели ~0.7-0.8), чтобы снизить риск языкового дрейфа на длинных ответах». У kirilltkachev 0, что ещё безопаснее. **Берём 0**.

### 2.3 Что НЕ брать

- REPL-режим (интерактивный ввод вопросов) — избыточно для отчёта, задача просит 3 запроса.
- Опциональное сравнение с облачной моделью — тоже избыточно, у нас пока плана нет сравнивать с DeepSeek.
- CJK-регекс с диапазонами `一-鿿` — красиво, но именно наша 7b на английском/русском скорее всего не будет уходить в CJK; ждать реального симптома.

---

## 3. dpmn (Python CLI-only) — референс минимальности

Репо: `dpmn/ai-advent-challenge`, ветка `day-26`. Один README, 69 строк, кода нет.

### 3.1 Формат вопросов — можно позаимствовать

3 уровня × 2 вопроса на уровень:
- **Простой**: «Сколько будет 17 × 24?», «Столица Австралии?»
- **Средний**: «Функция palindrome на Python», «Задача про яблоки Пети».
- **Сложный**: «Рекурсия vs итерация с примером где рекурсия хуже», «Верни JSON name/age/skills».

### 3.2 Что унести

- Идея «сложного» вопроса как **сравнительно-объяснительного** (рекурсия vs итерация) — это то, что 7b может проверить в осмысленности. Автопроверка тут только по длине/наличию ключевых слов.
- «Верни JSON без пояснений, только валидный JSON» — dpmn отдельно отмечает: «модель соблюла буквально, не добавила лишний текст». Это ровно наш Task#4.

### 3.3 Что НЕ брать

- Отсутствие кода — dpmn прогнал вручную через `ollama run` и записал ответы в README. Для нашего отчёта нужен код + метрики.

---

## 4. ShirobokovNE (Kotlin, OkHttp/Gson) — идея OllamaManager

Репо: `ShirobokovNE/ai-challenge`, ветка `day26`.
Файл: `src/main/kotlin/ru/myproject/aichat/OllamaManager.kt`.

### 4.1 Стек

- OkHttp + Gson + OpenAI-совместимый endpoint `/v1/chat/completions` с `apiKey = "ollama"` (константа).
- Модель — выбирается пользователем интерактивно через `listModels()`.

### 4.2 Что унести — health-check

Sanity-паттерн: `isRunning()` через GET `/api/tags` с 1-секундным таймаутом. Мы у kirilltkachev уже видели `version()` — то же самое, но с более говорящим ответом. **Берём `version()`** как у kirilltkachev.

### 4.3 Что НЕ брать (и почему)

- **Автозапуск `ProcessBuilder("ollama", "serve")`** — соблазн, но опасно: наши Windows-пользователи запускают Ollama по-своему (иногда как GUI-service). Лучше просто печатать «Запустите Ollama serve» и выходить (kirilltkachev так и делает).
- **Kill ollama через `pkill`** в `stop()` — вообще нет, чужой процесс не убиваем.
- **OpenAI-compat `/v1/chat/completions`** — работает, но при этом теряем метрики `eval_count` / `eval_duration`, которые Ollama отдаёт только через нативный `/api/chat`. Для нашей таблицы с tok/s это критично. **Берём нативный `/api/chat`.**

---

## 5. Polurival (Python + Ollama) — Modelfile-трюк и один баг

Репо: `Polurival/AI-Playground`, каталог `week_6_local_LLM/`. 3 файла: `local_llm_chat.py`, `benchmark.py`, `Modelfile`.

### 5.1 Ценный факт про Ollama `/v1` endpoint

Автор в комментариях Modelfile документирует баг: **OpenAI-совместимый `/v1` endpoint не уважает `num_ctx`, переданный через request (extra_body)** в Ollama 0.24. Через нативный `/api/chat` — работает. Это ещё один довод не идти через `/v1` (см. §4.3).

### 5.2 Что унести — before/after benchmark

Сама методика: тот же вопросник, но **два конфига** — «before» (базовая модель, default temperature=0.7) и «after» (тюненная модель, temperature=0.2, custom system-prompt). Для day26 это перебор (нам не поручено сравнивать конфиги), но **идея контр-модели (0.5b vs 7b) от kirilltkachev даёт похожий контраст** без Modelfile-магии.

### 5.3 Что НЕ брать

- Modelfile (baking system-prompt в модель) — избыточно, наша задача не тюнинг.
- `openai` SDK — тянуть зависимость ради `/v1` endpoint, теряя метрики.

---

## 6. mobdev778 (Kotlin, OkHttp) — как ничего не делать для day26

Репо: `mobdev778/aiadventchallenge8`, ветка `day_26`. Дифф: **10 строк изменений в 3 файлах**:

```kotlin
// SettingsRepository.kt
baseUrl = "http://localhost:1234/v1"  // ← был api.proxyapi.ru/openai/v1
baseModel = "qwen/qwen3-14b"           // ← был gpt-5.2

// NetworkModule.kt
CONNECT_TIMEOUT_SECONDS = 600L  // ← был 30L
WRITE_TIMEOUT_SECONDS = 600L    // ← добавлен
```

То есть у него уже был чат-плагин с OpenAI-compat клиентом → просто перенаправил на **LM Studio** (`localhost:1234/v1`) вместо proxyapi.ru. Никакого day26-специфичного кода, никаких метрик, никаких 3 промптов в коде — «оно уже работало, стало ходить локально».

**Урок для Данила:** это валидный подход **только когда у тебя уже есть готовый чат-клиент**. У нас его нет — план создать OllamaClient с нуля правильный.

---

## 7. Проверка нашего плана против эталонов

Наш план (из брифа):
> Минимальный Kotlin — `OllamaClient` (Java HttpClient + Gson) → 3 промпта → таблица метрик (latency, tokens, tok/s) → `day26_report.md`.

### 7.1 HTTP-клиент — Java HttpClient. **Правильно.**

kirilltkachev, dpmn, gavris, Polurival — половина использует чистый HttpClient/urllib. OkHttp + Retrofit — избыточно для 3 запросов. Ктор — тоже перебор.

### 7.2 Endpoint — нативный `/api/chat`. **Правильно, менять план.**

В бриф-плане не указано, какой endpoint. **Берём `/api/chat`** (не `/v1/chat/completions`), потому что:
- Нативный endpoint отдаёт `prompt_eval_count`, `eval_count`, `eval_duration` — все нужные метрики уже в ответе.
- OpenAI-compat `/v1` даёт «стандартную» форму ответа, но обрезает Ollama-специфичные поля метрик.
- Мы не переиспользуем клиент с DeepSeek — можно смело идти нативным путём.

### 7.3 Метрики. **План уточнить.**

Наш план: latency, tokens, tok/s.
Эталонная схема (kirilltkachev):
- `promptTokens` (`prompt_eval_count` из ответа)
- `answerTokens` (`eval_count`)
- `evalMs` (`eval_duration` / 1e6)
- `totalMs` (`total_duration` / 1e6)
- `loadMs` (`load_duration` / 1e6) — 0 при повторных вызовах, >0 при первой подгрузке модели в память
- `tokensPerSec = answerTokens * 1000.0 / evalMs`

**Все брать из ответа Ollama, а не мерить `System.currentTimeMillis()`.** Если совсем перфекционизм — добавить ещё `wallMs` через `System.currentTimeMillis()` вокруг `httpClient.send()`, чтобы показать разницу «wall vs eval» (обычно wall = eval + IO + JSON parsing, для 7b на локалхосте это доли процента, но заметно на первом вызове из-за load_duration).

### 7.4 3 промпта. **Взять 3 из 4 у kirilltkachev.**

Рекомендация:

| # | Уровень | Промпт | Автопроверка |
|---|---------|--------|--------------|
| 1 | Простой факт | «Какой город является столицей Австралии? Ответь одним словом.» | `contains("Канберра", ignoreCase=true)` |
| 2 | Логическая задача | Bat-and-ball про ручку+карандаш = 110₽ | `Regex("Ответ:\\s*(\\d+)").find(a)?.groupValues[1] == "5"` |
| 3 | JSON extraction | «Извлеки JSON из текста: 12 марта 2026, Иван Петров, Новосибирск, 4990₽, дата ISO 8601» | `Json.parseToJsonElement(stripCodeFence(a)).jsonObject`, проверка полей + `date.startsWith("2026-03-12")` |

Отбрасываем task#2 «isPalindrome» из-за shallow-проверки (kirilltkachev сам её отмечает как проблемную: 0.5B прошла формально, а логика была битая). Заменяем логической ловушкой — она чище проверяется.

Если хочется 4-й — возьмите «объяснительный» из dpmn: «Объясни разницу между рекурсией и итерацией с примером где рекурсия хуже» — здесь автопроверка невозможна, только глазами. Пометить в отчёте как «качественная проверка», не считать в pass/fail.

### 7.5 Temperature. **`0` (repro), не default.**

kirilltkachev делает `temperature = 0` в `options`. У Danila в плане параметр не указан. Ставим 0 — чтобы отчёт был воспроизводимым и таблица не плыла между прогонами.

### 7.6 Что добавить в план

1. **Health-check перед прогоном** (kirilltkachev §1.5): `client.version()` + `client.localModels()` → если модель не скачана, печатать `ollama pull qwen2.5:7b` и выходить. 20 строк, экономит время дебага.
2. **`stripCodeFence()` хелпер** для JSON-промпта. 4 строки.
3. **`Duration.ofMinutes(2)` timeout** на запрос (у kirilltkachev 10 мин — избыточно, у нас 7b на GPU быстрее). Connect timeout 5 сек.
4. **`data class ChatResult(...)`** с полями из §7.3 — единый объект вместо кучи локальных `var`.
5. **`load_duration` в отчёте** — покажет что первый вызов «стоил» модели подгрузки; это интересный факт для видео.

### 7.7 Что НЕ добавлять

- CJK-guard (Sergio) — только если увидим в реальном прогоне.
- Второй модели (0.5b) для сравнения — если есть время и место на диске, добавьте; но задание требует одну, и Данил уже поставил только 7b. Достаточно.
- OpenAI-compat `/v1` endpoint — теряем метрики.
- Автозапуск `ollama serve` из кода (Shirobokov) — рискованно и не универсально.
- Modelfile / кастомизация модели (Polurival) — не в задании.

---

## 8. Финальная рекомендация по структуре кода

Рекомендуемая раскладка `week6/day1/`:

```
week6/day1/
├── build.gradle.kts           # kotlin-jvm, no-Retrofit; Gson для JSON, java.net.http.HttpClient
├── src/main/kotlin/
│   ├── OllamaClient.kt        # version(), localModels(), chat() → ChatResult (~110 строк)
│   ├── Prompts.kt             # data class Task + список из 3 задач + stripCodeFence (~80 строк)
│   ├── Config.kt              # baseUrl, model, outputDir (env vars или дефолты) (~20 строк)
│   └── Main.kt                # health-check → прогон → отчёт → println итога (~80 строк)
├── output/report.md           # автогенерируемый отчёт прогона (git-ignored или коммитим на память)
└── README.md                  # инструкция запуска + краткая таблица результатов
```

Итого ~300 строк Kotlin. Полностью соответствует духу «минимально, без магии» (правило Данила из брифа).

Gson vs kotlinx.serialization — оба валидны. У kirilltkachev kotlinx (buildJsonObject DSL), у нас в предыдущих днях Gson. **Оставляем Gson** — Данил уже с ним работает, не менять инструмент под один день.

---

## Приложение A. Список найденных решений day26

Полностью в `/tmp/day26_authors.json`. Кратко (по релевантности):

| # | Автор | Стек | Модель | Что взять |
|---|-------|------|--------|-----------|
| 1 | kirilltkachev-92 | Kotlin, java.net.http.HttpClient, kotlinx.serialization | qwen2.5:14b + 0.5b | всё: клиент, промпты с автопроверкой, метрики из ответа Ollama, формат отчёта |
| 2 | Sergio-rsd | Kotlin, HttpURLConnection, org.json | qwen2.5:7b + llama3.1:8b | CJK-guard в запасе, идея сравнения моделей |
| 3 | ShirobokovNE | Kotlin, OkHttp, Gson | user-selected | health-check через `/api/tags` (но у kirilltkachev через `/api/version` чище) |
| 4 | mobdev778 | Kotlin, OkHttp (уже был) | qwen/qwen3-14b via LM Studio | ничего — просто переключил baseUrl |
| 5 | dpmn | Python CLI-only | qwen2.5-coder:7b | идея сложного объяснительного вопроса |
| 6 | GavrisAS | Python stdlib (urllib) | qwen3.6:27b (несуществующая?) | ничего — overengineered 505 строк, JSON+MD артефакты |
| 7 | Polurival | Python + OpenAI SDK + Modelfile | qwen2.5:3b | факт про баг `/v1`+num_ctx |
| 8 | Mystery-xx | Java Spring Boot + React | ? | ничего — RAG-приложение, не про день 26 |
| 9 | 1Avalanche | Kotlin Multiplatform | nomic-embed-text | ничего — про эмбеддинги |
| 10 | VerushkinRoman | Kotlin + Ktor | ? | ничего — минимальный router-пример |
| 11 | kperederiy | Android + OkHttp | ? | ничего — Android-обёртка |

---

## Приложение B. Ссылки на файлы эталонов

### kirilltkachev-92 (в порядке важности)
- `day 26/src/main/kotlin/OllamaClient.kt` — https://github.com/kirilltkachev-92/AI-Advent-Challenge-8/blob/main/day%2026/src/main/kotlin/OllamaClient.kt
- `day 26/src/main/kotlin/Prompts.kt` — там же
- `day 26/src/main/kotlin/Main.kt` — там же
- `day 26/README.md` — там же
- `day 26/output/report.md` — реальный прогон 14b + 0.5b

### Sergio-rsd
- `src/apps/Week6Day1LocalLLM.kt` (ветка `week6/day-1-total-26`)

### ShirobokovNE
- `src/main/kotlin/ru/myproject/aichat/OllamaManager.kt` (ветка `day26`)

### dpmn
- `week-06/day-26/README.md` (ветка `day-26`)

### mobdev778
- Дифф ветки `day_26` vs `day_25`: 10 строк в `SettingsRepository.kt` + `NetworkModule.kt`

Клоны локально: `/tmp/day26_research_repos/`.
