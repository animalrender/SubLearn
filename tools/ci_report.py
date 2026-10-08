#!/usr/bin/env python3
"""Extracts the reasons a Gradle build failed, for publication as CI check-run annotations.

The Actions log is not readable from an agent sandbox without a browser session, so the workflow prints
what this script finds. Two shapes matter: the "* What went wrong" block Gradle writes for every failed
task under --continue, and raw compiler/AAPT2 error lines that never reach such a block.
"""

from __future__ import annotations

import re
import sys

MAX_BLOCKS = 40
AAPT = re.compile(r"^(ERROR|error):")


def what_went_wrong(lines: list[str]) -> list[str]:
    blocks: list[str] = []
    index = 0
    while index < len(lines):
        if not lines[index].startswith("* What went wrong"):
            index += 1
            continue
        body: list[str] = []
        cursor = index + 1
        while cursor < len(lines):
            line = lines[cursor].rstrip()
            if line.startswith("*") or line.startswith("---") or line.startswith("BUILD ") or line.startswith("FAILURE"):
                break
            if line.strip() and not re.match(r"^\s+at ", line):
                body.append(line[:280])
            cursor += 1
        if body:
            blocks.append("\n".join(body))
        index = cursor
    unique: list[str] = []
    for block in blocks:
        if block not in unique:
            unique.append(block)
    return unique[:MAX_BLOCKS]


def tool_errors(lines: list[str]) -> list[str]:
    """AAPT2 and manifest-merge errors are printed at the start of a line, without a reason block."""
    out: list[str] = []
    for index, line in enumerate(lines):
        if AAPT.match(line) and line not in out:
            out.append(line[:280])
            following = lines[index + 1 : index + 3]
            out.extend(text.rstrip()[:280] for text in following if text.strip() and not AAPT.match(text))
    return out[:MAX_BLOCKS]


def report(log: str) -> int:
    with open(log, encoding="utf-8", errors="replace") as handle:
        lines = handle.read().splitlines()
    sections = what_went_wrong(lines) + tool_errors(lines)
    if sections:
        print("\n\n".join(sections))
    else:
        print("\n".join(lines[-30:]))
    return 0


if __name__ == "__main__":
    sys.exit(report(sys.argv[1]) if len(sys.argv) > 1 else 1)
