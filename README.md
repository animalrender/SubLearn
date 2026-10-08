# SubLearn

Learn English from real video: an offline-first Android player with two independent subtitle layers,
tap-to-translate, shadowing tools, your own word list and an optional AI helper for the lines that
refuse to make sense.

Free and open source (Apache-2.0). No ads, no accounts, no telemetry, no paywall.

## Why this app exists

Most players show subtitles; most learning apps show sentences out of context. SubLearn puts the
learning tools on top of the video you actually want to watch: the line you are reading stays where
you put it, a tap gives you the word, a long tap gives you the line, and the block can be repeated
with a pause that is computed from how long it takes to read it.

The default UI language is English and the default translation language is Persian (RTL). The design
of the settings, string and font layers does not assume either: see [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#adding-a-language).

## What works today

- **Local files and streams** through Media3 (ExoPlayer): seek, speed, PiP, playlist, audio and
  embedded subtitle tracks, background-safe playback while you navigate the app.
- **Two subtitle layers** (learning + translation), each accepting several SRT / WebVTT / ASS tracks,
  with per-layer delay, scale, transparency, anchor, stacking order and charset detection
  (UTF-8, UTF-16, Windows-1256 for Persian). A subtitle file named like the video is offered
  automatically.
- **Tap translate** at word / line / block granularity through ML Kit on-device translation, pausing
  only when the line is about to disappear. Word tap uses real glyph positions, so it also works on
  RTL and mixed-direction runs.
- **Subtitle List View** (right panel in landscape, below the player in portrait) with current-row
  highlight, auto-scroll, search, tap-to-seek and a no-spoiler mode.
- **Shadowing**: repeat the current block once, or auto-repeat with a configurable count and a pause
  derived from block duration; *stop at end of block*; press-and-hold inverts any toggle temporarily.
- **Quick actions**: a dock or free-floating buttons, all repositionable and resizable, including
  layout mode where you drag each subtitle layer where you want it.
- **My Words** (Room, with FTS) plus word styling by list membership and CEFR level, and a
  **learning mode** that pops above-level words in the corner as you watch.
- **AI help for hard blocks**: Gemini / OpenAI / Anthropic / any OpenAI-compatible endpoint, editable
  prompt with variables, the previous N blocks of context, a loading ring and an explicit pause
  contract. Keys live in a Keystore-backed store and are never written to logs or backups.
- **Settings**: searchable, grouped, typed and versioned, exportable and importable as JSON.
- **Gestures**: brightness, volume, seek, double tap, two-finger speed, all remappable in Settings.
- **Opening from outside the app**: video files, subtitle files and direct stream URLs.

The offline dictionary, YouTube, PDF/OCR learning, quizzes, automatic level detection, on-device AI
and speech-to-text subtitles are designed for but not built. Each has an interface, a flag and a
short section in [docs/EXTENSION_POINTS.md](docs/EXTENSION_POINTS.md).

## Build

Requirements: JDK 17, Android SDK platform 35 + build-tools 35.0.0, and the Gradle wrapper (no
system Gradle needed).

```bash
./gradlew assembleDebug            # APK in app/build/outputs/apk/debug/
./gradlew test                     # every unit test (18 test classes today)
./gradlew lintDebug                # Android lint, abort on error
./gradlew testDebugUnitTest lintDebug assembleDebug --stacktrace   # what CI runs
```

Without a JVM (the agent sandbox case) run the static checks instead and let CI compile:

```bash
python3 tools/check_sources.py core app feature tools
python3 tools/check_symbols.py
python3 tools/check_deps.py
```

Translation needs the ML Kit model, which the Play Store service downloads on first use; the app
offers the download in Settings → Translation and reports progress rather than failing silently.

## Repository layout

```
app/                    single activity, Koin graph, intent filters, navigation
core/common             AppResult, SubLearnError, time and text utilities (pure Kotlin)
core/subtitles          parsers, normalizer, blocks, charset sniffing (pure Kotlin)
core/settings           typed versioned settings, DataStore storage, JSON export/import
core/designsystem       tokens, motion, theme, typography, backdrop, all UI strings (EN + FA)
core/player             PlayerController interface, Media3 implementation, FakePlayerController
core/translate          ML Kit wrapper, model download state, translation cache
core/ai                 provider abstraction (Gemini/OpenAI/Anthropic/custom), prompt builder
core/security           Keystore-backed secret store
core/lexicon            word levels, frequency list importer
core/data               Room: My Words, recent videos, translated text cache
feature/home            recents, open a file, open a URL
feature/player          the player screen and all of its overlays
feature/settings        the settings tree
feature/words           My Words
feature/learn           learning tab
```

Read [AGENTS.md](AGENTS.md) first if you are going to change code, then
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Privacy

Everything except the optional AI request runs on the device. There is no analytics, no ad SDK and
no crash reporter in the dependency graph; see [docs/DECISIONS.md](docs/DECISIONS.md).

## License and third-party material

Apache-2.0 — see [LICENSE](LICENSE). Every dependency, font and asset with its license:
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Third-party *databases* and *model files* are
deliberately not part of this repository, and never will be: read the rights note in
[docs/AGENT_REQUESTS.md](docs/AGENT_REQUESTS.md) before suggesting one.
