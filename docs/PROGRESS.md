# Progress

Always answers: what was the goal, what is done, what remains, how to test it. Newest entry on top.

## 2026-10-08 — phases 0–8 implemented on one branch, CI is the first compiler

**Goal.** Take the repository from a `hello world` commit to the full NOW scope: module scaffold, core
layers, the player with dual subtitles, interaction, shadowing, learning popups, the AI button, subtitle
tools and the app shell — with CI, license audit and docs, and `main` never broken.

**Done**

- 16 Gradle modules, version catalog (AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.12.01, Media3 1.5.1,
  Room 2.6.1, Koin 3.5.6, ML Kit translate 17.0.3), minSdk 26, compile/target 35, `.gitignore` covering
  build outputs, signing material and every forbidden data/model file.
- CI: `ci.yml` (static checks → `assembleDebug test lintDebug assembleDebugAndroidTest --continue`, debug
  APK + lint/test/log artifacts, license-hygiene guard that fails on a tracked `.sqlite`/`.bipe`/keystore
  or a literal key) and a manual `instrumented.yml` emulator job. Actions pinned by SHA,
  `persist-credentials: false`, read-only token.
- `core:*`: parsers + `SubtitleFormat` + charset sniffing + normalizer + block builder; typed versioned
  settings with JSON export/import; `PlayerController` (+Media3, +`FakePlayerController`) and SAF
  contracts; ML Kit translation with model-download state and cache; AI providers with prompt builder and
  answer parser; Keystore AES-GCM secret store; word-level providers and frequency importer; Room for My
  Words, recents and the translation cache; design system tokens/motion/theme/backdrop/badge and the
  single EN+FA string owner (418 keys each).
- `feature:*`: the whole player screen (layers, hit testing, gestures, quick actions, layout mode, list
  panel, sheets, popups, AI sheet, shadowing), the searchable settings tree, My Words, the Learn tab,
  Home with recents and open-a-file/open-a-url; `app` with Koin, intent filters, PiP and locale override.
- 18 unit-test classes for the pure and Android-adjacent layers, one instrumented smoke test for the
  overlays that need real font measurement.
- Docs: `AGENTS.md`, `CLAUDE.md`, `.github/copilot-instructions.md`, `README.md`, `LICENSE` (Apache-2.0),
  `THIRD_PARTY_NOTICES.md`, `CHANGELOG.md` and the twelve `docs/*` files.
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
4. Open owner questions are listed in [AGENT_REQUESTS.md](AGENT_REQUESTS.md) (REQ-1…REQ-9) with the
   workaround used in each case.

**How to test right now**

```bash
# no JVM here: static checks first, CI compiles
python3 tools/check_sources.py core app feature tools
python3 tools/check_symbols.py
python3 tools/check_deps.py
gh run list --branch arena/49da4955-sublearn

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
