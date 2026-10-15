#!/usr/bin/env python3
"""check_schema.py — Keep the exported Room schema and the hand-written migrations in step.

`core/data` exports its schema (`exportSchema = true`) and ships one hand-written migration
(`MIGRATION_1_2`). Those two can drift silently: a migration that adds a column the entity no
longer declares, or an entity column that no migration creates, both only fail on a user's
device. This script compares the exported `N.json` with the columns the migrations add, so the
mismatch shows up in CI instead of on an upgrade.

Checks per database:
1. The exported schema versions are contiguous (1..N) and end at the version the code declares.
2. Every `Column` constant used in a migration is checked for the build's own `ALTER TABLE`
   statements: the column and table it names must exist in the version-N export.
3. The phrase-span cache columns required by the analyser cache key exist in the export.

Usage:
    python3 tools/check_schema.py [--repo-root .]
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys

SCHEMA_DIR = os.path.join("core", "data", "schemas", "com.sublearn.core.data.SubLearnDatabase")
DATABASE_FILE = os.path.join("core", "data", "src", "main", "kotlin", "com", "sublearn", "core", "data", "SubLearnDatabase.kt")

# Columns the analyser cache key and the My Words list depend on. A migration that drops or
# renames one of these would break analysis or the words screen, so they are asserted here.
REQUIRED_COLUMNS = {
    "phrase_span": {
        "sourceKey",
        "packId",
        "packVersion",
        "analyzerVersion",
        "spanId",
        "blockId",
        "startChar",
        "endChar",
        "tokensCsv",
        "kind",
        "headword",
        "lemmaKey",
        "confidence",
        "separable",
    },
    "my_words": {"wordLower", "phraseType", "lemma", "lexiconEntryId", "surfaceParts", "sourceKind"},
    "installed_packs": {"packId", "kind", "version", "localPath", "sha256", "enabled"},
}

ALTER_ADD_COLUMN = re.compile(
    r"ALTER\s+TABLE\s+`?(?P<table>\w+)`?\s+ADD\s+COLUMN\s+`?(?P<column>\w+)`?",
    re.IGNORECASE,
)
CREATE_TABLE = re.compile(r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?`?(?P<table>\w+)`?", re.IGNORECASE)
VERSION_DECL = re.compile(r"version\s*=\s*(?P<version>\d+)")


def load_schemas(repo_root: str) -> dict[int, dict]:
    directory = os.path.join(repo_root, SCHEMA_DIR)
    if not os.path.isdir(directory):
        raise SystemExit(f"error: schema directory not found: {directory}")
    schemas: dict[int, dict] = {}
    for name in os.listdir(directory):
        if not name.endswith(".json"):
            continue
        with open(os.path.join(directory, name), encoding="utf-8") as handle:
            data = json.load(handle)
        version = int(data["database"]["version"])
        schemas[version] = data
    return schemas


def declared_version(repo_root: str) -> int:
    path = os.path.join(repo_root, DATABASE_FILE)
    with open(path, encoding="utf-8") as handle:
        text = handle.read()
    match = VERSION_DECL.search(text)
    if not match:
        raise SystemExit(f"error: no @Database version found in {path}")
    return int(match.group("version"))


def migration_statements(repo_root: str) -> str:
    path = os.path.join(repo_root, DATABASE_FILE)
    with open(path, encoding="utf-8") as handle:
        return handle.read()


def tables_in(schema: dict) -> dict[str, set[str]]:
    out: dict[str, set[str]] = {}
    for entity in schema["database"]["entities"]:
        out[entity["tableName"]] = {field["columnName"] for field in entity["fields"]}
    return out


def check(repo_root: str) -> list[str]:
    problems: list[str] = []
    schemas = load_schemas(repo_root)
    latest = declared_version(repo_root)

    if not schemas:
        return ["no exported schema files found"]

    expected = set(range(1, latest + 1))
    missing = expected - set(schemas)
    if missing:
        problems.append(f"missing exported schema versions: {sorted(missing)}")
    extra = set(schemas) - expected
    if extra:
        problems.append(f"exported schema versions beyond the declared version {latest}: {sorted(extra)}")

    newest = schemas.get(latest)
    if newest is None:
        return problems + [f"the declared version {latest} has no exported schema"]

    tables = tables_in(newest)

    source = migration_statements(repo_root)
    for match in ALTER_ADD_COLUMN.finditer(source):
        table, column = match.group("table"), match.group("column")
        if table not in tables:
            problems.append(f"migration adds column {column} to unknown table {table}")
        elif column not in tables[table]:
            problems.append(f"migration adds {table}.{column}, which the v{latest} schema does not declare")

    for match in CREATE_TABLE.finditer(source):
        table = match.group("table")
        if table not in tables and not table.endswith("_fts") and table != "room_master_table":
            problems.append(f"migration creates table {table}, which the v{latest} schema does not declare")

    for table, columns in REQUIRED_COLUMNS.items():
        if table not in tables:
            problems.append(f"v{latest} schema is missing the {table} table")
            continue
        for column in sorted(columns - tables[table]):
            problems.append(f"v{latest} schema is missing {table}.{column}")

    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Check exported Room schema against migrations")
    parser.add_argument("--repo-root", default=".")
    args = parser.parse_args(argv)

    problems = check(args.repo_root)
    if problems:
        print(f"SCHEMA CHECK FAILED ({len(problems)} problems):", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        return 1

    print("SCHEMA CHECK PASSED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
