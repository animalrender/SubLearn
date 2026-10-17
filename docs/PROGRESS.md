## 2026-10-16 — the Downloads screen made usable, the player made quiet, and a lab for the packs

**Implemented on `arena/6da9d669-sublearn`; CI green on `917bd34` ([run 38975245322](https://github.com/animalrender/SubLearn/actions/runs/38975245322)); no device run claimed.**

The owner's second report, after the downloads fix: the Downloads screen's words were all over the
place; the player should open in landscape, turn over with the phone even when the system rotation
is locked, and pause when the screen is left or the headphones change; the side menu should cover
half the screen; the file name should be shown exactly; each pack and model should show where it
comes from; downloads should be stoppable; a downloaded model should be testable; and there should
be a sandbox to try packs and profiles on a subtitle without playing a video.

**What changed**

- **Player (D-84, D-85).** `orientationLock` defaults to `LANDSCAPE` (settings schema 5, migration
  `AUTO → LANDSCAPE`) and maps to `SENSOR_LANDSCAPE`, so the picture flips to reverse-landscape
  with the device regardless of the system rotation switch; `PORTRAIT` → `SENSOR_PORTRAIT`.
  `Media3PlayerController` watches `AudioManager` for output-device changes and pauses on any of
  them (Media3's becoming-noisy handling stays). `PlayerViewModel.onLeave(pausePlayback)` pauses
  when the screen is left or the Activity stops, except while picture-in-picture is showing;
  closing the PiP window pauses too.
- **Shell.** The navigation drawer is `fillMaxWidth(0.5f)`. The player title and the Recent list
  use the provider's `DISPLAY_NAME`; `MediaInfo.titleFromFileName` is deleted, and `MainActivity`
  resolves the name for a file opened from another app.
- **Downloads (D-87).** `DownloadItemCard` is rebuilt: stacked rows, `Dimens` tokens, a rendered
  `detail` line, a `PAUSED` state with *Resume*, a *Stop* button while downloading (hidden when the
  caller cannot stop — ML Kit), an indeterminate bar when the size is unknown, and a wrapping
  actions row. The catalog packs, the imported packs, the translation model and the accuracy
  models all use it. `DownloadsViewModel` keeps a `Job` per download so *Stop* cancels it: the pack
  downloader keeps the `.part` (resume continues it), the model installer deletes the partial
  directory (`ModelDownloader` and `NlpModelInstaller` now clean up on *any* throw, not only on
  `InterruptedException`). The status line is dismissible; import buttons and profile chips wrap.
- **Sources.** The details dialog of every pack shows the full URL, selectable, and the SHA-256 in
  full; an accuracy model's dialog lists every file with its URL, size and SHA-256; the translation
  model's dialog says that ML Kit fetches it from Google's servers and that there is no URL to show.
- **Self-test (D-86).** `core:ai/NlpModelSelfTest` (new) opens an installed model with the real
  loaders, runs one probe and reports a verdict with timings and heap delta; a *Test* button on
  every installed model card runs it; `NlpModelSelfTestTest` pins all eight verdicts with fakes.
- **Subtitle lab (D-87).** `LabViewModel` + `LabSection` (new, in `feature:settings`, section
  `lab` under Downloads): pick one subtitle, step through its blocks, analyse a block with the
  player's exact pipeline (phrases, lookups, ranking with the profile's ranker), switch profile
  and active pack in place, time the phrase pass over the first 200 blocks, and read the engine
  report and the process resources (`core:models/ProcessResourceReader`, new).
- **Strings.** 63 new keys in EN and FA; the three orientation labels now say what they do.

**Checks:** `check_sources`, `check_symbols`, `check_deps`, `check_resources`, `check_docs` and
`git diff --check` pass. New unit tests: `NlpModelSelfTestTest` (8), a stop-from-callback case in
`ModelDownloaderTest` and `NlpModelInstallerTest`, and the v4→v5 settings migration in
`SettingsRepositoryTest`. The compile is CI's job.

**Still to verify by hand, on a device:** the reverse-landscape flip with system rotation locked;
the pause on a Bluetooth headset connecting and disconnecting; stopping and resuming a pack
download; the self-test on a real `.onnx` and a real `.task` file (this is the first inference on
a device — #24); the lab's timings on a low-memory phone.

## 2026-10-16 — one downloads screen, automatic releases, and the catalog that matches the bytes

**Implemented on `arena/40fc4e81-sublearn`; CI green on `345f763`, device verification pending.**

The owner reported that the released builds are broken, that the offline translation model download
fails in three places, that the pack download ends in "SHA mismatched", and that the accuracy
profiles do not work. The causes turned out to be four separate ones, all confirmed in the code:

1. **The translation model had two implementations.** A hand-written installer unpacked Google's
   archive itself, demanded `translate_enfa/` at the extraction root, had the wrong size and an
   empty SHA-256, and wrote a layout ML Kit does not read — which is what produced "missing
   translation_model.pb" while the UI said "installed". The SDK path existed too, but it required
   Wi-Fi and only fetched the target language.
2. **The catalog described bytes that were never published.** The pack entries carried a size and a
   SHA-256 that did not match the assets in the `packs-v2.0.0` release, so the verifier rejected
   every pack download.
3. **The profile guard compared the profile's floor with the app's heap.** A phone with 8 GB of RAM
   gives the app a few hundred MB, so every profile was downgraded and the chips showed raw enum
   names.
4. **No release had ever happened.** The version was a literal in `app/build.gradle.kts` and the
   only automation was a conventional-commit rule that needed a tag that did not exist.

**What changed**

- **Settings → Downloads** replaces "Library & Resources" and "Models & Downloads" (D-81). One
  screen holds the offline translation model, the offline packs, the accuracy models and the
  accuracy profile; `DownloadsViewModel` owns all four. The library screen's hardcoded English
  status strings, the models screen and their view model are deleted.
- **One model, one state, one download.** `TranslationModelManager` (new, in `core:translate`) is the
  single source of truth for the offline translation model; the player popup, the translation
  settings and the downloads screen all go through it. The custom `MlKitModelInstaller`,
  `MlKitInstallLogic` and their test are deleted, and the SDK path no longer requires Wi-Fi.
- **The accuracy models are installed by the installer that can open them.** The library screen
  downloaded them through the *pack* downloader — one file into the wrong folder — so they never
  loaded. `NlpModelInstaller` fetches every asset the model needs.
- **Profiles are advised, not enforced.** `ModelRuntimeGuard` (and its test) is deleted.
  `SmartProfileSelector` compares the profile with the device's memory, storage and installed
  models, `DeviceCapabilityReader` (new, in `core:models`) reads the device, and the screen says
  what a profile still needs instead of switching the user's choice. The chip labels are real names
  in both languages.
- **The catalog matches the published bytes** (D-83): the pack sizes and SHA-256 are the digests
  the release API reports, `ModelCatalogParser.bundledDefault()` is generated from the same file,
  `tools/check_catalog.py` compares the two field by field, and `verify-catalog.yml` asks the GitHub
  API for the digest of every published asset.
- **Releases bump themselves by a patch** (D-82): `VERSION` holds the floor, `tools/next_version.py`
  computes the next version from the highest stable tag, `auto-release.yml` publishes `v3.0.0`,
  `v3.0.1`, `v3.0.2` … from `main`, and `versionCode` is derived from the name. The old
  `tools/bump_version.py` is deleted.

**Checks so far:** all eight static checkers pass, `tools/test_next_version.py` is green (15 cases),
and `git diff --check` is clean. Local Gradle cannot start (no JVM, no Android SDK), so the compile
is CI's job and no device run is claimed.

CI is green on `345f763`: [run 38942626135](https://github.com/animalrender/SubLearn/actions/runs/38942626135)
(push) and [run 38942617914](https://github.com/animalrender/SubLearn/actions/runs/38942617914)
(pull request) both passed `assembleDebug`, `assembleRelease`, `test`, `lintDebug` and
`assembleDebugAndroidTest`.

CI found two real problems in the first build of this branch, and both are fixed here:

- A development build is called `0.0.0-dev-<branch>+<sha>`, so the `versionCode` derived from the
  name was 0 and the Android plugin refused to configure the project. A development build uses the
  number of commits instead (D-82).
- The new profile test described "a 512 MB phone" and built a device with 2048 MB, so the RAM
  floor was never crossed. The devices are named for what they are now: 64 MB, 2 GB and 8 GB.

**Still to verify by hand, on a device:** the translation model download (all three entry points
now share one manager), a pack download with the published digests, and the profile advice on a
phone with little memory.

## 2026-10-16 — Settings entry, Learn scope and automatic player cards

**Implemented on `arena/89bec83d-sublearn`; CI green on `a65db3e`, device verification pending.**

[CI run 38923376453](https://github.com/animalrender/SubLearn/actions/runs/38923376453) passed:
`assembleDebug`, `assembleRelease`, `test`, `lintDebug`, and `assembleDebugAndroidTest` (8m 9s).
The production Settings/Resources graph regression test and seven learning-selection tests passed.
The two new instrumented visibility/overlap tests compile; they have not run on a device.

- Fixed the Settings Koin graph: bind `NlpModelInstaller` to the downloader's `::download` function.
  Added a Robolectric test resolving the production Settings and Resources view models.
- Learn is only the localized browser/PDF/image Coming soon screen. Moved onboarding, pack imports,
  catalog/installed packs and accuracy profiles to Settings → Library & Resources; model downloads
  have a direct link there. Player learning retains level, word styles, popup and lookup settings,
  with separate cards for mode, automatic card appearance, interactions, level source and colours.
- Removed duplicated app/feature toolbars on Settings and My Words.
- Word styling now refreshes when level/settings change, excludes equal/unknown levels and clears in Off.
- Settings search now includes localized titles/hints. Section and query survive recreation;
  Android back returns to the root, prompt back returns to AI, and section scrolling is scoped.
- Added automatic offline translated word/phrase hints for the current learning subtitles (file and
  embedded). Strict above-level filtering, lemma deduplication, known-word exclusion and bounded
  counts are unit tested. Cue/settings/media changes and seeks cancel stale work. At most two small
  hints animate in a corner; UI suppresses them while other controls/popups/PiP need the picture.
- Hints try alternative corners when measured subtitle plates overlap the requested area; compact
  video regions show only one hint, and buffering/opening suppresses drawing.
- Shared no-dot progress and slider components now cover Home, Settings, downloads and player seek.
  Motion is reduced-motion-aware; appearance uses existing settings and design tokens, EN/FA strings.
- Resources integrity checks no longer claim verification when no reference hash exists; checksums
  run on IO and failed catalog imports/deletions no longer report success.

**Checks so far:** all seven source/resource/dependency/docs/schema/catalog checks and `git diff --check` pass.
Python tooling tests: 61 run, 59 passed, 2 intentionally skipped. Local Gradle
cannot start: no Java/JAVA_HOME or Android SDK. Added seven selection tests, the production-graph
regression test and an instrumented card visibility smoke test; unit-test execution passed in CI; instrumented execution is device-pending.
The first CI compile caught the split Dictionary status field and an invalid test import; both were
fixed. All new player/settings production code compiled in that run. The follow-up caught a missing
WindowInsets import on My Words after the toolbar cleanup; that import is fixed too. The final CI
run linked above is green, including the app graph regression test. No device verification is claimed.
See D-78..D-80 and KNOWN_ISSUES 29.

## 2026-10-15 — phases 20–25: detection accuracy, downloads, navigation, profiles, releases, docs

**Goal.** Address all remaining gaps across detection accuracy, ML Kit downloads, settings/navigation,
smart profiles, feature completion, auto-versioning, and documentation.

**Implemented (pending CI compile)**

### Phase 20 — Detection accuracy
- **`computeConfidence` uses `pattern.prior`** (D-63): base confidence from the lexicon pack's prior
  probability instead of a flat 0.85. Adjustments: +0.05 contiguous ≥3 tokens, -0.15 gap ≥3,
  +0.05 POS agreement, -0.30 ambiguous particle/preposition follower.
- **Per-type thresholds** (D-64): `NlpConfig` now has `phrasalThreshold=0.75`, `idiomThreshold=0.75`,
  `collocationThreshold=0.65`. PHRASE kind is never underlined. Analyzer version bumped to 2.
- **Separable phrasal verb bug fixed** (D-65): particle must be the first element after verb in pattern;
  "put it with" no longer matches "put up with"; "look that up" now works.
- **Alternatives kept** (D-67): `resolveOverlaps` attaches losing spans as `PhraseSpan.alternatives`.
- **Quality filters in `build_lexicon.py`** (D-66): 5 demotion rules (all closed-class, DET+proper,
  noun-adj combo, phrasal-to-motion, COL without explicit code 19). Demotions tracked in report.
- **`validate_pack.py` expanded**: asserts "in truth", "the way", "a lot", "go to", "the swedish"
  are demoted; "give up", "make a decision", "look forward to", "keep an eye on" are kept.
- **Gold evaluation set** (`tools/eval/gold_en_v1.jsonl`): 52 entries (30 positives, 16 negatives,
  6 two-cue, 8 noisy). `RealPackIntegrationTest` JVM test harness created.
- **Room: no destructive migration** (D-68): `fallbackToDestructiveMigration()` removed,
  `exportSchema = true` enabled.
- **Word card design tokens** (`WordCardTokens.kt`): ProudVocab values — container, word row,
  phrase row, badges, save button, CEFR colors, type colors. All in `core:designsystem`.

### Phase 25 — Auto-versioning
- **`app/build.gradle.kts`**: reads `SUBLEARN_VERSION_NAME` from env (CI), falls back to
  `0.0.0-dev+<shortsha>` for local builds. `versionCode` uses `GITHUB_RUN_NUMBER` or derived.
- **`tools/bump_version.py`**: conventional commit parser, computes next version (feat→minor,
  fix/perf/refactor→patch, BREAKING→major). 9 unit tests pass locally.

**Checks run**
- `python3 tools/check_sources.py core app feature tools`: 149 files, 0 problems.
- `python3 tools/check_symbols.py`: 0 unresolved imports.
- `python3 tools/check_deps.py`: 18 modules, 0 missing dependencies.
- `python3 tools/check_resources.py core app feature`: 15 resource files, 0 problems.
- `tools/test_bump_version.py`: 9/9 pass.
- `git diff --check`: clean.

## 2026-10-16 — completing the remaining phase 20–25 items

**Branches:** `arena/3779275b-sublearn`. Every change below is on real code paths; the CI run
listed at the end is the evidence.

### Phase 20 — analysis input (§2.4) and schema drift (§2.5)
- **`CueSlice` carries `cueId`** and `CueStreamPhraseAnalyzer` now cleans text before analysis
  (`AnalysisTextCleanup`: HTML tags, `JOHN:` speaker labels, `[MUSIC]`/`[LAUGHTER]`, `♪`,
  leading dashes, curly quotes), segments long streams (`SegmentConfig`, hard gap 1500 ms,
  64 token segments with 8 token overlap), builds an `AnalysisCacheKey` from the content,
  normaliser, pack, analyser version and profile, and exposes `CueOffsetMapping` for the UI.
- **`tools/check_schema.py`** compares `MIGRATION_1_2` against the exported v2 schema, so a
  column the entity no longer declares fails in CI, and asserts the analyser cache columns
  survive. Verified to fail on a deliberately broken migration.
- Gold set grown from 52 to **209 entries** (120 positive, 50 negative, 20 noise, 19 two-cue).

### Phase 21 — ML Kit installer (§3.1, §3.2)
- `MlKitInstallLogic` (D-69) holds the URL scheme, SHA-256 check, zip-slip guard and structure
  validation; `MlKitModelInstaller` keeps the Android and network half.
- **10 unit tests** cover both hosts, version routing, a known SHA-256, parent and nested
  traversal rejection, valid extraction, both directions of structure validation and the
  metadata constants.
- **`DownloadItemCard`** in `core:designsystem` renders available / downloading / verifying /
  installing / installed / error with progress, cancel, retry, delete and an info action.
- A new **Models & Downloads** settings section drives it from the installer through
  `SettingsViewModel`, registered in Koin.

### Phase 22 — navigation, insets, downloads UI
- `SettingsScreen` and `MyWordsScreen` set `contentWindowInsets = WindowInsets(0)` so the nested
  scaffolds stop insetting twice under the app-level scaffold (§4.3), and both use
  `Icons.AutoMirrored.Filled.ArrowBack`.
- `SubLearnLinearProgress` gives the app one progress bar to reach for.

### Phase 23/24 — profiles, models, packs
- **`NlpModelCatalog`** maps the six catalog models to the accuracy profiles with runtime, size
  and RAM floor, **`SmartProfileSelector`** picks the strongest runnable profile, and
  **`NlpModelRuntimeGuard`** reports `NotImplemented` instead of silently degrading (D-71).
  Covered by 9 tests in `core:ai`.
- The Learn tab's profile chips now say how many models the profile adds and how large the
  download is, and flag profiles whose runtime is missing.
- **The ONNX and LiteRT engines exist now.** `core:nlp-onnx` (ONNX Runtime 1.22.0) loads the
  `Xenova` int8 encoders; `core:nlp-llm` (MediaPipe `tasks-genai` 0.10.35) runs the LiteRT `.task`
  bundles. `NlpRuntimeBootstrap` probes both native libraries at startup and registers what loads,
  `ProfiledNlpEngine` opens the models a profile needs through injectable loaders, and
  `NlpModelInstaller` downloads a model's files with per-file resume and deletes a partial
  directory on failure (D-74, D-75). 19 tests cover the engine and the installer.
- **`ModelStore` and `ModelDownloader`** give the models a real home in `filesDir/ai-models` and
  one plain-JVM transfer path with checksum verification. The catalog's sizes and hashes were
  wrong for every model; `tools/refresh_model_catalog.py` now derives them from the publisher's own
  metadata, and `tools/check_catalog.py` proves `ModelStore`'s file table matches the catalog field
  by field.
- Models & Downloads **lists all six models** with the profile each one unlocks, progress across
  every file of a model, and a stated reason (rather than a dead button) when this build cannot run
  one. `DownloadItemCard` gained that state, a two-line description and theme colours.
- **`tools/update_catalog.py`** derives the catalog's size and SHA-256 from the released files and
  `release-packs.yml` commits the result (D-70). 11 tests.
- My Words gained a stable **sort selector** (Newest, Oldest, A–Z, Level) and **SAF export** for
  CSV, JSON and Anki TSV alongside the clipboard actions (D-73), with 5 tests for the orderings.

### Phase 25 — docs, versions, checks
- `docs/README.md` indexes every document, and `tools/check_docs.py` fails when the index and the
  directory disagree, alongside the existing catalog and EN/FA string checks. Both are wired into
  the CI static checks.
- Decisions **D-69 .. D-73** record the reasoning above.
- `tools/check_catalog.py` cross-checks `catalog-v2.json` against the Kotlin model table (ids,
  sizes, RAM floors, runtimes, profile placement) and runs on every build and every catalog
  change. Writing it found a real gap — two alternative SMART LLMs the app did not name — now
  recorded as `NlpModelCatalog.smartAlternatives`.
- The library rows gained a **details dialog** (version, size, runtime, RAM, profile, licence,
  source, hash prefix, and an explicit warning when no hash is published).
- `build-extra-packs.yml` no longer **discards its own output** when the release is missing.
- A 20k-token **throughput tripwire** for the cue analyser, and tests for the runtime guard's
  budgets, downgrade ladder, guarded-call fallbacks and the two profile scorers.

**Honest gaps that remain**
- The ONNX and LiteRT engines are **implemented but have never executed a model**. Both native call
  paths need a device and a downloaded model; the selection, layout, tokenizer, pooling, download
  and install logic around them is unit tested, and CI compiles the native bindings.
- The ML Kit installer compiles, is unit tested and is wired to the UI, but has **not been run on
  a device**: no download was performed against Google's CDN from this environment.
- No Compose UI test was added for the rebuilt word card, so its layout is unverified beyond
  compilation.
- `Compose` navigation still uses the hand-rolled `AppNavigator`; the back stack is tested by
  compilation and manual reasoning, not by an automated test.

**CI verification (2026-10-16, engines)**
- Both workflows are green on `7264aac`: `CI` ("Build, test, lint") run 38838624639 and
  `Lexicon Integration Test` run 38838624586, both **success**, plus the same two checks on the
  pull request. `assembleDebug`, `assembleRelease`, `test`, `lintDebug` and
  `assembleDebugAndroidTest` all pass with the ONNX and LiteRT engines, the profile-selected sense
  ranker and the settings UI changes in the tree.
- Getting there took four rounds, and each round's reason came from a check-run annotation rather
  than from the log (the Actions log host is unreachable from the agent sandbox):
  1. `LlmInferenceOptions` is nested inside `LlmInference`, and the ONNX embedder did not match
     `TextEmbedder` or the tokenizer/pooling APIs the module actually has;
  2. `SettingsRows` was missing the design system's `R`, and `NlpModel` had no
     `requiresTermsAcceptance` for the models card to read;
  3. two test sources followed the old contracts (`embed()` returning `AppResult`, `File.setLength`);
  4. twelve unit-test failures: eleven were tests asserting values the code no longer has (round
     model sizes now read from the catalog, a tokenizer helper that dropped `[SEP]`), and one was
     real — the engine cache returned a model ranker with no model loaded.
- `tools/check_sources.py` gained the backtick case after a real miss: an apostrophe in a test name
  made it swallow the rest of the file, so a broken test file passed the check that exists to catch
  broken test files.
- Static checks on `7264aac`: 182 files, 0 problems; 0 unresolved imports; 20 modules, 0 missing
  dependencies; catalog, schema, resources and docs checks all pass; 36 python tests pass.

**CI verification (2026-10-16)**
- CI green on `86cd5fc`: `assembleDebug`, `assembleRelease`, `test`, `lintDebug` and
  `assembleDebugAndroidTest` all pass, with the new static checks (`check_docs`, `check_schema`,
  `check_catalog` and the 20 new python tests) running inside the same job.
- Two failures were found and fixed by CI in this round: a missing type argument on the
  `WordSort` comparator, and a test that assumed a blocking sleep could be cancelled by
  `withTimeoutOrNull` (it cannot — the test now suspends).

**CI verification (2026-10-15)**
- CI run 38808380130 on `arena/3779275b-sublearn` (commit `cc60465`): **green**.
  `assembleDebug`, `assembleRelease`, `test`, `lintDebug` all pass.
  All 18 test classes pass (including 4 new tests for prior-based confidence,
  per-type thresholds, separable phrasal bug fixes, and `look that up`).
- Static checks: 150 files, 0 problems; 0 unresolved imports; 18 modules, 0 missing deps;
  15 resource files, 0 problems. EN/FA strings in sync.
- Auto-release pre-release should be published at `dev-arena-3779275b-sublearn`.

**Sense ranking per profile (2026-10-16, D-76)**
- `ProfiledSenseRanker` implements the D-33 tier table over the engines `ProfiledNlpEngine` opens:
  `ModelSenseRanker` scores the line against each sense's English gloss with the encoder, SMART's
  `LlmSenseRanker` adds the prompt model's choice, and every failure path falls back to
  `PriorSenseRanker` instead of hiding the word's meanings.
- `ProfiledNlpEngineCache` keeps the selected profile's resident models open between popups and
  reopens them when the profile changes; a missing model is retried next call, so installing one
  takes effect immediately.
- The player's phrase section now asks for the ranker before it shows a headword's glosses; with no
  model installed the order is unchanged. `core:ai` gained a dependency on `core:nlp` (one-way) for
  the `SenseRanker` contract, and `tools/check_sources.py` learned to skip backticked identifiers,
  which had made it swallow the rest of any file whose test name contained an apostrophe.

**Pack validation (2026-10-16, second round)**
- The lexicon workflow's five `VALIDATION FAILED` errors are fixed in `apply_quality_filters`
  (D-77): `look forward to` keeps a real prior, `in truth` is demoted as a prepositional phrase, and
  `go to`/`come to`/`get to` are demoted even though the source labels them with the phrasal code.
  The rules are a pure function with 9 tests in `test_lexicon_builder.py` (33 in the file), because
  the real pack needs the 98 MB source database, which this sandbox cannot download.
- The workflow's reporter now matches the validator's `  - <reason>` lines, which is how the five
  reasons became visible: the previous version only published the headline.

**What remains (honest)**
- Phases 21–24 are implemented and compile, and what is *not* proven is device behaviour: no ONNX
  or LiteRT model has ever been executed (KNOWN_ISSUES 24), the ML Kit installer has never
  completed a real download (25), and the new UI has been verified by compilation, static checks
  and code review rather than on a screen.
- The lexicon pack's validator passes on the full database in CI, which is the only place the 98 MB
  source database is reachable; the five errors it reported came from demotion rules that read the
  wrong field, and the build now prints a `quality_audit` line per asserted key so the next one
  explains itself.
- No device testing has been done.
- The gold integration test needs a real pack to compute precision/recall numbers.
- The `build-lexicon-pack.yml` workflow needs the quality filters tested against the real DB.

---

## 2026-10-11 — phase 10 (Lexicon pack) started

**Goal.** Build the foundation for offline phrase detection: the lexicon v2 format, the converter
tool, the NLP engine module, the model catalog, and the dictionary management UI.

**Implemented (not yet compiled on this branch)**

- **Lexicon converter** (`tools/lexicon_builder/`): Python script that reads `fastdic_plain.sqlite`
  and produces `sublearn-lexicon-en-fa-v2.sqlite` + `build_report.json`. Includes one-click
  `.bat` (Windows) and `.sh` (macOS/Linux) scripts and a Persian README. Tested against
  `dictionaryproject/docs/sample.sqlite`: 1,404 entries, 2,715 senses, 1,214 examples, 437
  patterns, 1,138 lemma forms.
- **core:nlp module** (pure Kotlin, no Android): `PhraseSpan`, `PhraseKind` (PHRASAL/IDIOM/
  COLLOCATION), `NlpConfig`, `Lemmatiser`, `PatternCompiler`, `DeterministicPhraseDetector`,
  `CrossBlockBridge`, `PriorSenseRanker`, and all interfaces (`PhraseDetector`, `PosTagger`,
  `SenseRanker`, `LexiconAccess`).
- **core:models module** (Android): `ModelCatalogEntry`, `ModelKind`, `AccuracyProfile`,
  `ModelRuntime`, `SourceInfo`, `ModelCatalog`.
- **core:nlp-onnx module** (Android, skeleton): ONNX Runtime wrapper placeholder for phase 14.
- **core:nlp-llm module** (Android, skeleton): LLM adapter placeholder for phase 15.
- **Model catalog** (`docs/catalog/catalog-v1.json`): 8 entries (1 lexicon + 7 models), all with
  `verified: false` pending source verification (REQ-12).
- **LexiconV2 types** in `core:lexicon`: `LexiconV2Entry`, `LexiconV2Sense`, `LexiconV2Example`,
  `LexiconV2Pattern`, `LexiconV2Relation`, `LemmaFormEntry`, `LexiconV2Meta`.
- **Documentation**: PHASES.md updated with phases 10-16, DECISIONS.md added D-31 through D-42,
  AGENT_REQUESTS.md added REQ-10 through REQ-13, ARCHITECTURE.md module graph updated, AGENTS.md
  module map updated.

**Checks run**
- `python3 tools/check_sources.py core app feature tools`: 132 files, 0 problems.
- `python3 tools/check_symbols.py`: 0 unresolved imports.
- `python3 tools/check_deps.py`: 18 modules, 0 missing dependencies.
- `python3 tools/check_resources.py core app feature`: 15 resource files, 0 problems.
- Lexicon converter tested against sample.sqlite: correct output.
- Android compile/unit/instrumented tests have not been run; repository guidance says this sandbox
  has no JVM or Android SDK. CI is still required.

**Remains / honest limitations**
- CI must type-check Kotlin and run `assembleDebug`, `assembleRelease`, tests and lint; no code
  here should be called build-verified yet.
- The `core:nlp`, `core:models`, `core:nlp-onnx`, `core:nlp-llm` modules need Koin DI registration
  in `app/di/`.
- The `DictionaryProvider` stub needs to be replaced with `ImportedDictionaryProvider` backed by
  the v2 SQLite.
- Room migration 1→2 for My Words (phraseType, lemma, lexiconEntryId, surfaceParts) not yet done.
- Settings for Smart Learning section not yet added.
- The lexicon pack hosting URL is not yet provided (REQ-10).
- All catalog entries are `verified: false` (REQ-12).

# Progress

Always answers: what was the goal, what is done, what remains, how to test it. Newest entry on top.

## 2026-10-11 — player transport, multi-source tracks and recovery (v0.2.1 work)

**Goal.** Finish the requested player pass end-to-end: separate subtitle/audio management, resilient
Recent Videos, sensor/manual orientation, translucent readable prompts, flexible one-line action docks,
bottom-only transport, live scrub preview, and matching product/design documentation.

**Implemented (not yet compiled on this branch)**
- Replaced the centre transport cluster with a bottom row containing play/pause, previous/next video,
  previous/next subtitle cue, seek steps and user-arranged actions. The seek row now keeps elapsed and
  total/remaining time beside the seek line; tapping the right clock toggles the display, and every drag
  update seeks while playback is paused so video frames and subtitle layers preview in place.
- Added TOP / BAR (second row) / PLAYBACK / FLOATING / HIDDEN quick-action locations, reorder/dock UI,
  expanded labels for docked actions, single-line horizontal scrolling, overflow arrows and bounded
  floating positions. Top and bottom chrome use the existing reduce-motion-aware fade/slide motion.
- Reworked subtitle management into Learning and Translation tabs with checkboxes for every embedded
  soft track and multiple external file keys per layer. Each file can be disabled or removed. Audio has a
  separate sheet with embedded selection, external audio merge, player-volume slider and mute toggle.
- Added Media3 external-audio source merging, SAF persistable grants for video/subtitle/audio pickers,
  Home's stale-grant fallback picker, and an accessible chronological recent-video playback queue.
- Mapped manual orientation options and full-sensor orientation; added turquoise/dark-text semantic prompt
  colors for player messages and layout hints. Added `rememberedMediaUri` and `showRemainingTime` defaults.
- Bumped the package version to `0.2.1` / derived code `201`; updated the product contract, checklist,
  architecture, design-system, decisions, known issues and changelog. The new EN/FA resource count is
  472 each.

**Checks run**
- `python3 tools/check_sources.py core app feature tools`: 118 files, 0 problems.
- `python3 tools/check_symbols.py`: 0 unresolved imports.
- `python3 tools/check_deps.py`: 16 modules, 0 missing dependencies.
- `python3 tools/check_resources.py core app feature`: 15 resource files, 0 problems.
- `git diff --check`: clean. Android compile/unit/instrumented tests have not been run; repository guidance
  says this sandbox has no JVM or Android SDK. CI is still required.

**Remains / honest limitations**
- CI must type-check Media3/Compose and run `assembleDebug`, `assembleRelease`, tests and lint; no code
  here should be called build-verified yet. No device/emulator pass has been done.
- Media3's `CueGroup` does not expose source track IDs. The controller associates cues when one selected
  soft track (or a one-cue-per-track match) makes that safe; ambiguous multiple-track cues are marked
  untagged and may appear in every layer assigned an embedded track. KNOWN_ISSUES 22 records the gap.
- The accessible previous/next queue is drawn from the most recent 40 videos with readable grants; the
  playlist sheet remains read-only. Subtitle source references currently remember the most recently
  opened video, not a full per-video table.

**How to test on device after CI is green**
1. Open a recent video, revoke its SAF grant, tap it again and verify the picker replaces the opaque IO error.
2. Add an external audio file, select embedded/external tracks, mute/unmute and remove the attachment.
3. In both subtitle tabs, check multiple soft tracks and external files, disable/remove one, then scrub and
   verify the picture and each active line update before release.
4. Arrange actions into all three docked zones; expand/collapse, scroll to the end, float/drag one, hide one.
5. Verify no buttons appear over the picture centre; tap the right clock; test sensor, portrait, landscape
   and reverse-landscape; inspect prompt contrast in dark/light themes and with reduce motion enabled.

## 2026-10-11 — releases publish themselves after CI is green

**Goal.** Stop releasing by hand. A change that passes every check should end up as installable APKs
on the Releases page without a tag push, and a branch build should be downloadable without an
Actions login (the artifact host is unreachable from the agent sandbox anyway).

**Done**
- `.github/workflows/auto-release.yml` (new): reacts to a successful CI run (`workflow_run`, push
  runs only) and to a manual dispatch. A `plan` job reads `versionName` at the tested commit and
  picks the channel — stable (`v<version>` on `main`, only while that tag does not exist) or dev
  (`dev-<branch>`, pre-release, assets replaced on every run). A `publish` job downloads the release
  APKs from the CI run that just passed, renames them, writes `SHA256SUMS`, moves or creates the tag
  and creates or updates the release. It rebuilds and signs instead of reusing when
  `SUBLEARN_KEYSTORE_BASE64` exists, and it deletes `dev-*` pre-releases whose branch is gone.
- `tools/release_notes.py`: `--channel`, `--branch`, `--commit`, `--signing`, `--build-url`. Dev
  notes say which commit they came from, that the next green build replaces them, and show the
  `[Unreleased]` changelog section; the signing paragraph now states the key that was actually used.
  The old positional call still works.
- `app/build.gradle.kts`: `versionName = "0.2.0"`, and `versionCode` derived from it (`200`) with a
  `SUBLEARN_VERSION_CODE` override, so a release bumps one number.
- `release.yml`: passes the signing state and the commit into the notes; the `v*` path is otherwise
  unchanged and still works for a hand-made release.
- Docs: D-28 (and D-17's permission note), `AGENTS.md` release section, README download/build
  sections, REQ-6 update, `CHANGELOG.md` `## [0.2.0]`.

**Remains**
- The keystore secrets (REQ-6). Until they exist every release is debug-signed, which the notes say.
- F-Droid metadata and an update check inside the app are still not planned work.

**Verified on the merge of PR #4 (2026-10-11)**
- CI run 38092802028 (`main`, merge commit `128a339`) went green; `Auto release` run 38093127642
  started by itself and published **v0.2.0** — `SubLearn-v0.2.0-{universal,arm64-v8a,armeabi-v7a,
  x86_64}.apk` (62.9 / 18.7 / 14.1 / 19.6 MB) plus `SHA256SUMS`, notes from the changelog section,
  marked latest, debug-signed and saying so. No tag was pushed by hand.
- Both jobs took about half a minute together, because the APKs came from the CI artifact: the build
  steps reported `skipped`, the download step `success`.
- The dev channel was checked the same way on this branch: CI run 38093233271 (`arena/7487aaef-sublearn`,
  commit `bbb22c0`) produced the pre-release `dev-arena-7487aaef-sublearn` —
  `SubLearn-0.2.0-dev-bbb22c0-<abi>.apk`, titled with the branch and the commit, notes saying the next
  green build replaces it, not marked latest. Pushing again to the same branch moves the tag, drops the
  assets of the previous commit and uploads the new ones.

**How to test it again**
- Push anything to `main` while `versionName` is already tagged: the same job refreshes the
  `dev-main` pre-release instead of creating a release.
- Push to a `phase/**` or `arena/**` branch: a `dev-<branch>` pre-release appears with that commit's
  APKs and is replaced on every later green run; it is deleted on the next run from `main` after the
  branch is gone.
- `workflow_run` only uses the default-branch copy of the workflow, so pipeline changes take effect
  once merged; a branch can also be released on demand with `gh workflow run auto-release.yml --ref
  <branch>` (that path rebuilds, because a manual dispatch has no CI run attached to it).

## 2026-10-10 — player rebuild (MX-style controls, two subtitle layers, gestures)

**Goal.** Rebuild the video player (`feature/player`, with `core/player` and the designsystem pieces it
needs) so it looks and behaves like MX Player's controls while meeting PLY-1..7, SUB-1..7, SHD-1..3 and
GEN-1..7. The old player had overlapping gesture layers, window effects that never restored, and
hard-coded colours and sizes.

**Done**
- `Controls.kt` (rewritten): top bar (back and title; audio, subtitles, decoder SW/HW/HW+, and More with
  PiP, share, rotation lock, layout mode, subtitle options, add subtitle file and settings); centre
  cluster (replay, play or pause, forward); bottom chrome (elapsed and total over a seek bar with a buffer
  track; previous subtitle, repeat block, next subtitle; speed, aspect, playlist and the subtitle list,
  where a long press toggles no-spoiler); lock button; quick-action dock (bar and floating; floating
  actions drag); layout-mode banner.
- `Hud.kt` (new): brightness and volume levels, seek readout, speed pill and double-tap bubbles, with
  reduce-motion honoured.
- `PlayerGestures.kt` (new): the single gesture surface and `PlayerHitRegistry`; multi-tap on words; left
  and right vertical drags; horizontal seek; double tap; two-finger speed; long-press speed; pinch.
- `SubtitleOverlay.kt` (rewritten): two layers with placement anchors and offsets, an outline in layout
  mode, and word styles (colour, background, decoration, bold, italic, alpha and scale) as annotations.
- `PlayerScreen.kt` (rewritten): fixed layer order; immersive bars, orientation and keep-screen-on are
  restored on leave; back closes the innermost open thing first; `ON_STOP` saves the position; every
  `PlayerIntent` is handled.
- `Sheets.kt` (rewritten): tracks, speed, aspect, decoder, playlist, per-layer options, subtitle tools and
  the subtitle list (right panel in landscape, below the picture in portrait). Choices are radio buttons
  for TalkBack.
- `PlayerViewModel.kt` (rewritten): `applyNormalizer` saves the tool settings and reloads the files in one
  coroutine; word states are computed off the main thread; no-spoiler hides only lines not yet reached.
- `Popups.kt`: the popup card blocks gestures; sizes are tokens.
- `core:designsystem`: new `Dimens` tokens; `SubLearnColors.subtitleText` and `letterbox`; 29 new
  strings in EN and FA (446 keys each).
- `core:settings`: `PlayerSettings.longPressSpeedPercent` (default 200), with a Settings row (100–400 %,
  step 25).
- `PlayerPopupsSmokeTest` updated to the four-field `SubtitleListRow`.

**Fixed defects from the old player**
- Layer drags ignored the stored vertical offset. Plates now position from the offset the ViewModel stores.
- The seek slider passed `fraction × duration − position` as a delta. Scrubbing now passes the target time.
- The floating quick-action drag handler was never attached. It is attached now.
- The gesture layer sat above the subtitle layers despite a comment saying otherwise. The order is explicit.
- System bars stayed hidden after lock or layout mode. They are restored when the screen leaves.
- Orientation was not restored on leave. It is restored.
- The language badge showed a cue count, not a language. It is removed.
- Subtitle tools could reload files with the previous settings (a race). `applyNormalizer` fixes it.
- Player messages and quick-action labels were English literals. They are resources now.

**Deviations at v0.2.0** (recorded in DECISIONS D-26 and KNOWN_ISSUES 19–20)
- Play and pause sat in the centre cluster, not the bottom row; **superseded by the v0.2.1 PLY-3 redesign** above.
- The repeat-block hold runs the auto-repeat count but does not invert the setting until release.
- In portrait the subtitle list takes the lower 45 % of the screen and the picture keeps the rest.
- Playlist rows are read-only.

**CI change.** The build job timeout went from 30 to 60 minutes. On this branch the first full
`assembleDebug assembleRelease test lintDebug assembleDebugAndroidTest` ran past 30 minutes with a cold
Gradle cache (the green run on the previous branch took 12 minutes in that step), so the job was
cancelled before it could report compile errors.

**CI history for this entry.** The sandbox has no JVM or Android SDK, so CI was the first compiler. The
first run failed on compile errors in `feature:player` (a delegate missing its import, a missing
`waitForUpOrCancellation` import, a nonexistent `Offset.getDistance`, a scope error, two missing imports).
The second run failed on one lint error (an unstable Media3 API, now removed, because `core:player`
already hides the subtitle view). The third run, commit `494a8e6`, is green: static checks,
`assembleDebug`, `assembleRelease`, `test`, `lintDebug` and `assembleDebugAndroidTest` all pass.

**Not verified**
- Nothing has run on a device or an emulator. The instrumented smoke test compiles but has not been run.
- The behaviour of gestures, picture-in-picture, rotation and word hit testing on real glyphs is reviewed,
  not tested on hardware (KNOWN_ISSUES 21).

**How to test by hand** (once CI is green): open a video with an external SRT; check the top bar, the centre
cluster, the bottom chrome and the lock; double-tap left and right; two-finger swipe up; long-press a word
for the multi-tap popups; open the list in landscape and in portrait; rotate with the lock on; press back
with a sheet open; leave the app and come back.

## 2026-10-09 — full review, first compile of the pure-JVM modules, release pipeline (v0.1.0)

**Goal.** Review every module against the docs, fix what the first compile and a line-by-line Compose
review turned up, and make GitHub Actions build release APKs for `arm64-v8a`, `armeabi-v7a`,
`x86_64` and a universal one, attached to a GitHub Release.

**Done**

- Diagnosed why every CI run on `main` was red before a single Kotlin file compiled:
  `:app:checkDebugAarMetadata` failed on the nonexistent `com.google.mlkit:language-id:16.5.5`
  (`core:translate`). Removed; language identification stays a LATER stub.
- Built a local kotlinc 2.0.21 harness (JRE from the `jdk4py` wheel, compiler from npm, serialization
  jars from the `kotlin-jupyter-kernel` wheel — the only reachable sources) and ran every pure-JVM test
  class: 97 pass / 19 fail at the start, 122 pass / 0 fail / 1 skipped after the fixes listed in
  `CHANGELOG.md` → 0.1.0 → "Review fixes" (subtitles, settings, lexicon, common, data, translate, ai).
- Hand-reviewed the Android-only modules (`core:player`, `core:security`, `core:designsystem`, all
  `feature:*`, `app`) with a checklist of Compose mistakes that do not survive `kotlinc`; the fixes
  are in the same CHANGELOG section. Highlights: `dynamicDarkColorScheme` crash on API < 31, the ML
  Kit provider built on APIs that do not exist, composable calls inside click lambdas, `align` outside
  a `Box`, `PlayerViewModel.open()` wiping the running video on every re-entry.
- Release pipeline: ABI splits + universal, R8 shrink without obfuscation, optional secrets-driven
  signing (`REQ-6`), `release.yml` (tag or manual, `contents: write` only there), `tools/release_notes.py`,
  CI builds `assembleRelease` and publishes the "Gradle failure report" check run
  (`tools/ci_failure_report.py`) because the log and artifact hosts are unreachable from the sandbox.
  Decisions D-21…D-24.
- Docs: CHANGELOG 0.1.0 section, KNOWN_ISSUES 1, 4, 15–18, REQ-3/REQ-6 updates, README download table,
  CHECKLIST PLY-7 corrected to partial.

**Remaining / in progress**

1. **CI is green on `arena/956a666c-sublearn`** (run 37854997366, 2026-10-09: the first green run of
   this repository — four red→green rounds fixed missing imports, a `RowScope` receiver, the test
   classpath BOM and three lint errors, all read through the failure-report check run).
   **v0.1.0 is published**: tag `v0.1.0` on the branch head (`f32ae6f`) ran `release.yml` (run
   37856428079) and <https://github.com/animalrender/SubLearn/releases/tag/v0.1.0> carries
   `arm64-v8a` (18.6 MB), `armeabi-v7a` (14.1 MB), `x86_64` (19.6 MB), `universal` (62.8 MB) and
   `SHA256SUMS`, debug-signed. `workflow_dispatch` could not be used because GitHub only registers
   workflows that exist on the default branch; once `main` has `release.yml`, later tags can be cut by
   hand from the Actions tab. PR #2 takes this branch to `main`.
2. Device pass (REQ-7): nothing in this entry has run on a phone. Install the `arm64-v8a` APK from
   the release, walk the smoke path below, and file what breaks under KNOWN_ISSUES.
3. KNOWN_ISSUES 15–17: English literals in view models, floating quick-action drag, one file per layer.
4. Phase 9 proper: view-model tests on `FakePlayerController` (REQ-5), animation/accessibility pass,
   README screenshots.

**How to test right now**

```bash
python3 tools/check_sources.py core app feature tools && python3 tools/check_symbols.py \
  && python3 tools/check_deps.py && python3 tools/check_resources.py core app feature
gh run list --branch arena/956a666c-sublearn
gh api repos/animalrender/SubLearn/commits/<sha>/check-runs --jq '.check_runs[] | select(.name | test("failure report")) | .output.text'
gh release view v0.1.0          # four APKs + SHA256SUMS once the release workflow has run
```

## 2026-10-08 — phases 0–8 implemented on one branch, CI is the first compiler

**Goal.** Take the repository from a `hello world` commit to the full NOW scope: module scaffold, core
layers, the player with dual subtitles, interaction, shadowing, learning popups, the AI button, subtitle
tools and the app shell — with CI, repository-hygiene checks and docs, and `main` never broken.

**Done**

- 16 Gradle modules, version catalog (AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.12.01, Media3 1.5.1,
  Room 2.6.1, Koin 3.5.6, ML Kit translate 17.0.3), minSdk 26, compile/target 35, `.gitignore` covering
  build outputs, signing material and every forbidden data/model file.
- CI: `ci.yml` (static checks → `assembleDebug test lintDebug assembleDebugAndroidTest --continue`, debug
  APK + lint/test/log artifacts, a hygiene guard that fails on a tracked `.sqlite`/`.bipe`/keystore
  or a literal key) and a manual `instrumented.yml` emulator job. Actions pinned by SHA,
  `persist-credentials: false`, read-only token.
- `core:*`: parsers + `SubtitleFormat` + charset sniffing + normalizer + block builder; typed versioned
  settings with JSON export/import; `PlayerController` (+Media3, +`FakePlayerController`) and SAF
  contracts; ML Kit translation with model-download state and cache; AI providers with prompt builder and
  answer parser; Keystore AES-GCM secret store; word-level providers and frequency importer; Room for My
  Words, recents and the translation cache; design system tokens/motion/theme/backdrop/badge and the
  single EN+FA string owner (417 keys each).
- `feature:*`: the whole player screen (layers, hit testing, gestures, quick actions, layout mode, list
  panel, sheets, popups, AI sheet, shadowing), the searchable settings tree, My Words, the Learn tab,
  Home with recents and open-a-file/open-a-url; `app` with Koin, intent filters, PiP and locale override.
- 18 unit-test classes for the pure and Android-adjacent layers, one instrumented smoke test for the
  overlays that need real font measurement.
- Docs: `AGENTS.md`, `CLAUDE.md`, `.github/copilot-instructions.md`, `README.md`, `CHANGELOG.md` and the
  twelve `docs/*` files.
- Fixed along the way: `SubtitleFormat` was referenced but never declared (now declared + tested);
  `TranslationKey` had no package line; `app` imported `subLearnModules` from a nonexistent package;
  `core:data` declared the nonexistent `androidx.room:room-fts` artifact; the placement nudge helper had
  a bogus third parameter; five quick-action ids did not exist (`AI`, `ASPECT`, `TRACKS`, `LOCK`,
  `TRANSLATE_BLOCK` → real ids, and the unwired ones now open the right sheet); the player's UI state
  file was lost in a workspace reset and has been reconstructed from every consumer's usage
  (`feature/player/.../PlayerUi.kt`).

**Remaining / in progress**

1. **Green CI.** `gh run list` on `arena/49da4955-sublearn`; the run is the first real compile of ~150
   files, so expect fix-up commits (`--continue` publishes every module's errors as check-run
   annotations, which is how the agent reads them — see REQ-3).
2. Phase 9 hardening: view-model tests on `FakePlayerController`, device pass (RTL, animation, PiP,
   process death, gestures), README screenshots, tag `v0.1.0`.
3. LATER items stay stubs by design; the only permitted promotions are the three ordered stretch items,
   after NOW is green (see [EXTENSION_POINTS.md](EXTENSION_POINTS.md)).
4. Open owner questions are listed in [AGENT_REQUESTS.md](AGENT_REQUESTS.md), each with the
   workaround used in each case.

**How to test right now**

```bash
# no JVM here: static checks first, CI compiles
python3 tools/check_sources.py core app feature tools
python3 tools/check_symbols.py
python3 tools/check_deps.py
python3 tools/check_resources.py core app feature
gh run list --branch arena/49da4955-sublearn   # tools/ci_report.py turns a red run's log into annotations

# on a machine with JDK 17 + SDK 35
./gradlew assembleDebug test lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Manual smoke path on a device: open a local `.mp4` with a same-named `.srt` (or pick one), confirm both
layers render, tap a word (translation card, playback pauses if the line is about to change), hold the
translation toggle to invert it, open the subtitle list and seek by tapping a row, turn on repeat-block
and stop-at-end, add a word to My Words and see it styled, open Settings → Fonts and change only the
learning surface, then Settings → About → export and re-import the JSON, and finally put the app in PiP
and rotate. With no network: the same, plus translation only for a previously downloaded model.

## 2026-10-17 — file names, model diagnostics and autoplay (D-88..D-90)

- Read the agent handbook and product/architecture, design, decisions and current verification docs.
- Replaced SAF document-ID labels with shared DISPLAY_NAME resolution, including remembered subtitle
  loads and sidecar matching. Added provider-backed Robolectric regressions for numeric URIs, missing
  metadata, local files and canceled picker results.
- Fixed the safetensors header endianness and the misleading big-endian test fixtures. Added tensor
  shape/range checks before allocation and regressions for truncated weights and inconsistent shapes.
- Downloads model tests now bind a non-exported, disposable process. Free-memory checks, exception
  reports, binder-death reporting, timeout and unbind cleanup protect the main UI from test failures.
  Added a throwing-loader regression; actual native abort/OOM containment still needs device testing.
- Player explicitly autoplays and offers a localized, non-blocking restart action only on saved-position
  resumes. First-time opens do not ask; the action expires without stopping playback.
- Local source, symbol, dependency and EN/FA resource checks passed. Android SDK/JVM are absent locally;
  CI build/unit tests/lint and real-device tests are pending at this entry's creation.
- Follow-up audit: SmolLM2 is a `.litertlm` artifact incorrectly routed to MediaPipe. New downloads
  are now explicitly unavailable with EN/FA explanation, and installer/test/native loader refuse it
  before native code. Added a regression ensuring the loader is never called for this asset. Enabling
  it requires a separate LiteRT-LM engine or a verified `.task`; no unsupported runtime is faked.
- Added client-side Robolectric regressions for service death, refused binding, timeout and coroutine
  cancellation, plus a picker-recreation regression. Picker contracts receive application context at
  construction so a pending result after Activity recreation can still resolve provider metadata.
