#!/usr/bin/env python3
"""Cheap source sanity checker.

The sandbox has no JVM, so brace/paren balance, unterminated blocks and a few style rules are
verified here before the (slow) Gradle CI round trip. This is a smoke check, not a compiler:
Gradle remains the source of truth.

Usage: python3 tools/check_sources.py [path ...]
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

PAIRS = {"{": "}", "(": ")", "[": "]"}


def strip_noise(src: str) -> str:
    out = []
    i = 0
    n = len(src)
    while i < n:
        c = src[i]
        # line comment
        if c == "/" and i + 1 < n and src[i + 1] == "/":
            j = src.find("\n", i)
            i = n if j < 0 else j
            continue
        # block comment (Kotlin nests)
        if c == "/" and i + 1 < n and src[i + 1] == "*":
            depth = 1
            i += 2
            while i < n and depth:
                if src[i] == "/" and i + 1 < n and src[i + 1] == "*":
                    depth += 1
                    i += 2
                elif src[i] == "*" and i + 1 < n and src[i + 1] == "/":
                    depth -= 1
                    i += 2
                else:
                    i += 1
            continue
        # triple-quoted string (may contain braces and newlines)
        if src.startswith('"""', i):
            j = src.find('"""', i + 3)
            i = n if j < 0 else j + 3
            continue
        if c in ('"', "'"):
            quote = c
            i += 1
            while i < n and src[i] != quote:
                i += 2 if src[i] == "\\" else 1
            i += 1
            continue
        out.append(c)
        i += 1
    return "".join(out)


def check_file(path: Path) -> list[str]:
    problems: list[str] = []
    src = path.read_text(encoding="utf-8")
    code = strip_noise(src)
    stack: list[tuple[str, int]] = []
    line = 1
    for ch in code:
        if ch == "\n":
            line += 1
        elif ch in PAIRS:
            stack.append((ch, line))
        elif ch in ")}]":
            if not stack:
                problems.append(f"{path}:{line}: unmatched '{ch}'")
                break
            opener, opener_line = stack.pop()
            if PAIRS[opener] != ch:
                problems.append(f"{path}:{line}: '{ch}' closes '{opener}' opened on line {opener_line}")
                break
    if stack:
        opener, opener_line = stack[-1]
        problems.append(f"{path}: unclosed '{opener}' opened on line {opener_line}")

    if re.search(r"\bTODO\(", code):
        problems.append(f"{path}: contains TODO() (use a documented stub instead)")
    exempt_length = "/test/" in str(path).replace("\\", "/") or "/androidTest/" in str(path)
    for idx, text in enumerate(src.splitlines(), start=1):
        if "FIXME" in text:
            problems.append(f"{path}:{idx}: FIXME found")
        if not exempt_length and len(text) > 140 and "http" not in text:
            problems.append(f"{path}:{idx}: line longer than 140 chars")
    return problems


def main(argv: list[str]) -> int:
    roots = [Path(a) for a in argv[1:]] or [Path(p) for p in ("app", "core", "feature")]
    files: list[Path] = []
    for root in roots:
        if root.is_file():
            files.append(root)
            continue
        if not root.exists():
            continue
        for ext in ("*.kt", "*.kts"):
            files.extend(sorted(root.rglob(ext)))
    problems: list[str] = []
    for f in files:
        problems.extend(check_file(f))
    for p in problems:
        print(p)
    print(f"checked {len(files)} files, {len(problems)} problems")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
