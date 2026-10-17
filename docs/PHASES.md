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

## 2026-10-16 corrective pass (phases 1, 6, 9 and 18)

Settings dependency wiring, a settings-owned resource library, a Coming soon-only Learn tab, passive
above-level player cards and no-dot tracks are implemented on `arena/89bec83d-sublearn`.
Static checks, Debug/Release assembly, unit tests, lint and instrumented-source compilation passed in
CI run 38923376453 (`a65db3e`); the on-device RTL/animation pass remains pending.
This pass corrects earlier completion claims for the automatic-card rendering path, which was absent.
See D-78..D-80 and the current entry in PROGRESS.md.

## 2026-10-16 corrective pass (phases 11, 15, 18 and 19)

The owner's report about broken releases, the broken translation-model download, the "SHA
mismatched" pack download and the profiles that do nothing is answered by one pass on
`arena/40fc4e81-sublearn`:

- **Phase 18 → one downloads screen.** "Library & Resources" and "Models & Downloads" become
  **Settings → Downloads**: the offline translation model, the offline packs, the accuracy models
  and the accuracy profile, in that order, with one view model and one state per item (D-81).
- **Phase 11 → the catalog describes the published bytes.** The pack entries carry the size and
  SHA-256 that the release API reports, the app's bundled copy is generated from the same file and
  checked against it, and `verify-catalog.yml` fails the build when the catalog and the release
  disagree (D-83).
- **Phase 15 → profiles are advised, not enforced.** `ModelRuntimeGuard` and the heap-based
  downgrade are gone; the advice uses the device's memory, storage and installed models, and the
  screen names what a profile still needs (D-81).
- **Phase 19 → the release bumps itself.** `VERSION`, `tools/next_version.py` and the updated
  `auto-release.yml` publish `v3.0.0`, `v3.0.1`, `v3.0.2` … from `main` with no manual tag
  (D-82).

The phase statuses above are not changed by this pass; it corrects what the earlier phases claimed
to deliver. See D-81..D-83 and the current entry in PROGRESS.md.

## 2026-10-16 corrective pass (phases 2, 15 and 18)

The owner's follow-up on the same day — the Downloads screen's layout, the player's orientation and
pausing, the drawer width, the exact file name, the download sources, stopping downloads, testing a
model, and a sandbox for packs and profiles — is answered on `arena/6da9d669-sublearn`:

- **Phase 2 → the player opens in landscape, flips with the device under a system rotation lock,
  and pauses when left or when the audio route changes** (D-84, D-85).
- **Phase 18 → one card for every download, with Stop, Resume, full URLs and a Test button**
  (D-86, D-87).
- **Phase 15 → the subtitle lab** runs the profile engine on a real subtitle and shows what it
  costs, which is the measurement the profile advice was waiting for (D-87).

The phase statuses above are not changed by this pass. See D-84..D-87 and the current entry in
PROGRESS.md.

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

## 2026-10-17 corrective pass (phases 1, 2, 9, 14 and 15)

D-88..D-90 repair numeric SAF labels and safetensors wire-format parsing, isolate Downloads model
self-tests in a disposable process, and add autoplay with a passive restart action on position resumes.
Local static checks pass. CI 38996445061 on `47f89b1` passed Debug/Release assembly, unit tests,
lint and instrumented-source compilation. Phone verification of native crash containment,
provider-specific filenames and playback UX remains pending.

## 2026-10-17 corrective pass (lab, profiles and feedback)

D-91..D-93 add tone-based status colours and step-named model-test failures, a generated description of
each accuracy profile, and a free-text mode in the subtitle lab. Local static checks pass; compilation and
the JVM tests run in CI for the first time with this change. The model-test crash remains open (#34).

## 2026-10-17 tooling pass (phase 9 verification)

D-94 adds the UI check: an emulator in CI, Maestro flows in `.maestro/`, and the evidence on the
`ui-snapshots` branch ([UI_VERIFICATION.md](UI_VERIFICATION.md)). The app shell (Home, Settings with
search and Downloads, My Words, Learn, Dictionary) has now been run and looked at on an emulator for the
first time. The parts of phase 9 that need a video, a phone or real hardware stay pending (KNOWN_ISSUES
21, 24, 25, 29 and 30). From here on, a phase that changes a visible screen ends with a UI check before its
merge (AGENTS.md, per-task loop step 6).
