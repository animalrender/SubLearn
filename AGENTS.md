# SubLearn — agent handbook

Start here. This file is the entry point for any agent (Claude Code, Copilot, Cursor, a human)
working on this repository. Read the linked doc for depth; do not guess.

## What the product is

An Android video player for learning English from real subtitles: dual subtitle layers, tap to
translate a word/line/block, shadowing and repeat tools, your own word list, and an optional AI
helper for hard lines. UI language English, translation language Persian (RTL) by default, other
languages structurally supported but not built. Full requirements:
[docs/PRODUCT_SPEC.md](docs/PRODUCT_SPEC.md) (every requirement has an ID like `PLY-6`, `SUB-4`,
`SHD-2`; that file is the contract). Status of each ID: [docs/CHECKLIST.md](docs/CHECKLIST.md).

## Non-negotiable rules

1. **License safety.** Never copy code from the GPL references (`yall-mp`, `jidoujisho`); re-implement
   ideas independently. Never commit a third-party dictionary database, `.bipe` file, extracted
   translation model, or anything derived from them. Translation is official ML Kit only. Every
   dependency, font, icon and asset gets a verified permissive license in
   [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md); if you cannot verify it, do not ship it and log
   the question in [docs/AGENT_REQUESTS.md](docs/AGENT_REQUESTS.md).
2. **No fake success.** No demo data in shipped paths, no silent stubs, no `TODO`. An unfinished item
   is either finished or written down in `docs/KNOWN_ISSUES.md` / `docs/AGENT_REQUESTS.md`.
3. **main always builds.** One branch per phase (`phase/N-name`), small conventional commits, one PR
   per phase, merge only at Definition of Done. If you are blocked, keep the branch green (feature
   flag or revert the partial work), log it, and move to the next independent task.
4. **Every PR updates the docs it affects**, and [docs/PROGRESS.md](docs/PROGRESS.md) must always say
   what is done, what remains and how to test it.
5. **No secrets**: no API keys, keystores, `local.properties` or build outputs. Keys go through
   `core:security`; CI fails the build on a tracked secret or forbidden file.

## The build reality in this sandbox

There is **no JVM here**, so `./gradlew` cannot run. GitHub Actions is the only compiler. Before
pushing, run the static checks that catch the cheap mistakes:

```bash
python3 tools/check_sources.py core app feature tools   # brace balance, 140 columns, no FIXME
python3 tools/check_symbols.py                          # com.sublearn imports resolve, and every
                                                        # repo type used is imported
python3 tools/check_deps.py                             # no module imports an AndroidX package it
                                                        # does not declare, and no cross-module import
                                                        # without a project() dependency
python3 tools/check_resources.py core app feature       # aapt2 text rules: escapes, placeholders,
                                                        # duplicates, translations without a default
```

These are name and shape checks, **not** type checking. They will not catch a wrong argument type, a
non-exhaustive `when` or a missing `@Composable`. After writing code in a module, also grep the
symbols you assumed: enum entry names, data class field names and function arities in `core:*` are
easy to get wrong from memory. Then push, watch `gh run watch`, and fix until green. Report honestly
that local compilation was not possible.

Two routes exist beyond the static checks (both documented in `docs/AGENT_REQUESTS.md`, REQ-3):

- The pure-JVM modules (`core:common`, `core:subtitles`, `core:settings`, `core:lexicon`, the logic
  in `core:data`/`core:translate`/`core:ai`) compile with a plain `kotlinc`; a JRE (`jdk4py` wheel on
  pypi) and `kotlin-compiler` (npm) are reachable from the sandbox, Gradle and the Android SDK are not.
- The Actions log and artifact hosts are **not** reachable. CI therefore publishes the first Gradle
  errors as the "Gradle failure report" check run (`tools/ci_failure_report.py`); read it with
  `gh api repos/<owner>/<repo>/commits/<sha>/check-runs` → `.output.text`. Do not loop on
  `gh run view --log` / `gh run download`; they fail here.

Releases: `release.yml` builds `assembleRelease` (one APK per ABI plus universal, R8 shrink without
obfuscation, debug-signed unless the `SUBLEARN_KEYSTORE_*` secrets exist) and attaches them to the
GitHub Release for a `v*` tag; the tag must equal `versionName` in `app/build.gradle.kts`. See D-21.

## Module map and boundaries

```
app                 single activity, Koin graph, intent filters, navigation, PiP handoff
core:common         AppResult, SubLearnError, TimeUtils — pure Kotlin, no Android types
core:subtitles      parsers (SRT/VTT/ASS), SubtitleFormat, normalizer, blocks, charset — pure Kotlin
core:settings       AppSettings (typed, @Serializable, versioned), SettingsRepository, DataStore — no Compose
core:designsystem   tokens, motion, typography, theme, SubtitleBackdrop, BadgePill, ALL UI strings
core:player         PlayerController interface + Media3 impl + FakePlayerController
core:translate      ML Kit wrapper, model download state, cache
core:ai             AiProvider interface (Gemini/OpenAI/Anthropic/custom), prompt builder, answer parser
core:security       Keystore-backed SecretStore
core:lexicon        WordLevelSource, FrequencyListImporter
core:data           Room database: My Words, recent videos, translation cache
feature:*           Compose screens + view models; a feature never depends on another feature
```

Rules that keep those boundaries:

- A feature depends on `core:*` only. Shared UI code goes to `core:designsystem`, shared logic to the
  owning `core:*` module.
- Only `core:designsystem` owns `strings.xml` (`values/` + `values-fa/`, 446 keys, kept in sync by
  hand and by CI review). Features import `com.sublearn.core.designsystem.R`.
- `core:settings` and `core:subtitles`/`core:lexicon`/`core:common` have **no Compose dependency**;
  keep them JVM-testable.
- Undocumented endpoints (and anything like them) live behind an interface in `core:*` so they can be
  replaced or removed — that is how the reference project kept YouTube caption scraping contained.

## Code conventions that already exist

- **One immutable UI state per screen** (`PlayerUi`, `WordsUi`…), written only by the view model;
  composables receive values and lambdas, never the repository.
- `AppSettings` groups are copied through `viewModel.update { it.copy(...) }`; per-layer subtitle
  edits go through `settings.subtitles.updated(role, layer)` so both layers stay symmetric.
- Enum → label mapping lives in `feature/settings/Labels.kt` as `labelRes()` extensions, so a new
  enum entry fails to compile instead of silently rendering nothing (exhaustive `when`, no `else`).
- No hardcoded colors, sizes, durations or type scales: use `Tokens.kt` (`Dimens`), `Motion.kt`
  (`Motion.SHORT`, `popupEnter`…), `Typography.kt`, `LocalSubLearnColors`.
- All animation is `reduceMotion`-aware (`LocalReduceMotion`), and every interactive element has a
  `contentDescription` from resources.
- Line limit 140 columns; KDoc on public types says *why*, not *what*.

## Adding things, quickly

- **A setting**: declare it in the right group in `core/settings/.../AppSettings.kt` with a default,
  bump `SCHEMA_VERSION` only if a stored shape changed (a new field with a default does not need a
  migration), add the row in the matching `feature/settings` section, add EN+FA strings, and add a
  case to `tools/../docs/CHECKLIST.md` if it maps to a spec ID.
- **A language**: add `values-<bcp47>/strings.xml`, register the tag in `app/.../SubLearnApplication`
  locale override and in `core/designsystem/src/main/res/xml/locales_config.xml` (currently `en`,
  `fa`). Text direction is decided per text run, so nothing else is required.
- **A provider (AI, dictionary, level)**: implement the interface in `core:*`, register it in that
  module's Koin module, add a token to the settings enum, and a row in the settings screen. Do not
  special-case a provider in a composable.
- **A LATER feature**: interface + `NotImplemented` stub + `FeatureFlag` entry + a disabled "coming
  soon" row + a section in [docs/EXTENSION_POINTS.md](docs/EXTENSION_POINTS.md). That is the whole
  job; do not build the feature early.

## Per-task loop

1. Plan 3–6 lines (what changes, which files, how it is verified).
2. Implement.
3. Run the three static checks; grep the symbols you assumed.
4. Push, `gh run watch`, fix until green.
5. Verify against `docs/CHECKLIST.md` on the real code path (not a preview harness), then update
   `docs/PROGRESS.md`, `docs/KNOWN_ISSUES.md` and the spec ID status.

## Reference repos (ideas only, studied in phase 0)

`dual-sub-replay` (MIT, Kotlin/Compose, cue merging, ML Kit wrapper, CI), `yall-mp` (GPL, subtitle
timeline editing), `jidoujisho` (GPL, tap-and-drag selection, swipe-to-repeat, dictionary popups),
SubX Player (closed, UX only), `proudvocab` (my own, card visual style), `dictionaryproject` (my own,
dictionary schema — see the rights note before using anything from it). Findings and what was
adopted/rejected: [docs/REFERENCES.md](docs/REFERENCES.md).
