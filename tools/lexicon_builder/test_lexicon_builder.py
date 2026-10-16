#!/usr/bin/env python3
"""Tests for the lexicon builder and pack validator.

Run with: python3 -m unittest tools/lexicon_builder/test_lexicon_builder.py
"""

import json
import os
import sqlite3
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_lexicon import (
    apply_quality_filters,
    build_lexicon,
    build_report_lines,
    convert_to_pattern,
    convert_to_patterns,
    find_anchor_lemma,
    is_separable_phrasal,
    normalize_key,
    normalize_persian_key,
)
from validate_pack import validate_pack

CANDIDATE_SAMPLE_PATHS = [
    os.path.join(
        os.path.dirname(os.path.abspath(__file__)),
        "..", "..", "..", "..", "dictionaryproject", "docs", "sample.sqlite",
    ),
    os.path.expanduser("~/dictionaryproject/docs/sample.sqlite"),
    "/tmp/refs/dictionaryproject/docs/sample.sqlite",
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "test_fixtures", "sample.sqlite"),
]

SAMPLE_DB = next((p for p in CANDIDATE_SAMPLE_PATHS if os.path.exists(p)), CANDIDATE_SAMPLE_PATHS[-1])


def _create_synthetic_source_db(path: str) -> None:
    """Create a realistic synthetic source SQLite database mirroring main_db.sqlite schema."""
    conn = sqlite3.connect(path)
    conn.executescript("""
        CREATE TABLE category_names (
            id INTEGER PRIMARY KEY,
            persian_category_name TEXT,
            english_category_name TEXT,
            is_hidden INTEGER,
            is_my_words INTEGER
        );
        INSERT INTO category_names VALUES (1, 'گیاه‌شناسی', 'Botany', 0, 0);
        INSERT INTO category_names VALUES (16, 'کامپیوتر', 'Computer', 0, 0);

        CREATE TABLE english_words (
            english_word_id INTEGER PRIMARY KEY,
            english_word TEXT,
            simple_past INTEGER,
            past_participle INTEGER,
            infinitive INTEGER,
            plural INTEGER,
            present_participle INTEGER,
            third_person_singular INTEGER,
            comparative INTEGER,
            superlative INTEGER,
            canonical INTEGER,
            word_description_html TEXT,
            english_word_id_parent INTEGER
        );

        CREATE TABLE english_details (
            english_detail_id INTEGER PRIMARY KEY,
            persian_meaning TEXT,
            most_common INTEGER,
            countable_uncountable INTEGER,
            english_word_id INTEGER,
            position INTEGER,
            formal_informal INTEGER,
            british_american INTEGER,
            cefr TEXT,
            has_image INTEGER,
            description_html TEXT
        );

        CREATE TABLE english_pos (
            english_pos_id INTEGER PRIMARY KEY,
            pos INTEGER,
            english_detail_id INTEGER
        );

        CREATE TABLE english_categories (
            english_category_id INTEGER PRIMARY KEY,
            category INTEGER,
            english_detail_id INTEGER
        );

        CREATE TABLE english_sentences (
            english_sentence_id INTEGER PRIMARY KEY,
            english_sentence TEXT,
            persian_sentence TEXT,
            position INTEGER,
            english_detail_id INTEGER
        );

        CREATE TABLE english_synonyms_antonyms (
            english_synonyms_antonyms_id INTEGER PRIMARY KEY,
            english_word_id INTEGER,
            definitions TEXT,
            synonyms TEXT,
            antonyms TEXT,
            pos TEXT,
            position INTEGER
        );

        CREATE TABLE english_word_families_words (
            english_word_family_word_id INTEGER PRIMARY KEY,
            pos INTEGER,
            word TEXT,
            english_word_family_id INTEGER
        );

        CREATE TABLE english_american_audios (
            english_american_audio_id INTEGER PRIMARY KEY,
            filename TEXT,
            phonetic TEXT,
            english_word_id INTEGER
        );

        CREATE TABLE english_british_audios (
            english_british_audio_id INTEGER PRIMARY KEY,
            filename TEXT,
            phonetic TEXT,
            english_word_id INTEGER
        );

        CREATE TABLE english_idioms (
            english_idiom_id INTEGER PRIMARY KEY,
            position INTEGER,
            english_word_id INTEGER,
            english_idiom_word_id INTEGER
        );

        CREATE TABLE persian_words (
            persian_word_id INTEGER PRIMARY KEY,
            persian_word TEXT
        );
        CREATE TABLE persian_details (
            persian_detail_id INTEGER PRIMARY KEY,
            english_meaning TEXT,
            persian_phonetic TEXT,
            persian_meaning TEXT,
            position INTEGER,
            persian_word_id INTEGER
        );
        CREATE TABLE persian_pos (
            persian_pos_id INTEGER PRIMARY KEY,
            pos INTEGER,
            persian_detail_id INTEGER
        );
        CREATE TABLE persian_categories (
            persian_category_id INTEGER PRIMARY KEY,
            category INTEGER,
            persian_detail_id INTEGER
        );
        CREATE TABLE persian_sentences (
            persian_sentence_id INTEGER PRIMARY KEY,
            persian_sentence TEXT,
            english_sentence TEXT,
            persian_detail_id INTEGER
        );
        CREATE TABLE persian_synonyms_antonyms (
            persian_synonyms_antonyms_id INTEGER PRIMARY KEY,
            synonym_antonym TEXT,
            type INTEGER,
            position INTEGER,
            persian_word_id INTEGER
        );
    """)

    # Insert test words including irregular paradigms (break/broke/broken, go/went/gone, good/better/best)
    words = [
        (1, "break", 2, 3, None, None, None, None, None, None, None, "<p>To separate into pieces</p>", None),
        (2, "broke", None, 3, 1, None, None, None, None, None, None, None, None),
        (3, "broken", 2, None, 1, None, None, None, None, None, None, None, None),
        (4, "go", 5, 6, None, None, None, None, None, None, None, None, None),
        (5, "went", None, 6, 4, None, None, None, None, None, None, None, None),
        (6, "gone", 5, None, 4, None, None, None, None, None, None, None, None),
        (7, "good", None, None, None, None, None, None, 8, 9, None, None, None),
        (8, "better", None, None, None, None, None, None, None, 9, None, None, None),
        (9, "best", None, None, None, None, None, None, 8, None, None, None, None),
        (10, "do over", None, None, None, None, None, None, None, None, None, None, None),
        (11, "look after", None, None, None, None, None, None, None, None, None, None, None),
        (12, "get (or have) the goods on", None, None, None, None, None, None, None, None, None, None, None),
        (13, "in (or into) flower", None, None, None, None, None, None, None, None, None, None, None),
        (14, "piece of cake", None, None, None, None, None, None, None, None, None, None, None),
    ]
    conn.executemany(
        "INSERT INTO english_words VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        words,
    )

    details = [
        (1, "شکستن", 1, None, 1, 0, 1, 0, "A1", 0, None),
        (2, "شکسته (گذشته)", 1, None, 2, 0, None, None, "A2", 0, None),
        (3, "شکسته‌شده", 1, None, 3, 0, None, None, "A2", 0, None),
        (4, "رفتن", 1, None, 4, 0, None, None, "A1", 0, None),
        (5, "خوب", 1, None, 7, 0, None, None, "A1", 0, None),
        (6, "دوباره انجام دادن", 1, None, 10, 0, None, None, "B1", 0, None),
        (7, "مراقبت کردن از", 1, None, 11, 0, None, None, "B1", 0, None),
        (8, "مدرک علیه کسی داشتن", 1, None, 12, 0, 0, 1, "C1", 0, None),
        (9, "گل دادن، شکوفه دادن", 1, None, 13, 0, None, None, "B2", 0, None),
        (10, "مثل آب خوردن", 1, None, 14, 0, 0, None, "B1", 0, None),
    ]
    conn.executemany(
        "INSERT INTO english_details VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        details,
    )

    pos_rows = [
        (1, 5, 1),
        (2, 6, 4),
        (3, 7, 5),
        (4, 20, 6),
        (5, 20, 7),
        (6, 18, 8),
        (7, 18, 9),
        (8, 18, 10),
    ]
    conn.executemany("INSERT INTO english_pos VALUES (?, ?, ?)", pos_rows)
    conn.execute("INSERT INTO english_categories VALUES (1, 1, 9)")
    conn.execute("INSERT INTO english_sentences VALUES (1, 'It was a piece of cake.', 'مثل آب خوردن بود.', 0, 10)")
    conn.execute("INSERT INTO english_synonyms_antonyms VALUES (1, 1, 'separate into pieces', 'shatter, smash', 'mend, repair', 'verb', 0)")
    conn.execute("INSERT INTO english_word_families_words VALUES (1, 1, 'break', 100)")
    conn.execute("INSERT INTO english_word_families_words VALUES (2, 7, 'breakable', 100)")
    conn.execute("INSERT INTO english_american_audios VALUES (1, 'break_us.mp3', '/breɪk/', 1)")
    conn.execute("INSERT INTO english_idioms VALUES (1, 0, 1, 10)")
    conn.execute("INSERT INTO persian_words VALUES (1, 'کتاب')")
    conn.execute("INSERT INTO persian_details VALUES (1, 'book', 'ketāb', 'نوشته چاپی', 0, 1)")
    conn.commit()
    conn.close()


class TestNormalizeKey(unittest.TestCase):
    def test_lowercase(self):
        self.assertEqual(normalize_key("Hello"), "hello")

    def test_strip_punctuation(self):
        self.assertEqual(normalize_key("hello."), "hello")
        self.assertEqual(normalize_key("hello,"), "hello")

    def test_collapse_whitespace(self):
        self.assertEqual(normalize_key("hello  world"), "hello world")

    def test_strip_both_ends(self):
        self.assertEqual(normalize_key("  hello  "), "hello")

    def test_persian_normalize(self):
        self.assertEqual(normalize_persian_key("كِتابي"), "کتابی")


class TestConvertToPattern(unittest.TestCase):
    def test_basic(self):
        self.assertEqual(convert_to_pattern("get off"), "get off")

    def test_possessive(self):
        result = convert_to_pattern("make one's mind")
        self.assertIn("POSS", result)

    def test_reflexive(self):
        result = convert_to_pattern("enjoy oneself")
        self.assertIn("REFL", result)

    def test_optional_parts(self):
        result = convert_to_pattern("a piece of cake (or thin)")
        self.assertNotIn("(or thin)", result)

    def test_someone_becomes_np(self):
        result = convert_to_pattern("help someone out")
        self.assertIn("{NP}", result)

    def test_or_expansion(self):
        pats, err = convert_to_patterns("get (or have) the goods on")
        self.assertIsNone(err)
        self.assertEqual(pats, ["get the goods on", "have the goods on"])

        pats2, err2 = convert_to_patterns("in (or into) flower")
        self.assertIsNone(err2)
        self.assertEqual(pats2, ["in flower", "into flower"])

    def test_optional_parenthetical_expansion(self):
        pats, err = convert_to_patterns("check up (on)")
        self.assertIsNone(err)
        self.assertEqual(pats, ["check up", "check up on"])

    def test_np_optional_expansion(self):
        pats, err = convert_to_patterns("take (someone or something) seriously")
        self.assertIsNone(err)
        self.assertIn("take seriously", pats)
        self.assertIn("take {NP} seriously", pats)

    def test_rejected_patterns(self):
        pats, err = convert_to_patterns("since you ask...")
        self.assertEqual(pats, [])
        self.assertEqual(err, "ellipsis")


class TestFindAnchorLemma(unittest.TestCase):
    def test_picks_content_word(self):
        anchor = find_anchor_lemma("piece of cake")
        self.assertIn(anchor, ["piece", "cake"])

    def test_skips_function_words(self):
        anchor = find_anchor_lemma("get up")
        self.assertTrue(len(anchor) > 0)

    def test_phrasal_verb_anchor_is_verb(self):
        self.assertEqual(find_anchor_lemma("do over", kind="PHRASAL"), "do")
        self.assertEqual(find_anchor_lemma("have on", kind="PHRASAL"), "have")
        self.assertEqual(find_anchor_lemma("look after", kind="PHRASAL"), "look")

    def test_separable_phrasal(self):
        self.assertTrue(is_separable_phrasal("do over", {20}))
        self.assertTrue(is_separable_phrasal("turn down", {20, 5}))
        self.assertFalse(is_separable_phrasal("look after", {20}))
        self.assertFalse(is_separable_phrasal("come up with", {20}))


class TestBuildLexicon(unittest.TestCase):
    def test_synthetic_build_and_validate(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            src_db = os.path.join(tmpdir, "src.sqlite")
            _create_synthetic_source_db(src_db)
            json_dir = os.path.join(tmpdir, "json")

            report = build_lexicon(src_db, tmpdir, direction="en-fa", gzip_output=True, export_test_json_dir=json_dir)
            output_path = report["output_file"]
            self.assertTrue(os.path.exists(output_path))
            self.assertTrue(os.path.exists(report["gzip_file"]))
            self.assertTrue(os.path.exists(os.path.join(json_dir, "lemma_forms.json")))

            ok, errors = validate_pack(output_path)
            self.assertTrue(ok, f"Validation failed: {errors}")

            # Also test fa-en build and validation
            report_fa = build_lexicon(src_db, tmpdir, direction="fa-en", gzip_output=True)
            ok_fa, errors_fa = validate_pack(report_fa["output_file"])
            self.assertTrue(ok_fa, f"FA-EN validation failed: {errors_fa}")

    @unittest.skipUnless(os.path.exists(SAMPLE_DB), f"Sample DB not found at {SAMPLE_DB}")
    def test_builds_valid_output(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            report = build_lexicon(SAMPLE_DB, tmpdir)

            self.assertTrue(report["stats"]["entries_created"] > 0)
            self.assertTrue(report["stats"]["senses_created"] > 0)
            self.assertIn("output_sha256", report)
            output_path = os.path.join(tmpdir, "sublearn-lexicon-en-fa-v2.sqlite")
            self.assertTrue(os.path.exists(output_path))

            ok, errors = validate_pack(output_path)
            self.assertTrue(ok, f"Sample DB pack validation failed: {errors}")

            conn = sqlite3.connect(output_path)
            tables = {r[0] for r in conn.execute(
                "SELECT name FROM sqlite_master WHERE type='table'"
            ).fetchall()}
            required_tables = {
                "meta", "lemma_form", "entry", "sense", "example",
                "pattern", "word_pos", "entry_extra", "pron", "colloc", "relation",
            }
            self.assertTrue(required_tables.issubset(tables), f"Missing tables: {required_tables - tables}")

            meta = dict(conn.execute("SELECT key, value FROM meta").fetchall())
            self.assertEqual(meta["format_version"], "2")
            self.assertEqual(meta["source_lang"], "en")
            self.assertEqual(meta["gloss_lang"], "fa")
            conn.close()

    @unittest.skipUnless(os.path.exists(SAMPLE_DB), f"Sample DB not found at {SAMPLE_DB}")
    def test_report_json(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            report = build_lexicon(SAMPLE_DB, tmpdir)
            self.assertIn("stats", report)
            self.assertIn("builder_version", report)
            self.assertIn("output_sha256", report)
            self.assertIn("rejected_samples", report)


class QualityFiltersTest(unittest.TestCase):
    """The demotion rules of §2.3, against the entries that reached the real pack.

    The full build needs the 98 MB source database, so these cases are pinned here instead: each
    one failed the pack validator (five errors on the en-fa pack, 2026-10-16) and the rule below is
    the fix. `token_tags` is every POS tag the source records for a token, not just the most
    frequent one — reading only that demoted the real phrasal verb "look forward to".
    """

    def call(
        self,
        key: str,
        kind: str,
        prior: float,
        tags: list[set[str]],
        lemmas: list[str] | None = None,
        in_idioms: bool = False,
        codes: set[int] | None = None,
    ):
        return apply_quality_filters(
            key=key,
            kind=kind,
            entry_prior=prior,
            token_tags=tags,
            token_lemmas=lemmas or [token.lower() for token in key.split()],
            in_idioms=in_idioms,
            all_pos_codes=codes or set(),
        )

    def test_a_real_phrase_keeps_its_prior_when_a_token_is_also_a_noun(self) -> None:
        # "look" is tagged NOUN as well as VERB; the key must not be read as a noun combo.
        kind, prior, reason = self.call(
            "look forward to",
            "PHRASAL",
            0.80,
            [{"NOUN", "VERB"}, {"ADV"}, {"PREP"}],
        )
        self.assertEqual("PHRASAL", kind)
        self.assertGreaterEqual(prior, 0.60)
        self.assertIsNone(reason)

    def test_prepositional_phrase_is_demoted_even_when_the_source_tags_a_verb(self) -> None:
        # The source lists a verb among "truth"'s senses, which is why rule 3 alone missed it.
        kind, prior, reason = self.call(
            "in truth",
            "COLLOCATION",
            0.70,
            [{"PREP", "NOUN"}, {"NOUN", "VERB"}],
            codes={19},
        )
        self.assertEqual("PHRASE", kind)
        self.assertLess(prior, 0.65)
        self.assertEqual("prepositional_phrase", reason)

    def test_motion_verb_plus_to_is_demoted_even_with_the_phrasal_code(self) -> None:
        for key in ("go to", "come to", "get to"):
            with self.subTest(key=key):
                kind, prior, reason = self.call(
                    key,
                    "PHRASAL",
                    0.90,
                    [{"VERB"}, {"PREP"}],
                    codes={20},
                )
                self.assertEqual("PHRASE", kind, "the source's phrasal code is wrong for this key")
                self.assertLess(prior, 0.65)
                self.assertEqual("phrasal_to_motion_db20", reason)

    def test_the_reason_records_whether_the_source_labelled_it(self) -> None:
        _, _, reason = self.call("go to", "PHRASAL", 0.90, [{"VERB"}, {"PREP"}])
        self.assertEqual("phrasal_to_motion_dbnone", reason)

    def test_a_phrasal_verb_with_a_real_particle_is_left_alone(self) -> None:
        kind, prior, reason = self.call("give up", "PHRASAL", 0.90, [{"VERB"}, {"ADP", "PART"}])
        self.assertEqual("PHRASAL", kind)
        self.assertEqual(0.90, prior)
        self.assertIsNone(reason)

    def test_an_idiom_is_not_demoted_by_the_shape_rules(self) -> None:
        # "a piece of cake" has the noun-combo shape and "in the nick of time" opens with a
        # preposition; both are idioms the learner wants to see underlined.
        for key, kind, tags in (
            ("a piece of cake", "IDIOM", [{"DET"}, {"NOUN"}, {"PREP"}, {"NOUN"}]),
            ("in the nick of time", "IDIOM", [{"PREP"}, {"DET"}, {"NOUN"}, {"PREP"}, {"NOUN"}]),
        ):
            with self.subTest(key=key):
                stayed_kind, prior, reason = self.call(key, kind, 0.75, tags)
                self.assertEqual(kind, stayed_kind)
                self.assertEqual(0.75, prior)
                self.assertIsNone(reason)

    def test_closed_class_keys_stay_demoted(self) -> None:
        for key in ("at that", "the way", "a lot"):
            with self.subTest(key=key):
                kind, prior, reason = self.call(key, "COLLOCATION", 0.70, [{"DET", "PRON"}, {"PRON"}])
                self.assertEqual("PHRASE", kind)
                self.assertEqual(0.25, prior)
                self.assertEqual("all_closed_class", reason)

    def test_single_words_are_never_demoted(self) -> None:
        kind, prior, reason = self.call("truth", "WORD", 0.50, [{"NOUN"}])
        self.assertEqual(("WORD", 0.50, None), (kind, prior, reason))

    def test_a_collocation_only_linked_from_the_idiom_table_loses_its_prior(self) -> None:
        kind, prior, reason = self.call(
            "make a decision",
            "COLLOCATION",
            0.70,
            [{"VERB"}, {"DET"}, {"NOUN"}],
            in_idioms=True,
        )
        self.assertEqual("COLLOCATION", kind)
        self.assertLessEqual(prior, 0.50)
        self.assertIsNone(reason)


class BuildReportTest(unittest.TestCase):
    """The summary the builder prints.

    `demotion_reasons` is a mapping among the counters, and formatting it as a number is what broke
    the real build: `f"  {k}: {v:,}"` raised `unsupported format string passed to dict.__format__`
    only on the full database, because the sample run never demoted anything.
    """

    def base_report(self, stats):
        return {
            "output_file": "/tmp/pack.sqlite",
            "output_size_bytes": 35_863_515,
            "output_sha256": "abc",
            "stats": stats,
        }

    def test_prints_a_mapping_without_crashing(self):
        lines = build_report_lines(
            self.base_report({"entries_created": 102_000, "demotion_reasons": {"all_closed_class": 9}}),
            "en-fa",
        )
        joined = "\n".join(lines)
        self.assertIn("entries_created: 102,000", joined)
        self.assertIn("demotion_reasons:", joined)
        self.assertIn("all_closed_class: 9", joined)

    def test_prints_a_mixed_stats_block(self):
        lines = build_report_lines(
            self.base_report(
                {"demotions": 12, "builder_note": "gzipped", "empty": {}, "ratio": 0.75},
            ),
            "fa-en",
        )
        joined = "\n".join(lines)
        self.assertIn("demotions: 12", joined)
        self.assertIn("builder_note: gzipped", joined)
        self.assertIn("ratio: 0.75", joined)

    def test_always_names_the_output_and_the_direction(self):
        lines = build_report_lines(self.base_report({}), "en-fa")
        self.assertTrue(lines[0].startswith("Build complete (en-fa)"))

    def test_prints_the_quality_audit_lines(self):
        """The audit is how a rule that read the wrong source data explains itself."""
        report = self.base_report({"entries_created": 5})
        report["quality_audit"] = {
            "in truth": {
                "kind": "PHRASE",
                "prior": 0.25,
                "reason": "prepositional_phrase",
                "idioms": False,
                "codes": [19],
                "tokens": "in: {PREP}/lemma=in | truth: {NOUN}/lemma=truth",
            },
        }
        audit = [line for line in build_report_lines(report, "en-fa") if "quality_audit" in line]
        self.assertEqual(1, len(audit))
        self.assertIn("quality_audit in truth: kind=PHRASE prior=0.25", audit[0])
        self.assertIn("reason=prepositional_phrase", audit[0])
        self.assertIn("tokens=[in: {PREP}/lemma=in", audit[0])

    def test_a_report_without_an_audit_still_prints(self):
        self.assertTrue(build_report_lines(self.base_report({}), "fa-en"))
        self.assertIn("35,863,515 bytes", lines[1])


if __name__ == "__main__":
    unittest.main()
