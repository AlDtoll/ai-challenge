"""
9 защитных слоёв для AI Target Bot.
Каждый слой — отдельная функция с чётко определённой зоной ответственности.
"""
import re
import logging
import unicodedata
import bleach
from typing import Optional

logger = logging.getLogger(__name__)

# Таблица транслитерации кириллических гомоглифов → латинские эквиваленты.
# Используется для нормализации перед проверкой injection-паттернов.
_HOMOGLYPH_TABLE = str.maketrans({
    # Кирилличные буквы, визуально идентичные латинским
    'а': 'a', 'е': 'e', 'о': 'o', 'р': 'p', 'с': 'c',
    'х': 'x', 'і': 'i', 'ї': 'i', 'ј': 'j', 'ѕ': 's',
    'А': 'A', 'В': 'B', 'Е': 'E', 'К': 'K', 'М': 'M',
    'Н': 'H', 'О': 'O', 'Р': 'P', 'С': 'C', 'Т': 'T',
    'Х': 'X', 'І': 'I',
    # Греческие гомоглифы
    'ο': 'o', 'Ο': 'O', 'α': 'a',
})

# Regex для удаления zero-width и невидимых управляющих символов.
# Категории Unicode: Cf (format chars) включает ZWS, ZWNJ, ZWJ, BOM и т.д.
_INVISIBLE_CHARS_RE = re.compile(r'[​-‏‪-‮⁠-⁤﻿]')


def _normalize_for_injection_check(text: str) -> str:
    """
    Нормализация текста перед проверкой injection-паттернов.
    Удаляет: zero-width символы, emoji, кириллические гомоглифы → Latin.
    Не затрагивает обычный русский текст (кирилличные буквы без латинских двойников).
    """
    # 1. Удалить zero-width и invisible Unicode chars
    text = _INVISIBLE_CHARS_RE.sub('', text)
    # 2. Заменить emoji на пробел (убрать разделители-emoji из потока)
    # Категория So = Other Symbol, Sm = Math Symbol; emoji обычно в So/Cs
    result = []
    for ch in text:
        cat = unicodedata.category(ch)
        if cat in ('So', 'Cs', 'Cn'):
            result.append(' ')
        else:
            result.append(ch)
    text = ''.join(result)
    # 3. Заменить кириллические гомоглифы → Latin
    text = text.translate(_HOMOGLYPH_TABLE)
    return text

# ─── Слой 1: Паттерны prompt-инъекций ────────────────────────────────────────
INJECTION_PATTERNS = [
    # EN — direct override / forget
    r'(?i)ignore\s+(all\s+)?(previous|prior)\s+instructions',
    r'(?i)ignore\s+(all\s+|the\s+)?(instructions|directives|rules)',  # "ignore instructions above/below"
    r'(?i)disregard\s+(all\s+)?(previous|prior)\s+instructions',
    r'(?i)forget\s+(all\s+)?(previous|prior)\s+instructions',
    # EN — persona switch
    r'(?i)you\s+are\s+now\s+\w+',
    r'(?i)pretend\s+(you\s+are|to\s+be)\s+',
    r'(?i)act\s+as\s+(a\s+)?(dan|dev\s?mode|jailbreak|grandma|different\s+ai)',
    r'(?i)from\s+now\s+on\s+you\s+will\s+',
    r'(?i)from\s+now\s+on,?\s+you\s+are(\s+|$)',
    # EN — exfiltration / reveal
    r'(?i)reveal\s+(your|the)\s+(system\s+)?(instructions|prompt)',
    r'(?i)display\s+(your|the)\s+(system\s+)?(instructions|prompt)',
    r'(?i)print\s+(the\s+)?system\s+prompt',
    r'(?i)show\s+me\s+(your|the)\s+instructions',
    r'(?i)repeat\s+everything\s+(above|before|verbatim)',
    r'(?i)output\s+(your|the)\s+(system\s+)?(prompt|instructions)',
    # EN — bypass / jailbreak
    r'(?i)do\s+anything\s+now',
    r'(?i)without\s+restrictions',
    r'(?i)bypass\s+(your|all|safety)',
    # EN — structural markup injection
    r'(?i)system\s*:\s*',
    r'(?i)</?(system|assistant|user)>',
    r'(?i)```(system|instruction)',
    # RU — override / forget
    r'(?i)игнориру[йе]\s+(все\s+)?(предыдущие|прежние)\s+инструкции',
    r'(?i)игнориру[йе]\s+(все\s+)?(инструкции|правила|ограничения)',  # "Игнорируй все инструкции"
    r'(?i)забудь\s+(все\s+)?(предыдущие|прежние)\s+инструкции',
    r'(?i)отбрось\s+(все\s+)?(предыдущие|прежние)\s+инструкции',
    # RU — persona switch
    r'(?i)ты\s+теперь\s+\w+',
    r'(?i)ты\s+больше\s+не\s+',
    r'(?i)притворись\s+(что\s+ты\s+)?(dan|дан|другим\s+ии|без\s+ограничений)',
    r'(?i)теперь\s+ты\s+(dan|дан|jailbreak)',
    # RU — exfiltration
    r'(?i)покажи\s+(мне\s+)?(свой\s+)?(системный\s+)?промпт',
    r'(?i)выведи\s+(свои\s+)?инструкции',
    r'(?i)повтори\s+(всё|все)\s+(выше|дословно|verbatim)',
    r'(?i)раскрой\s+(свои\s+)?(инструкции|промпт)',
    # RU — bypass
    r'(?i)без\s+ограничений',
    r'(?i)обойди\s+(защиту|правила|фильтр)',
    # Mixed-language bypass (EN/RU/ES/other mix)
    # R2-04: "Ignore предыдущие instrucciones" — смешивает языки чтобы обойти моноязычные паттерны
    r'(?i)ignore\s+\S+\s+\S*(instrucciones|instr|instructions)',
    r'(?i)(ignore|забудь|игнорируй)\s+\S*\s*(предыдущие|instrucciones|previous)\s+\S*(instr|instructions|инструкции)',
    r'(?i)(show|покажи|muestra)\s+\S*(promt|prompt|промпт|система|system)',
]

# ─── Слои 3 и 4: Паттерны секретов ───────────────────────────────────────────
SECRET_PATTERNS = [
    r'sk-[a-zA-Z0-9-]{20,}',        # API ключи (включая наш OAuth токен)
    r'AKIA[0-9A-Z]{16}',             # AWS access keys
    r'ghp_[a-zA-Z0-9]{36}',          # GitHub personal access tokens
    r'INTERNAL_API_KEY',             # маркер нашего внутреннего ключа
    r'(?i)internal.api.key',         # вариации написания
]


def _normalize_for_secret_check(text: str) -> str:
    """Нормализация: удалить пробелы/переводы/невидимые символы, нижний регистр."""
    # Удаление всех whitespace (пробелов, табов, newlines, невидимых)
    text = re.sub(r'[\s​-‏‪-‮﻿]+', '', text)
    return text.lower()


def check_output_for_secrets(reply: str, internal_key: str) -> tuple[bool, str]:
    """
    Слой 4 (расширенный): проверка ответа модели на утечку секрета.
    Возвращает (leaked: bool, matched_pattern: str).
    Обнаруживает: прямое вхождение, пробелы/невидимые, base64, hex.
    """
    import base64

    # 1. Прямая проверка — сам ключ (case-insensitive, exact)
    if internal_key.lower() in reply.lower():
        return True, "direct-key"

    # 2. Проверка нормализованного текста — обход через пробелы и невидимые
    normalized = _normalize_for_secret_check(reply)
    key_normalized = internal_key.lower().replace('-', '').replace('_', '')
    if key_normalized in normalized.replace('-', '').replace('_', ''):
        return True, "obfuscated-key"

    # 3. Проверка через base64 — включая двойное/тройное кодирование (R2-07)
    for match in re.findall(r'[A-Za-z0-9+/]{20,}={0,2}', reply):
        # Итеративно декодируем до 3 раз (одиночный, двойной, тройной base64)
        payload = match
        for _ in range(3):
            try:
                decoded_bytes = base64.b64decode(payload, validate=True)
                decoded = decoded_bytes.decode('utf-8', errors='ignore')
                if internal_key.lower() in decoded.lower():
                    return True, "base64-encoded"
                # Следующая итерация: пробуем декодировать результат ещё раз
                payload = decoded.strip()
            except Exception:
                break

    # 4. Проверка hex encoding (48+ hex chars — потенциальный ключ hex-encoded)
    for match in re.findall(r'\b[0-9a-fA-F]{40,}\b', reply):
        try:
            decoded = bytes.fromhex(match).decode('utf-8', errors='ignore')
            if internal_key.lower() in decoded.lower():
                return True, "hex-encoded"
        except Exception:
            pass

    return False, ""


# ─── Слой 7: Паттерны небезопасного кода ─────────────────────────────────────
INSECURE_CODE_PATTERNS = [
    r'eval\s*\(',
    r'os\.system\s*\(',
    r'subprocess\.(call|run|Popen)\s*\(',
    r'exec\s*\(',
    r'__import__\s*\(',
    r'compile\s*\(',
]

# Компилируем паттерны один раз при загрузке модуля
_compiled_injection = [re.compile(p) for p in INJECTION_PATTERNS]
_compiled_secrets = [re.compile(p) for p in SECRET_PATTERNS]
_compiled_insecure = [re.compile(p) for p in INSECURE_CODE_PATTERNS]


def check_prompt_injection(message: str) -> Optional[str]:
    """
    Слой 1: prompt-injection-guard.
    Проверяет входящее сообщение на паттерны инъекций.
    Сначала нормализует текст (удаляет zero-width chars, emoji, гомоглифы),
    затем проверяет оба варианта: оригинал и нормализованный.
    Возвращает описание найденного паттерна или None если чисто.
    """
    normalized = _normalize_for_injection_check(message)
    # Проверяем оба варианта — нормализованный ловит гомоглифы/emoji/zwsp обходы
    for pattern, compiled in zip(INJECTION_PATTERNS, _compiled_injection):
        if compiled.search(message) or compiled.search(normalized):
            logger.warning(f"Prompt injection detected, pattern: {pattern[:50]}")
            return f"Injection pattern matched: {pattern[:50]}"
    return None


# Alias для удобства: detect_prompt_injection(message) → Optional[str]
detect_prompt_injection = check_prompt_injection


def sanitize_html(message: str) -> str:
    """
    Слой 2: indirect-content-sanitizer.
    Удаляет HTML теги и экранирует спецсимволы из сообщения.
    Использует bleach для надёжной очистки.
    """
    cleaned = bleach.clean(message, tags=[], strip=True)
    return cleaned


def check_secret_patterns_in_input(message: str) -> Optional[str]:
    """
    Слой 3: gateway-input-guard.
    Блокирует сообщения содержащие паттерны секретов.
    Превентивная защита от эхо-атак (пользователь вставляет секрет чтобы бот его подтвердил).
    """
    for pattern, compiled in zip(SECRET_PATTERNS, _compiled_secrets):
        if compiled.search(message):
            logger.warning(f"Secret pattern in input, pattern: {pattern[:50]}")
            return f"Secret pattern detected in input: {pattern[:50]}"
    return None


def sanitize_output_secrets(response_text: str) -> str:
    """
    Слой 4: gateway-output-guard.
    Заменяет паттерны секретов в ответе модели на [REDACTED].
    """
    result = response_text
    for compiled in _compiled_secrets:
        result = compiled.sub("[REDACTED]", result)
    return result


def check_insecure_code_in_output(response_text: str) -> Optional[str]:
    """
    Слой 7: security-review-execution-loop.
    Проверяет ответ модели на небезопасные паттерны кода.
    Возвращает описание найденного паттерна или None если чисто.
    """
    for pattern, compiled in zip(INSECURE_CODE_PATTERNS, _compiled_insecure):
        if compiled.search(response_text):
            logger.warning(f"Insecure code pattern in output: {pattern[:50]}")
            return f"Insecure code pattern: {pattern[:50]}"
    return None


def sanitize_file_content(content: str) -> str:
    """
    Слой 6: workspace-secret-leak-guard.
    Удаляет строки содержащие паттерны секретов из загруженного файла.
    Используется при обработке загруженных файлов перед подачей в модель.
    """
    lines = content.split('\n')
    clean_lines = []
    for line in lines:
        has_secret = any(compiled.search(line) for compiled in _compiled_secrets)
        if has_secret:
            clean_lines.append("[LINE REDACTED - contains sensitive data]")
            logger.warning("Redacted line with secret pattern in uploaded file")
        else:
            clean_lines.append(line)
    return '\n'.join(clean_lines)


def validate_message_nonempty(message: str) -> bool:
    """
    Проверяет что сообщение содержит хотя бы один видимый символ.
    Защищает от Cost DoS через пустые/whitespace-only/invisible-chars-only сообщения:
    такие запросы бесплатно проходили guards, но Anthropic API возвращал 400 → наш 503.
    После нормализации (удаления invisible chars) проверяем strip() != ''.
    """
    if not message:
        return False
    # Нормализуем invisible символы (те же что в _normalize_for_injection_check)
    normalized = _INVISIBLE_CHARS_RE.sub('', message)
    # Убираем emoji (категория So/Cs/Cn) и whitespace
    result = []
    for ch in normalized:
        import unicodedata
        cat = unicodedata.category(ch)
        if cat not in ('So', 'Cs', 'Cn', 'Zs', 'Cc'):
            result.append(ch)
    visible = ''.join(result).strip()
    return len(visible) > 0


def validate_session_id(session_id: str) -> bool:
    """
    Проверяет sessionId на допустимые символы и длину.
    Предотвращает path traversal и инъекции через ID сессии.
    """
    if not session_id:
        return False
    if len(session_id) > 64:
        return False
    # Только буквы, цифры, дефис и подчёркивание
    pattern = re.compile(r'^[a-zA-Z0-9_-]{1,64}$')
    return bool(pattern.match(session_id))


def apply_all_input_guards(message: str) -> tuple[bool, str, str]:
    """
    Применяет все входные защиты последовательно.
    Возвращает (ok, error_reason, sanitized_message).
    ok=True если сообщение прошло все проверки.
    """
    # Предварительно: проверка что сообщение содержит хотя бы один видимый символ.
    # Защита от Cost DoS через пустые/whitespace-only/invisible-chars-only сообщения (R2-09/13/14/22).
    if not validate_message_nonempty(message):
        return False, "Message must contain visible text", ""

    # Слой 1: проверка инъекций
    injection_result = check_prompt_injection(message)
    if injection_result:
        return False, "Prompt injection detected", ""

    # Слой 2: санитизация HTML
    sanitized = sanitize_html(message)

    # Слой 3: проверка секретов во входе
    secret_result = check_secret_patterns_in_input(sanitized)
    if secret_result:
        return False, "Potentially sensitive content detected in message", ""

    return True, "", sanitized


def apply_all_output_guards(response_text: str) -> tuple[str, bool]:
    """
    Применяет все выходные защиты.
    Возвращает (sanitized_response, was_modified).
    """
    # Слой 4: санитизация секретов в выходе
    sanitized = sanitize_output_secrets(response_text)
    was_redacted = sanitized != response_text

    # Слой 7: проверка небезопасного кода
    insecure_result = check_insecure_code_in_output(sanitized)
    if insecure_result:
        logger.warning(f"Blocking response with insecure code: {insecure_result}")
        return "[Response blocked by security review: potentially unsafe code detected]", True

    return sanitized, was_redacted
