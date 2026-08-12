"""Defense 1: strip HTML comments, <style>, invisible text, zero-width chars."""
import re
import html

ZERO_WIDTH = ['​', '‌', '‍', '﻿', '­', '⁠']


def sanitize_email(raw: str) -> str:
    # Strip HTML comments
    text = re.sub(r'<!--.*?-->', '', raw, flags=re.DOTALL)
    # Strip <style> blocks
    text = re.sub(r'<style[^>]*>.*?</style>', '', text, flags=re.DOTALL | re.IGNORECASE)
    # Strip elements with white text or 0-1px font
    text = re.sub(
        r'<[^>]*(?:color\s*:\s*#(?:fff|ffffff|white)|font-size\s*:\s*[01]px)[^>]*>.*?</[^>]+>',
        '', text, flags=re.DOTALL | re.IGNORECASE)
    # Strip all remaining HTML tags
    text = re.sub(r'<[^>]+>', ' ', text)
    # Strip zero-width chars
    for zw in ZERO_WIDTH:
        text = text.replace(zw, '')
    text = html.unescape(text)
    return ' '.join(text.split())


if __name__ == "__main__":
    import sys
    print(sanitize_email(open(sys.argv[1]).read()))
