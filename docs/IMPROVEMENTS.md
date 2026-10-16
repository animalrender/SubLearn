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
  reloads immediately when packs are toggled or deleted in `DownloadsViewModel`.
- **Rich Dictionary Screen FTS suggestions and extended fields (`LearnScreen`)**: Displays clickable
  prefix/FTS search result chips alongside English glosses, antonyms, word family, and related phrasal
  verbs/idioms/collocations.
- **Room `phrase_span` cache wiring (`PlayerViewModel`)**: Wired `PhraseSpanCacheDao` into `PlayerViewModel`
  and `AppModule` so analyzed subtitle spans are persisted keyed by `(sourceKey, packId, packVersion, analyzerVersion, spanId)`.
- **Verified `packs-v2.0.0` SHA-256 digests in `catalog-v2.json` and `ModelCatalog.kt`**: Populated exact
  byte sizes and SHA-256 hashes from the full 98 MB `main_db.sqlite` (`db1.0.0`) build and added live HTTP
  HEAD verification in `verify-catalog.yml`. (Corrected 2026-10-16: the digests in the repository were
  the digests of the *local* build, not of the published assets, so every pack download failed with
  "SHA-256 mismatch". The values are now read from the release API — the digest GitHub reports for
  the uploaded asset — and the two copies of the catalog are checked against each other and against
  the release. See D-83.)


## 2026-10-16 — Settings and unobtrusive player learning

- Fixed the Koin function dependency that crashed Settings; regression test resolves production modules.
- Resource management is settings-owned, not a premature implementation of browser/PDF/image Learn.
- Strict above-level, known-word and lemma filters feed a real offline automatic-card renderer, rather
  than merely exposing settings for a missing pipeline. Tap lookups remain independent.
- All linear progress and slider tracks share no-dot drawing; localized settings search and nested
  back behaviour make the existing categories usable in both UI languages.
- Device checks are still necessary for animation, compact landscape geometry and native translations.


## 2026-10-16 — one downloads screen, honest profiles, automatic releases

- **One screen instead of three download systems.** "Library & Resources", "Models & Downloads" and
  the translation settings each had their own idea of what "installed" meant; the same model was
  listed twice with two sizes, and the accuracy models were fetched by the *pack* downloader, which
  puts one file in the wrong folder and leaves the runtime unable to open it. Settings → Downloads
  now holds the translation model, the packs, the accuracy models and the profile, with one view
  model and one state per item (D-81).
- **The offline model is the SDK's job, not the app's.** The hand-written installer is deleted: ML
  Kit downloads, verifies and stores its own models, and an archive unpacked into a directory the
  SDK does not read is exactly what produced "missing translation_model.pb" while the UI reported
  success. The Wi-Fi requirement is gone as well — the user asked for a 30 MB model once.
- **The profile advice is honest.** The guard compared the profile's floor with the app's *heap*, so
  every choice was downgraded and the profiles never ran. The advice now uses the phone's memory,
  storage and installed models, and the screen states what a profile still needs instead of
  silently choosing another one.
- **The catalog cannot drift from the published bytes again.** The app's bundled catalog is
  generated from the published one, `tools/check_catalog.py` compares them field by field, and the
  CI catalog check asks GitHub for the digest of every asset (D-83).
- **The release bumps itself.** `VERSION` plus `tools/next_version.py` turn every merge to `main`
  into the next patch release, and `versionCode` follows the name, so two releases can never claim
  the same code (D-82).
