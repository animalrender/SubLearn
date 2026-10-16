#!/usr/bin/env python3
"""refresh_model_catalog.py — Write the real size and SHA-256 of every model into the catalog.

`docs/catalog/catalog-v2.json` tells the download screen what a model costs and what the bytes
must hash to. Both values used to be typed by hand, and both had drifted: the MiniLM entry
promised 23,068,672 bytes for a 22,972,370-byte file, the SmolLM2 entry pointed at a `.task`
filename that exists only under a different name, and three of the six models carried no hash at
all despite being marked `verified`.

This script reads the publisher's own metadata (the HuggingFace model API, which reports each
file's LFS SHA-256) and writes it into the catalog, so the two cannot disagree:

* every needed file gets an entry in `files[]` with its size and hash;
* `sizeBytes` becomes the sum of those files, which is what the download actually costs;
* `sha256` becomes the digest of the file the entry's `source.url` points at, so the existing
  one-file verification path keeps working;
* a repository that needs licence acceptance is flagged rather than silently offered.

Usage:
    python3 tools/refresh_model_catalog.py [--catalog docs/catalog/catalog-v2.json] [--check]

`--check` reports drift and exits non-zero without writing, which is what CI runs when it cannot
reach the network: an offline build still fails a catalog whose sizes disagree with the recorded
file list.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from typing import Any

HF_API = "https://huggingface.co/api/models/{repo}?blobs=true"
RESOLVE = "https://huggingface.co/{repo}/resolve/main/{path}"

# Which files each catalog entry actually needs, and what the app calls them once installed.
# The encoder models need their WordPiece vocabulary next to the ONNX graph; the LiteRT bundles
# are single self-contained files.
REQUIRED_FILES: dict[str, list[tuple[str, str]]] = {
    "emb-potion-base-8m": [("model.safetensors", "model.safetensors"), ("vocab.txt", "vocab.txt")],
    "emb-minilm-l6-int8": [("onnx/model_quantized.onnx", "model_quantized.onnx"), ("vocab.txt", "vocab.txt")],
    "wsd-distilbert-int8": [("onnx/model_quantized.onnx", "model_quantized.onnx"), ("vocab.txt", "vocab.txt")],
    "llm-qwen25-05b": [
        ("Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task", "qwen2.5-0.5b-instruct.task"),
    ],
    "llm-smollm2-360m": [("SmolLM2_360M_instruct.litertlm", "smollm2-360m-instruct.litertlm")],
    "llm-gemma3-270m": [("gemma3-270m-it-q8.task", "gemma3-270m-it.task")],
}

# The repository each catalog entry downloads from, so a renamed repo is a one-line change.
REPOSITORIES: dict[str, str] = {
    "emb-potion-base-8m": "minishlab/potion-base-8M",
    "emb-minilm-l6-int8": "Xenova/all-MiniLM-L6-v2",
    "wsd-distilbert-int8": "Xenova/distilbert-base-uncased",
    "llm-qwen25-05b": "litert-community/Qwen2.5-0.5B-Instruct",
    "llm-smollm2-360m": "litert-community/SmolLM2-360M-Instruct",
    "llm-gemma3-270m": "litert-community/gemma-3-270m-it",
}

# Entry id → the single file whose digest goes in `sha256` (the app's one-file check).
PRIMARY_FILE: dict[str, str] = {
    entry: needed[0][0] for entry, needed in REQUIRED_FILES.items()
}


def fetch_repo(repo: str, timeout: int = 30) -> dict[str, Any]:
    """Read a HuggingFace repository's file list, with the LFS digest of each file."""
    request = urllib.request.Request(HF_API.format(repo=repo), headers={"User-Agent": "SubLearn-catalog"})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.load(response)


def files_of(repo: str, payload: dict[str, Any]) -> dict[str, dict[str, Any]]:
    """Map repository path → {size, sha256} for every file the API reported."""
    out: dict[str, dict[str, Any]] = {}
    for sibling in payload.get("siblings", []):
        path = sibling.get("rfilename")
        if not path:
            continue
        size = sibling.get("size")
        digest = (sibling.get("lfs") or {}).get("sha256")
        if size is None:
            continue
        out[path] = {"size": int(size), "sha256": digest}
    return out


def describe_entry(entry_id: str, repo: str, files: dict[str, dict[str, Any]]) -> dict[str, Any]:
    """Build the catalog fields for one entry from the repository metadata."""
    needed = REQUIRED_FILES[entry_id]
    described = []
    total = 0
    for remote_path, local_name in needed:
        info = files.get(remote_path)
        if info is None:
            raise KeyError(f"{repo} does not publish {remote_path}")
        total += info["size"]
        described.append(
            {
                "name": local_name,
                "sizeBytes": info["size"],
                "sha256": info["sha256"],
                "url": RESOLVE.format(repo=repo, path=remote_path),
            }
        )

    primary = next(item for item in described if item["name"] == os.path.basename(PRIMARY_FILE[entry_id]))
    return {
        "files": described,
        "sizeBytes": total,
        "sha256": primary["sha256"],
        "source": {"host": "huggingface.co", "url": primary["url"]},
    }


def refresh(catalog: dict[str, Any], fetch=fetch_repo) -> list[str]:
    """Rewrite every model entry from its repository. Returns the ids that changed."""
    changed: list[str] = []
    for entry in catalog.get("entries", []):
        entry_id = entry.get("id")
        if entry_id not in REPOSITORIES:
            continue

        repo = REPOSITORIES[entry_id]
        payload = fetch(repo)
        described = describe_entry(entry_id, repo, files_of(repo, payload))

        before = (entry.get("sizeBytes"), entry.get("sha256"), entry.get("files"))
        entry.update(described)
        entry["verified"] = True
        entry["license"] = (payload.get("cardData") or {}).get("license") or entry.get("license")
        # A gated repository answers an unauthenticated request with 401, so the download screen
        # has to say so instead of offering a button that cannot work.
        entry["requiresTermsAcceptance"] = bool(payload.get("gated"))
        if entry["requiresTermsAcceptance"]:
            entry["notes"] = (
                (entry.get("notes") or "").strip()
                + " Requires accepting the model licence on huggingface.co first."
            ).strip()

        after = (entry.get("sizeBytes"), entry.get("sha256"), entry.get("files"))
        if before != after:
            changed.append(entry_id)
    return changed


def check(catalog: dict[str, Any]) -> list[str]:
    """Offline consistency check, so a catalog edit that cannot be verified still fails CI.

    The publisher only exposes an LFS digest for large files, so a small helper file such as
    `vocab.txt` has no SHA-256 to record. Those files are still size-checked, and the entry's
    `sha256` must match the first file, which is the model itself.
    """
    problems: list[str] = []
    for entry in catalog.get("entries", []):
        files = entry.get("files") or []
        if not files:
            continue

        recorded = sum(int(f.get("sizeBytes", 0)) for f in files)
        declared = int(entry.get("sizeBytes", 0))
        if recorded != declared:
            problems.append(
                f"{entry['id']}: sizeBytes is {declared} but files[] adds up to {recorded}"
            )

        for f in files:
            if f.get("sizeBytes", 0) <= 0:
                problems.append(f"{entry['id']}: {f.get('name')} has no size")
            if not f.get("url", "").startswith("https://"):
                problems.append(f"{entry['id']}: {f.get('name')} has no HTTPS URL")

        primary = files[0]
        if not primary.get("sha256"):
            problems.append(f"{entry['id']}: the primary file {primary.get('name')} has no SHA-256")
        if primary.get("sha256") != entry.get("sha256"):
            problems.append(f"{entry['id']}: sha256 does not match the primary file")
        if primary.get("url") != (entry.get("source") or {}).get("url"):
            problems.append(f"{entry['id']}: source.url does not match the primary file")
    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Refresh model sizes and hashes from the publisher")
    parser.add_argument("--catalog", default=os.path.join("docs", "catalog", "catalog-v2.json"))
    parser.add_argument("--check", action="store_true", help="only verify the recorded sizes")
    parser.add_argument("--now", default=None)
    args = parser.parse_args(argv)

    with open(args.catalog, encoding="utf-8") as handle:
        catalog = json.load(handle)

    if args.check:
        problems = check(catalog)
        if problems:
            print(f"CATALOG FILE LIST FAILED ({len(problems)} problems):", file=sys.stderr)
            for problem in problems:
                print(f"  - {problem}", file=sys.stderr)
            return 1
        print("CATALOG FILE LIST PASSED")
        return 0

    try:
        changed = refresh(catalog)
    except (urllib.error.URLError, KeyError, TimeoutError) as error:
        print(f"error: could not read the publisher metadata: {error}", file=sys.stderr)
        return 1

    import datetime as _dt

    catalog["updatedAt"] = args.now or _dt.datetime.now(_dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    with open(args.catalog, "w", encoding="utf-8") as handle:
        json.dump(catalog, handle, indent=2, ensure_ascii=False)
        handle.write("\n")

    if changed:
        print(f"updated {len(changed)} entries: {', '.join(changed)}")
    else:
        print("every model entry already matches the publisher")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
