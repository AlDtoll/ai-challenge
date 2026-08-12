"""Output Guard — validate LLM response before returning to client."""
import re
from dataclasses import dataclass, field
from typing import Optional
from .patterns import PATTERNS, CREDIT_CARD_RE, luhn_check


DANGEROUS_CMD = re.compile(
    r'(?:rm\s+-rf\s+/|nc\s+-e\s+|/bin/bash\s+-i|wget[^\n]{0,80}\|\s*sh|curl[^\n]{0,80}\|\s*sh|mkfifo\s+/tmp)'
)
SUSPICIOUS_URL = re.compile(
    r'https?://[^\s]+[?&](?:data|token|key|payload|exfil)=[A-Za-z0-9+/=_-]{10,}'
)
SYS_PROMPT_LEAK = re.compile(
    r'(?is)(?:you are (?:a |an )?(?:helpful |friendly )?(?:assistant|ai|chatbot).{0,200}(?:instructions|rules|guidelines))|(?:system:\s*\n)|(?:rules:\s*\n?\s*1\.)'
)


@dataclass
class OutputGuardResult:
    blocked: bool
    reason: str = ""
    hits: list[str] = field(default_factory=list)


class OutputGuard:
    def check(self, response_text: str, input_emails: Optional[set[str]] = None) -> OutputGuardResult:
        hits = []
        # 1. Секреты в ответе (те же PATTERNS, EMAIL обрабатывается отдельно)
        for name, (regex, _) in PATTERNS.items():
            if name == "EMAIL":
                continue
            if regex and regex.search(response_text):
                hits.append(f"secret:{name}")
        # Кредитная карта в ответе
        for m in CREDIT_CARD_RE.finditer(response_text):
            if luhn_check(m.group(0)):
                hits.append("secret:CREDIT_CARD")
                break

        # 2. Утечка системного промпта
        if SYS_PROMPT_LEAK.search(response_text):
            hits.append("sys_prompt_leak")

        # 3. Подозрительные URL (с параметрами data/token/key/payload/exfil)
        if SUSPICIOUS_URL.search(response_text):
            hits.append("suspicious_url")

        # 4. Опасные команды
        if DANGEROUS_CMD.search(response_text):
            hits.append("dangerous_command")

        # 5. Email-утечка (если email не был в input)
        if input_emails is not None:
            found_emails = set(PATTERNS["EMAIL"][0].findall(response_text))
            new_emails = found_emails - input_emails
            if new_emails:
                hits.append(f"email_leak:{len(new_emails)}")

        if hits:
            return OutputGuardResult(blocked=True, reason=";".join(hits), hits=hits)
        return OutputGuardResult(blocked=False)
