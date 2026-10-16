#!/usr/bin/env python3
"""Tests for the source sanity checker's lexer.

The checker runs in CI before Gradle, so a bug here is either a false alarm that blocks a good
commit or a blind spot that lets a broken one through. The cases below are the ones that have
already caused a miss or a false report.
"""
from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import check_sources  # noqa: E402


class StripNoiseTest(unittest.TestCase):
    def test_backticked_name_with_an_apostrophe_keeps_the_code(self) -> None:
        # Regression: `the model's answer` used to start a bogus char literal, and everything
        # until the next apostrophe — a closing brace among it — vanished from the check.
        source = (
            "class A {\n"
            "    @Test\n"
            "    fun `the model's answer is used`() {\n"
            "        assertEquals(1, 1)\n"
            "    }\n"
            "}\n"
        )
        self.assertEqual([], check_sources.check_source(source, "A.kt"))

    def test_char_literal_braces_are_not_counted(self) -> None:
        source = "fun f() {\n    val c = '{'\n    val d = '}'\n}\n"
        self.assertEqual([], check_sources.check_source(source, "B.kt"))

    def test_nested_block_comments_are_ignored(self) -> None:
        source = "/** outer /* inner { */ still a comment } */\nfun f() {}\n"
        self.assertEqual([], check_sources.check_source(source, "C.kt"))

    def test_comment_markers_inside_strings_do_not_start_comments(self) -> None:
        source = 'fun f() {\n    val url = "https://example.com/x"\n    val glob = "/* not a comment */"\n}\n'
        self.assertEqual([], check_sources.check_source(source, "D.kt"))

    def test_triple_quoted_strings_may_contain_braces(self) -> None:
        source = 'val json = """{"a": 1}"""\nfun f() {}\n'
        self.assertEqual([], check_sources.check_source(source, "E.kt"))

    def test_a_real_imbalance_is_still_reported(self) -> None:
        problems = check_sources.check_source("fun f() {\n}\n}\n", "F.kt")
        self.assertTrue(problems, "an extra closing brace must be reported")


class RepositoryTest(unittest.TestCase):
    def test_the_repository_is_clean(self) -> None:
        root = Path(__file__).resolve().parent.parent
        problems: list[str] = []
        for directory in ("core", "app", "feature", "tools"):
            target = root / directory
            if not target.is_dir():
                continue
            for path in sorted(target.rglob("*.kt")):
                problems += check_sources.check_file(path)
        self.assertEqual([], problems)


if __name__ == "__main__":
    unittest.main()
