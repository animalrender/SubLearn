#!/usr/bin/env python3
"""Writes the GitHub Release notes for one build from CHANGELOG.md plus the list of built APKs.

Both release workflows call this after the APKs exist, so the notes always describe the files that
are actually attached: one APK per ABI, the universal one, and their SHA-256 sums.

Two channels:
  * `stable` (a `v*` tag) uses the changelog section of the version verbatim; a missing section is
    reported in the notes instead of being invented.
  * `dev` (the rolling pre-release of a branch, see `.github/workflows/auto-release.yml`) says which
    commit it was built from, that it will be replaced by the next green build, and shows the
    `[Unreleased]` changelog section as "what is new so far".

How the APKs were signed is passed in rather than guessed, because the auto-release workflow signs
with the release key only when the secret exists and falls back to the debug key otherwise (REQ-6).

Usage: python3 tools/release_notes.py <version> <apk-dir> [changelog]
                                      [--channel stable|dev] [--branch <name>] [--commit <sha>]
                                      [--signing release|debug] [--build-url <url>]
"""

from __future__ import annotations

import argparse
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

SIGNING_NOTES = {
    "release": (
        "- Signed with the project's release key, so it installs over any earlier build signed with "
        "the same key.\n"
    ),
    "debug": (
        "- Signed with the repository's public debug key, because no signing keystore is configured "
        "yet (docs/AGENT_REQUESTS.md REQ-6). Android refuses to install it over a build with a "
        "different key, so uninstall the old one first.\n"
    ),
    "unknown": (
        "- Signed with the repository's debug key unless the release workflow was given a signing "
        "keystore (docs/AGENT_REQUESTS.md REQ-6); a later build with a different key will not "
        "install over this one.\n"
    ),
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


def dev_header(version: str, branch: str | None, commit: str | None) -> str:
    where = f"`{branch}`" if branch else "a branch"
    at = f" at `{commit[:7]}`" if commit else ""
    return (
        f"# SubLearn {version} — development build\n\n"
        f"Automatic build of {where}{at}, published because every CI check passed on that commit.\n"
        "**This is not a released version.** The next green build of the same branch replaces these "
        "files, and the pre-release disappears when the branch does. For a version that will stay "
        "where it is, use the latest `v*` release.\n"
    )


def build_notes(
    version: str,
    apk_dir: Path,
    changelog: Path,
    channel: str = "stable",
    branch: str | None = None,
    commit: str | None = None,
    signing: str = "unknown",
    build_url: str | None = None,
) -> str:
    parts: list[str] = []
    if channel == "dev":
        parts.append(dev_header(version, branch, commit))
        unreleased = changelog_section(changelog, "Unreleased")
        if unreleased and unreleased.lower() != "nothing yet.":
            parts.append("## Changes that are not in a release yet\n")
            parts.append(unreleased + "\n")
    else:
        parts.append(f"# SubLearn {version}\n")
        section = changelog_section(changelog, version)
        if section:
            parts.append(section + "\n")
        else:
            parts.append(f"CHANGELOG.md has no `## [{version}]` section yet; see the commit history for this tag.\n")

    parts.append("## Downloads\n")
    parts.append(apk_table(apk_dir))

    built = "- Built by GitHub Actions from "
    built += f"commit `{commit}`" if commit else "the tagged commit"
    built += " (`assembleRelease`, R8 shrinking on, names kept)"
    built += f": [build log]({build_url}).\n" if build_url else ".\n"
    parts.append(
        "\n## Before installing\n\n"
        + built
        + SIGNING_NOTES.get(signing, SIGNING_NOTES["unknown"])
        + "- Minimum Android 8.0 (API 26). Translation needs one-time ML Kit model downloads.\n"
    )
    return "\n".join(parts)


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("version")
    parser.add_argument("apk_dir")
    parser.add_argument("changelog", nargs="?", default="CHANGELOG.md")
    parser.add_argument("--channel", choices=("stable", "dev"), default="stable")
    parser.add_argument("--branch", default=None)
    parser.add_argument("--commit", default=None)
    parser.add_argument("--signing", choices=("release", "debug", "unknown"), default="unknown")
    parser.add_argument("--build-url", dest="build_url", default=None)
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv[1:])
    sys.stdout.write(
        build_notes(
            args.version,
            Path(args.apk_dir),
            Path(args.changelog),
            channel=args.channel,
            branch=args.branch,
            commit=args.commit,
            signing=args.signing,
            build_url=args.build_url,
        )
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
