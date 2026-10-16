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

# `ModelStore.LAYOUTS` is the second copy of the catalog's file list: the installer downloads from
# it and the runtimes open files through it. It is checked field by field below.
LAYOUT_FILE = os.path.join(
    "core", "ai", "src", "main", "kotlin", "com", "sublearn", "core", "ai", "ModelStore.kt",
)
# The closing parenthesis is anchored to its indentation: an asset closes at 20 spaces and the
# layout at 12, so a looser pattern would cut the first asset's `),` and lose the rest of the list.
LAYOUT_BLOCK = re.compile(
    r"^ {12}ModelFileLayout\(\s*id\s*=\s*\"(?P<id>[^\"]+)\"(?P<body>.*?)\n {12}\),",
    re.DOTALL | re.MULTILINE,
)
ASSET_BLOCK = re.compile(
    r"ModelAsset\(\s*name\s*=\s*\"(?P<name>[^\"]+)\",\s*"
    r"sizeBytes\s*=\s*(?P<size>[\d_]+)L,\s*"
    r"sha256\s*=\s*(?P<sha>\"[^\"]*\"|null),\s*"
    r"url\s*=\s*\"(?P<url>[^\"]+)\",",
    re.DOTALL,
)
LAYOUT_VOCAB = re.compile(r'vocabularyFile\s*=\s*"(?P<name>[^"]+)"')
LAYOUT_GATED = re.compile(r"requiresTermsAcceptance\s*=\s*true")

# The catalog the app ships with. `docs/catalog/catalog-v2.json` is the published catalog and
# `ModelCatalogParser.bundledDefault()` is the copy the downloads screen reads; when the two
# disagree the app offers a download whose SHA-256 can never match (D-29).
BUNDLED_FILE = os.path.join(
    "core", "models", "src", "main", "kotlin", "com", "sublearn", "core", "models", "ModelCatalog.kt",
)
BUNDLED_ENTRY = re.compile(
    r"ModelCatalogEntry\(\s*(?P<body>.*?)\n {12}\),",
    re.DOTALL,
)
BUNDLED_FILEINFO = re.compile(
    r"FileInfo\(\s*name\s*=\s*\"(?P<name>[^\"]+)\",\s*"
    r"sizeBytes\s*=\s*(?P<size>[\d_]+)L,\s*"
    r"(?:sha256\s*=\s*\"(?P<sha>[^\"]*)\",\s*)?",
    re.DOTALL,
)


def parse_bundled_default(repo_root: str) -> dict[str, dict]:
    """The `bundledDefault()` table from `ModelCatalog.kt`, as {id: {field: value}}."""
    path = os.path.join(repo_root, BUNDLED_FILE)
    if not os.path.exists(path):
        raise SystemExit(f"error: {path} not found")
    with open(path, encoding="utf-8") as handle:
        text = handle.read()

    entries: dict[str, dict] = {}
    for block in BUNDLED_ENTRY.finditer(text):
        body = block.group("body")
        identifier = ID_FIELD.search(body)
        if not identifier:
            continue
        size = SIZE_FIELD.search(body)
        runtime = re.search(r"runtime\s*=\s*ModelRuntime\.(?P<value>[A-Z_]+)", body)
        kind = re.search(r"kind\s*=\s*ModelKind\.(?P<value>[A-Z_]+)", body)
        profile = re.search(r"profile\s*=\s*AccuracyProfile\.(?P<value>[A-Z_]+)", body)
        ram = RAM_FIELD.search(body)
        url = re.search(r"url\s*=\s*\"(?P<value>[^\"]+)\"", body)
        sha = re.search(r"sha256\s*=\s*\"(?P<value>[^\"]+)\"", body)
        entries[identifier.group("value")] = {
            "kind": kind.group("value") if kind else None,
            "sizeBytes": int(size.group("value").replace("_", "")) if size else None,
            "sha256": sha.group("value") if sha else None,
            "runtime": runtime.group("value") if runtime else None,
            "minRamMb": int(ram.group("value")) if ram else None,
            "profile": profile.group("value") if profile else None,
            "url": url.group("value") if url else None,
            "verified": bool(re.search(r"verified\s*=\s*true", body)),
            "requiresTermsAcceptance": bool(re.search(r"requiresTermsAcceptance\s*=\s*true", body)),
            "texts": {
                field: (match.group("value") if match else None)
                for field, match in (
                    ("nameEn", re.search(r"nameEn\s*=\s*\"(?P<value>[^\"]*)\"", body)),
                    ("nameFa", re.search(r"nameFa\s*=\s*\"(?P<value>[^\"]*)\"", body)),
                    ("version", re.search(r"version\s*=\s*\"(?P<value>[^\"]*)\"", body)),
                    ("direction", re.search(r"direction\s*=\s*\"(?P<value>[^\"]*)\"", body)),
                    ("license", re.search(r"license\s*=\s*\"(?P<value>[^\"]*)\"", body)),
                )
            },
            "notes": (re.search(r"notes\s*=\s*\"(?P<value>[^\"]*)\"", body).group("value")
                      if re.search(r"notes\s*=\s*\"(?P<value>[^\"]*)\"", body) else None),
            "files": [
                {
                    "name": item.group("name"),
                    "sizeBytes": int(item.group("size").replace("_", "")),
                    "sha256": item.group("sha"),
                }
                for item in BUNDLED_FILEINFO.finditer(body)
            ],
        }
    return entries


def check_bundled_default(catalog: dict, bundled: dict[str, dict]) -> list[str]:
    """The app's own catalog must be the published catalog, field by field."""
    problems: list[str] = []
    entries = {entry["id"]: entry for entry in catalog.get("entries", [])}

    for model_id, entry in sorted(entries.items()):
        copy = bundled.get(model_id)
        if copy is None:
            problems.append(f"{model_id} is in the catalog but not in the app's bundled catalog")
            continue
        for field in ("sizeBytes", "sha256", "runtime", "minRamMb", "profile", "kind"):
            if copy.get(field) != entry.get(field):
                problems.append(
                    f"{model_id} {field} differs: catalog {entry.get(field)} vs bundled {copy.get(field)}",
                )
        if copy.get("url") != entry.get("source", {}).get("url"):
            problems.append(f"{model_id} download URL differs from the catalog")
        if copy.get("verified") != bool(entry.get("verified")):
            problems.append(f"{model_id} verified flag differs from the catalog")
        if copy.get("requiresTermsAcceptance") != bool(entry.get("requiresTermsAcceptance")):
            problems.append(f"{model_id} licence gate differs from the catalog")
        for field, value in copy.get("texts", {}).items():
            if value != entry.get(field):
                problems.append(f"{model_id} {field} differs: catalog {entry.get(field)!r} vs bundled {value!r}")
        if copy.get("notes") != entry.get("notes"):
            problems.append(f"{model_id} notes differ from the catalog")
        catalog_files = [
            (item["name"], item.get("sizeBytes"), item.get("sha256"))
            for item in (entry.get("files") or [])
        ]
        bundled_files = [
            (item["name"], item["sizeBytes"], item["sha256"]) for item in copy.get("files", [])
        ]
        if catalog_files != bundled_files:
            problems.append(f"{model_id} file list differs: catalog {catalog_files} vs bundled {bundled_files}")

    for model_id in sorted(set(bundled) - set(entries)):
        problems.append(f"{model_id} is in the app's bundled catalog but not in the published catalog")

    return problems


def parse_kotlin_layouts(repo_root: str) -> dict[str, dict]:
    """The file layout table from `ModelStore.kt`, as {id: {assets: [...], ...}}."""
    path = os.path.join(repo_root, LAYOUT_FILE)
    if not os.path.exists(path):
        raise SystemExit(f"error: {path} not found")
    with open(path, encoding="utf-8") as handle:
        text = handle.read()

    layouts: dict[str, dict] = {}
    for block in LAYOUT_BLOCK.finditer(text):
        body = block.group("body")
        vocabulary = LAYOUT_VOCAB.search(body)
        layouts[block.group("id")] = {
            "assets": [
                {
                    "name": asset.group("name"),
                    "sizeBytes": int(asset.group("size").replace("_", "")),
                    "sha256": None if asset.group("sha") == "null" else asset.group("sha").strip('"'),
                    "url": asset.group("url"),
                }
                for asset in ASSET_BLOCK.finditer(body)
            ],
            "vocabularyFile": vocabulary.group("name") if vocabulary else None,
            "requiresTermsAcceptance": bool(LAYOUT_GATED.search(body)),
        }
    return layouts


def check_layouts(catalog: dict, layouts: dict[str, dict]) -> list[str]:
    """Every catalog model's files must match the Kotlin layout, field by field."""
    problems: list[str] = []
    for entry in catalog.get("entries", []):
        model_id = entry.get("id")
        files = entry.get("files") or []
        if not files:
            continue

        layout = layouts.get(model_id)
        if layout is None:
            problems.append(f"{model_id} has files in the catalog but no ModelStore layout")
            continue

        recorded = layout["assets"]
        if [asset["name"] for asset in recorded] != [item["name"] for item in files]:
            problems.append(
                f"{model_id} file names differ: catalog {[i['name'] for i in files]} "
                f"vs ModelStore {[a['name'] for a in recorded]}",
            )
            continue

        for asset, item in zip(recorded, files):
            if asset["sizeBytes"] != item.get("sizeBytes"):
                problems.append(
                    f"{model_id}/{item['name']} size differs: catalog {item.get('sizeBytes')} "
                    f"vs ModelStore {asset['sizeBytes']}",
                )
            if asset["sha256"] != item.get("sha256"):
                problems.append(f"{model_id}/{item['name']} SHA-256 differs from the catalog")
            if asset["url"] != item.get("url"):
                problems.append(f"{model_id}/{item['name']} URL differs from the catalog")

        if layout["requiresTermsAcceptance"] != bool(entry.get("requiresTermsAcceptance")):
            problems.append(f"{model_id} licence-gating differs between the catalog and ModelStore")

        vocabulary = layout["vocabularyFile"]
        if vocabulary and vocabulary not in [item["name"] for item in files]:
            problems.append(f"{model_id} declares vocabulary {vocabulary} but never downloads it")
    return problems

# Mirrors ProfileTier in the Kotlin file: the models each tier adds on top of the previous one.
PROFILE_MODEL_IDS = {
    "BALANCED": ["emb-potion-base-8m"],
    "PRECISE": ["emb-potion-base-8m", "emb-minilm-l6-int8"],
    "ADVANCED": ["emb-potion-base-8m", "emb-minilm-l6-int8", "wsd-distilbert-int8"],
    "SMART": ["emb-potion-base-8m", "emb-minilm-l6-int8", "wsd-distilbert-int8", "llm-qwen25-05b"],
}

# Runtimes this build links. ONNX and LiteRT ship as dependencies of `core:nlp-onnx` and
# `core:nlp-llm`, which `app` depends on; whether a given *device* can load their native library is
# decided at startup by `NlpRuntimeBootstrap` and is not something a repository check can know.
AVAILABLE_RUNTIMES = {"NONE", "ONNX", "LITERT"}


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
            "requiresTermsAcceptance": bool(re.search(r"requiresTermsAcceptance\s*=\s*true", body)),
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
    problems += check_layouts(catalog, parse_kotlin_layouts(repo_root))
    problems += check_bundled_default(catalog, parse_bundled_default(repo_root))

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
        if bool(entry.get("requiresTermsAcceptance")) != model.get("requiresTermsAcceptance", False):
            problems.append(
                f"{model_id} licence gate differs: catalog {bool(entry.get('requiresTermsAcceptance'))} "
                f"vs Kotlin {model.get('requiresTermsAcceptance', False)}",
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
