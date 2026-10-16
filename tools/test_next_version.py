#!/usr/bin/env python3
"""Tests for next_version.py — the version the release pipeline publishes."""

import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from next_version import (  # noqa: E402
    bump_patch,
    format_version,
    highest_stable,
    next_version,
    parse_version,
    read_floor,
)


class ParseVersionTest(unittest.TestCase):
    def test_plain_and_prefixed(self):
        self.assertEqual(parse_version("3.0.0"), (3, 0, 0))
        self.assertEqual(parse_version("v3.0.1"), (3, 0, 1))
        self.assertEqual(parse_version(" 3.0.1 "), (3, 0, 1))

    def test_pre_release_suffix_is_ignored(self):
        self.assertEqual(parse_version("3.0.1-rc1"), (3, 0, 1))

    def test_garbage_is_rejected(self):
        with self.assertRaises(ValueError):
            parse_version("not-a-version")
        with self.assertRaises(ValueError):
            parse_version("3.0")


class HighestStableTest(unittest.TestCase):
    def test_rolling_prereleases_do_not_count(self):
        tags = ["v0.2.1", "dev-main", "dev-arena-40fc4e81-sublearn", "v0.2.10"]
        self.assertEqual(highest_stable(tags), (0, 2, 10))

    def test_no_stable_tag(self):
        self.assertIsNone(highest_stable(["dev-main"]))


class BumpPatchTest(unittest.TestCase):
    def test_only_the_patch_moves(self):
        self.assertEqual(bump_patch((3, 0, 0)), (3, 0, 1))
        self.assertEqual(bump_patch((3, 0, 9)), (3, 0, 10))
        self.assertEqual(bump_patch((3, 1, 4)), (3, 1, 5))


class NextVersionTest(unittest.TestCase):
    def test_the_first_release_is_the_floor(self):
        # Nothing released yet, but the repository says the next release is 3.0.0.
        version, base, _ = next_version([], "3.0.0")
        self.assertEqual(version, "3.0.0")
        self.assertIsNone(base)

    def test_after_3_0_0_every_release_adds_one_patch(self):
        version, base, _ = next_version(["v3.0.0"], "3.0.0")
        self.assertEqual(version, "3.0.1")
        self.assertEqual(base, (3, 0, 0))

        version, _, _ = next_version(["v3.0.0", "v3.0.1"], "3.0.0")
        self.assertEqual(version, "3.0.2")

    def test_the_floor_never_lowers_a_version(self):
        # A floor below the candidate is ignored: the patch bump always wins.
        version, _, _ = next_version(["v3.0.7"], "3.0.0")
        self.assertEqual(version, "3.0.8")

    def test_without_a_floor_the_patch_bump_starts_from_zero(self):
        version, _, _ = next_version([], None)
        self.assertEqual(version, "0.0.1")

    def test_rolling_prereleases_are_ignored(self):
        version, _, _ = next_version(["v3.0.0", "dev-main", "dev-arena-x"], "3.0.0")
        self.assertEqual(version, "3.0.1")


class FloorFileTest(unittest.TestCase):
    def test_the_committed_floor_is_read(self):
        repo_root = Path(__file__).resolve().parent.parent
        self.assertEqual(read_floor(repo_root / "VERSION"), "3.0.0")

    def test_a_missing_file_is_not_an_error(self):
        with tempfile.TemporaryDirectory() as directory:
            self.assertIsNone(read_floor(Path(directory) / "VERSION"))

    def test_an_empty_file_is_not_a_floor(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "VERSION"
            path.write_text("\n", encoding="utf-8")
            self.assertIsNone(read_floor(path))


class FormatVersionTest(unittest.TestCase):
    def test_round_trip(self):
        self.assertEqual(format_version((3, 0, 1)), "3.0.1")


if __name__ == "__main__":
    unittest.main()
