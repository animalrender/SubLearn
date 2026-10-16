# Changelog

All notable changes to SubLearn are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses semantic versioning.
Unreleased work lands on phase branches; `main` only ever contains merged, CI-green phases.

[Unreleased]: https://github.com/animalrender/SubLearn/compare/v3.0.0...HEAD
[3.0.0]: https://github.com/animalrender/SubLearn/compare/v0.2.1...v3.0.0
[0.2.1]: https://github.com/animalrender/SubLearn/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/animalrender/SubLearn/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/animalrender/SubLearn/releases/tag/v0.1.0

## [3.0.0] - 2026-10-16

### Fixed
- The offline translation model now downloads through ML Kit's own API everywhere. The hand-written
  installer that unpacked Google's archive into a directory the SDK never reads — the cause of
  "missing translation_model.pb" while the UI said "installed" — is gone, and the download no
  longer requires Wi-Fi.
- The offline packs download again: the catalog carried a size and a SHA-256 that did not match the
  published assets, so every download ended in "SHA-256 mismatch".
- The accuracy models are installed by the installer that fetches every file a model needs; the
  library screen used the pack downloader, which left them unloadable.
- The accuracy profiles work: the guard compared the profile's floor with the app's heap, so every
  choice was downgraded and the profiles never ran. The advice now uses the phone's own memory,
  storage and installed models, and the profile names are real names in both languages.

### Changed
- Settings has one downloads section instead of two: "Library & Resources" and "Models & Downloads"
  are now **Settings → Downloads**, holding the offline translation model, the offline packs, the
  accuracy models and the accuracy profile, with one state per item.
- The translation settings keep their behaviour and point at the downloads screen; the player popup
  starts the same download.
- The version lives in the `VERSION` file and every release to `main` bumps it by a patch
  (3.0.0 → 3.0.1 → 3.0.2). `versionCode` is derived from the name.

### Added
- A check that compares the app's bundled catalog with the published one field by field, and a CI
  step that asks GitHub for the digest of every published asset, so a stale catalog fails the build
  instead of the download.
- Profile advice in the downloads screen: what each profile still needs, and the strongest profile
  the device can run.

## [Unreleased]

### Fixed
- Settings entry crash from an unresolved Koin downloader-function dependency.
- Decorative stop/tick dots on progress bars and slider tracks throughout the app.
- Localized settings search, nested back handling and section/search restoration.
- Duplicate Settings/My Words toolbars and stale subtitle styling after level/mode changes.

### Changed
- Learn now shows only Coming soon for browser/PDF/image learning; onboarding, resources and accuracy
  profiles moved to Settings → Downloads, separate from Settings → Player learning.

### Added
- Passive offline above-level word/phrase cards in the player Learning mode, with strict level/known
  filters, bounded corner layout and reduced-motion-aware rise/fade. No automatic cards in Entertainment.
- Production Settings graph regression test, selection unit tests and card visibility device smoke test.

## [0.2.1] - 2026-10-11

### Player

- Changed: moved play/pause, previous/next video and subtitle-cue navigation into a distinct,
  horizontally scrollable bottom transport row; the video centre is no longer covered by a control
  cluster. The bottom seek line now places elapsed and total/remaining time on one row, makes the right
  clock toggle total versus remaining, and previews seeks while dragging.
- Added: top-toolbar, second-row and playback-row quick-action docks, a dock/ordering manager, expanded
  labels, scroll cues, safer floating bounds, and reduce-motion-aware fades for the chrome.
- Added: Learning/Translation subtitle tabs with per-track checkboxes, simultaneous external subtitle
  files, enable/remove controls, a separate audio surface with embedded-track selection, external
  audio-file merging, player volume and mute.
- Fixed: persist SAF read grants for video/subtitle/audio documents; Recent Videos routes stale local
  grants through the picker. Previous/next video actions use the accessible recent-video queue.
- Changed: player status and layout prompts use a translucent turquoise surface and dark foreground;
  sensor orientation now follows the full device sensor rather than landscape-only.

### Settings, design and verification

- Added: remaining-time preference and current-media subtitle association, plus EN/FA labels for the
  new controls and surfaces (472 keys in each locale).
- Bumped `versionName` to `0.2.1` (`versionCode` derives as `201`). Static repository checks pass;
  Android compilation, tests, and the device pass are pending CI/hardware access.

## [0.2.0] - 2026-10-11

The rebuilt player, and a release that publishes itself: once CI is green on `main`, the APKs are
attached to a GitHub Release without anyone pushing a tag.

### Release pipeline

- Added: `.github/workflows/auto-release.yml` — reacts to a successful CI run (`workflow_run`) and
  publishes the APKs. On `main` with a `versionName` that has no tag yet it creates the stable
  release `v<version>`; every other green push (`main` again, `phase/**`, `arena/**`) refreshes a
  rolling pre-release tagged `dev-<branch>`. Pre-releases of deleted branches are cleaned up on the
  next run from `main`; `v*` releases are never touched by it.
- Added: the published APKs are the ones CI already built and checked — the workflow downloads the
  CI artifact instead of building a second time. When the `SUBLEARN_KEYSTORE_BASE64` secret exists
  it rebuilds the commit instead, so a signed release never needs the key inside CI (REQ-6).
- Changed: `versionCode` is derived from `versionName` (`0.2.0` → `200`), overridable with
  `SUBLEARN_VERSION_CODE`, so a release bumps exactly one number.
- Changed: `tools/release_notes.py` takes `--channel`, `--branch`, `--commit`, `--signing` and
  `--build-url`; the notes now say which commit was built and which key signed it instead of
  hedging, and a dev build shows the `[Unreleased]` section as "what is new so far".
- Changed: `release.yml` (the `v*` tag path) passes the signing state into the notes. Its behaviour
  is otherwise unchanged and it stays the way to publish a tag by hand.

### Player

- Changed: the video player was rebuilt around MX-style controls — top bar (audio, subtitle and
  decoder pickers, PiP, share, rotation lock, layout mode), a centred replay/play/forward cluster,
  a bottom row with the seek bar, subtitle steps and repeat block, a lock button and the
  quick-action dock.
- Added: `Hud.kt` (brightness, volume, seek, speed and double-tap feedback) and `PlayerGestures.kt`
  with a single gesture surface and `PlayerHitRegistry`, so taps on words, drags, double taps,
  two-finger speed and pinch no longer fight each other (D-25).
- Added: `PlayerSettings.longPressSpeedPercent` (100–400 %, default 200) with its Settings row, and
  29 new EN/FA strings (446 keys per language).
- Fixed: layer drags ignored the stored vertical offset; the seek slider passed a wrong delta; the
  floating quick-action drag handler was never attached; the gesture layer sat above the subtitle
  layers; system bars and orientation were not restored when the screen left; subtitle tools could
  reload with the previous settings; player messages were English literals.

## [0.1.0] - 2026-10-09

First tagged build. Everything below was built and unit-tested by GitHub Actions; no device pass has
happened yet, so treat it as a technical preview (see `docs/KNOWN_ISSUES.md`).

### Release pipeline

- Added: `.github/workflows/release.yml` — runs on a `v*` tag or by hand (`workflow_dispatch` with
  `tag`), checks that the tag matches `versionName`, builds `assembleRelease`, renames the APKs to
  `SubLearn-<tag>-<abi>.apk`, writes `SHA256SUMS` and creates (or updates) the GitHub Release with
  notes generated by `tools/release_notes.py` from this file. Only this workflow has `contents: write`.
- Added: release builds are split per ABI (`arm64-v8a`, `armeabi-v7a`, `x86_64`) plus a universal
  APK; R8 shrinks resources and code but does not obfuscate, so stack traces stay readable without a
  mapping file. `app/proguard-rules.pro` keeps the kotlinx.serialization serializers.
- Added: optional release signing from `SUBLEARN_KEYSTORE_*` environment variables / repository
  secrets; without them the build falls back to the debug key (REQ-6 in `docs/AGENT_REQUESTS.md`).
- Changed: CI now also runs `assembleRelease` and `assembleDebugAndroidTest` on every push, uploads
  the release APKs as an artifact and publishes the full Gradle failure report as a check run
  (`tools/ci_failure_report.py`) so agents without log access can read the first compiler errors.
- Added: `tools/check_resources.py` to the documented static-check set.

### Review fixes (first full review; pure-JVM modules compiled and tested with a kotlinc harness)

- Fixed: dependency resolution failed on every CI run (`:app:checkDebugAarMetadata`) — `core:translate`
  declared the nonexistent `com.google.mlkit:language-id:16.5.5`; language identification is a LATER
  feature and the dependency is gone. `tools/check_deps.py` maps `com.google.android.gms.*` to
  `mlkit-translate`.
- Fixed: `core:subtitles` — UTF-16 files were decoded with the BOM still inside and the endianness
  heuristic inverted; the UTF-16 check now runs before strict UTF-8. `blockAt` before the first block
  returned that block; the block builder only merges cues that continue a sentence
  (`continuesSentence`) and renumbers ids sequentially; the normalizer's punctuation-spacing rules
  no longer break `3.5`, `10:30` or `e.g.` and know the Persian marks; line splitting was rewritten
  to cut only between words; SRT export writes comma milliseconds; sidecar auto-loading ignores
  `.txt`; the tokenizer treats the curly apostrophe like the straight one.
- Fixed: `core:settings` — `QuickActions.visibleIn(HIDDEN)` returned every action; import reports
  the sections actually present in the file and lists unknown ones as dropped.
- Fixed: `core:lexicon` CEFR bands for `word,frequency` lists (B1/B2 thresholds); `core:common`
  `SubLearnError.from` maps `NotImplementedInThisBuild`, `TimeUtils.formatTimestamp` pads hours and
  takes the millisecond separator; `core:data` FTS join query and match-string quoting; the My Words
  normalizer replaces ZWNJ before trimming.
- Fixed: `core:translate` — the ML Kit provider called model-manager APIs that do not exist in
  ML Kit Translate 17 (`TranslationModelManager`, `Translation.getClientManager`,
  `isModelDownloaded(source, target)`); rewritten on `RemoteModelManager` / `TranslateRemoteModel`
  with the same `Task`→coroutine bridge (progress is coarse, see KNOWN_ISSUES 18). The service now
  completes its in-flight deferred when a request fails; `core:ai` default prompt template had a
  literal `${'$'}` and the answer parser lost text before the first heading and merged paragraphs
  after the last one.
- Fixed: `core:designsystem` crashed on API 26–30 by calling `dynamicDarkColorScheme` without the
  `SDK_INT >= S` guard that lint flagged; `core:player` software decoder mode now really prefers
  software codecs.
- Fixed: app shell — missing imports, Koin bindings declared on the `TranslationProvider` /
  `HttpJsonClient` interfaces, `MlKitTranslationProvider(logger = get())`, layout direction for the Persian UI,
  `AppScaffold` version label, `openUrl` from a non-activity context (`FLAG_ACTIVITY_NEW_TASK`).
- Fixed: player feature — `PlayerViewModel.open()` keeps the current video when the screen is
  re-entered (and loads a file picked in Settings into an empty layer), clears layers and remembered
  file keys when another video opens, resumes playback after the AI sheet closes (`AiUi.wasPlaying`),
  `cancelAi`; Compose fixes in `Controls`, `PlayerScreen`, `Sheets`, `Popups`, `SubtitleOverlay`,
  `Gestures` (`align` outside `Box`, composable calls inside non-composable lambdas, missing gesture
  imports, negative slider `steps`, nullable colours in `SpanStyle`) and in `PlayerPopupsSmokeTest`.
- Fixed: settings feature — `ChoiceRow` label composable, `Context.MODE_PRIVATE`, decoder names
  shared with `core:settings` (`HARDWARE_PLUS`), `FontEditor`, indeterminate model-download
  progress, `externalFileKeys` editing, level state; My Words — filter chips bound to real state and
  a `combine`d filter in `WordsViewModel`, auto-translate guard; `HomeScreen` referenced an undefined
  `viewmodel`.
- Fixed (first CI compile of the Android modules): missing `Star`/`Share` icon and `background`
  imports, `WindowInsetsCompat.Type.systemBars()`, a duplicate `ExperimentalMaterial3Api` import that
  hid `ModalBottomSheet`, `TabEntry` as a `RowScope` extension so `NavigationBarItem` resolves; the
  Compose BOM is declared on the test configurations too (AGP's `androidTestImplementation` does not
  inherit the platform, so `ui-test-junit4` resolved with no version).
- Fixed (lint): the backup rules named a `sharedpref` domain the app never uses and excluded paths that
  were not included; they now cover `files/datastore` (minus the secrets file) and the Room database.
  `windowLayoutInDisplayCutoutMode` lives in `values-v27`; the HLS mime type in the stream intent
  filter is lower-case.
- Fixed: Windows-1256 subtitle files decode the Arabic yeh/kaf to the Persian yeh/keheh, so a legacy
  Persian file produces the same words as a UTF-8 one (the code page cannot store U+06CC/U+06A9).

### Phase 0 — Foundations

- Added: 16-module Gradle project (app, ten `core:*`, five `feature:*`) with a version catalog,
  Gradle 8.11.1 wrapper, AGP 8.7.3, Kotlin 2.0.21, compile/target SDK 35, minSdk 26.
- Added: GitHub Actions CI (`build, test, lintDebug, assembleDebug`, debug APK artifact, lint and test
  report artifacts) plus a repository hygiene guard that fails on a tracked third-party database, model
  file, keystore or literal API key, and a manual instrumented workflow for the emulator run.
- Added: static check tools for sandboxes without a JVM — `tools/check_sources.py`,
  `tools/check_symbols.py`, `tools/check_deps.py`.
- Added: design system (tokens, motion specs, typography, theme with light/dark/AMOLED and an accent
  list, `SubtitleBackdrop`, `BadgePill`) and the single EN/FA string owner (417 keys per locale).
- Added: typed versioned settings schema (`AppSettings`, SCHEMA_VERSION 3) with DataStore storage and
  JSON export/import; Keystore-backed secret store; `AppResult`/`SubLearnError` conventions.
- Added: subtitle engine — SRT/WebVTT/ASS parsers, `SubtitleFormat` detection, charset sniffing
  (UTF-8/UTF-16/Windows-1256), normalization pipeline, block builder with binary lookup; 18 unit-test
  classes covering parsers, normalizer, blocks, charset, shadowing math, settings and repositories.
- Added: interfaces and `NotImplemented` stubs plus feature flags for every LATER item
  (YouTube, PDF/OCR, dictionary import, quiz, level auto-detection, POS analysis, speech-to-text,
  on-device AI, update checker).
- Docs: `AGENTS.md`, `README.md` and the `docs/*` set (brief, product spec, references, architecture,
  design system, decisions, phases, checklist, extension points, agent requests, known issues,
  progress).

### Phase 1 — Player, subtitles and interaction

- Added: `PlayerController` abstraction with a Media3 implementation and `FakePlayerController`.
- Added: player screen — dual subtitle layers with per-word hit testing (RTL/bidi correct), gesture
  layer with configurable priority and remapping, quick actions (dock, floating, hidden) with
  resize/reorder, layout mode, subtitle list panel with search and no-spoiler, tracks/speed/aspect/
  decoder/tools/playlist sheets, translation popups, AI answer sheet with its pause/resume contract,
  shadowing repeat plans and stop-at-block-end.
- Added: settings tree with search (appearance, player, subtitles per layer, fonts per surface and
  role, gestures, shadowing, learning, AI, prompt editor, translation, dictionary, quick actions,
  about with export/import/reset).
- Added: My Words screen (status, notes, jump back to the line) and the Learn tab.
- Added: app shell — Koin graph, single activity, intent filters for video/subtitle/stream URIs,
  PiP handoff, orientation and brightness intents, locale override.
- Fixed: `core:data` declared the nonexistent `androidx.room:room-fts` artifact (FTS4 ships inside
  `room-runtime`), which would have broken dependency resolution; removed with the two unused catalog
  aliases.
