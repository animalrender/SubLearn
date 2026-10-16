#!/usr/bin/env python3
"""Tests for check_catalog.py, using a temporary repository copy so the real catalog is untouched."""

import json
import os
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from check_catalog import check, load_catalog, parse_kotlin_models, parse_smart_alternatives  # noqa: E402

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


class ParseKotlinTest(unittest.TestCase):
    def test_reads_the_shipped_models(self):
        models = parse_kotlin_models(REPO_ROOT)
        self.assertEqual(6, len(models))
        potion = models["emb-potion-base-8m"]
        # Weights plus the WordPiece vocabulary, as published (tools/refresh_model_catalog.py).
        self.assertEqual(30_456_450, potion["sizeBytes"])
        self.assertEqual(128, potion["minRamMb"])
        self.assertEqual("NONE", potion["runtime"])
        self.assertEqual("POTION_BASE_8M", potion["constant"])

    def test_smart_alternatives_resolve_to_ids(self):
        models = parse_kotlin_models(REPO_ROOT)
        by_constant = {model["constant"]: model_id for model_id, model in models.items()}
        self.assertEqual(
            ["llm-smollm2-360m", "llm-gemma3-270m"],
            parse_smart_alternatives(REPO_ROOT, by_constant),
        )


class CheckTest(unittest.TestCase):
    def test_the_repository_is_consistent(self):
        self.assertEqual([], check(REPO_ROOT))

    def _broken_copy(self, mutate):
        tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, tmp, True)
        shutil.copytree(os.path.join(REPO_ROOT, "core"), os.path.join(tmp, "core"))
        shutil.copytree(os.path.join(REPO_ROOT, "docs"), os.path.join(tmp, "docs"))
        catalog_path = os.path.join(tmp, "docs", "catalog", "catalog-v2.json")
        catalog = json.load(open(catalog_path, encoding="utf-8"))
        mutate(catalog)
        with open(catalog_path, "w", encoding="utf-8") as handle:
            json.dump(catalog, handle, indent=2, ensure_ascii=False)
        return tmp

    def test_detects_a_size_mismatch(self):
        def mutate(catalog):
            for entry in catalog["entries"]:
                if entry["id"] == "emb-potion-base-8m":
                    entry["sizeBytes"] = 123

        problems = check(self._broken_copy(mutate))
        self.assertTrue(any("size differs" in problem for problem in problems), problems)

    def test_detects_a_runtime_mismatch(self):
        def mutate(catalog):
            for entry in catalog["entries"]:
                if entry["id"] == "wsd-distilbert-int8":
                    entry["runtime"] = "NONE"

        problems = check(self._broken_copy(mutate))
        self.assertTrue(any("runtime differs" in problem for problem in problems), problems)

    def test_detects_a_profile_that_no_tier_uses(self):
        def mutate(catalog):
            for entry in catalog["entries"]:
                if entry["id"] == "llm-gemma3-270m":
                    entry["profile"] = "BALANCED"

        problems = check(self._broken_copy(mutate))
        self.assertTrue(any("first used by" in problem for problem in problems), problems)

    def test_detects_a_model_the_catalog_forgot(self):
        def mutate(catalog):
            catalog["entries"] = [e for e in catalog["entries"] if e["id"] != "emb-minilm-l6-int8"]

        problems = check(self._broken_copy(mutate))
        self.assertTrue(any("missing from the catalog" in problem for problem in problems), problems)

    def _copy_with_bundled_mutation(self, mutate):
        """A copy of the repository whose app-side catalog (ModelCatalog.kt) is changed."""
        tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, tmp, True)
        shutil.copytree(os.path.join(REPO_ROOT, "core"), os.path.join(tmp, "core"))
        shutil.copytree(os.path.join(REPO_ROOT, "docs"), os.path.join(tmp, "docs"))
        bundled = os.path.join(
            tmp, "core", "models", "src", "main", "kotlin", "com", "sublearn", "core", "models",
            "ModelCatalog.kt",
        )
        with open(bundled, encoding="utf-8") as handle:
            source = handle.read()
        with open(bundled, "w", encoding="utf-8") as handle:
            handle.write(mutate(source))
        return tmp

    def test_detects_a_bundled_hash_that_drifted(self):
        # The app's own copy of the catalog is what the download is verified against. A SHA-256
        # there that is not the published one is exactly what made every pack download fail with
        # "SHA-256 mismatch" (D-83), so the checker has to notice it.
        def mutate(source):
            return source.replace(
                'sha256 = "1957c0afbf7e7b7c42d5453f91120a5726a53747b67132b8005851118001b95e"',
                'sha256 = "500696765c0d5339c5c4d0823604d8d53fec9abff96bd1dd1b0bf1b4504669ef"',
            )

        problems = check(self._copy_with_bundled_mutation(mutate))
        self.assertTrue(any("sha256 differs" in problem for problem in problems), problems)

    def test_detects_a_bundled_size_that_drifted(self):
        def mutate(source):
            return source.replace("sizeBytes = 35_648_482L", "sizeBytes = 35_863_515L")

        problems = check(self._copy_with_bundled_mutation(mutate))
        self.assertTrue(any("sizeBytes differs" in problem for problem in problems), problems)

    def test_detects_a_pack_with_a_non_release_url(self):
        def mutate(catalog):
            for entry in catalog["entries"]:
                if entry["kind"] == "LEXICON":
                    entry["source"]["url"] = "https://example.com/pack.sqlite.gz"

        problems = check(self._broken_copy(mutate))
        self.assertTrue(any("GitHub release" in problem for problem in problems), problems)


class LoadTest(unittest.TestCase):
    def test_catalog_has_the_two_packs_and_six_models(self):
        catalog = load_catalog(REPO_ROOT)
        self.assertEqual(2, catalog["version"])
        ids = [entry["id"] for entry in catalog["entries"]]
        self.assertEqual(8, len(ids))
        self.assertIn("sublearn-lexicon-en-fa-v2", ids)
        self.assertIn("llm-qwen25-05b", ids)


if __name__ == "__main__":
    unittest.main(verbosity=2)
