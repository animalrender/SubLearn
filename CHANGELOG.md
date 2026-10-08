# Changelog

All notable changes to SubLearn are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses semantic versioning.
Unreleased work lands on phase branches; `main` only ever contains merged, CI-green phases.

## [Unreleased]

### Phase 0 — Foundations (in review, PR #1)

- Added: 16-module Gradle project (app, ten `core:*`, five `feature:*`) with a version catalog,
  Gradle 8.11.1 wrapper, AGP 8.7.3, Kotlin 2.0.21, compile/target SDK 35, minSdk 26.
- Added: GitHub Actions CI (`build, test, lintDebug, assembleDebug`, debug APK artifact, lint and test
  report artifacts) plus a license/hygiene guard that fails on a tracked third-party database, model
  file, keystore or literal API key, and a manual instrumented workflow for the emulator run.
- Added: static check tools for sandboxes without a JVM — `tools/check_sources.py`,
  `tools/check_symbols.py`, `tools/check_deps.py`.
- Added: design system (tokens, motion specs, typography, theme with light/dark/AMOLED and an accent
  list, `SubtitleBackdrop`, `BadgePill`) and the single EN/FA string owner (418 keys per locale).
- Added: typed versioned settings schema (`AppSettings`, SCHEMA_VERSION 3) with DataStore storage and
  JSON export/import; Keystore-backed secret store; `AppResult`/`SubLearnError` conventions.
- Added: subtitle engine — SRT/WebVTT/ASS parsers, `SubtitleFormat` detection, charset sniffing
  (UTF-8/UTF-16/Windows-1256), normalization pipeline, block builder with binary lookup; 18 unit-test
  classes covering parsers, normalizer, blocks, charset, shadowing math, settings and repositories.
- Added: interfaces and `NotImplemented` stubs plus feature flags for every LATER item
  (YouTube, PDF/OCR, dictionary import, quiz, level auto-detection, POS analysis, speech-to-text,
  on-device AI, update checker).
- Docs: `AGENTS.md`, `README.md`, `LICENSE` (Apache-2.0), `THIRD_PARTY_NOTICES.md` and the `docs/*`
  set (brief, product spec, references, architecture, design system, decisions, phases, checklist,
  extension points, agent requests, known issues, progress).

### Phase 1 — Player, subtitles and interaction (in review, same branch)

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
