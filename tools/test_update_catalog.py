#!/usr/bin/env python3
"""Tests for update_catalog.py."""

import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from update_catalog import (  # noqa: E402
    apply_assets,
    asset_record,
    build_release_url,
    parse_asset_args,
    sha256_file,
)


class Sha256Test(unittest.TestCase):
    def test_known_digest(self):
        with tempfile.NamedTemporaryFile(delete=False) as handle:
            handle.write(b"hello world")
            path = handle.name
        try:
            self.assertEqual(
                "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9",
                sha256_file(path),
            )
        finally:
            os.unlink(path)

    def test_empty_file(self):
        with tempfile.NamedTemporaryFile(delete=False) as handle:
            path = handle.name
        try:
            self.assertEqual(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                sha256_file(path),
            )
        finally:
            os.unlink(path)


class UrlTest(unittest.TestCase):
    def test_release_url_shape(self):
        self.assertEqual(
            "https://github.com/animalrender/SubLearn/releases/download/packs-v2.1.0/sublearn-lexicon-en-fa-v2.sqlite.gz",
            build_release_url(
                "animalrender/SubLearn",
                "packs-v2.1.0",
                "sublearn-lexicon-en-fa-v2.sqlite.gz",
            ),
        )


class ApplyAssetsTest(unittest.TestCase):
    def catalog(self):
        return {
            "version": 2,
            "updatedAt": "2026-01-01T00:00:00Z",
            "entries": [
                {"id": "sublearn-lexicon-en-fa-v2", "sizeBytes": 1, "sha256": "old", "verified": False},
                {"id": "sublearn-dict-fa-en-v2", "sizeBytes": 2, "sha256": "old", "verified": False},
            ],
        }

    def test_writes_size_hash_url_and_tag(self):
        catalog = self.catalog()
        assets = [
            {
                "id": "sublearn-lexicon-en-fa-v2",
                "filename": "pack.sqlite.gz",
                "sizeBytes": 1234,
                "sha256": "abc",
                "url": "https://github.com/o/r/releases/download/t/pack.sqlite.gz",
            }
        ]
        matched = apply_assets(catalog, assets, "t", "2026-02-02T00:00:00Z")

        self.assertEqual(["sublearn-lexicon-en-fa-v2"], matched)
        entry = catalog["entries"][0]
        self.assertEqual(1234, entry["sizeBytes"])
        self.assertEqual("abc", entry["sha256"])
        self.assertEqual("t", entry["releaseTag"])
        self.assertTrue(entry["verified"])
        self.assertEqual("github.com", entry["source"]["host"])
        self.assertEqual("2026-02-02T00:00:00Z", catalog["updatedAt"])

    def test_leaves_unrelated_entries_alone(self):
        catalog = self.catalog()
        apply_assets(
            catalog,
            [
                {
                    "id": "sublearn-lexicon-en-fa-v2",
                    "filename": "pack.sqlite.gz",
                    "sizeBytes": 5,
                    "sha256": "abc",
                    "url": "u",
                }
            ],
            "t",
            "now",
        )
        untouched = catalog["entries"][1]
        self.assertEqual(2, untouched["sizeBytes"])
        self.assertEqual("old", untouched["sha256"])
        self.assertFalse(untouched["verified"])

    def test_unknown_id_raises(self):
        with self.assertRaises(KeyError):
            apply_assets(
                self.catalog(),
                [{"id": "nope", "filename": "f", "sizeBytes": 1, "sha256": "x", "url": "u"}],
                "t",
                "now",
            )


class ParseAssetArgsTest(unittest.TestCase):
    def test_parses_repeated_arguments(self):
        self.assertEqual(
            {"a": "one.gz", "b": "two.gz"},
            parse_asset_args(["a=one.gz", "b=two.gz"]),
        )

    def test_path_may_contain_equals(self):
        self.assertEqual({"a": "dir=name.gz"}, parse_asset_args(["a=dir=name.gz"]))

    def test_rejects_missing_separator(self):
        with self.assertRaises(ValueError):
            parse_asset_args(["no-separator"])

    def test_rejects_empty_halves(self):
        with self.assertRaises(ValueError):
            parse_asset_args(["=path"])
        with self.assertRaises(ValueError):
            parse_asset_args(["id="])


class RecordTest(unittest.TestCase):
    def test_asset_record_uses_basename(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "sublearn-lexicon-en-fa-v2.sqlite.gz")
            with open(path, "wb") as handle:
                handle.write(b"pack bytes")
            record = asset_record("o/r", "packs-v2.1.0", "sublearn-lexicon-en-fa-v2", path)

        self.assertEqual("sublearn-lexicon-en-fa-v2", record["id"])
        self.assertEqual("sublearn-lexicon-en-fa-v2.sqlite.gz", record["filename"])
        self.assertEqual(10, record["sizeBytes"])
        self.assertTrue(record["url"].endswith("/packs-v2.1.0/sublearn-lexicon-en-fa-v2.sqlite.gz"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
