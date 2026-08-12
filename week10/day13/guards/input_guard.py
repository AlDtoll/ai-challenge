"""Input Guard — scan incoming prompts for secrets/PII, block or mask."""
import re
from dataclasses import dataclass, field
from typing import Literal, Optional
from .patterns import PATTERNS, CREDIT_CARD_RE, luhn_check


@dataclass
class Hit:
    pattern_name: str
    snippet: str          # первые 20 символов совпадения, для логов (не утекает полностью)
    replacement: str


@dataclass
class GuardResult:
    blocked: bool
    hits: list[Hit] = field(default_factory=list)
    masked_messages: Optional[list[dict]] = None  # только если не blocked


class InputGuard:
    def __init__(self, mode: Literal["block", "mask"] = "block"):
        self.mode = mode

    def _scan_text(self, text: str) -> list[Hit]:
        hits = []
        for name, (regex, replacement) in PATTERNS.items():
            if regex is None:
                continue
            for m in regex.finditer(text):
                hits.append(Hit(pattern_name=name, snippet=m.group(0)[:20], replacement=replacement))

        # Кредитная карта: regex + проверка Луна
        for m in CREDIT_CARD_RE.finditer(text):
            if luhn_check(m.group(0)):
                hits.append(Hit(pattern_name="CREDIT_CARD", snippet=m.group(0)[:20], replacement="[REDACTED_CREDIT_CARD]"))

        return hits

    def _mask_text(self, text: str) -> str:
        for name, (regex, replacement) in PATTERNS.items():
            if regex is None:
                continue
            text = regex.sub(replacement, text)
        # Маскировка кредитных карт (только Luhn-валидные)
        def _cc_repl(m):
            return "[REDACTED_CREDIT_CARD]" if luhn_check(m.group(0)) else m.group(0)
        text = CREDIT_CARD_RE.sub(_cc_repl, text)
        return text

    def check(self, messages: list[dict]) -> GuardResult:
        """Сканирует весь контент через конкатенацию (защищает от split-secret атак)."""
        # Конкатенируем весь контент для глобального сканирования
        all_content = "\n".join(
            m.get("content", "") if isinstance(m.get("content"), str) else str(m.get("content", ""))
            for m in messages
        )
        hits = self._scan_text(all_content)

        if not hits:
            return GuardResult(blocked=False, hits=[], masked_messages=messages)

        if self.mode == "block":
            return GuardResult(blocked=True, hits=hits, masked_messages=None)

        # Режим mask: применяем замены к каждому сообщению
        masked = []
        for m in messages:
            new_m = dict(m)
            content = m.get("content", "")
            if isinstance(content, str):
                new_m["content"] = self._mask_text(content)
            masked.append(new_m)
        return GuardResult(blocked=False, hits=hits, masked_messages=masked)
