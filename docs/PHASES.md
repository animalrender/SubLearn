# Phases

The plan the agent works to. Order is fixed; scope inside a phase may move between phases when a
dependency forces it, but only with an entry here explaining the move. Definition of Done for every
phase: the listed deliverables exist on the real code path, CI is green, `docs/CHECKLIST.md` and
`docs/PROGRESS.md` are updated, and the phase PR is merged into `main`.

| # | Name | Deliverables | Spec IDs | Status |
| --- | --- | --- | --- | --- |
| 0 | Foundations | Repo audit, references, docs skeleton, 16 Gradle modules with version catalog, CI (build/unit tests/lint/assembleDebug + APK artifact), design tokens and motion specs, typed versioned settings, EN/FA + RTL base, LATER stubs with flags | GEN-1..7 (structure) | done (`v0.1.0`) |
| 1 | App shell | Theme, navigation, settings screens, Home with recent videos in Room, opening video/URL/subtitle intents | GEN-2, GEN-5, settings system, entry points | done (`v0.1.0`) |
| 2 | Player core | Media3 wrapper, local + URL playback, MX-style overlay with auto-hide, gestures, orientation + lock, decoder choice, track selection, PiP, aspect ratio, speed, playlist, lock | PLY-1..5 | done (`v0.1.0`) |
| 3 | Subtitle engine | Parsers, normalizer, two layers, multi-track per layer, delay, styling hooks, Subtitle List View, layout mode, toggle buttons, dockable quick actions | PLY-6, PLY-7, SUB-1..3, SUB-7 | done (`v0.1.0`) |
| 4 | Interaction | Tap translate (word/line/block), pause/resume rule, ML Kit translation, entertainment cards, My Words + screen | SUB-4, SUB-5, LRN-1 | done (`v0.1.0`) |
| 5 | Shadowing | Repeat block, auto-repeat, stop at end of block, pause formula and settings | SHD-1..3 | done (`v0.1.0`) |
| 6 | Learning mode | Popup pipeline, WordLevelProvider, manual level + frequency list import, word-colouring styles | LRN-2, SUB-5 | done (`v0.1.0`) |
| 7 | AI button | Providers, settings, prompt editor, context builder, loading ring and pause/resume contract | AI-1..4 | done (`v0.1.0`) |
| 8 | Subtitle tools | Batch line-break removal, max-char split, search, no-spoiler mode | SUB-6, SUB-7, PLY-6 | done (`v0.1.0`) |
| 9 | Hardening | Animation and accessibility polish, performance pass, process-death restore, test gaps, README screenshots, `v0.1.0` tag | GEN-4..6, accessibility | done (`v0.2.0`) |
| 10 | Lexicon pack v2 | `build_lexicon.py` v2 (`EN→FA` & `FA→EN`), `validate_pack.py`, `build-lexicon-pack.yml`, `installed_packs` Room v2, `PackImporter`, `LegacyV1Importer` (`.bipe` / `main_db.sqlite`), `PackLexiconAccess` (chunked `<=500`), `ImportedDictionaryProvider`, `OFFLINE_DICTIONARY` enabled | MWE-1..7, DCT-1..5 | in progress |
| 17a | Translation architecture | Separate `LEXICON_EN_FA`, `TranslationSourceKind` (`PACK`, `LEGACY_V1`, `ML_KIT`, `ONLINE_GOOGLE`), and `DICT_FA_EN`; `TranslationOrchestrator` (`PRIORITY_CHAIN` & `MERGE_ALL`); `TranslationCacheDao` source provenance; `TranslationSettings` migration | TRN-1..6 | in progress |
| 12 | Cue-stream NLP & styling | Continuous cue-stream phrase analysis (`gap <= 1200ms`), remove `CrossBlockBridge`, precompiled anchor pattern cache, Room `(sourceKey, packId, packVersion, analyzerVersion, spanId)` cache, `LexiconPosTagger`, range-based `WordVisualState`, real Canvas dashed/dotted underlines | MWE-8..15 | in progress |
| 13 | ProudVocab card & My Words | Dual-section popup card (word top + `1dp` divider + phrase bottom), dual bookmarks, source chips, IPA, saved words green+dashed (`#10B981`), saved phrases keep type color (`savedPhrasesKeepTypeColor = true`), lemma-based saved highlight, My Words filters/search/export/import/jump, Room v1→v2 migration | MWE-16..25 | in progress |
| 17b | Online Google Translate | Optional `ONLINE_GOOGLE` (`client=dict-chrome-ex` primary, `client=it` fallback), default off, privacy notice, "Offline only" master switch, sends only tapped word/phrase (`<=60` chars), editable templates/host, `probe-online-translate.yml` + unit tests | TRN-7..12 | in progress |
| 18 | Library & Dictionary UI | Unified "Library & Resources" screen (`feature:packs`) with 4 sections (Dictionaries, ML Kit, NLP Models, Storage), full bidirectional EN↔FA Dictionary tab (`feature:dictionary`), first-run setup card | LIB-1..6, DCT-1..5 | in progress |
| 11 | Resumable downloader | `WorkManager` `PackDownloadWorker` with HTTP `Range` resume (`.part` + ETag), streaming SHA-256 verification, atomic rename, free-space guard (`>= 2.2x`), Wi-Fi-only guard `> 50 MB`, Catalog v2 (`docs/catalog/catalog-v2.json`) | MDL-1..6 | in progress |
| 14 | Balanced & Precise ONNX | `core:nlp-onnx` (`EmbeddingSenseRanker` with `MiniLM-L6-v2`, `OnnxPosTagger` with `MobileBERT`), contextual sense ranking and literal-vs-idiomatic scoring | MWE-26..30 | in progress |
| 15 | Advanced LLM & Smart profile | `core:nlp-llm` (`MediaPipeLlmSelector` `.task` adapter), 6 profiles (`Base/Light/Balanced/Precise/Advanced/Smart`), `DeviceCapabilityProbe`, benchmark runner, `ModelRuntimeGuard` auto-downgrade | MWE-31..35 | in progress |
| 16 | Workflows & real-DB CI | `build-lexicon-pack.yml`, `build-extra-packs.yml`, `release-packs.yml`, `verify-catalog.yml`, `probe-online-translate.yml`, real-DB CI evaluation suite | CI-1..5 | in progress |
| 19 | Final cleanup & release | Remove dead `NotImplemented*` stubs for shipped features, full EN/FA strings parity, Room migration & JVM tests green, `CHANGELOG.md` + `docs/PROGRESS.md` + `docs/CHECKLIST.md` updated | GEN-1..7 | in progress |

## Execution Order for Phases 10–19

Phases 10 through 19 execute in dependency order:
`10 → 17a → 12 → 13 → 17b → 18 → 11 → 14 → 15 → 16 → 19`
so that `PackLexiconAccess` and `TranslationOrchestrator` exist before the NLP engine and Player card
consume them, and the unified Resource Library (`18`) and WorkManager downloader (`11`) are in place
before wiring ONNX/MediaPipe models (`14`, `15`) and release workflows (`16`, `19`).

## Notes on the split actually used

- Phases 0–8 were implemented on one reviewable branch, because the codebase had never been compiled
  and each phase's UI depends on the symbols of the previous one; splitting it earlier would have put
  red commits on `main`-adjacent branches for no benefit. Commit boundaries still follow the phase
  order (`chore(repo)` → `feat(core)` → `feat(features)` → `feat(app)` → docs). See
  [DECISIONS.md](DECISIONS.md) D-20.
- Phase 9 is where the remaining verification work belongs: a device pass on RTL, animation and
  process death, plus the screenshots the README should have. The `v0.1.0` tag is cut from the review
  branch by `release.yml` (D-21) so the first installable build exists before the device pass, not
  after it; later tags follow the same route from `main`.
