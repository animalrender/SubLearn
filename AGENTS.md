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

1. **No third-party data in the repository.** Never commit a dictionary database, a `.bipe` file, an
   extracted translation model, or anything derived from them: the user imports their own files or
   downloads packs from the Resource Library at runtime. Translation is multi-source via
   `TranslationOrchestrator` (offline `PACK`, `ML_KIT`, `LEGACY_V1`, plus optional user-enabled
   `ONLINE_GOOGLE` with an "Offline only" master switch — see D-58, D-59, D-62). `.gitignore`
   keeps data, model and signing files out of the repository.
2. **No fake success.** No demo data in shipped paths, no silent stubs, no `TODO`. An unfinished item
   is either finished or written down in `docs/KNOWN_ISSUES.md` / `docs/AGENT_REQUESTS.md`.
3. **main always builds.** One branch per phase (`phase/N-name`), small conventional commits, one PR
   per phase, merge only at Definition of Done. If you are blocked, keep the branch green (feature
   flag or revert the partial work), log it, and move to the next independent task.
4. **Every PR updates the docs it affects**, and [docs/PROGRESS.md](docs/PROGRESS.md),
   [docs/PHASES.md](docs/PHASES.md), [docs/DECISIONS.md](docs/DECISIONS.md), and
   [docs/IMPROVEMENTS.md](docs/IMPROVEMENTS.md) must always reflect what is done, what remains,
   and the rationale for every architectural decision.
5. **No secrets**: no API keys, keystores, `local.properties` or build outputs. Keys go through
   `core:security`; `.gitignore` keeps secrets and forbidden files out of the repository.

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
python3 tools/check_workflows.py --repo-root .          # the workflows are valid YAML and their
                                                        # run blocks are valid shell
```

These are name and shape checks, **not** type checking. They will not catch a wrong argument type, a
non-exhaustive `when` or a missing `@Composable`. After writing code in a module, also grep the
symbols you assumed: enum entry names, data class field names and function arities in `core:*` are
easy to get wrong from memory. Then push, watch `gh run watch`, and fix until green. Report honestly
that local compilation was not possible.

The release workflows have their own trap: a `run:` block whose indentation is broken is not a
shell error, it is a **YAML** error, and a workflow with invalid YAML never runs at all — the
release simply does not happen and there is no log to read. `tools/check_workflows.py` parses every
workflow file and hands every `run:` block to `bash -n`, so that class of mistake fails the build
instead of the release.

Two routes exist beyond the static checks (both documented in `docs/AGENT_REQUESTS.md`, REQ-3):

- The pure-JVM modules (`core:common`, `core:subtitles`, `core:settings`, `core:lexicon`, the logic
  in `core:data`/`core:translate`/`core:ai`) compile with a plain `kotlinc`; a JRE (`jdk4py` wheel on
  pypi) and `kotlin-compiler` (npm) are reachable from the sandbox, Gradle and the Android SDK are not.
- The Actions log and artifact hosts are **not** reachable. CI therefore publishes the first Gradle
  errors as the "Gradle failure report" check run (`tools/ci_failure_report.py`); read it with
  `gh api repos/<owner>/<repo>/commits/<sha>/check-runs` → `.output.text`. Do not loop on
  `gh run view --log` / `gh run download`; they fail here.

Releases happen on their own (D-28, D-82). When CI goes green, `auto-release.yml` publishes the
APKs CI just built: on `main` it computes the next version with `tools/next_version.py` (the highest
stable tag, plus a patch, floored by the `VERSION` file) and creates the stable release `v<version>`
when that tag is free and the commit has no stable tag yet; any other green push (`main` at an
already-released version, `phase/**`, `arena/**`) refreshes the rolling pre-release `dev-<branch>`,
which is also how an agent gets an installable APK without reaching the artifact host. So
**releasing is merging to `main`** — the version is computed, the tag is created and the APK is
attached. Do not push tags by hand; `versionCode` is derived from the name (`3.0.0` → `30000`).
`VERSION` holds the floor for the next release and is the only file a human has to touch when a
release has to carry a different number. `release.yml` still builds a `v*` tag manually (one APK
per ABI plus universal, R8 shrink without obfuscation, debug-signed unless the `SUBLEARN_KEYSTORE_*`
secrets exist, in which case `auto-release.yml` rebuilds and signs too). See D-21, D-28 and D-82.
Pipeline changes only take effect once they are on `main`: `workflow_run` always runs the
default-branch copy.

## Seeing and driving the app

A change the user can see or operate is not done until it has been seen running. CI boots an Android
emulator, runs [Maestro](https://docs.maestro.dev) flows against the debug APK and publishes screenshots,
view-hierarchy dumps and logs to the orphan branch `ui-snapshots`, which this sandbox reads with plain
`git`: no JVM, no log host and no artifact host are needed. The procedure, the review checklist, the flow
cheatsheet and the stop rules are in **[docs/UI_VERIFICATION.md](docs/UI_VERIFICATION.md)**, the single
source of truth. The short version:

1. Finish the change, then push an **empty last commit**: `git commit --allow-empty -m "chore: ui check [ui]"`.
2. Confirm that a run of `ui-snapshot.yml` exists for your branch and that its `headSha` is your `HEAD`
   (`gh run list --branch <b>`, `gh run watch`). If no run appears, say so; do not assume.
3. `git clone --depth 1 -b ui-snapshots <repo-url> /tmp/snap` and read `STATUS.md`: its `sha:` must equal
   your `HEAD` (a stale result from an older commit is the main trap), it must say `app crash: no`, and
   every flow must be `PASS`.
4. **Open the PNG images and look at them** against what the user asked for; a green run does not prove
   the UI is right. Judge crashes only from `logcat-app.txt` and the `app crash:` line, never from
   `logcat-full.txt`.
5. Delete `.maestro/_scratch/` before the merge. The UI check is the last step before the merge; after the
   merge the session is over.

It is mandatory for any change the user can see or operate. A PNG, a video or a log is never committed
anywhere except the `ui-snapshots` branch, and `main` stays free of artifacts.

## Module map and boundaries

```
app                 single activity, Koin graph, intent filters, navigation, PiP handoff
core:common         AppResult, SubLearnError, TimeUtils — pure Kotlin, no Android types
core:subtitles      parsers (SRT/VTT/ASS), SubtitleFormat, normalizer, blocks, charset — pure Kotlin
core:settings       AppSettings (typed, @Serializable, versioned), SettingsRepository, DataStore — no Compose
core:designsystem   tokens, motion, typography, theme, SubtitleBackdrop, BadgePill, ALL UI strings
core:player         PlayerController interface + Media3 impl + FakePlayerController
core:translate      ML Kit wrapper, TranslationModelManager (one state for the offline model), cache
core:ai             AiProvider interface (Gemini/OpenAI/Anthropic/custom), prompt builder, answer parser
core:security       Keystore-backed SecretStore
core:lexicon        WordLevelSource, FrequencyListImporter, LexiconV2 types
core:nlp            pure Kotlin: PhraseSpan, Lemmatiser, PatternCompiler, PhraseDetector, SenseRanker
core:models         ModelCatalog, ModelKind, download/verify interfaces, DeviceCapabilityReader
core:nlp-onnx       ONNX Runtime wrapper, WordPiece tokenizer (Android)
core:nlp-llm        LLM adapter for on-device selection (Android)
core:data           Room database: My Words, recent videos, translation cache
feature:*           Compose screens + view models; a feature never depends on another feature
```

Rules that keep those boundaries:

- A feature depends on `core:*` only. Shared UI code goes to `core:designsystem`, shared logic to the
  owning `core:*` module.
- Only `core:designsystem` owns `strings.xml` (`values/` + `values-fa/`, 624 keys, kept in sync by
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
3. Run all four documented static checks and `git diff --check`; grep the symbols you assumed.
4. Push, `gh run watch`, fix until green.
5. Verify against `docs/CHECKLIST.md` on the real code path (not a preview harness), then update
   `docs/PROGRESS.md`, `docs/KNOWN_ISSUES.md` and the spec ID status.
6. If the change touches anything visible or operable, run the UI check and fix until clean, before
   merging (the empty `[ui]` commit, the sha check, the images; [docs/UI_VERIFICATION.md](docs/UI_VERIFICATION.md)).
   It is the last step before the merge.

## Reference repos (ideas only, studied in phase 0)

`dual-sub-replay` (Kotlin/Compose, cue merging, ML Kit wrapper, CI), `yall-mp` (subtitle timeline
editing), `jidoujisho` (tap-and-drag selection, swipe-to-repeat, dictionary popups), SubX Player
(UX only), `proudvocab` (my own, card visual style), `dictionaryproject` (my own, dictionary
schema). Findings and what was adopted/rejected: [docs/REFERENCES.md](docs/REFERENCES.md).
