#!/usr/bin/env python3
"""bump_version.py — Compute the next version from conventional commits.

Usage:
    python3 tools/bump_version.py [--last-tag v0.2.1] [--dry-run]

Rules:
    - `feat:` → minor bump
    - `fix:`, `perf:`, `refactor:` → patch bump
    - `BREAKING CHANGE` in body or `!` in type → major bump
    - `chore:`, `docs:`, `style:`, `test:`, `ci:`, `build:` → no release
    - If no release-worthy commits found, exits with code 1

Output: prints the next version (e.g. "0.3.0") to stdout.
"""

import argparse
import re
import subprocess
import sys


CONVENTIONAL_RE = re.compile(
    r'^(?P<type>\w+)(?:\((?P<scope>[^)]*)\))?(?P<breaking>!)?:\s*(?P<description>.+)$'
)

RELEASE_TYPES = {
    'feat': 'minor',
    'fix': 'patch',
    'perf': 'patch',
    'refactor': 'patch',
}

NON_RELEASE_TYPES = {
    'chore', 'docs', 'style', 'test', 'ci', 'build', 'revert',
}


def get_commits_since(tag: str | None) -> list[tuple[str, str]]:
    """Return list of (hash, full_message) since the given tag."""
    cmd = ['git', 'log', '--format=%H%n%s%n%b%n---END---']
    if tag:
        cmd.append(f'{tag}..HEAD')
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        return []

    commits = []
    current_hash = None
    current_lines = []

    for line in result.stdout.split('\n'):
        if line == '---END---':
            if current_hash and current_lines:
                full_msg = '\n'.join(current_lines)
                commits.append((current_hash, full_msg))
            current_hash = None
            current_lines = []
        elif current_hash is None:
            current_hash = line.strip()
        else:
            current_lines.append(line)

    return commits


def parse_version(tag: str) -> tuple[int, int, int]:
    """Parse a version tag like 'v0.2.1' or '0.2.1' into (major, minor, patch)."""
    clean = tag.lstrip('v')
    parts = clean.split('-')[0].split('.')
    return (
        int(parts[0]) if len(parts) > 0 else 0,
        int(parts[1]) if len(parts) > 1 else 0,
        int(parts[2]) if len(parts) > 2 else 0,
    )


def format_version(major: int, minor: int, patch: int) -> str:
    return f'{major}.{minor}.{patch}'


def get_latest_tag() -> str | None:
    """Get the latest v* tag."""
    result = subprocess.run(
        ['git', 'describe', '--tags', '--abbrev=0', '--match', 'v*'],
        capture_output=True, text=True,
    )
    if result.returncode == 0:
        return result.stdout.strip()
    return None


def bump(commits: list[tuple[str, str]], current_version: str) -> tuple[str, str] | None:
    """Compute the next version. Returns (new_version, changelog_entry) or None if no bump needed."""
    major, minor, patch = parse_version(current_version)
    has_breaking = False
    bump_type = None  # 'major', 'minor', 'patch'
    changelog_lines = []

    for hash_val, message in commits:
        match = CONVENTIONAL_RE.match(message.split('\n')[0])
        if not match:
            continue

        commit_type = match.group('type')
        is_breaking = bool(match.group('breaking'))
        description = match.group('description')
        scope = match.group('scope') or ''

        # Check for BREAKING CHANGE in body
        if 'BREAKING CHANGE' in message:
            is_breaking = True

        if commit_type in NON_RELEASE_TYPES:
            continue

        if is_breaking:
            has_breaking = True
            bump_type = 'major'
            prefix = f'{scope}: ' if scope else ''
            changelog_lines.append(f'- **BREAKING** {prefix}{description} ({hash_val[:8]})')
        elif commit_type in RELEASE_TYPES:
            release_level = RELEASE_TYPES[commit_type]
            if release_level == 'minor' and bump_type != 'major':
                bump_type = 'minor'
            elif release_level == 'patch' and bump_type is None:
                bump_type = 'patch'
            prefix = f'{scope}: ' if scope else ''
            changelog_lines.append(f'- {prefix}{description} ({hash_val[:8]})')

    if bump_type is None:
        return None

    if has_breaking:
        major += 1
        minor = 0
        patch = 0
    elif bump_type == 'minor':
        minor += 1
        patch = 0
    elif bump_type == 'patch':
        patch += 1

    new_version = format_version(major, minor, patch)
    changelog_entry = f'## [{new_version}]\n\n' + '\n'.join(changelog_lines)
    return new_version, changelog_entry


def main():
    parser = argparse.ArgumentParser(description='Compute next version from conventional commits')
    parser.add_argument('--last-tag', default=None, help='Tag to start from (default: latest v* tag)')
    parser.add_argument('--current-version', default=None, help='Current version (default: parse from tag)')
    parser.add_argument('--dry-run', action='store_true', help='Print result without side effects')
    parser.add_argument('--changelog', action='store_true', help='Also print the changelog entry')
    args = parser.parse_args()

    tag = args.last_tag or get_latest_tag()
    current = args.current_version or (tag.lstrip('v') if tag else '0.0.0')

    commits = get_commits_since(tag)
    if not commits:
        print('No commits found since last tag', file=sys.stderr)
        sys.exit(1)

    result = bump(commits, current)
    if result is None:
        print('No release-worthy commits found', file=sys.stderr)
        sys.exit(1)

    new_version, changelog_entry = result
    print(new_version)

    if args.changelog:
        print('---')
        print(changelog_entry)


if __name__ == '__main__':
    main()