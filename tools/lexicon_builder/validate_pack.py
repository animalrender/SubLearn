#!/usr/bin/env python3
"""validate_pack.py — Validate a built SubLearn Lexicon Pack v2 SQLite database.

Usage:
    python3 tools/lexicon_builder/validate_pack.py <path-to-pack.sqlite> [--full]

Exits 0 if all assertions pass, non-zero otherwise.
"""

import argparse
import os
import sqlite3
import sys


REQUIRED_TABLES = {
    "meta", "lemma_form", "entry", "sense", "example",
    "pattern", "word_pos", "entry_extra", "pron", "colloc", "entry_fts",
}


def validate_pack(db_path: str, require_full_counts: bool = False) -> tuple[bool, list[str]]:
    errors: list[str] = []

    if not os.path.exists(db_path):
        return False, [f"File does not exist: {db_path}"]

    size_bytes = os.path.getsize(db_path)
    max_bytes = 130 * 1024 * 1024
    if size_bytes > max_bytes:
        errors.append(f"Pack size {size_bytes:,} bytes exceeds 130 MB limit ({max_bytes:,} bytes)")

    conn = sqlite3.connect(db_path)
    cur = conn.cursor()

    # 1. Required tables
    tables = {
        r[0] for r in cur.execute("SELECT name FROM sqlite_master WHERE type IN ('table', 'view')").fetchall()
    }
    missing = REQUIRED_TABLES - tables
    if missing:
        errors.append(f"Missing required tables: {sorted(missing)}")
        conn.close()
        return False, errors

    # 2. Meta table checks
    meta = dict(cur.execute("SELECT key, value FROM meta").fetchall())
    if meta.get("format_version") != "2":
        errors.append(f"Expected format_version='2', got {meta.get('format_version')!r}")

    direction = meta.get("direction", "en-fa")

    # 3. Duplicate entry key check
    dup_keys = cur.execute(
        "SELECT key, COUNT(*) FROM entry GROUP BY key HAVING COUNT(*) > 1"
    ).fetchall()
    if dup_keys:
        errors.append(f"Duplicate keys in entry table ({len(dup_keys)} found): {dup_keys[:5]}")

    entry_count = cur.execute("SELECT COUNT(*) FROM entry").fetchone()[0]
    sense_count = cur.execute("SELECT COUNT(*) FROM sense").fetchone()[0]
    example_count = cur.execute("SELECT COUNT(*) FROM example").fetchone()[0]
    fts_count = cur.execute("SELECT COUNT(*) FROM entry_fts").fetchone()[0]

    if entry_count == 0 or sense_count == 0:
        errors.append(f"Empty pack: entry_count={entry_count}, sense_count={sense_count}")

    if fts_count != entry_count:
        errors.append(f"entry_fts count ({fts_count}) != entry count ({entry_count})")

    if direction == "fa-en":
        if meta.get("source_lang") != "fa" or meta.get("gloss_lang") != "en":
            errors.append(f"Invalid fa-en meta languages: {meta}")
        conn.close()
        return len(errors) == 0, errors

    # 4. EN->FA specific validations
    # 4a. Irregular lemma_form assertions (Requirement A1)
    def get_lemmas(form: str) -> list[tuple[str, int]]:
        return cur.execute(
            "SELECT lemma, rank FROM lemma_form WHERE form = ? ORDER BY rank, lemma",
            (form,),
        ).fetchall()

    exact_assertions = {
        "gave": [("give", 0)],
        "broken": [("break", 0)],
        "went": [("go", 0)],
        "left": [("leave", 0), ("left", 1)],
        "saw": [("see", 0), ("saw", 1)],
        "came": [("come", 0)],
        "took": [("take", 0)],
        "taken": [("take", 0)],
        "made": [("make", 0)],
        "ran": [("run", 0)],
        "was": [("be", 0)],
        "were": [("be", 0)],
        "been": [("be", 0)],
        "had": [("have", 0)],
        "has": [("have", 0)],
        "did": [("do", 0)],
        "done": [("do", 0)],
        "does": [("do", 0)],
        "children": [("child", 0)],
        "mice": [("mouse", 0)],
        "feet": [("foot", 0)],
        "teeth": [("tooth", 0)],
        "men": [("man", 0)],
        "women": [("woman", 0)],
        "worse": [("bad", 0)],
        "worst": [("bad", 0)],
    }
    for form, expected in exact_assertions.items():
        actual = get_lemmas(form)
        if actual != expected:
            errors.append(f"lemma_form[{form!r}]: expected {expected}, got {actual}")

    # Rank-0 membership assertions for forms with multiple rank-0 lemmas or optional rank-1
    rank0_assertions = {
        "broke": "break",
        "gone": "go",
        "better": "good",
        "best": "good",
    }
    for form, expected_lemma in rank0_assertions.items():
        actual = get_lemmas(form)
        rank0_lemmas = [lem for lem, rnk in actual if rnk == 0]
        if expected_lemma not in rank0_lemmas:
            errors.append(f"lemma_form[{form!r}]: expected ({expected_lemma!r}, 0) in {actual}")
        # Ensure intermediate forms (e.g. broken->broke, gone->went, best->better) are NOT present
        if form == "broken" and any(lem == "broke" for lem, _ in actual):
            errors.append(f"lemma_form['broken'] still contains intermediate 'broke': {actual}")
        if form == "gone" and any(lem == "went" for lem, _ in actual):
            errors.append(f"lemma_form['gone'] still contains intermediate 'went': {actual}")
        if form == "best" and any(lem == "better" for lem, _ in actual):
            errors.append(f"lemma_form['best'] still contains intermediate 'better': {actual}")
        if form == "better" and any(lem == "best" for lem, _ in actual):
            errors.append(f"lemma_form['better'] still contains intermediate 'best': {actual}")

    # 4b. Pattern table assertions (Requirement A6)
    bad_patterns = cur.execute(
        "SELECT COUNT(*) FROM pattern WHERE pattern LIKE '%(%' OR pattern LIKE '%or %'"
    ).fetchone()[0]
    if bad_patterns != 0:
        samples = cur.execute(
            "SELECT pattern FROM pattern WHERE pattern LIKE '%(%' OR pattern LIKE '%or %' LIMIT 5"
        ).fetchall()
        errors.append(f"Found {bad_patterns} unexpanded patterns matching '%(%' or '%or %': {samples}")

    # Check 'do over' anchor if 'do over' exists in entry
    do_over = cur.execute(
        "SELECT p.anchor_lemma FROM pattern p JOIN entry e ON e.id = p.entry_id WHERE e.key = 'do over'"
    ).fetchall()
    if do_over and any(r[0] != "do" for r in do_over):
        errors.append(f"Expected anchor_lemma='do' for 'do over', got {do_over}")

    # Check 'get (or have) the goods on' expansion if present in entry
    goods_on = cur.execute(
        "SELECT p.pattern FROM pattern p JOIN entry e ON e.id = p.entry_id WHERE e.headword = 'get (or have) the goods on'"
    ).fetchall()
    if goods_on:
        pats = {r[0] for r in goods_on}
        if not {"get the goods on", "have the goods on"}.issubset(pats):
            errors.append(f"Expected 'get the goods on' and 'have the goods on', got {pats}")

    # Check 'in (or into) flower' expansion if present in entry
    in_flower = cur.execute(
        "SELECT p.pattern FROM pattern p JOIN entry e ON e.id = p.entry_id WHERE e.headword = 'in (or into) flower'"
    ).fetchall()
    if in_flower:
        pats = {r[0] for r in in_flower}
        if not {"in flower", "into flower"}.issubset(pats):
            errors.append(f"Expected 'in flower' and 'into flower', got {pats}")

    # Check 'look after' separability if present
    look_after = cur.execute(
        "SELECT p.separable FROM pattern p JOIN entry e ON e.id = p.entry_id WHERE e.key = 'look after'"
    ).fetchall()
    if look_after and any(r[0] != 0 for r in look_after):
        errors.append(f"Expected separable=0 for 'look after', got {look_after}")

    # Check separable ratio across PHRASAL patterns
    phrasal_sep = cur.execute(
        """SELECT COUNT(*), COALESCE(SUM(p.separable), 0)
           FROM pattern p JOIN entry e ON e.id = p.entry_id
           WHERE e.kind = 'PHRASAL'"""
    ).fetchone()
    if phrasal_sep and phrasal_sep[0] > 0:
        ratio = phrasal_sep[1] / phrasal_sep[0]
        if ratio >= 0.90:
            errors.append(f"PHRASAL separable ratio {ratio:.3f} >= 0.90 ({phrasal_sep[1]}/{phrasal_sep[0]})")

    # 4c. Auxiliary tables populated check
    word_pos_count = cur.execute("SELECT COUNT(*) FROM word_pos").fetchone()[0]
    if word_pos_count == 0:
        errors.append("word_pos table is empty")

    # 4d. Full database count bounds (when running against full main_db.sqlite)
    if require_full_counts or entry_count >= 50000:
        lemma_count = cur.execute("SELECT COUNT(*) FROM lemma_form").fetchone()[0]
        if not (75000 <= entry_count <= 100000):
            errors.append(f"Full pack entry_count {entry_count:,} outside expected 75k..100k range")
        if not (120000 <= sense_count <= 160000):
            errors.append(f"Full pack sense_count {sense_count:,} outside expected 120k..160k range")
        if not (120000 <= example_count <= 160000):
            errors.append(f"Full pack example_count {example_count:,} outside expected 120k..160k range")
        if lemma_count < 40000:
            errors.append(f"Full pack lemma_form count {lemma_count:,} < 40,000")

    conn.close()
    return len(errors) == 0, errors


def main():
    parser = argparse.ArgumentParser(description="Validate a SubLearn v2 lexicon pack")
    parser.add_argument("db_path", help="Path to the .sqlite pack")
    parser.add_argument("--full", action="store_true", help="Enforce full-DB row count bounds")
    args = parser.parse_args()

    ok, errors = validate_pack(args.db_path, require_full_counts=args.full)
    if ok:
        print(f"VALIDATION PASSED: {args.db_path}")
        sys.exit(0)
    else:
        print(f"VALIDATION FAILED ({len(errors)} errors) for {args.db_path}:", file=sys.stderr)
        for err in errors:
            print(f"  - {err}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
