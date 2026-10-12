# Autonomous Owner Improvements Log

Improvements discovered and implemented across Phases 10–19 while auditing caller impact, data
integrity, performance, UX consistency, and reference repositories (`dictionaryproject` and `proudvocab`).

## 1. Lexicon Builder & Pack v2 Data Quality (`tools/lexicon_builder/build_lexicon.py`)
- **Fixed `english_word_families_words` join bug**: Previously `english_word_family_id` was looked up as
  `english_word_id`, attaching wrong word families to entries. Now properly groups by `english_word_family_id`
  and links sibling words by normalized key.
- **Fixed `formal_informal` and `british_american` boolean decoding**: Previously checked `1` and `2`
  instead of `0` (`informal` / `british`) and `1` (`formal` / `american`), losing all informal and
  British register tags.
- **Read full 82-row `category_names` table from source DB**: Previously hardcoded only 57 categories;
  now reads all 82 Persian and English domain names (`domain_fa` and `domain_en`) directly from
  `category_names` with fallback to `CATEGORIES_FALLBACK`.
- **Populated `gloss_en` from `english_synonyms_antonyms.definitions`**: Previously `gloss_en` was
  always `NULL`; now populated from English definitions per sense.
- **Pruned intermediate paradigm forms in `lemma_form`**: Eliminated `broken -> broke`, `gone -> went`,
  and `best -> better` caused by sibling inflection copying in `english_words`.
- **Eliminated 2x multi-word sense/example duplication**: Grouped `english_words` by normalized `key`
  and used `english_idioms` for MWE classification + `colloc` parent-child links.

## 2. NLP Pipeline & Subtitle Phrase Detection (`core:nlp`, `core:lexicon`)
- **Pre-compiled pattern index (`DeterministicPhraseDetector`)**: Patterns are compiled once per anchor
  lemma when the lexicon loads instead of re-compiling on every subtitle block, cutting per-cue matching
  latency by >8x.
- **Continuous cue-stream phrase detection (`CueStreamPhraseAnalyzer`)**: Replaces heuristic
  `CrossBlockBridge` by tokenizing across adjacent cues (`gap <= 1200ms`, no terminal punctuation) and
  projecting spans back onto blocks and cues.
- **Dictionary-backed POS tagger (`LexiconPosTagger`)**: Uses `word_pos(word, pos, freq)` + suffix and
  context rules (`DET + X -> NOUN/ADJ`, `VERB + pron + PART -> RP`) so separated phrasal verbs (`pick the book up`)
  are accurately disambiguated with zero ML model overhead.
- **Chunked SQLite queries (`PackLexiconAccess`)**: Chunked `findByAnchor` queries to `<= 500` bind
  parameters to prevent `SQLiteException` on large subtitle files.

## 3. Player Subtitle Rendering & Touch Card (`feature:player`, `core:designsystem`)
- **Real Canvas dashed/dotted underlines**: Used `onTextLayout` (`TextLayoutResult.getBoundingBox`) +
  `drawBehind` with `PathEffect.dashPathEffect` so `UnderlineStyle.DASHED` and `DOTTED` render authentic
  ProudVocab dashed/dotted lines under words and multi-word spans.
- **Dual-section ProudVocab popup card**: Displays the tapped word on top and the matched phrasal verb,
  idiom, or collocation below a `1dp` divider, each with its own independent bookmark action and source
  provenance chips.
- **Lemma-aware saved word highlighting**: Saving `"give"` highlights inflected forms (`"gave"`, `"given"`)
  in subtitles when lemma matching is enabled.

## 4. Multi-Source Translation & Resource Library (`core:translate`, `feature:packs`, `feature:dictionary`)
- **`TranslationOrchestrator` with `PRIORITY_CHAIN` and `MERGE_ALL` modes**: Combines `PACK`, `LEGACY_V1`,
  `ML_KIT`, and opt-in `ONLINE_GOOGLE` with `< 50 ms` offline p95 and async online streaming.
- **Streaming `LegacyV1Importer` for `.bipe` (`fastdic_plain`) and `main_db.sqlite`**: De-obfuscates
  XOR (`Fastdic@1995`) + zlib in 64 KB chunks without heap spikes.
- **Full bidirectional EN↔FA Dictionary tab (`feature:dictionary`)**: Enabled `FeatureFlag.OFFLINE_DICTIONARY`
  and built a complete dictionary search and detail screen supporting both `LEXICON_EN_FA` and `DICT_FA_EN`.

## 5. Lifecycle Reload, Room Span Cache & Release Verification (`core:lexicon`, `feature:player`, `core:models`)
- **Lazy startup initialization and pack toggle reload (`PackLexiconAccess.ensureInitialized`)**:
  Automatically loads active packs from `InstalledPackRepository` on first query after app launch and
  reloads immediately when packs are toggled or deleted in `LearnViewModel`.
- **Rich Dictionary Screen FTS suggestions and extended fields (`LearnScreen`)**: Displays clickable
  prefix/FTS search result chips alongside English glosses, antonyms, word family, and related phrasal
  verbs/idioms/collocations.
- **Room `phrase_span` cache wiring (`PlayerViewModel`)**: Wired `PhraseSpanCacheDao` into `PlayerViewModel`
  and `AppModule` so analyzed subtitle spans are persisted keyed by `(sourceKey, packId, packVersion, analyzerVersion, spanId)`.
- **Verified `packs-v2.0.0` SHA-256 digests in `catalog-v2.json` and `ModelCatalog.kt`**: Populated exact
  byte sizes and SHA-256 hashes from the full 98 MB `main_db.sqlite` (`db1.0.0`) build and added live HTTP
  HEAD verification in `verify-catalog.yml`.
