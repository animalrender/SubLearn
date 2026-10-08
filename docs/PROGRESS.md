# Progress

Always answers: what was the goal, what is done, what remains, how to test it. Newest entry on top.

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
   classpath BOM and three lint errors, all read through the failure-report check run). Next:
   `gh workflow run release.yml --ref arena/956a666c-sublearn -f tag=v0.1.0` creates the tag and the
   release, then the PR to `main`; the outcome is recorded here.
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
  single EN+FA string owner (417 keys each).
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
