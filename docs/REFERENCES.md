# Reference projects — what was studied, adopted and rejected

Audited 2026-10-08 from the current default branches. This file records the *reasoning*, so a later
agent does not re-read 60k lines of someone else's app. Nothing was copied from a GPL source; where
an idea was adopted it was re-implemented against this project's own models and interfaces.

## hoangkien1703/dual-sub-replay — MIT, Kotlin + Compose

YouTube-based dual-subtitle learner with on-device translation and a `SubtitleMerger`.

**Adopted as an idea**

- Isolating caption retrieval behind a single class, because it depends on an undocumented endpoint.
  Same principle here: anything like that lives behind an interface in `core:*` (see
  [EXTENSION_POINTS.md](EXTENSION_POINTS.md)).
- Merging short cues into readable paragraphs before rendering, as a *separate pipeline stage* from
  parsing. That is exactly `SubtitleNormalizer` + `BlockBuilder` in `core:subtitles`: parsers produce
  raw cues, the normalizer fixes text, the block builder groups cues. Keeping the three stages pure and
  separately unit-tested makes the styling layer's job trivial.
- Treating ML Kit as a wrapper with explicit model-download state instead of an opaque call, so the UI
  can offer the download instead of showing an error. `TranslationService` / `ModelDownloadState`
  follow that shape.
- CI that runs unit tests and lint on every push with a pinned toolchain — the skeleton of
  `.github/workflows/ci.yml`.

**Rejected**

- YouTube as the only media source: SubLearn's primary path is a local file or a direct stream URL
  through Media3, which is what makes offline use and external subtitle files possible.
- Storing settings in `SharedPreferences` with string keys. Here everything is one typed, versioned,
  `@Serializable` `AppSettings`, which is what makes JSON export/import and per-surface font settings
  maintainable.

**License note**: MIT would have allowed direct reuse with attribution. No code was taken, because
re-implementing against our own `Cue`/`Block` types is cheaper than adapting theirs and keeps the
dependency graph clean of another app's assumptions.

## kgurniak91/yall-mp — GPL-3.0, desktop (Electron/Angular + mpv)

**Adopted as an idea**

- Interactive subtitle timeline editing (split, merge, retime) as the model for the subtitle tools
  sheet; SubLearn ships the two safe, non-destructive operations from that family now (remove line
  breaks inside a block, split blocks at a max character count) and keeps re-segmentation by word
  timing as a LATER AI operation.
- Presets for listening/speaking practice → the `LearningMode` + shadowing preset settings.
- Context-aware speed control (slow down only the hard part) is a documented extension point on
  `PlayerController`, not a now feature.
- Tokenization for word selection and a sentence-mining flow → `CueToken`/`Tokenizer` in
  `core:subtitles` and My Words in `core:data` + `feature:words`.

**Rejected**

- Its storage of edited subtitles back into the video folder. Writing to the user's media directory
  needs permissions we do not want; SubLearn keeps edits in per-app sidecar state.
- Desktop-only concerns (mpv property plumbing, keyboard-first UI) and the Electron process model.

**License constraint obeyed**: GPL-3.0 — ideas only, no code, no data files, no assets.

## arianneorpilla/jidoujisho — GPL-3.0, Flutter

**Adopted as an idea**

- Tap-and-drag word selection over subtitle text: SubLearn implements word-granularity selection by
  hit-testing the real `TextLayoutResult` instead of re-flowing words into separate composables,
  because the latter breaks RTL/bidi shaping and kills scroll performance.
- Transcript/list view with the current line pinned, and horizontal swipe on the video to repeat the
  current subtitle → `SubtitleListPanel` and `GestureAction.REPEAT_BLOCK`.
- Subtitle delay as a first-class per-layer setting (`SubtitleLayerSettings.delayMs`), and
  auto-loading an external subtitle that shares the video's base filename (`sidecarTreeUri`).
- Popup dismissal by tapping outside, with the resume decision attached to the popup rather than the
  player (`wasPlayingBeforePause`).

**Rejected**

- A whole-app gamified dictionary loop (its core is a reading app, ours is a player).
- Flutter's `RichText`-with-gesture-detectors-per-word approach for the reasons above.
- Any use of its bundled dictionary data or `.bipe` loader (see the rights note below).

## SubX Player — closed-source commercial

UX target only, from publicly observable behaviour: dual layers with toggle buttons (tap = show/hide,
hold = temporarily invert), dockable quick actions with a bar/floating/hidden choice, a layout mode
that lets you resize and move each layer by gesture, auto-repeat with delay and count, auto-skip,
auto-pause, seek-by-subtitle, folder playlists, and the subtitle list as a right-hand panel in
landscape. All of those became spec IDs (`PLY-*`, `SUB-*`, `SHD-*`), implemented from the described
behaviour with our own gesture priority model. No code, no assets, no strings were obtained.

## melonityhub/proudvocab — mine

Read for the word/translation card look: rounded card, word on its own line with the gloss under it,
compact badges for level and status, and a soft shadow that survives dark themes. Re-implemented
natively in Compose from `core:designsystem` tokens (`Dimens.cardElevation`, `BadgePill`,
`SubtitleBackdrop`) — no ported layout code, no extracted drawables.

## melonityhub/dictionaryproject — mine, with a rights problem

Its README states that the SQLite schema and queries were taken from the Java source of a third-party
Android dictionary app and that the translation-model loader was reverse-engineered from `.bipe` model
files belonging to that app. Consequences for SubLearn, in force from phase 0:

- `fastdic_plain.sqlite`, anything derived from it (including a trimmed `docs/sample.sqlite`), and any
  `.bipe`/model file must never be committed — the `.gitignore` enforces this and CI fails on it.
- The offline dictionary is a **user-supplied local import**: `DictionaryProvider` + a documented
  schema + an importer that reads a file the user picks. We ship no data.
- The result layout (meanings, synonyms/antonyms, phrasal verbs, collocations, idioms, word family,
  CEFR, categories) is used only as a description of what a good dictionary card shows; it is written
  out in [EXTENSION_POINTS.md](EXTENSION_POINTS.md) and the card model re-implemented from that text.
- Translation goes through the official ML Kit API only.
- The rights question is logged for the owner in [AGENT_REQUESTS.md](AGENT_REQUESTS.md) (`REQ-2`).
