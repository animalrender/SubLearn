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
        ↑              ↑                ↑             ↑            ↑
   core:security   core:translate    core:ai     core:player   core:nlp (pure Kotlin)
                          └────────────┴───── core:designsystem  ↑
                                                     core:models ─┘
                                                     core:nlp-onnx (Android, ONNX)
                                                     core:nlp-llm  (Android, LLM)
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
                        │                              (position, duration, tracks, volume, speed, error)
                        ▼
        collect + subtitle documents (per layer) ─▶ publish() ─▶ PlayerUi (one immutable snapshot)
                        ▲                                     │
   settings repository ─┘  onSettings() merges typed settings │
                                     ▼                        ▼
                            composables (controls, layers, popups, sheets, list)
```

`publish()` (in `PlayerViewModel`) is the single place that maps a playback tick into UI state: for
each `TrackRole` it resolves every enabled external document at `position + layer.delayMs`, associates
selected embedded cues with the layer's checked track keys where Media3 provides enough information,
computes keyed `SubtitleLineUi` rows, and derives the shadowing and list state. Consequences:

- recomposition can never see a half-applied update, and a `PlayerUi` literal is a valid test fixture;
- per-frame work is two layer entries plus binary searches in their active documents — no allocation-heavy scan of every cue;
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
tree URI. File picking requests a persistable read grant; the player remembers several external file
keys and per-file enabled state in each layer. `SubtitleSettings.rememberedMediaUri` prevents old track
indices/keys from being applied to a different video. This is deliberately only the most-recent video's
association for now; a complete per-video table is a known issue.

### Two layer sources, and what each can do

| Source | Where the text comes from | List view | Seek by subtitle | Block repeat | Batch tools | Per-layer delay |
| --- | --- | --- | --- | --- | --- | --- |
| `FILE` | our own parser | yes | yes | yes | yes | yes |
| `PLAYER_CUES` | live `Cue` callbacks from selected Media3 soft tracks | no lookahead, so no | no | current block only | no | applied to our rendering only |

That asymmetry is deliberate and visible: a panel that cannot work shows an explanation with a
"pick a subtitle file" action instead of an empty list. Hiding the difference would be a lie to the
user and a trap for the next contributor.

## Track selection, playback queues and rendering (PLY-7, PLY-8, PLY-9; SUB-4)

`SubtitleManagerSheet` has separate Learning and Translation tabs. Both list all player-reported soft
subtitle tracks with independent checkboxes; the ViewModel unions the checked `TrackRef`s for Media3's
text renderer and routes tagged cues back to their assigned layer. Each tab can also hold multiple
parsed external files; each file has an independent on/off and remove control while delay, style and
placement remain layer-level. `SubtitleLayerStack` draws every current `SubtitleLineUi` in a role as one
plate, with each line maintaining a stable source key and optional parsed block.

Media3's `CueGroup` does not include source-track IDs. `Media3PlayerController` makes a best-effort
association when there is one selected soft track or when one current cue corresponds to each selected
track; ambiguous cues stay untagged and can appear in each assigned layer. This limitation is recorded
in [KNOWN_ISSUES.md](KNOWN_ISSUES.md) and keeps the controller honest rather than inventing metadata.

`PlayerController.setExternalAudioTrack` adds an audio-only `MediaSource` through `MergingMediaSource`
for the currently selected video; its audio groups join normal Media3 audio selection, while player
volume/mute remains separate from Android's system stream. A source is scoped to its media URI and is
not silently carried into unrelated queue entries.

Recent readable videos form the playlist in chronological order, capped at 40 while always retaining
the explicitly opened item. `PlayerViewModel.recentPlaylist` omits other `content://` entries without a
persisted read grant, and `PlayerController.setPlaylist` supplies previous/next transport navigation.
Media3 transitions publish the new target/index and track groups together; the ViewModel saves the old
position and clears subtitle assignments before applying defaults for the new item. A stale Home grant
launches the SAF picker instead of opening an opaque IO failure. The playlist sheet is currently read-only.

`SubtitleLayerStack` (`SubtitleOverlay.kt`) draws each layer as a plate. Its anchor picks a
`BiasAlignment`, and the stored offset is mirrored for END and BOTTOM anchors, so a positive offset
always means "further from the anchor edge". `PlayerViewModel.movedBy` uses the same rule, which keeps
a drag and its rendering in step. Each rendered line carries the `SUBTITLE_WORD_TAG` annotation, and
`PlayerHitRegistry` stores a map of line keys to `TextLayoutResult`s for accurate word/source hit-testing.

Nothing in the overlay consumes pointer input. Each plate and text block reports its
`LayoutCoordinates` (and the layout) to the screen's `PlayerHitRegistry`, which turns a position into
a layer and a word only when a pointer arrives. That keeps hit testing right through rotation and
recomposition, and it means one place decides what a touch means. Word lookup uses the real glyph
layout, so it is correct for Persian, bidi text and text scaling; "one clickable composable per word"
is not. The tokens a tap maps back to come from `SubtitleBlock.tokens`, so a marked word stores the
same string the parser saw.

Multi-tap granularity (1 word / 2 line / 3 block) is recognised in `PlayerGestures.kt`, with the window
from `LearningSettings.multiTapWindowMs`.

The seek slider pauses playback at drag start and calls `PlayerViewModel.onScrubPreview(targetMs)` for
every value change. `PlayerController.seekTo` publishes the preview position immediately, so `publish()`
resolves file subtitles at the drag target before the user releases; `onScrubEnd` resumes only if playback
was previously active. The right-side clock persists the total/remaining preference in player settings.

## Gesture priority

`PlayerScreen` draws, in this order: picture, subtitle layers, the gesture surface, chrome, transient
feedback (HUD, buffering, errors), then the subtitle list, popups, the AI answer and the sheets. The
chrome, the popup card and the list register their bounds as blockers (`blocksGestures`), so the
surface never reacts to a touch that a control has taken:

```
subtitle word or plate  >  chrome, popup card, list (registered blockers)  >  video surface
```

The surface decides a tap's meaning by position through the registry: a word (multi-tap), a plate
(a drag in layout mode), or a side of the picture (left, centre, right). It asks the ViewModel for the
action of each `GestureSlot`, so every slot is remappable (PLY-4), and a double tap follows
`PlayerSettings.doubleTapAction`. `GestureMode` turns the surface off while locked or in PiP, and
limits it to plate drags in layout mode. The system bars are hidden for the whole player screen
(D-27), so the edges need no exclusion beyond `playerEdgeInset`.

## Settings-owned resources and automatic learning hints (D-78..D-83)

Learn is a localized Coming soon destination for browser/PDF/image learning. Player mode, level and
popup appearance belong to Settings → Player learning. `feature:settings/DownloadsViewModel` owns
the offline translation model, the packs, the accuracy models and the accuracy profile; the
Dictionary screen keeps its own `LearnViewModel`, and neither feature imports the other. No
settings schema change is needed.

After resolving live subtitle lines, `PlayerViewModel.publish` schedules passive learning hints.
The cancelable job is keyed by media URI, line source/timing/text and settings, and resets on seeks.
It checks real lexicon CEFR or the configured level provider and My Words before bounded offline
translations. `selectLearningCandidates` implements strict above-level, known-word and lemma filters;
`LearningCardsOverlay` draws at most two bounded, non-interactive hints, suppressed behind controls,
tap popups, lists, sheets and PiP. It never pauses the controller or calls the online translation flow.
The geometry-only hit registry publishes measured subtitle plate bounds; hints try other corners
rather than covering either layer, and compact video regions draw only one hint.
`LEARNING_POPUP` fonts are isolated from the tapped word card's fonts. Cross-cue automatic phrases
remain a documented limitation; live tracks are never merged into an invented sentence.

The shared design-system linear progress/slider wrappers disable Material stop/tick dots. The Koin
model installer receives `get<ModelDownloader>()::download` explicitly, not an unresolved function
binding; a Robolectric production-graph test covers Settings entry.

## The downloads screen (D-81)

```
Settings → Downloads
  ├── offline translation model   ── TranslationModelManager (core:translate)
  │      └── TranslationProvider ── MlKitTranslationProvider ── ML Kit (RemoteModelManager)
  ├── offline packs                ── PackDownloader (core:models) ── GitHub release
  │      └── PackImporter / LegacyV1Importer (core:lexicon) ── InstalledPackRepository
  ├── accuracy models              ── NlpModelInstaller (core:ai) ── ModelStore layout
  └── accuracy profile             ── SmartProfileSelector (core:ai) + DeviceCapabilityReader
```

One rule runs through the screen: **one state per item, and one place that changes it.** The
translation model's state lives in `TranslationModelManager`, which every screen reads and every
download button calls; the packs' state lives in `PackDownloader`; the accuracy models' state is
read from `ModelStore` through `NlpModelInstaller`; the profile's advice is computed by
`SmartProfileSelector` from the device's memory, storage and the installed models. The catalog the
screen offers is `ModelCatalogParser.bundledDefault()`, which is generated from
`docs/catalog/catalog-v2.json` and checked against it by `tools/check_catalog.py` — so the size and
the SHA-256 the user is shown are the ones the download is verified against (D-83).

## Settings, storage and migration

`AppSettings` is one `@Serializable` graph of small typed groups (`AppearanceSettings`,
`PlayerSettings`, `SubtitleSettings`, `GestureSettings`, `ShadowingSettings`, `LearningSettings`,
`WordStyleSettings`, `AiSettings`, `TranslationSettings`, `DictionarySettings`, `LevelSettings`) plus
per-surface fonts. Storage is DataStore Preferences holding a single JSON document with a
`SCHEMA_VERSION` int; `SettingsRepository` exposes `current()`, `update {}`, `exportJson()`,
`importJson(text, ImportMode)`, `reset()` and `exportPath()`.

- Adding a field with a default needs **no** version bump: absent keys simply take the default. This pass adds
  `PlayerSettings.showRemainingTime` and `SubtitleSettings.rememberedMediaUri` with defaults.
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

`PlayerIntent` carries only the requests that need the Activity: brightness, volume, PiP, share, and
the two navigation requests (words, settings). Orientation, immersive bars and keep-screen-on are applied
by `PlayerScreen` itself, and restored when it leaves composition (D-27).

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
