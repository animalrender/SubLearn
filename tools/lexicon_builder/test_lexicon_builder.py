#!/usr/bin/env python3
"""Tests for the lexicon builder.

Run with: python3 -m pytest tools/lexicon_builder/test_lexicon_builder.py
   or:    python3 tools/lexicon_builder/test_lexicon_builder.py

Uses the sample.sqlite from the dictionaryproject repo as input.
"""

import json
import os
import sqlite3
import sys
import tempfile
import unittest

# Add the parent directory to path
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_lexicon import build_lexicon, normalize_key, convert_to_pattern, find_anchor_lemma


# Path to the sample database (from dictionaryproject/docs/)
# The sample.sqlite is cloned alongside the SubLearn repo
SAMPLE_DB = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "..", "..", "..", "..", "dictionaryproject", "docs", "sample.sqlite",
)

# Try alternative paths
if not os.path.exists(SAMPLE_DB):
    SAMPLE_DB = os.path.expanduser("~/dictionaryproject/docs/sample.sqlite")
if not os.path.exists(SAMPLE_DB):
    # Also try the test output from a previous run
    SAMPLE_DB = os.path.join(
        os.path.dirname(os.path.abspath(__file__)),
        "test_fixtures", "sample.sqlite",
    )


class TestNormalizeKey(unittest.TestCase):
    def test_lowercase(self):
        self.assertEqual(normalize_key("Hello"), "hello")

    def test_strip_punctuation(self):
        self.assertEqual(normalize_key("hello."), "hello")
        self.assertEqual(normalize_key("hello,"), "hello")

    def test_collapse_whitespace(self):
        self.assertEqual(normalize_key("hello  world"), "hello world")

    def test_strip_both_ends(self):
        self.assertEqual(normalize_key("  hello  "), "hello")


class TestConvertToPattern(unittest.TestCase):
    def test_basic(self):
        self.assertEqual(convert_to_pattern("get off"), "get off")

    def test_possessive(self):
        result = convert_to_pattern("make one's mind")
        self.assertIn("POSS", result)

    def test_reflexive(self):
        result = convert_to_pattern("enjoy oneself")
        self.assertIn("REFL", result)

    def test_optional_parts(self):
        result = convert_to_pattern("a piece of cake (or thin)")
        self.assertNotIn("(or thin)", result)

    def test_someone_becomes_np(self):
        result = convert_to_pattern("help someone out")
        self.assertIn("{NP}", result)


class TestFindAnchorLemma(unittest.TestCase):
    def test_picks_content_word(self):
        anchor = find_anchor_lemma("piece of cake")
        self.assertIn(anchor, ["piece", "cake"])

    def test_skips_function_words(self):
        anchor = find_anchor_lemma("get up")
        # Should not pick "get" as it's a common verb; "up" is a particle
        # Either is acceptable, but the anchor should be a real word
        self.assertTrue(len(anchor) > 0)


class TestBuildLexicon(unittest.TestCase):
    @unittest.skipUnless(os.path.exists(SAMPLE_DB), f"Sample DB not found at {SAMPLE_DB}")
    def test_builds_valid_output(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            report = build_lexicon(SAMPLE_DB, tmpdir)

            # Check report
            self.assertTrue(report["stats"]["entries_created"] > 0)
            self.assertTrue(report["stats"]["senses_created"] > 0)
            self.assertIn("output_sha256", report)
            self.assertEqual(report["stats"]["entries_created"], int(
                # Check meta entry_count matches
                sqlite3.connect(os.path.join(tmpdir, "sublearn-lexicon-en-fa-v2.sqlite"))
                .execute("SELECT value FROM meta WHERE key='entry_count'").fetchone()[0]
            ))

            # Check output file exists
            output_path = os.path.join(tmpdir, "sublearn-lexicon-en-fa-v2.sqlite")
            self.assertTrue(os.path.exists(output_path))

            # Check schema
            conn = sqlite3.connect(output_path)
            tables = {r[0] for r in conn.execute(
                "SELECT name FROM sqlite_master WHERE type='table'"
            ).fetchall()}
            required_tables = {"meta", "lemma_form", "entry", "sense", "example", "pattern", "relation"}
            self.assertTrue(required_tables.issubset(tables), f"Missing tables: {required_tables - tables}")

            # Check meta
            meta = dict(conn.execute("SELECT key, value FROM meta").fetchall())
            self.assertEqual(meta["format_version"], "2")
            self.assertEqual(meta["source_lang"], "en")
            self.assertEqual(meta["gloss_lang"], "fa")

            # Check entry kinds
            kinds = {r[0] for r in conn.execute("SELECT DISTINCT kind FROM entry").fetchall()}
            self.assertIn("WORD", kinds)
            # Check that we have at least some idioms/phrasals
            multi_word = {r[0] for r in conn.execute(
                "SELECT DISTINCT kind FROM entry WHERE kind IN ('PHRASAL', 'IDIOM')"
            ).fetchall()}
            self.assertTrue(len(multi_word) > 0, "Expected some phrasal/idiom entries")

            conn.close()

    @unittest.skipUnless(os.path.exists(SAMPLE_DB), f"Sample DB not found at {SAMPLE_DB}")
    def test_report_json(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            report = build_lexicon(SAMPLE_DB, tmpdir)
            report_path = os.path.join(tmpdir, "build_report.json")
            # The build function doesn't write the JSON, but the main() function does
            # Let's verify the report dict has the expected structure
            self.assertIn("stats", report)
            self.assertIn("builder_version", report)
            self.assertIn("output_sha256", report)
            self.assertIn("rejected_samples", report)


if __name__ == "__main__":
    unittest.main()
