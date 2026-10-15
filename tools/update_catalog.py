#!/usr/bin/env python3
"""update_catalog.py — Refresh catalog entries from freshly built release assets.

The pack release workflow builds the packs, so it also knows their exact size and
SHA-256. This script writes those values back into the catalog, keyed by entry id,
so `docs/catalog/catalog-v2.json` can never drift from what was actually uploaded.

Usage:
    python3 tools/update_catalog.py \\
        --catalog docs/catalog/catalog-v2.json \\
        --asset sublearn-lexicon-en-fa-v2=dist/sublearn-lexicon-en-fa-v2.sqlite.gz \\
        --asset sublearn-dict-fa-en-v2=dist/sublearn-dict-fa-en-v1.sqlite.gz \\
        --tag packs-v2.1.0 \\
        --repo animalrender/SubLearn

Exit codes: 0 when every asset was matched and written, 1 when an asset id is not in
the catalog or when a file is missing, so a release fails loudly instead of publishing a
catalog that points at the wrong bytes.
"""

from __future__ import annotations

import argparse
import datetime as _dt
import hashlib
import json
import os
import sys
from typing import Iterable


def sha256_file(path: str) -> str:
    """Streaming SHA-256 of a file, lowercase hex."""
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def build_release_url(repo: str, tag: str, filename: str) -> str:
    return f"https://github.com/{repo}/releases/download/{tag}/{filename}"


def asset_record(repo: str, tag: str, asset_id: str, path: str) -> dict:
    """Describe one built file for the catalog."""
    return {
        "id": asset_id,
        "filename": os.path.basename(path),
        "sizeBytes": os.path.getsize(path),
        "sha256": sha256_file(path),
        "url": build_release_url(repo, tag, os.path.basename(path)),
    }


def apply_assets(catalog: dict, assets: Iterable[dict], tag: str, now: str) -> list[str]:
    """Write each asset into the matching catalog entry. Returns the matched ids."""
    entries = catalog.setdefault("entries", [])
    by_id = {entry.get("id"): entry for entry in entries}
    matched: list[str] = []

    for asset in assets:
        entry = by_id.get(asset["id"])
        if entry is None:
            raise KeyError(f"catalog has no entry with id {asset['id']!r}")
        entry["sizeBytes"] = asset["sizeBytes"]
        entry["sha256"] = asset["sha256"]
        entry.setdefault("source", {})["host"] = "github.com"
        entry["source"]["url"] = asset["url"]
        entry["releaseTag"] = tag
        entry["verified"] = True
        matched.append(asset["id"])

    catalog["updatedAt"] = now
    return matched


def parse_asset_args(values: Iterable[str]) -> dict[str, str]:
    """Parse repeated `id=path` arguments."""
    out: dict[str, str] = {}
    for value in values:
        if "=" not in value:
            raise ValueError(f"asset must be given as id=path, got {value!r}")
        asset_id, path = value.split("=", 1)
        if not asset_id or not path:
            raise ValueError(f"asset must be given as id=path, got {value!r}")
        out[asset_id] = path
    return out


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Update catalog entries from release assets")
    parser.add_argument("--catalog", required=True)
    parser.add_argument("--asset", action="append", default=[], metavar="ID=PATH")
    parser.add_argument("--tag", required=True)
    parser.add_argument("--repo", required=True, help="owner/name of the GitHub repository")
    parser.add_argument("--now", default=None, help="ISO-8601 timestamp (defaults to now, UTC)")
    parser.add_argument("--dry-run", action="store_true", help="report changes without writing")
    args = parser.parse_args(argv)

    try:
        wanted = parse_asset_args(args.asset)
    except ValueError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1

    if not wanted:
        print("error: at least one --asset is required", file=sys.stderr)
        return 1

    assets = []
    for asset_id, path in wanted.items():
        if not os.path.exists(path):
            print(f"error: asset file not found: {path}", file=sys.stderr)
            return 1
        assets.append(asset_record(args.repo, args.tag, asset_id, path))

    with open(args.catalog, encoding="utf-8") as handle:
        catalog = json.load(handle)

    now = args.now or _dt.datetime.now(_dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    try:
        matched = apply_assets(catalog, assets, args.tag, now)
    except KeyError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1

    for asset in assets:
        print(f"{asset['id']}: {asset['sizeBytes']} bytes sha256={asset['sha256']}")

    if args.dry_run:
        print(f"dry run: would update {len(matched)} entries in {args.catalog}")
        return 0

    with open(args.catalog, "w", encoding="utf-8") as handle:
        json.dump(catalog, handle, indent=2, ensure_ascii=False)
        handle.write("\n")
    print(f"updated {len(matched)} entries in {args.catalog}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
