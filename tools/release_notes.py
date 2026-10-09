#!/usr/bin/env python3
"""Writes the GitHub Release notes for one version from CHANGELOG.md plus the list of built APKs.

The release workflow calls this after `assembleRelease`, so the notes always describe the files that
are actually attached: one APK per ABI, the universal one, and their SHA-256 sums. The changelog
section for the version is used verbatim when it exists; a missing section is reported in the notes
instead of being invented.

Usage: python3 tools/release_notes.py <version> <apk-dir> [changelog]
"""

from __future__ import annotations

import hashlib
import re
import sys
from pathlib import Path

ABI_ORDER = ["universal", "arm64-v8a", "armeabi-v7a", "x86_64"]
ABI_HINTS = {
    "universal": "every device (largest file; pick this if unsure)",
    "arm64-v8a": "most phones and tablets from 2016 on (64-bit ARM)",
    "armeabi-v7a": "older or very cheap 32-bit ARM devices",
    "x86_64": "Android emulators and Chromebooks with Intel/AMD chips",
}


def changelog_section(changelog: Path, version: str) -> str | None:
    if not changelog.exists():
        return None
    text = changelog.read_text(encoding="utf-8")
    pattern = re.compile(r"^## \[" + re.escape(version) + r"\][^\n]*\n(.*?)(?=^## \[|\Z)", re.M | re.S)
    match = pattern.search(text)
    if not match:
        return None
    body = match.group(1).strip()
    return body or None


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def abi_of(name: str) -> str:
    for abi in ABI_ORDER:
        if f"-{abi}" in name:
            return abi
    return "unknown"


def sort_key(path: Path) -> tuple[int, str]:
    abi = abi_of(path.name)
    return (ABI_ORDER.index(abi) if abi in ABI_ORDER else len(ABI_ORDER), path.name)


def apk_table(apk_dir: Path) -> str:
    apks = sorted(apk_dir.glob("*.apk"), key=sort_key)
    if not apks:
        return "No APK was produced by this run.\n"
    rows = ["| File | For | Size | SHA-256 |", "| --- | --- | --- | --- |"]
    for apk in apks:
        abi = abi_of(apk.name)
        size_mb = apk.stat().st_size / (1024 * 1024)
        rows.append(f"| `{apk.name}` | {ABI_HINTS.get(abi, abi)} | {size_mb:.1f} MB | `{sha256(apk)}` |")
    return "\n".join(rows) + "\n"


def build_notes(version: str, apk_dir: Path, changelog: Path) -> str:
    section = changelog_section(changelog, version)
    parts = [f"# SubLearn {version}\n"]
    if section:
        parts.append(section + "\n")
    else:
        parts.append(f"CHANGELOG.md has no `## [{version}]` section yet; see the commit history for this tag.\n")
    parts.append("## Downloads\n")
    parts.append(apk_table(apk_dir))
    parts.append(
        "\n## Before installing\n\n"
        "- Built by GitHub Actions from the tagged commit (`assembleRelease`, R8 shrinking on, names kept).\n"
        "- Signed with the repository's debug key unless the release workflow was given a signing keystore "
        "(docs/AGENT_REQUESTS.md REQ-6); a later build with a different key will not install over this one.\n"
        "- Minimum Android 8.0 (API 26). Translation needs one-time ML Kit model downloads.\n"
    )
    return "\n".join(parts)


def main(argv: list[str]) -> int:
    if len(argv) < 3:
        print(__doc__)
        return 1
    changelog = Path(argv[3]) if len(argv) > 3 else Path("CHANGELOG.md")
    sys.stdout.write(build_notes(argv[1], Path(argv[2]), changelog))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
