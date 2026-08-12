"""Execution loop with security-review step. All LLM calls go through Gateway."""
import json
import os
import re
import time
from pathlib import Path
from llm_via_gateway import call as llm_call

BASE = Path(__file__).parent

with open(BASE / "prompts" / "generation.md") as f:
    GEN_PROMPT = f.read()
with open(BASE / "prompts" / "security_review.md") as f:
    SEC_PROMPT = f.read()

MAX_ITERATIONS = 3


def extract_kotlin(text: str) -> str:
    """Извлечь kotlin code block из LLM-вывода."""
    m = re.search(r'```(?:kotlin)?\n?(.*?)```', text, re.DOTALL)
    return m.group(1).strip() if m else text.strip()


def run_lint(code: str) -> tuple[bool, list[str]]:
    """Простой regex lint (заглушка вместо gradle:lintDebug — Gradle не запускаем по feedback_no_gradle_build).
    Returns (passed, warnings)."""
    warnings = []
    # Проверка сбалансированности скобок
    if code.count("{") != code.count("}"):
        warnings.append("unbalanced braces")
    # Информационное предупреждение о Log.* (не фейлит)
    if re.search(r'\bLog\.\w+\(', code):
        warnings.append("has Log.* calls (check for PII in security-review)")
    passed = "unbalanced braces" not in warnings
    return passed, warnings


def security_review(code: str) -> tuple[dict, list[dict], list[str]]:
    """Вызов LLM с security-review prompt. Returns (summary, issues, gateway_hits)."""
    r = llm_call(SEC_PROMPT, code, temperature=0.0, max_tokens=1500)
    if r["status"] != "ok":
        return ({"verdict": "ERROR", "reason": r.get("gateway_error", "")}, [], r.get("gateway_hits", []))
    raw = r["content"]
    # Парсинг: JSON-lines + SUMMARY
    issues = []
    summary = {"verdict": "UNKNOWN"}
    for line in raw.splitlines():
        line = line.strip()
        if not line:
            continue
        if line.startswith("SUMMARY:"):
            try:
                summary = json.loads(line.split("SUMMARY:", 1)[1].strip())
            except Exception:
                pass
            continue
        try:
            issue = json.loads(line)
            if isinstance(issue, dict) and "severity" in issue:
                issues.append(issue)
        except Exception:
            pass
    # Fallback verdict если не распарсился
    if "verdict" not in summary:
        crit = sum(1 for i in issues if i.get("severity") == "Critical")
        high = sum(1 for i in issues if i.get("severity") == "High")
        if crit or high:
            summary["verdict"] = "BLOCK"
        elif issues:
            summary["verdict"] = "WARN"
        else:
            summary["verdict"] = "OK"
    return summary, issues, r.get("gateway_hits", [])


def run_task(task_id: str, user_prompt: str, out_dir: Path) -> dict:
    """Выполнить полный loop для одной задачи. Вернуть trace."""
    out_dir.mkdir(parents=True, exist_ok=True)
    trace = {
        "task_id": task_id,
        "user_prompt": user_prompt,
        "iterations": [],
        "final_status": "unknown",
        "gateway_hits_total": [],
    }

    feedback_from_prev = ""
    for it in range(1, MAX_ITERATIONS + 1):
        iter_data = {"iteration": it}

        # 1. Генерация
        gen_user = user_prompt + (
            f"\n\nPrevious attempt failed security review with:\n{feedback_from_prev}"
            if feedback_from_prev else ""
        )
        gen = llm_call(GEN_PROMPT, gen_user, temperature=0.3)
        iter_data["gen_status"] = gen["status"]
        iter_data["gen_gateway_hits"] = gen.get("gateway_hits", [])
        trace["gateway_hits_total"].extend(gen.get("gateway_hits", []))
        if gen["status"] != "ok":
            iter_data["error"] = gen.get("gateway_error", "")
            trace["iterations"].append(iter_data)
            trace["final_status"] = f"gen_failed:{gen['status']}"
            break

        code = extract_kotlin(gen["content"])
        (out_dir / f"iter{it}_generated.kt").write_text(code)
        iter_data["code_lines"] = len(code.splitlines())

        # 2. Lint
        lint_ok, lint_warnings = run_lint(code)
        iter_data["lint_ok"] = lint_ok
        iter_data["lint_warnings"] = lint_warnings
        if not lint_ok:
            trace["iterations"].append(iter_data)
            trace["final_status"] = f"lint_failed_iter{it}"
            continue

        # 3. Security review
        sec_summary, sec_issues, sec_gateway_hits = security_review(code)
        iter_data["security_summary"] = sec_summary
        iter_data["security_issues"] = sec_issues
        iter_data["sec_gateway_hits"] = sec_gateway_hits
        trace["gateway_hits_total"].extend(sec_gateway_hits)

        (out_dir / f"iter{it}_security.json").write_text(
            json.dumps({"summary": sec_summary, "issues": sec_issues}, indent=2, ensure_ascii=False)
        )

        # 4. Решение
        verdict = sec_summary.get("verdict", "UNKNOWN")
        iter_data["verdict"] = verdict

        if verdict == "BLOCK":
            feedback_from_prev = "\n".join(
                f"[{i['severity']}] {i.get('issue', '')} — fix: {i.get('fix', '')}"
                for i in sec_issues if i.get("severity") in ("Critical", "High")
            )
            trace["iterations"].append(iter_data)
            if it == MAX_ITERATIONS:
                trace["final_status"] = "blocked_max_iterations"
            continue

        if verdict == "WARN":
            iter_data["action"] = "committed with warnings"
            trace["iterations"].append(iter_data)
            trace["final_status"] = "committed_with_warnings"
            (out_dir / "FINAL.kt").write_text(code)
            break

        if verdict == "OK":
            iter_data["action"] = "committed clean"
            trace["iterations"].append(iter_data)
            trace["final_status"] = "committed_clean"
            (out_dir / "FINAL.kt").write_text(code)
            break

        # Неизвестный verdict — остановка
        iter_data["action"] = f"unknown verdict, stop"
        trace["iterations"].append(iter_data)
        trace["final_status"] = f"unknown_verdict:{verdict}"
        break

    return trace


if __name__ == "__main__":
    import sys
    task_id = sys.argv[1] if len(sys.argv) > 1 else "manual"
    prompt = sys.argv[2] if len(sys.argv) > 2 else "Print hello world in Kotlin"
    result = run_task(task_id, prompt, BASE / "generated" / task_id)
    print(json.dumps(result, indent=2, ensure_ascii=False))
