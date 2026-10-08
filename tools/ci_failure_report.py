#!/usr/bin/env python3
"""Builds one plain-text failure report for a Gradle CI run.

The Actions log host is not reachable from the agent sandbox, and annotations are capped at ten per
step, so the workflow publishes the output of this script as the text of a check run: that endpoint
is readable through the REST API. The report keeps the parts that explain a failure and drops the
noise: compiler errors, failed tasks, Gradle "What went wrong" blocks, failing unit tests, lint
errors and the tail of the log.

Usage: python3 tools/ci_failure_report.py <gradle.log> [workspace-root]
"""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ElementTree
from pathlib import Path

from ci_report import tool_errors, what_went_wrong

MAX_TEXT = 60_000
MAX_COMPILER_LINES = 300
MAX_TASK_LINES = 80
MAX_TEST_FAILURES = 60
MAX_LINT_ERRORS = 120
TAIL_LINES = 60
MAX_LINE = 400
RUNNER_PREFIX = re.compile(r"file:///home/runner/work/[^/]+/[^/]+/")


def compiler_errors(lines: list[str]) -> list[str]:
    out: list[str] = []
    seen: set[str] = set()
    for index, line in enumerate(lines):
        if not line.startswith("e: "):
            continue
        text = RUNNER_PREFIX.sub("", line)[:MAX_LINE]
        if text in seen:
            continue
        seen.add(text)
        out.append(text)
        # The Kotlin compiler sometimes continues the message on indented lines.
        for extra in lines[index + 1 : index + 3]:
            if extra.startswith("  ") and extra.strip():
                out.append("    " + extra.strip()[:MAX_LINE])
    return out[:MAX_COMPILER_LINES]


def failed_tasks(lines: list[str]) -> list[str]:
    out: list[str] = []
    for line in lines:
        if line.startswith("> Task ") and line.rstrip().endswith("FAILED") and line not in out:
            out.append(line.rstrip())
    return out[:MAX_TASK_LINES]


def test_failures(root: Path) -> list[str]:
    out: list[str] = []
    for report in sorted(root.glob("**/build/test-results/**/*.xml")):
        try:
            tree = ElementTree.parse(report)
        except ElementTree.ParseError:
            continue
        for case in tree.iter("testcase"):
            for kind in ("failure", "error"):
                node = case.find(kind)
                if node is None:
                    continue
                stack = (node.text or "").strip().splitlines()
                detail = node.get("message") or (stack[0] if stack else "")
                out.append(f"{case.get('classname')}.{case.get('name')}: {detail[:MAX_LINE]}")
                # The first frame inside the project points at the assertion that failed.
                for frame in stack[1:12]:
                    if "com.sublearn" in frame:
                        out.append("    " + frame.strip()[:MAX_LINE])
                        break
        if len(out) >= MAX_TEST_FAILURES:
            break
    return out[:MAX_TEST_FAILURES]


def lint_errors(root: Path) -> list[str]:
    out: list[str] = []
    for report in sorted(root.glob("**/build/reports/lint-results*.xml")):
        try:
            tree = ElementTree.parse(report)
        except ElementTree.ParseError:
            continue
        for issue in tree.iter("issue"):
            if issue.get("severity") != "Error":
                continue
            location = issue.find("location")
            where = ""
            if location is not None:
                file = re.sub(r"^/home/runner/work/[^/]+/[^/]+/", "", location.get("file") or "")
                where = f" ({file}:{location.get('line') or '?'})"
            out.append(f"{issue.get('id')}: {issue.get('message')}{where}"[:MAX_LINE])
        if len(out) >= MAX_LINT_ERRORS:
            break
    return out[:MAX_LINT_ERRORS]


def section(title: str, body: list[str]) -> str:
    if not body:
        return ""
    return f"## {title}\n\n" + "\n".join(body) + "\n\n"


def build_report(log: Path, root: Path) -> str:
    lines = log.read_text(encoding="utf-8", errors="replace").splitlines() if log.exists() else []
    parts = [
        section("Compiler errors", compiler_errors(lines)),
        section("Failed tasks", failed_tasks(lines)),
        section("What went wrong", what_went_wrong(lines)),
        section("Tool errors", tool_errors(lines)),
        section("Failing tests", test_failures(root)),
        section("Lint errors", lint_errors(root)),
        section("Log tail", [text.rstrip()[:MAX_LINE] for text in lines[-TAIL_LINES:]]),
    ]
    text = "".join(part for part in parts if part) or "The Gradle log is empty: the failure happened before Gradle ran.\n"
    if len(text) > MAX_TEXT:
        text = text[: MAX_TEXT - 40] + "\n\n[report truncated]\n"
    return text


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(__doc__)
        return 1
    root = Path(argv[2]) if len(argv) > 2 else Path(".")
    sys.stdout.write(build_report(Path(argv[1]), root))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
