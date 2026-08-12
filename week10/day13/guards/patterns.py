"""Regex patterns for secret / PII detection.
Used by both InputGuard (block/mask user input) and OutputGuard (verify LLM response).
"""
import re

# tuple = (compiled_regex_or_None, replacement_placeholder)
PATTERNS: dict[str, tuple] = {
    "OPENAI_KEY":     (re.compile(r'\bsk-(?:proj-)?[A-Za-z0-9_-]{30,}\b'), "[REDACTED_API_KEY]"),
    "ANTHROPIC_KEY":  (re.compile(r'\bsk-ant-[A-Za-z0-9_-]{80,}\b'), "[REDACTED_API_KEY]"),
    "AWS_ACCESS_KEY": (re.compile(r'\bAKIA[0-9A-Z]{16}\b'), "[REDACTED_AWS_KEY]"),
    "AWS_SECRET_KEY": (re.compile(r'(?i)(?:aws[_.]?secret|secret[_.]?access)[^=\n]{0,30}[=:\s]+([A-Za-z0-9/+=]{40})\b'), "[REDACTED_AWS_SECRET]"),
    "GITHUB_PAT":     (re.compile(r'\bghp_[A-Za-z0-9]{36}\b|\bgithub_pat_[A-Za-z0-9_]{82,}\b'), "[REDACTED_GITHUB_TOKEN]"),
    "SLACK_TOKEN":    (re.compile(r'\bxoxb-[A-Za-z0-9-]{50,}\b'), "[REDACTED_SLACK_TOKEN]"),
    "PRIVATE_KEY":    (re.compile(r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----'), "[REDACTED_PRIVATE_KEY]"),
    "EMAIL":          (re.compile(r'\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b'), "[REDACTED_EMAIL]"),
    "PHONE_RU_US":    (re.compile(r'\b(?:\+7|8)[\s-]?\(?\d{3}\)?[\s-]?\d{3}[\s-]?\d{2}[\s-]?\d{2}\b|\b\+1[\s-]?\(?\d{3}\)?[\s-]?\d{3}[\s-]?\d{4}\b'), "[REDACTED_PHONE]"),
    "BASE64_SECRET":  (re.compile(r'(?i)(?:secret|token|password|key)\s*[=:]\s*([A-Za-z0-9+/]{32,}={0,2})\b'), "[REDACTED_SECRET_B64]"),
}

CREDIT_CARD_RE = re.compile(r'\b(?:\d[ -]?){13,19}\b')


def luhn_check(number: str) -> bool:
    """Standard Luhn algorithm — validates credit card / PAN checksum."""
    digits = [int(d) for d in re.sub(r'\D', '', number)]
    if len(digits) < 13 or len(digits) > 19:
        return False
    odd_digits = digits[-1::-2]
    even_digits = digits[-2::-2]
    total = sum(odd_digits)
    for d in even_digits:
        total += sum(divmod(d * 2, 10))
    return total % 10 == 0
