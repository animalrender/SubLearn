#!/usr/bin/env python3
"""next_version.py — The one place that decides what the next release is called.

From v3.0.0 on, every merge to `main` is a release and the patch number goes up by one, so the
sequence is 3.0.0, 3.0.1, 3.0.2 … (D-29). The rule is deliberately boring: a version that a human
has to think about is a version that gets forgotten.

How the number is found:

1. the highest stable tag `vX.Y.Z` (the `dev-*` rolling pre-releases do not count) is the base;
2. the candidate is the base with the patch number raised by one;
3. the repository's `VERSION` file is a floor: while it is higher than the candidate — the case
   until v3.0.0 exists — the floor wins, so the first release after the reorganisation is 3.0.0
   and not 0.2.2.

The same function answers for CI and for the release workflow, which is what keeps the APK that
CI tested and the APK that is published on the same version number. A version is only ever
*computed*; nothing here writes a tag or a file.

Usage:
    python3 tools/next_version.py                    # read the tags from git
    python3 tools/next_version.py --tags v3.0.0 v3.0.1
    python3 tools/next_version.py --floor 3.0.0      # override the VERSION file
    python3 tools/next_version.py --print-all        # also print the base and the floor
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

STABLE_TAG = re.compile(r"^v(\d+)\.(\d+)\.(\d+)$")
VERSION_FILE = Path(__file__).resolve().parent.parent / "VERSION"


def parse_version(text: str) -> tuple[int, int, int]:
    """Parse `3.0.1` (with or without a `v`) into `(3, 0, 1)`."""
    clean = text.strip().lstrip("v")
    parts = clean.split("-")[0].split(".")
    if len(parts) != 3 or not all(part.isdigit() for part in parts):
        raise ValueError(f"not a version: {text!r}")
    return int(parts[0]), int(parts[1]), int(parts[2])


def format_version(version: tuple[int, int, int]) -> str:
    return f"{version[0]}.{version[1]}.{version[2]}"


def highest_stable(tags: list[str]) -> tuple[int, int, int] | None:
    """The highest `vX.Y.Z` tag, or None when there is none."""
    versions = []
    for tag in tags:
        match = STABLE_TAG.match(tag.strip())
        if match:
            versions.append((int(match.group(1)), int(match.group(2)), int(match.group(3))))
    return max(versions) if versions else None


def bump_patch(version: tuple[int, int, int]) -> tuple[int, int, int]:
    """3.0.1 → 3.0.2. The patch number is the only one that moves."""
    return version[0], version[1], version[2] + 1


def next_version(
    tags: list[str],
    floor: str | None = None,
) -> tuple[str, tuple[int, int, int] | None, str | None]:
    """The next release version, with the base it came from and the floor that was applied.

    @param tags every tag the repository knows, stable and rolling alike
    @param floor the committed `VERSION` file, or None when it should be ignored
    @return (version, base, floor) where `base` is None when no stable release exists yet
    """
    base = highest_stable(tags)
    candidate = bump_patch(base) if base else (0, 0, 1)
    floor_version = parse_version(floor) if floor else None
    if floor_version is not None and floor_version > candidate:
        candidate = floor_version
    return format_version(candidate), base, floor


def git_tags() -> list[str]:
    """Every tag in the repository, read from git."""
    import subprocess

    result = subprocess.run(
        ["git", "tag", "--list"],
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        return []
    return [line.strip() for line in result.stdout.splitlines() if line.strip()]


def read_floor(path: Path = VERSION_FILE) -> str | None:
    """The committed `VERSION` file, or None when it is missing or unreadable."""
    try:
        text = path.read_text(encoding="utf-8").strip()
    except OSError:
        return None
    return text or None


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Compute the next release version")
    parser.add_argument("--tags", nargs="*", default=None, help="the tags to consider (default: git)")
    parser.add_argument("--floor", default=None, help="the floor version (default: the VERSION file)")
    parser.add_argument("--no-floor", action="store_true", help="ignore the VERSION file")
    parser.add_argument("--print-all", action="store_true", help="also print the base and the floor")
    args = parser.parse_args(argv)

    tags = args.tags if args.tags is not None else git_tags()
    floor = None if args.no_floor else (args.floor if args.floor is not None else read_floor())

    try:
        version, base, applied_floor = next_version(tags, floor)
    except ValueError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1

    if args.print_all:
        print(f"base={format_version(base) if base else 'none'}")
        print(f"floor={applied_floor or 'none'}")
    print(version)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
