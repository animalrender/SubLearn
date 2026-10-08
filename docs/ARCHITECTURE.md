# Architecture

How the pieces fit, why the boundaries are where they are, and what to touch (and not touch) when you
add something. The requirement IDs referenced here are in
[PRODUCT_SPEC.md](PRODUCT_SPEC.md).

## Module graph

```
                 ┌────────────────────────── app ──────────────────────────┐
                 │  MainActivity · SubLearnApp · AppNavigator · di/         │
                 └──┬────────────┬──────────────┬──────────────┬───────────┘
                    │            │              │              │
        feature:home  feature:player  feature:settings  feature:words / learn
                    └────────────┴───────┬──────────────┴────────────┘
                                         │  (api / implementation)
   core:common ← core:subtitles ← core:settings ← core:data ← core:lexicon
        ↑              ↑                ↑             ↑
   core:security   core:translate    core:ai     core:player
                          └────────────┴───── core:designsystem
```

Rules that are enforced by review and by `tools/check_deps.py`:

1. A `feature:*` module depends on `core:*` only — never on another feature. Shared UI code belongs in
   `core:designsystem`, shared logic in the `core:*` module that owns it.
2. `core:common`, `core:subtitles`, `core:lexicon` and the *model* half of `core:settings` are pure
   Kotlin (`kotlin-jvm`) so they are testable without a device. `core:settings` reaches DataStore only
   in its storage class, and nothing in `core:*` uses Compose except `core:designsystem` and
   `core:player` (which needs no Compose at all: `PlayerView` binding stays in the feature).
3. Every external capability sits behind an interface with at least two implementations or a documented
   stub: `PlayerController` (Media3 + `FakePlayerController`), `SubtitleParser`/`SubtitleRepository`,
   `TranslationProvider` (ML Kit), `AiProvider` (four implementations), `DictionaryProvider`,
   `WordAnalyzer`, `WordLevelProvider`, `SpeechToText`, `UpdateChecker`
   (see [EXTENSION_POINTS.md](EXTENSION_POINTS.md)).

## The player's data flow

`PlayerViewModel` is the **only** writer of player state, and `PlayerUi` is the only reader's view of
it:

```
Media3 events ─▶ PlayerController.state: StateFlow<PlaybackState>
                        │                              (position, duration, tracks, speed, error)
                        ▼
        collect + subtitle documents (per layer) ─▶ publish() ─▶ PlayerUi (one immutable snapshot)
                        ▲                                     │
   settings repository ─┘  onSettings() merges typed settings │
                                     ▼                        ▼
                            composables (controls, layers, popups, sheets, list)
```

`publish()` (in `PlayerViewModel`) is the single place that maps a playback tick into UI state: for
each `TrackRole` it resolves the block at `position + layer.delayMs`, decides the layer's
`LayerSource`, computes the visible text, and derives the shadowing and list state. Consequences:

- recomposition can never see a half-applied update, and a `PlayerUi` literal is a valid test fixture;
- per-frame work is a map of two entries plus a binary search per layer — no allocation-heavy scanning;
- features that need lookahead (list, block stepping, batch tools) ask `canStepBySubtitle`, which is
  true only for a `LayerSource.FILE`.

## Subtitle pipeline

```
SAF uri ─▶ SafTextResourceReader (contentResolver, Dispatchers.IO)
        ─▶ CharsetSniffer (UTF-8 / UTF-16 BOM / Windows-1256 for Persian)
        ─▶ SubtitleFormat.detect(name, head)      — extension first, then content
        ─▶ SrtParser | WebVttParser | AssParser   — raw Cue list (id, startMs, endMs, text, trackId)
        ─▶ SubtitleNormalizer(NormalizerConfig)   — strip tags, trim, collapse spaces, drop empty cues,
                                                    join hyphenated line breaks, punctuation fixes
        ─▶ BlockBuilder                           — merge cues that belong to one sentence, respect
                                                    maxCharsPerLine / maxLinesPerBlock / gapMs
        ─▶ SubtitleDocument                       — sorted blocks, O(log n) blockAt(ms), indexOf,
                                                    blockById, next/previousBlock
```

ASS/SSA is parsed for **text and timing only**; its styling overrides are ignored on purpose, because
honouring them would fight the per-surface font settings (GEN-3) and the layer placement the user set.
This is documented in the UI (the layer's format badge) and in
[KNOWN_ISSUES.md](KNOWN_ISSUES.md) rather than being silently half-supported.

Auto-loading a sidecar file (`movie.mkv` + `movie.en.srt`) needs a directory grant: SAF per-file
permissions do not let an app read a sibling. `SubtitleLayerSettings.sidecarTreeUri` stores the granted
tree URI; without it the user picks the file and the choice is remembered per layer
(`externalFileKeys`), which is also what survives process death.

### Two layer sources, and what each can do

| Source | Where the text comes from | List view | Seek by subtitle | Block repeat | Batch tools | Per-layer delay |
| --- | --- | --- | --- | --- | --- | --- |
| `FILE` | our own parser | yes | yes | yes | yes | yes |
| `PLAYER_CUES` | live `Cue` callbacks from Media3 for an embedded track | no lookahead, so no | no | current block only | no | applied to our rendering only |

That asymmetry is deliberate and visible: a panel that cannot work shows an explanation with a
"pick a subtitle file" action instead of an empty list. Hiding the difference would be a lie to the
user and a trap for the next contributor.

## Rendering and hit testing (SUB-4, GEN-4)

`SubtitleLayerStack` draws each layer in a `Box` positioned by `SubtitlePlacement` (anchor + dp
offsets), wrapped in `SubtitleBackdrop` (rounded, semi-opaque, outline). Each layer renders one
`Text` with an `AnnotatedString`, and `onTextLayout` captures the `TextLayoutResult`.

Word taps resolve like this: `pointerInput` gives a position → `TextLayoutResult` is asked for the
layout line at the y position → `getWordBoundaryAtOffset`-style binary search over the line's
`getStringAnnotations`/`OffsetMapping` yields the word and its box. Because it uses the *real* glyph
layout, it is correct for Persian, for bidi mixtures and for a text scale factor, all of which break the
"one clickable composable per word" approach. The tokens that a tap maps back to come from
`SubtitleBlock.tokens` (`CueToken`), so marking a word stores the same string the parser saw.

Multi-tap granularity (1 word / 2 line / 3 block) is implemented in one `detectMultiTap` in
`Gestures.kt` with the window from `LearningSettings.multiTapWindowMs`, so a double tap on a word
never reaches the player's own double-tap handler.

## Gesture priority

One `GestureLayer` sits above the video and below the subtitle layers and controls:

```
subtitle text (tap/drag on the layer)  >  buttons (chrome, quick actions)  >  video surface gestures
```

Implemented by ordering composables (top-most consumes first) plus `Modifier.pointerInput` blocks that
consume only what they handle; the subtitle layer consumes taps only when it has text and the tap
target is inside a word box. The video surface handler consults
`GestureSettings.actionFor(GestureSlot)` so every slot is remappable (PLY-4), and the two-edge
double-tap seek respects `DoubleTapAction`. System gesture areas (status bar, nav bar) are excluded
by `playerEdgeInset` padding rather than by fighting the system.

## Settings, storage and migration

`AppSettings` is one `@Serializable` graph of small typed groups (`AppearanceSettings`,
`PlayerSettings`, `SubtitleSettings`, `GestureSettings`, `ShadowingSettings`, `LearningSettings`,
`WordStyleSettings`, `AiSettings`, `TranslationSettings`, `DictionarySettings`, `LevelSettings`) plus
per-surface fonts. Storage is DataStore Preferences holding a single JSON document with a
`SCHEMA_VERSION` int; `SettingsRepository` exposes `current()`, `update {}`, `exportJson()`,
`importJson(text, ImportMode)`, `reset()` and `exportPath()`.

- Adding a field with a default needs **no** version bump: absent keys simply take the default.
- Renaming, removing or reshaping a field does: bump the version and write the upgrade in
  `DataStoreSettingsStorage` before decoding.
- Import applies per section with `ImportMode.REPLACE|MERGE` and returns an `ImportReport` of applied
  sections and warnings, so a file from a newer version degrades visibly instead of half-applying.
- Secrets are not in this document: `AiSettings.keyRef` is an opaque reference; the key itself is
  AES-GCM ciphertext produced by a Keystore key in `core:security`, excluded from backups.

## Threading and performance

- Parsing, charset sniffing and file IO: `Dispatchers.IO`, results delivered to the view model.
- Word-state computation for styling and level popups: `Dispatchers.Default`, debounced by job
  cancellation (`wordStateJob`) and skipped entirely when the visible word set has not changed.
- Cue lookup: binary search, no per-frame `filter`/`firstOrNull` over the whole document.
- Recomposition scope: the animated chrome, the layer stack and each popup are separate composables
  taking the narrowest state they need, so a position tick does not re-render the subtitle text (it is
  `remember`-ed on `(text, wordStates, styles)`).
- The player view is bound through `AndroidView` with `attachView/detachView` in a
  `DisposableEffect`, so navigation never tears down playback.

## Process death, PiP, rotation

`MainActivity` keeps the `PlayerController` (and thus `ExoPlayer`) alive across navigation; on
`onUserLeaveHint` it enters PiP with the aspect ratio from settings. State that must survive process
death is *derived*, not snapshotted: the recent-video row (URI, title, duration and last position) plus
`AppSettings` reproduce the session, and the SAF persisted permission is what makes the URI usable
again. `PlayerStart(documentId/startPositionMs)` is how a restart re-attaches the same target without a
custom Parcelable of the whole UI.

Orientation lock, window brightness, immersive bars and PiP are the only things `PlayerIntent`
carries, because they are window effects Compose cannot own — the seam is intentionally tiny and is the
same one an `Activity`-based player would need.

## Adding a language

Nothing in the code keys off `"en"` or `"fa"`; the pair is data:

1. `core/designsystem/src/main/res/values-<tag>/strings.xml` — the only string owner; keep every key
   (CI review checks the two locales are in sync, and the app would crash on a missing key in a
   partially translated release).
2. Register the tag in `app/src/main/res/xml/locales_config.xml` (per-app language, API 33+) so the
   system can switch the app without a reboot.
3. Add the language to `LanguageSettings`' picker list (`core:settings`) — learning and native
   language are two independent fields, each a BCP-47 tag string.
4. RTL is decided per text run by `TextDirection` inference from content, so an RTL language needs no
   extra flag; if a new script is vertical or cursive, the font surface settings
   (`FontSurface` + `FontSpec.lineHeightEm`) are the knobs, not a special case.
5. Offline translation for it must be supported by ML Kit's on-device catalog; the model download
   already reports "not available" as `TranslationError.ModelMissing`-style state, so an unsupported
   language degrades to a message rather than a crash.

Adding a *dictionary* for that language is a different, larger change: see
[EXTENSION_POINTS.md](EXTENSION_POINTS.md) (user-supplied import only).

## Testing strategy

- **Pure logic** (`core:common`, `core:subtitles`, `core:lexicon`, `core:settings` repository) runs as
  JVM unit tests: parsers, normalizer, block builder, charset sniffing, shadowing pause formula, time
  utilities, settings export/import. 18 test classes today, all fast and device-free.
- **Android-adjacent** code (Room repositories, DataStore, Keystore cipher) uses Robolectric.
- **Compose** behaviour that depends on real font measurement (word hit testing, bidi shaping) is an
  instrumented test in `feature:player/src/androidTest`, run by the manual
  `.github/workflows/instrumented.yml` (it needs KVM). CI always *compiles* those sources via
  `assembleDebugAndroidTest`, so test code cannot silently rot.
- **`FakePlayerController`** exists so the player view model can be driven without Media3: a future
  commit adds view-model-level tests on it (logged in [KNOWN_ISSUES.md](KNOWN_ISSUES.md) as the current
  test gap, not as a passing claim).
