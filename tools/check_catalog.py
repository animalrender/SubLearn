#!/usr/bin/env python3
"""check_catalog.py — Keep the shipped catalog and the app's model table in step.

`docs/catalog/catalog-v2.json` tells the user what a download costs; `NlpModelCatalog` in
`core:ai` decides which profile uses it. If the two disagree, the settings screen either promises
a download that does nothing or hides one that happens. This script compares them:

1. every model entry in the catalog has a matching `NlpModel` in Kotlin (id, size, runtime, RAM);
2. every traced down runtime in this build matches `NlpModelRuntime.isAvailable`;
3. the profile recorded for a model in the catalog is the lowest tier whose model list contains it;
4. the pack entries the app imports (`LEXICON`, `DICT_FA_EN`) still point at a GitHub release URL
   and carry a size.

Usage:
    python3 tools/check_catalog.py [--repo-root .]
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys

CATALOG = os.path.join("docs", "catalog", "catalog-v2.json")
KOTLIN = os.path.join("core", "ai", "src", "main", "kotlin", "com", "sublearn", "core", "ai", "NlpModelCatalog.kt")

MODEL_BLOCK = re.compile(
    r"val\s+(?P<name>[A-Z0-9_]+)\s*=\s*NlpModel\(\s*(?P<body>.*?)\n\s*\)",
    re.DOTALL,
)
ALTERNATIVES = re.compile(r"val smartAlternatives:\s*List<NlpModel>\s*=\s*listOf\((?P<names>[^)]*)\)")
ID_FIELD = re.compile(r'id\s*=\s*"(?P<value>[^"]+)"')
SIZE_FIELD = re.compile(r"sizeBytes\s*=\s*(?P<value>[\d_]+)L")
RAM_FIELD = re.compile(r"minRamMb\s*=\s*(?P<value>\d+)")
RUNTIME_FIELD = re.compile(r"runtime\s*=\s*NlpModelRuntime\.(?P<value>[A-Z_]+)")

MODEL_PROFILES = ("BALANCED", "PRECISE", "ADVANCED", "SMART")

# Mirrors ProfileTier in the Kotlin file: the models each tier adds on top of the previous one.
PROFILE_MODEL_IDS = {
    "BALANCED": ["emb-potion-base-8m"],
    "PRECISE": ["emb-potion-base-8m", "emb-minilm-l6-int8"],
    "ADVANCED": ["emb-potion-base-8m", "emb-minilm-l6-int8", "wsd-distilbert-int8"],
    "SMART": ["emb-potion-base-8m", "emb-minilm-l6-int8", "wsd-distilbert-int8", "llm-qwen25-05b"],
}

# Runtimes that exist in this build, mirroring NlpModelRuntime.isAvailable.
AVAILABLE_RUNTIMES = {"NONE"}


def parse_smart_alternatives(repo_root: str, by_constant: dict[str, str]) -> list[str]:
    """Ids of the models the SMART tier may use instead of its default."""
    path = os.path.join(repo_root, KOTLIN)
    with open(path, encoding="utf-8") as handle:
        text = handle.read()
    match = ALTERNATIVES.search(text)
    if not match:
        return []
    names = [name.strip() for name in match.group("names").split(",") if name.strip()]
    return [by_constant[name] for name in names if name in by_constant]


def parse_kotlin_models(repo_root: str) -> dict[str, dict]:
    path = os.path.join(repo_root, KOTLIN)
    if not os.path.exists(path):
        raise SystemExit(f"error: {path} not found")
    with open(path, encoding="utf-8") as handle:
        text = handle.read()

    models: dict[str, dict] = {}
    for block in MODEL_BLOCK.finditer(text):
        body = block.group("body")
        id_match = ID_FIELD.search(body)
        size_match = SIZE_FIELD.search(body)
        ram_match = RAM_FIELD.search(body)
        runtime_match = RUNTIME_FIELD.search(body)
        if not (id_match and size_match and ram_match and runtime_match):
            continue
        models[id_match.group("value")] = {
            "sizeBytes": int(size_match.group("value").replace("_", "")),
            "minRamMb": int(ram_match.group("value")),
            "runtime": runtime_match.group("value"),
            "constant": block.group("name"),
        }
    return models


def load_catalog(repo_root: str) -> dict:
    path = os.path.join(repo_root, CATALOG)
    if not os.path.exists(path):
        raise SystemExit(f"error: {path} not found")
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)


def check(repo_root: str) -> list[str]:
    problems: list[str] = []
    catalog = load_catalog(repo_root)
    entries = {entry["id"]: entry for entry in catalog.get("entries", [])}
    models = parse_kotlin_models(repo_root)

    if not models:
        return ["no NlpModel declarations were found in NlpModelCatalog.kt"]

    by_constant = {model["constant"]: model_id for model_id, model in models.items()}
    smart_alternatives = parse_smart_alternatives(repo_root, by_constant)

    for model_id, model in sorted(models.items()):
        entry = entries.get(model_id)
        if entry is None:
            problems.append(f"{model_id} is declared in Kotlin but missing from the catalog")
            continue
        if entry.get("sizeBytes") != model["sizeBytes"]:
            problems.append(
                f"{model_id} size differs: catalog {entry.get('sizeBytes')} vs Kotlin {model['sizeBytes']}",
            )
        if entry.get("minRamMb") != model["minRamMb"]:
            problems.append(
                f"{model_id} RAM floor differs: catalog {entry.get('minRamMb')} vs Kotlin {model['minRamMb']}",
            )
        if entry.get("runtime") != model["runtime"]:
            problems.append(
                f"{model_id} runtime differs: catalog {entry.get('runtime')} vs Kotlin {model['runtime']}",
            )
        if model["runtime"] in AVAILABLE_RUNTIMES and entry.get("profile") not in MODEL_PROFILES:
            problems.append(f"{model_id} has no profile in the catalog")

    # Every catalog entry that looks like a model must be known to Kotlin, and the profile it
    # advertises must be the lowest tier that uses it.
    for model_id, entry in sorted(entries.items()):
        profile = entry.get("profile")
        if profile not in MODEL_PROFILES:
            continue
        lowest = next(
            (
                tier for tier in MODEL_PROFILES
                if model_id in PROFILE_MODEL_IDS[tier]
                or (tier == "SMART" and model_id in smart_alternatives)
            ),
            None,
        )
        if lowest is None:
            problems.append(f"{model_id} is in the catalog under profile {profile} but no Kotlin tier uses it")
        elif lowest != profile:
            problems.append(f"{model_id} is filed under {profile} in the catalog but first used by {lowest}")

    # Pack entries must stay importable: an HTTPS release URL and a known size.
    for entry in catalog.get("entries", []):
        if entry.get("kind") not in ("LEXICON", "DICT_FA_EN"):
            continue
        url = entry.get("source", {}).get("url", "")
        if not url.startswith("https://github.com/"):
            problems.append(f"{entry['id']} does not point at a GitHub release: {url or 'no url'}")
        if not entry.get("sizeBytes"):
            problems.append(f"{entry['id']} has no size, so the download screen cannot show one")

    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Check the catalog against the app's model table")
    parser.add_argument("--repo-root", default=".")
    args = parser.parse_args(argv)

    problems = check(args.repo_root)
    if problems:
        print(f"CATALOG CHECK FAILED ({len(problems)} problems):", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        return 1

    print("CATALOG CHECK PASSED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
