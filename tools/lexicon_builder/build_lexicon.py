#!/usr/bin/env python3
"""build_lexicon.py — Convert fastdic_plain.sqlite to sublearn-lexicon-en-fa-v2.sqlite.

Reads the source database (from the dictionaryproject repo) and produces:
  1. sublearn-lexicon-en-fa-v2.sqlite  — the SubLearn lexicon pack (v2 format)
  2. build_report.json                  — statistics, SHA-256, sample rejections

Usage:
    python3 build_lexicon.py --input /path/to/fastdic_plain.sqlite --output ./

The v2 schema is documented in docs/EXTENSION_POINTS.md and consists of:
  meta(key, value), lemma_form(form, lemma, pos), entry(id, key, headword, kind, pos, cefr, register),
  sense(id, entry_id, position, pos, gloss_fa, gloss_en, domain, register, is_primary),
  example(sense_id, en, fa), pattern(entry_id, pattern, anchor_lemma, separable, min_tokens, max_tokens),
  relation(entry_id, kind, target), plus FTS on entry.key.

POS codes are taken from fastdic_desktop/fd_data.py in the dictionaryproject repo.
"""

import argparse
import hashlib
import json
import os
import re
import sqlite3
import sys
import time
from collections import defaultdict
from pathlib import Path

# ---------------------------------------------------------------------------
# POS codes from fastdic_desktop/fd_data.py (dictionaryproject repo)
# ---------------------------------------------------------------------------
POS_EN = {
    1: "noun", 2: "plural", 3: "noun phrase", 4: "verb - use participle",
    5: "verb - transitive", 6: "verb - intransitive", 7: "adjective",
    8: "adverb", 9: "conjunction", 10: "preposition", 11: "interjection",
    12: "pronoun", 13: "definite article", 14: "indefinite article",
    15: "nominative", 16: "prefix", 17: "suffix", 18: "idiom",
    19: "collocation", 20: "phrasal verb", 21: "auxiliary verb",
    22: "determiner", 23: "predeterminer", 24: "abbreviation",
    25: "slang", 26: "idiomatic phrase", 27: "acronym", 28: "symbol",
    29: "verb phrase", 30: "phrase", 31: "singular", 32: "exclamation",
    33: "contraction", 34: "proverb",
}

# Mapping from source POS codes to SubLearn normalised POS tags
POS_NORMALIZE = {
    "noun": "NOUN", "plural": "NOUN", "singular": "NOUN", "noun phrase": "NOUN",
    "verb - use participle": "VERB", "verb - transitive": "VERB",
    "verb - intransitive": "VERB", "auxiliary verb": "VERB", "verb phrase": "VERB",
    "adjective": "ADJ", "adverb": "ADV",
    "conjunction": "CONJ", "preposition": "PREP",
    "interjection": "INTJ", "exclamation": "INTJ",
    "pronoun": "PRON", "definite article": "DET", "indefinite article": "DET",
    "determiner": "DET", "predeterminer": "DET",
    "prefix": "PREFIX", "suffix": "SUFFIX",
    "idiom": "IDIOM", "idiomatic phrase": "IDIOM",
    "collocation": "COLLOCATION",
    "phrasal verb": "PHRASAL",
    "abbreviation": "ABBREV", "acronym": "ABBREV",
    "symbol": "SYMBOL", "phrase": "PHRASE",
    "nominative": "PRON", "contraction": "CONTRACTION",
    "proverb": "PROVERB", "slang": "SLANG",
}

# Verb form column names in english_words
VERB_FORM_COLS = [
    "simple_past", "past_participle", "present_participle",
    "third_person_singular", "plural", "comparative", "superlative",
]

# Idiom validation: 2-8 tokens, has a Persian meaning
MIN_IDIOM_TOKENS = 2
MAX_IDIOM_TOKENS = 8

# Pattern slot tokens
POSS_TOKEN = "POSS"
REFL_TOKEN = "REFL"
NP_SLOT = "{NP}"

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def normalize_key(text: str) -> str:
    """Lowercase, strip trailing punctuation, collapse whitespace."""
    text = text.lower().strip()
    text = re.sub(r"[.,\/#!$%\^&\*;:{}=\-_`~()?'\"\[\]]+$", "", text)
    text = re.sub(r"^[.,\/#!$%\^&\*;:{}=\-_`~()?'\"\[\]]+", "", text)
    text = re.sub(r"\s+", " ", text)
    return text.strip()


def token_count(text: str) -> int:
    return len(text.split())


def is_valid_idiom(text: str, has_persian: bool) -> bool:
    tc = token_count(text)
    return MIN_IDIOM_TOKENS <= tc <= MAX_IDIOM_TOKENS and has_persian


def clean_html(text: str | None) -> str:
    if not text:
        return ""
    text = re.sub(r"<[^>]+>", "", text)
    return text.strip()


def split_gloss(text: str) -> list[str]:
    """Split a Persian gloss on common separators."""
    if not text:
        return []
    parts = re.split(r"ØŒ|Ø›|;", text)
    return [p.strip() for p in parts if p.strip()]


# ---------------------------------------------------------------------------
# Pattern conversion (§ 5.4 of the brief)
# ---------------------------------------------------------------------------

def convert_to_pattern(phrase: str) -> str:
    """Convert an idiom/phrasal phrase to a SubLearn pattern string.

    Rules from the owner's brief section 5.4:
    - (or thin), (all) → optional
    - one's, his/her → POSS
    - oneself → REFL
    - someone/something → {NP}
    - be → BE (normalized by lemmatiser at match time)
    """
    result = phrase.lower().strip()
    # Parenthesized optional parts
    result = re.sub(r"\(or\s+\w+\)", "", result)
    result = re.sub(r"\(all\)", "", result)
    result = re.sub(r"\(\)", "", result)
    # Possessive
    result = re.sub(r"\bone's\b", POSS_TOKEN, result)
    result = re.sub(r"\b(his|her|their|its|my|your|our)\b", POSS_TOKEN, result)
    # Reflexive
    result = re.sub(r"\boneself\b", REFL_TOKEN, result)
    result = re.sub(r"\b(himself|herself|themselves|itself|myself|yourself|ourselves)\b", REFL_TOKEN, result)
    # NP slot
    result = re.sub(r"\b(someone|somebody|something|anyone|anybody|anything)\b", NP_SLOT, result)
    # Collapse whitespace
    result = re.sub(r"\s+", " ", result).strip()
    return result


def find_anchor_lemma(pattern: str) -> str:
    """Pick the rarest content word as the anchor for indexing.

    Heuristic: skip function words (be, have, do, a, the, to, of, in, on, at, etc.)
    and pick the longest non-slot word.
    """
    FUNCTION_WORDS = {
        "be", "been", "being", "am", "is", "are", "was", "were",
        "have", "has", "had", "do", "does", "did",
        "a", "an", "the", "to", "of", "in", "on", "at", "by", "for",
        "with", "about", "up", "down", "out", "off", "over", "into",
        "from", "as", "or", "and", "but", "if", "so", "no", "not",
        "it", "its", "this", "that", "than",
    }
    words = [w for w in pattern.split() if w not in FUNCTION_WORDS and not w.startswith("{") and w not in (POSS_TOKEN, REFL_TOKEN)]
    if not words:
        # Fall back to the first non-slot word
        words = [w for w in pattern.split() if not w.startswith("{") and w not in (POSS_TOKEN, REFL_TOKEN)]
    if not words:
        return pattern.split()[0] if pattern.split() else pattern
    # Pick the longest word as a proxy for rarity
    return max(words, key=len)


# ---------------------------------------------------------------------------
# Schema creation
# ---------------------------------------------------------------------------

V2_SCHEMA = """
CREATE TABLE IF NOT EXISTS meta (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS lemma_form (
    form TEXT NOT NULL,
    lemma TEXT NOT NULL,
    pos TEXT,
    PRIMARY KEY (form, pos)
);
CREATE INDEX IF NOT EXISTS idx_lemma_form ON lemma_form(form);

CREATE TABLE IF NOT EXISTS entry (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    key TEXT NOT NULL,
    headword TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('WORD', 'PHRASAL', 'IDIOM', 'COLLOCATION')),
    pos TEXT,
    cefr TEXT,
    register TEXT
);
CREATE INDEX IF NOT EXISTS idx_entry_key ON entry(key);

CREATE TABLE IF NOT EXISTS sense (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    entry_id INTEGER NOT NULL REFERENCES entry(id),
    position INTEGER NOT NULL DEFAULT 0,
    pos TEXT,
    gloss_fa TEXT,
    gloss_en TEXT,
    domain TEXT,
    register TEXT,
    is_primary INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_sense_entry ON sense(entry_id);

CREATE TABLE IF NOT EXISTS example (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    sense_id INTEGER NOT NULL REFERENCES sense(id),
    en TEXT,
    fa TEXT
);

CREATE TABLE IF NOT EXISTS pattern (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    entry_id INTEGER NOT NULL REFERENCES entry(id),
    pattern TEXT NOT NULL,
    anchor_lemma TEXT NOT NULL,
    separable INTEGER NOT NULL DEFAULT 0,
    min_tokens INTEGER NOT NULL DEFAULT 2,
    max_tokens INTEGER NOT NULL DEFAULT 8
);
CREATE INDEX IF NOT EXISTS idx_pattern_anchor ON pattern(anchor_lemma);
CREATE INDEX IF NOT EXISTS idx_pattern_entry ON pattern(entry_id);

CREATE TABLE IF NOT EXISTS relation (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    entry_id INTEGER NOT NULL REFERENCES entry(id),
    kind TEXT NOT NULL CHECK (kind IN ('SYNONYM', 'ANTONYM', 'WORD_FAMILY')),
    target TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_relation_entry ON relation(entry_id);

CREATE VIRTUAL TABLE IF NOT EXISTS entry_fts USING fts4(key, headword);
"""


# ---------------------------------------------------------------------------
# Main converter
# ---------------------------------------------------------------------------

def build_lexicon(input_path: str, output_dir: str) -> dict:
    """Build the v2 lexicon from the source fastdic database."""
    report = {
        "builder_version": "1.0.0",
        "built_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "source_file": os.path.basename(input_path),
        "source_sha256": sha256_file(input_path),
        "stats": {
            "words_read": 0,
            "details_read": 0,
            "idioms_read": 0,
            "entries_created": 0,
            "senses_created": 0,
            "examples_created": 0,
            "patterns_created": 0,
            "relations_created": 0,
            "lemma_forms_created": 0,
            "idioms_kept": 0,
            "idioms_rejected_no_persian": 0,
            "idioms_rejected_bad_length": 0,
            "idioms_rejected_as_collocation": 0,
        },
        "rejected_samples": [],
    }

    src = sqlite3.connect(input_path)
    src.row_factory = sqlite3.Row

    output_path = os.path.join(output_dir, "sublearn-lexicon-en-fa-v2.sqlite")
    if os.path.exists(output_path):
        os.remove(output_path)

    dst = sqlite3.connect(output_path)
    dst.executescript(V2_SCHEMA)

    # ------------------------------------------------------------------
    # 1. Build a word_id → word string map
    # ------------------------------------------------------------------
    word_id_to_word: dict[int, str] = {}
    word_id_to_canonical: dict[int, str | None] = {}
    for row in src.execute("SELECT english_word_id, english_word, canonical FROM english_words"):
        word_id_to_word[row["english_word_id"]] = row["english_word"]
        word_id_to_canonical[row["english_word_id"]] = row["canonical"]

    # ------------------------------------------------------------------
    # 2. Build verb form table (lemma_form)
    #    english_words.simple_past etc. point to other word_ids
    # ------------------------------------------------------------------
    lemma_forms: list[tuple[str, str, str | None]] = []
    for row in src.execute(
        "SELECT english_word_id, english_word, simple_past, past_participle, "
        "present_participle, third_person_singular, plural, comparative, superlative "
        "FROM english_words"
    ):
        base_word = row["english_word"]
        base_id = row["english_word_id"]
        for col in VERB_FORM_COLS:
            form_id = row[col]
            if form_id and form_id in word_id_to_word:
                form_word = word_id_to_word[form_id]
                if form_word.lower() != base_word.lower():
                    lemma_forms.append((form_word.lower(), base_word.lower(), "VERB"))
    # Deduplicate, keeping first lemma when multiple entries map to same (form, pos)
    seen_lemma: dict[tuple[str, str | None], str] = {}
    for form, lemma, pos in lemma_forms:
        key = (form, pos)
        if key not in seen_lemma:
            seen_lemma[key] = lemma
    lemma_forms_dedup = [(f, l, p) for (f, p), l in seen_lemma.items()]
    dst.executemany("INSERT INTO lemma_form (form, lemma, pos) VALUES (?, ?, ?)", lemma_forms_dedup)
    report["stats"]["lemma_forms_created"] = len(lemma_forms_dedup)

    # ------------------------------------------------------------------
    # 3. Process english_words + english_details → entry + sense
    # ------------------------------------------------------------------
    # Collect details grouped by english_word_id
    details_by_word: dict[int, list[dict]] = defaultdict(list)
    for row in src.execute("SELECT * FROM english_details ORDER BY english_word_id, position"):
        details_by_word[row["english_word_id"]].append(dict(row))
    report["stats"]["details_read"] = sum(len(v) for v in details_by_word.values())

    # Collect POS per detail
    pos_by_detail: dict[int, list[str]] = defaultdict(list)
    for row in src.execute("SELECT english_detail_id, pos FROM english_pos"):
        pos_code = row["pos"]
        pos_name = POS_EN.get(pos_code, "")
        normalized = POS_NORMALIZE.get(pos_name, "")
        if normalized:
            pos_by_detail[row["english_detail_id"]].append(normalized)

    # Collect sentences per detail
    sentences_by_detail: dict[int, list[dict]] = defaultdict(list)
    for row in src.execute("SELECT * FROM english_sentences"):
        sentences_by_detail[row["english_detail_id"]].append({
            "en": row["english_sentence"],
            "fa": row["persian_sentence"],
        })

    # Collect synonyms/antonyms per word
    synant_by_word: dict[int, list[dict]] = defaultdict(list)
    for row in src.execute("SELECT * FROM english_synonyms_antonyms"):
        synant_by_word[row["english_word_id"]].append(dict(row))

    # Collect word family per word (via english_word_families_words)
    family_by_word: dict[str, list[str]] = defaultdict(list)
    for row in src.execute(
        "SELECT ewfw.word, ewf2.word as family_head "
        "FROM english_word_families_words ewfw "
        "JOIN english_word_families ewf ON ewfw.english_word_family_id = ewf.english_word_family_id "
        "JOIN english_word_families_words ewf2 ON ewf.english_word_family_id = ewf2.english_word_family_id"
    ):
        pass
    # Actually let's build family groups properly
    family_groups: dict[int, list[str]] = defaultdict(list)
    for row in src.execute(
        "SELECT english_word_family_id, word FROM english_word_families_words"
    ):
        family_groups[row["english_word_family_id"]].append(row["word"])

    # Map each word to its family id
    word_to_family: dict[str, int] = {}
    for fid, words in family_groups.items():
        for w in words:
            word_to_family[w.lower()] = fid

    # ------------------------------------------------------------------
    # 4. Create entries for each english_word with its senses
    # ------------------------------------------------------------------
    entry_id_map: dict[int, int] = {}  # english_word_id → entry id

    for word_id, word_text in word_id_to_word.items():
        details = details_by_word.get(word_id, [])
        if not details:
            continue
        report["stats"]["words_read"] += 1

        key = normalize_key(word_text)
        if not key:
            continue

        # Determine primary POS
        primary_pos = None
        primary_cefr = None
        for d in details:
            detail_pos = pos_by_detail.get(d["english_detail_id"], [])
            if detail_pos and not primary_pos:
                primary_pos = detail_pos[0]
            if d.get("cefr") and not primary_cefr:
                primary_cefr = d["cefr"]

        # Create entry
        dst.execute(
            "INSERT INTO entry (key, headword, kind, pos, cefr) VALUES (?, ?, 'WORD', ?, ?)",
            (key, word_text, primary_pos, primary_cefr),
        )
        entry_id = dst.execute("SELECT last_insert_rowid()").fetchone()[0]
        entry_id_map[word_id] = entry_id
        report["stats"]["entries_created"] += 1

        # Create senses
        for pos_idx, detail in enumerate(details):
            gloss_fa = clean_html(detail.get("persian_meaning"))
            if not gloss_fa:
                continue  # Skip senses without Persian meaning

            detail_pos_list = pos_by_detail.get(detail["english_detail_id"], [])
            sense_pos = detail_pos_list[0] if detail_pos_list else primary_pos

            dst.execute(
                "INSERT INTO sense (entry_id, position, pos, gloss_fa, gloss_en, domain, register, is_primary) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                (
                    entry_id,
                    pos_idx,
                    sense_pos,
                    gloss_fa,
                    None,  # gloss_en: english_details doesn't have English gloss (it IS English→Persian)
                    None,  # domain: not in source
                    None,  # register
                    1 if pos_idx == 0 else 0,
                ),
            )
            sense_id = dst.execute("SELECT last_insert_rowid()").fetchone()[0]
            report["stats"]["senses_created"] += 1

            # Examples
            for ex in sentences_by_detail.get(detail["english_detail_id"], []):
                dst.execute(
                    "INSERT INTO example (sense_id, en, fa) VALUES (?, ?, ?)",
                    (sense_id, ex["en"], ex["fa"]),
                )
                report["stats"]["examples_created"] += 1

        # Relations: synonyms/antonyms
        for sa in synant_by_word.get(word_id, []):
            if sa.get("synonyms"):
                for syn in sa["synonyms"].split(","):
                    syn = syn.strip()
                    if syn:
                        dst.execute(
                            "INSERT INTO relation (entry_id, kind, target) VALUES (?, 'SYNONYM', ?)",
                            (entry_id, syn.lower()),
                        )
                        report["stats"]["relations_created"] += 1
            if sa.get("antonyms"):
                for ant in sa["antonyms"].split(","):
                    ant = ant.strip()
                    if ant:
                        dst.execute(
                            "INSERT INTO relation (entry_id, kind, target) VALUES (?, 'ANTONYM', ?)",
                            (entry_id, ant.lower()),
                        )
                        report["stats"]["relations_created"] += 1

        # Word family relations
        family_id = word_to_family.get(word_text.lower())
        if family_id:
            for fam_word in family_groups[family_id]:
                if fam_word.lower() != word_text.lower():
                    dst.execute(
                        "INSERT INTO relation (entry_id, kind, target) VALUES (?, 'WORD_FAMILY', ?)",
                        (entry_id, fam_word.lower()),
                    )
                    report["stats"]["relations_created"] += 1

    # ------------------------------------------------------------------
    # 5. Process idioms → entry + sense + pattern
    # ------------------------------------------------------------------
    # Group idiom rows by idiom word
    idiom_groups: dict[int, list[dict]] = defaultdict(list)
    for row in src.execute("SELECT * FROM english_idioms ORDER BY position"):
        idiom_groups[row["english_idiom_word_id"]].append(dict(row))

    for idiom_word_id, idiom_rows in idiom_groups.items():
        idiom_text = word_id_to_word.get(idiom_word_id, "")
        if not idiom_text:
            continue
        report["stats"]["idioms_read"] += 1

        # Check if this is really an idiom or actually a collocation/example
        idiom_key = normalize_key(idiom_text)
        tc = token_count(idiom_key)

        # Determine kind
        kind = "IDIOM"
        # If it starts with an existing phrasal verb key and is longer, it's a collocation/example
        first_word = idiom_key.split()[0] if idiom_key.split() else ""

        # Check if there's a Persian meaning in any of the linked details
        has_persian = False
        sense_data = []
        for ir in idiom_rows:
            detail_id = ir["english_word_id"]
            details = details_by_word.get(detail_id, [])
            for d in details:
                gloss = clean_html(d.get("persian_meaning"))
                if gloss:
                    has_persian = True
                    sense_data.append({
                        "gloss_fa": gloss,
                        "cefr": d.get("cefr"),
                        "detail_id": d["english_detail_id"],
                    })

        # Validation
        if not has_persian:
            report["stats"]["idioms_rejected_no_persian"] += 1
            if len(report["rejected_samples"]) < 50:
                report["rejected_samples"].append({
                    "text": idiom_text,
                    "reason": "no_persian_meaning",
                })
            continue

        if tc < MIN_IDIOM_TOKENS or tc > MAX_IDIOM_TOKENS:
            report["stats"]["idioms_rejected_bad_length"] += 1
            if len(report["rejected_samples"]) < 50:
                report["rejected_samples"].append({
                    "text": idiom_text,
                    "reason": f"bad_token_count={tc}",
                })
            continue

        # Classify: if it looks like a phrasal verb (verb + particle), mark as PHRASAL
        first_word_lower = idiom_key.split()[0].lower()
        # Heuristic: if first word is a common verb and second is a particle
        PARTICLES = {"up", "down", "out", "off", "on", "over", "away", "back", "in", "about", "through", "along", "apart"}
        if tc == 2 and idiom_key.split()[1] in PARTICLES:
            kind = "PHRASAL"
        elif tc >= 2 and idiom_key.split()[-1] in PARTICLES:
            kind = "PHRASAL"

        # Create entry
        primary_cefr = None
        for sd in sense_data:
            if sd.get("cefr"):
                primary_cefr = sd["cefr"]
                break

        dst.execute(
            "INSERT INTO entry (key, headword, kind, pos, cefr) VALUES (?, ?, ?, ?, ?)",
            (idiom_key, idiom_text, kind, "PHRASAL" if kind == "PHRASAL" else None, primary_cefr),
        )
        entry_id = dst.execute("SELECT last_insert_rowid()").fetchone()[0]
        report["stats"]["entries_created"] += 1
        report["stats"]["idioms_kept"] += 1

        # Create senses
        for pos_idx, sd in enumerate(sense_data):
            dst.execute(
                "INSERT INTO sense (entry_id, position, pos, gloss_fa, gloss_en, is_primary) VALUES (?, ?, ?, ?, ?, ?)",
                (entry_id, pos_idx, None, sd["gloss_fa"], None, 1 if pos_idx == 0 else 0),
            )
            sense_id = dst.execute("SELECT last_insert_rowid()").fetchone()[0]
            report["stats"]["senses_created"] += 1

            # Examples from sentences
            for ex in sentences_by_detail.get(sd["detail_id"], []):
                dst.execute(
                    "INSERT INTO example (sense_id, en, fa) VALUES (?, ?, ?)",
                    (sense_id, ex["en"], ex["fa"]),
                )
                report["stats"]["examples_created"] += 1

        # Create pattern
        pattern_str = convert_to_pattern(idiom_text)
        anchor = find_anchor_lemma(pattern_str)
        separable = 1 if kind == "PHRASAL" else 0
        dst.execute(
            "INSERT INTO pattern (entry_id, pattern, anchor_lemma, separable, min_tokens, max_tokens) "
            "VALUES (?, ?, ?, ?, ?, ?)",
            (entry_id, pattern_str, anchor, separable, tc, tc + 2),
        )
        report["stats"]["patterns_created"] += 1

    # ------------------------------------------------------------------
    # 6. Populate FTS
    # ------------------------------------------------------------------
    dst.execute("INSERT INTO entry_fts (key, headword) SELECT key, headword FROM entry")

    # ------------------------------------------------------------------
    # 7. Write meta
    # ------------------------------------------------------------------
    entry_count = dst.execute("SELECT COUNT(*) FROM entry").fetchone()[0]
    meta_rows = [
        ("format_version", "2"),
        ("source_lang", "en"),
        ("gloss_lang", "fa"),
        ("entry_count", str(entry_count)),
        ("built_at", report["built_at"]),
        ("builder_version", report["builder_version"]),
    ]
    dst.executemany("INSERT INTO meta (key, value) VALUES (?, ?)", meta_rows)

    dst.commit()
    dst.close()
    src.close()

    # Compute output SHA-256
    report["output_file"] = os.path.basename(output_path)
    report["output_sha256"] = sha256_file(output_path)
    report["output_size_bytes"] = os.path.getsize(output_path)

    return report


def main():
    parser = argparse.ArgumentParser(description="Build SubLearn lexicon v2 from fastdic database")
    parser.add_argument("--input", required=True, help="Path to fastdic_plain.sqlite (or sample.sqlite for testing)")
    parser.add_argument("--output", default=".", help="Output directory for the lexicon pack and report")
    args = parser.parse_args()

    if not os.path.exists(args.input):
        print(f"Error: input file not found: {args.input}", file=sys.stderr)
        sys.exit(1)

    os.makedirs(args.output, exist_ok=True)

    print(f"Building lexicon from {args.input}...")
    report = build_lexicon(args.input, args.output)

    report_path = os.path.join(args.output, "build_report.json")
    with open(report_path, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)

    print(f"\nBuild complete:")
    print(f"  Output: {report['output_file']}")
    print(f"  Size: {report['output_size_bytes']:,} bytes")
    print(f"  SHA-256: {report['output_sha256']}")
    print(f"  Entries: {report['stats']['entries_created']:,}")
    print(f"  Senses: {report['stats']['senses_created']:,}")
    print(f"  Examples: {report['stats']['examples_created']:,}")
    print(f"  Patterns: {report['stats']['patterns_created']:,}")
    print(f"  Lemma forms: {report['stats']['lemma_forms_created']:,}")
    print(f"  Idioms kept: {report['stats']['idioms_kept']:,}")
    print(f"  Idioms rejected (no Persian): {report['stats']['idioms_rejected_no_persian']:,}")
    print(f"  Idioms rejected (bad length): {report['stats']['idioms_rejected_bad_length']:,}")
    print(f"  Report: {report_path}")


if __name__ == "__main__":
    main()
