#!/usr/bin/env python3
"""verify_catalog.py — Verify model catalog entries against their source hosts.

For each entry in the catalog JSON:
1. If a direct download URL is available, fetch the file headers to get the size.
2. Compute SHA-256 if the file is downloaded (or use Content-Length for size only).
3. Verify the license URL is reachable.
4. Write a verification report and a verified catalog.

Usage:
    python3 tools/verify_catalog.py [--catalog docs/catalog/catalog-v1.json] [--output-dir .]

This script is designed to run on GitHub Actions where network access is available.
In the agent sandbox, most hosts are unreachable, so entries remain `verified: false`.
"""

import argparse
import hashlib
import json
import os
import sys
import urllib.request
import urllib.error
from pathlib import Path


def verify_entry(entry: dict) -> dict:
    """Verify a single catalog entry. Returns updated entry with verification results."""
    result = dict(entry)
    result["verification"] = {}

    source_url = entry.get("source", {}).get("url", "")
    if not source_url:
        result["verification"]["status"] = "no_url"
        result["verification"]["note"] = "No download URL provided"
        result["verified"] = False
        return result

    # Try to fetch the URL headers
    try:
        req = urllib.request.Request(source_url, method="HEAD")
        req.add_header("User-Agent", "SubLearn-Catalog-Verifier/1.0")
        with urllib.request.urlopen(req, timeout=30) as resp:
            content_length = resp.headers.get("Content-Length")
            content_type = resp.headers.get("Content-Type", "")
            result["verification"]["status"] = "reachable"
            result["verification"]["http_status"] = resp.status
            if content_length:
                result["verification"]["actual_size_bytes"] = int(content_length)
                # Compare with catalog size
                catalog_size = entry.get("sizeBytes", 0)
                if catalog_size > 0:
                    diff = abs(int(content_length) - catalog_size)
                    result["verification"]["size_match"] = diff < catalog_size * 0.1  # 10% tolerance
                else:
                    result["verification"]["size_match"] = None
            result["verification"]["content_type"] = content_type
    except urllib.error.HTTPError as e:
        result["verification"]["status"] = "http_error"
        result["verification"]["http_status"] = e.code
    except urllib.error.URLError as e:
        result["verification"]["status"] = "unreachable"
        result["verification"]["error"] = str(e.reason)
    except Exception as e:
        result["verification"]["status"] = "error"
        result["verification"]["error"] = str(e)

    # Verify license URL
    license_url = entry.get("license", {}).get("url", "")
    if license_url:
        try:
            req = urllib.request.Request(license_url, method="HEAD")
            req.add_header("User-Agent", "SubLearn-Catalog-Verifier/1.0")
            with urllib.request.urlopen(req, timeout=15) as resp:
                result["verification"]["license_reachable"] = True
                result["verification"]["license_status"] = resp.status
        except Exception:
            result["verification"]["license_reachable"] = False

    # Determine if verified
    is_verified = (
        result["verification"].get("status") == "reachable"
        and result["verification"].get("size_match", False)
        and result["verification"].get("license_reachable", False)
    )

    # If we have a SHA-256 in the catalog and the URL is reachable, we'd need to download
    # the full file to verify. For now, just check reachability and size.
    if is_verified and entry.get("sha256"):
        result["verification"]["note"] = "SHA-256 verification requires full download (not done in HEAD check)"
        # For full SHA-256 verification, a separate download step is needed

    result["verified"] = is_verified
    return result


def main():
    parser = argparse.ArgumentParser(description="Verify model catalog entries")
    parser.add_argument("--catalog", default="docs/catalog/catalog-v1.json", help="Path to catalog JSON")
    parser.add_argument("--output-dir", default=".", help="Output directory for reports")
    args = parser.parse_args()

    if not os.path.exists(args.catalog):
        print(f"Error: catalog file not found: {args.catalog}", file=sys.stderr)
        sys.exit(1)

    with open(args.catalog, "r", encoding="utf-8") as f:
        catalog = json.load(f)

    os.makedirs(args.output_dir, exist_ok=True)

    report = {
        "catalog_file": args.catalog,
        "total_entries": len(catalog.get("entries", [])),
        "verified": 0,
        "unverified": 0,
        "results": [],
    }

    verified_entries = []
    for entry in catalog.get("entries", []):
        print(f"Verifying: {entry['id']} ({entry['kind']})...")
        result = verify_entry(entry)
        verified_entries.append(result)
        report["results"].append({
            "id": entry["id"],
            "kind": entry["kind"],
            "verified": result["verified"],
            "status": result["verification"].get("status", "unknown"),
            "note": result["verification"].get("note", ""),
        })
        if result["verified"]:
            report["verified"] += 1
        else:
            report["unverified"] += 1

    # Write verified catalog
    verified_catalog = dict(catalog)
    verified_catalog["entries"] = verified_entries
    verified_path = os.path.join(args.output_dir, "catalog-v1.verified.json")
    with open(verified_path, "w", encoding="utf-8") as f:
        json.dump(verified_catalog, f, ensure_ascii=False, indent=2)

    # Write report
    report_path = os.path.join(args.output_dir, "catalog_verification_report.json")
    with open(report_path, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)

    print(f"\nVerification complete:")
    print(f"  Total: {report['total_entries']}")
    print(f"  Verified: {report['verified']}")
    print(f"  Unverified: {report['unverified']}")
    print(f"  Report: {report_path}")
    print(f"  Verified catalog: {verified_path}")


if __name__ == "__main__":
    main()
