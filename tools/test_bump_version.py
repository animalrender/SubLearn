#!/usr/bin/env python3
"""Tests for bump_version.py"""

import sys
import os

# Add tools dir to path
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from bump_version import bump, parse_version, format_version


def test_parse_version():
    assert parse_version('v0.2.1') == (0, 2, 1)
    assert parse_version('1.0.0') == (1, 0, 0)
    assert parse_version('v2.3.4-rc1') == (2, 3, 4)
    assert parse_version('0.0.0') == (0, 0, 0)
    print('PASS: test_parse_version')


def test_format_version():
    assert format_version(0, 2, 1) == '0.2.1'
    assert format_version(1, 0, 0) == '1.0.0'
    print('PASS: test_format_version')


def test_feat_bumps_minor():
    commits = [
        ('abc123', 'feat(player): add scrub preview'),
        ('def456', 'fix(settings): correct label'),
    ]
    result = bump(commits, '0.2.1')
    assert result is not None
    assert result[0] == '0.3.0', f'Expected 0.3.0, got {result[0]}'
    print('PASS: test_feat_bumps_minor')


def test_fix_bumps_patch():
    commits = [
        ('abc123', 'fix(nlp): correct confidence formula'),
    ]
    result = bump(commits, '0.2.1')
    assert result is not None
    assert result[0] == '0.2.2', f'Expected 0.2.2, got {result[0]}'
    print('PASS: test_fix_bumps_patch')


def test_breaking_bumps_major():
    commits = [
        ('abc123', 'feat(api)!: redesign settings schema\n\nBREAKING CHANGE: settings format changed'),
    ]
    result = bump(commits, '0.2.1')
    assert result is not None
    assert result[0] == '1.0.0', f'Expected 1.0.0, got {result[0]}'
    print('PASS: test_breaking_bumps_major')


def test_chore_no_bump():
    commits = [
        ('abc123', 'chore(repo): update dependencies'),
        ('def456', 'docs(readme): add screenshots'),
    ]
    result = bump(commits, '0.2.1')
    assert result is None
    print('PASS: test_chore_no_bump')


def test_empty_commits():
    result = bump([], '0.2.1')
    assert result is None
    print('PASS: test_empty_commits')


def test_mixed_commits():
    commits = [
        ('abc123', 'feat(nlp): add phrase detection'),
        ('def456', 'fix(translate): timeout handling'),
        ('ghi789', 'chore(deps): bump kotlin'),
        ('jkl012', 'perf(nlp): cache pattern compilation'),
        ('mno345', 'docs: update architecture'),
    ]
    result = bump(commits, '0.2.1')
    assert result is not None
    # feat bumps minor, so 0.3.0
    assert result[0] == '0.3.0', f'Expected 0.3.0, got {result[0]}'
    print('PASS: test_mixed_commits')


def test_changelog_entry():
    commits = [
        ('abc1234567890', 'feat(nlp): add phrase detection'),
        ('def4567890123', 'fix(translate): timeout handling'),
    ]
    result = bump(commits, '0.2.1')
    assert result is not None
    assert '0.3.0' in result[1]
    assert 'add phrase detection' in result[1]
    assert 'timeout handling' in result[1]
    print('PASS: test_changelog_entry')


if __name__ == '__main__':
    test_parse_version()
    test_format_version()
    test_feat_bumps_minor()
    test_fix_bumps_patch()
    test_breaking_bumps_major()
    test_chore_no_bump()
    test_empty_commits()
    test_mixed_commits()
    test_changelog_entry()
    print('\nAll tests passed!')