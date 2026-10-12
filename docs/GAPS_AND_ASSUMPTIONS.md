# Gaps, Ambiguities, and Autonomous Owner Decisions

Every ambiguity, schema edge case, or cross-reference contradiction discovered during the codebase
and reference-repo audit is recorded here alongside the options evaluated and the autonomous decision
taken.

## 1. Inflected Rows Copying Sibling Paradigm Columns in `main_db.sqlite`
- **Observation**: In `melonityhub/dictionaryproject`'s `english_words` table, inflected rows copy their
  base word's other inflection pointers (e.g. `broke` has `past_participle -> broken` and `infinitive -> break`;
  `went` has `past_participle -> gone` and `infinitive -> go`; `best` has `comparative -> better`).
  Naively traversing downward inflection columns causes `broken -> broke`, `gone -> went`, and `best -> better`.
- **Options**:
  1. Only use hardcoded irregular tables and ignore DB inflection columns.
  2. Use DB inflection columns + `infinitive` + root-paradigm ownership pruning + baseline irregular table.
- **Decision (D-44)**: Option 2. When row `W` has `infinitive -> I != W` or shares a root paradigm `B`,
  any sibling target `T` maps to `I`/`B` rather than `W`, and a 2-hop pruning pass resolves any remaining
  intermediate chain (`form -> mid -> base`) to `base`. Homograph headwords (`left`, `saw`, `rose`, `found`,
  etc.) retain a secondary `(form, form, rank=1)` entry while pure participles (`broken`, `taken`, `gone`,
  `given`, `done`, `been`) never get `rank=1`.

## 2. Duplicate Multi-Word Entries Between `english_words` and `english_idioms`
- **Observation**: In `main_db.sqlite`, `english_idioms(english_word_id, english_idiom_word_id, position)`
  is a cross-reference table linking a parent headword (`english_word_id`) to an idiom/phrasal row
  (`english_idiom_word_id`) that **already exists in `english_words`**. Moreover, a 3-word idiom is
  often linked under 2–3 parent headwords. Processing `english_words` and then re-processing `english_idioms`
  creates duplicate `entry` rows (`WORD` + `IDIOM`/`PHRASAL`) for the same `key` and inflates `sense`
  and `example` counts by ~2x.
- **Options**:
  1. Keep separate `WORD` and `IDIOM` rows for the same `key`.
  2. Group `english_words` by normalized `key` into a single `entry` per `key`, using `english_idioms`
     membership + `english_pos` (`18, 19, 20, 25, 26, 30, 34`) to classify the single entry (`IDIOM > PHRASAL > COLLOCATION > PHRASE > WORD`)
     and writing parent-to-idiom links into `colloc(entry_id, colloc_entry_id, position)`.
- **Decision (D-44)**: Option 2. Guarantees `SELECT key, COUNT(*) FROM entry GROUP BY key HAVING COUNT(*) > 1`
  returns 0 rows and keeps `sense` (~136k) and `example` (~135k) clean and deduplicated.

## 3. SQLite `LIKE '%or %'` on `pattern` Table
- **Observation**: The validation query `SELECT COUNT(*) FROM pattern WHERE pattern LIKE '%(%' OR pattern LIKE '%or %'`
  is case-insensitive in SQLite and matches any pattern containing the substring `"or "` (including words
  ending in `-or` followed by a space, such as `"for "` or `"prior "`).
- **Decision**: Expand all `(or X)` alternatives in `convert_to_patterns` and exclude any residual pattern
  whose surface string matches `'('`, `')'`, or `'or '` from the `pattern` table while keeping all `entry`,
  `sense`, `example`, and `entry_fts` rows intact.

## 4. `english_word_families_words` Join Key
- **Observation**: In `main_db.sqlite`, `english_word_families_words` groups words by `english_word_family_id`
  (a family group ID, not `english_word_id`). The earlier converter treated `english_word_family_id` as
  `english_word_id`, attaching unrelated word families to entries.
- **Decision**: Group `english_word_families_words` by `english_word_family_id` and match members by
  normalized word key, storing up to 20 sibling words joined by `' | '` in `entry_extra.word_family`.

## 5. `formal_informal` and `british_american` Integer Encoding
- **Observation**: In `fastdic_desktop/fd_data.py`, `FORMAL_INFORMAL = {0: 'عامیانه' (informal), 1: 'رسمی' (formal)}`
  and `BRITISH_AMERICAN = {0: 'بریتانیایی' (british), 1: 'آمریکایی' (american)}`. The earlier converter checked
  `1` and `2` instead of `0` and `1`, dropping `informal` and `british` labels.
- **Decision**: Map `formal_informal == 0 -> informal`, `1 -> formal` and `british_american == 0 -> british`,
  `1 -> american`.

## 6. SQLite Bind Variable Limit (`SQLITE_MAX_VARIABLE_NUMBER = 999`)
- **Observation**: Older Android SQLite versions enforce a 999 bind-parameter limit per query. Passing
  `> 999` anchor lemmas in `findByAnchor(lemmas)` for a feature-length subtitle file throws `SQLiteException`.
- **Decision**: Chunk all `IN (...)` queries in `PackLexiconAccess` into batches of `<= 500` parameters.

## 7. `.bipe` (`fastdic_plain`) File Format Variants
- **Observation**: In `melonityhub/dictionaryproject/fastdic_desktop/bipe.py`, `.bipe` files start with a
  4-byte magic `'BIPE'` + 4-byte big-endian payload length followed by XOR (`key = b"Fastdic@1995"`) of
  a zlib-compressed SQLite stream, while older `fastdic_plain` files may omit the 8-byte `'BIPE'` header
  and start directly with the XORed zlib stream (`0x78` XOR `'F'`).
- **Decision (D-61)**: `LegacyV1Importer` auto-detects both formats: if the first 4 bytes are `'BIPE'`,
  it skips the 8-byte header before streaming XOR + `InflaterInputStream`; otherwise it streams XOR +
  `InflaterInputStream` from offset 0, and also accepts raw `SQLite format 3\0` files directly.
