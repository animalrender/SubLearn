# Known issues

Honest list of what is not right yet, including consequences of decisions. Nothing here is hidden in a
TODO comment; each entry has a route to being fixed.

## Build and verification

1. **Android and Compose modules are only ever compiled by CI**: the agent sandbox has no Android SDK
   or Gradle. The pure-JVM modules (`core:common`, `core:subtitles`, `core:settings`, `core:lexicon`,
   `core:data` logic, `core:translate` service, `core:ai`) were compiled and unit-tested locally with
   a kotlinc 2.0.21 harness during the 2026-10-09 review (122 tests, 1 skipped); everything that touches
   `android.*`, Media3, Room or Compose was reviewed by hand and compiled by CI. The CI job publishes
   the first Gradle errors as the "Gradle failure report" check run (`tools/ci_failure_report.py`) so
   an agent that cannot download logs can read them through the API. The current
   `arena/96924659-sublearn` player pass has static-check results only; its Android compile and tests
   are pending. The first green CI run is
   37854997366 on `arena/956a666c-sublearn` (2026-10-09); review that commit range carefully, that
   is where mechanical mistakes live.
2. **The four static checkers are not a type checker.** `tools/check_sources.py` (brace balance, 140
   columns, no `FIXME`), `tools/check_symbols.py` (every `com.sublearn.*` import resolves to a declared
   name, generated `R`/`BuildConfig` skipped, and a type declared in another package that is used without an
   import is reported — the `Unresolved reference` that a same-package name in the author's head hides),
   `tools/check_deps.py` (declared dependencies vs imports,
   catalog alias existence, cross-module visibility) and `tools/check_resources.py` (the aapt2 text rules
   that make `packageDebugResources` fail) catch a lot, but not argument types or `when` exhaustiveness.
3. **No view-model-level tests yet.** `FakePlayerController` exists precisely to make them cheap; that
   gap is `REQ-5` in [AGENT_REQUESTS.md](AGENT_REQUESTS.md). 18 test classes cover the pure and
   Android-adjacent logic today.

4. **Release builds are not device-tested and are signed with the debug key.** `assembleRelease`
   (R8 shrinking, no obfuscation — D-21) is built on every CI run and the four APKs are attached to
   the GitHub Release by `release.yml`, but nobody has installed them yet. R8 keeps are limited to
   kotlinx.serialization; a missing keep rule shows up as a crash at runtime, not at build time. The
   debug key fallback is `REQ-6`: once the owner adds the `SUBLEARN_KEYSTORE_*` secrets the next
   release is signed properly and users of earlier builds must uninstall first.

## Behaviour that is intentional but surprising

5. **Embedded subtitle tracks cannot drive the list view, seek-by-subtitle or the batch tools.** A
   layer fed by player cues has no lookahead, so those actions are unavailable and the panel says why
   (pick a file instead). See [ARCHITECTURE.md](ARCHITECTURE.md) "two layer sources".
6. **ASS/SSA styling is ignored** (colours, positions, karaoke tags); we take text and timing only.
   Otherwise a fansub's own styling would override the per-surface fonts (GEN-3) and layer placement.
7. **Software decoder is "prefer software MediaCodec", not an FFmpeg decoder.** `HW+` means "hardware
   with fallback". The Media3 FFmpeg extension was rejected (native build, size), so a
   codec no device codec list supports genuinely fails — the sheet explains the mapping rather than
   pretending. See D-9 in [DECISIONS.md](DECISIONS.md).
8. **No word list is bundled**, so with nothing imported the level popups fall back to "words not marked
   as known" and `LevelProviderToken.IMPORTED_FREQUENCY_LIST` reports that no list is loaded. Which
   list to point users at is still open (`REQ-4`).
9. **Offline dictionary is disabled by flag.** The card's "full details" opens the web translation
   window; the Dictionary tab is a labelled "coming soon" entry. That is the LATER contract, not a bug.
10. **Sidecar auto-load needs a folder grant.** SAF gives per-file permission only, so the first use
   asks for the video's folder (`sidecarTreeUri`); declining it degrades to manual picking.

## Smaller things

11. `resourceConfigurations` is deprecated in AGP 8.7 in favour of `androidResources.localeFilters`;
    changing it now would need the min-version of that API to be checked against our AGP, so the
    deprecation warning stays and is listed here rather than silenced in a lint baseline.
12. **Instrumented tests are not run in CI automatically** (no KVM on standard runners); they compile
    there (`assembleDebugAndroidTest`) and run in the manual `instrumented.yml` workflow.
13. **README has no screenshots yet** (phase 9 owns them). Adding pretty fakes would be worse than
    waiting; see `REQ-7`.
14. Gradle 8.11.1 is reported as out-of-date by `setup-gradle`. It is the newest line compatible with
    AGP 8.7.3 + Kotlin 2.0.21 as pinned in the catalog; bumping all three is one deliberate commit, not
    a drive-by.
15. **Some status text is still English outside the player**: `Sources.kt`'s file errors (`cannot read …`,
    `the file could not be opened`), `SettingsViewModel`'s import and export status lines and the
    `LaterCapabilities` descriptions. The player's own messages and its quick-action labels have been
    resources since the 2026-10-10 rebuild, and the new Downloads screen resolves its own status
    text from `core:designsystem` in both languages. Route: a `UiText` (resource id + arguments) carried in the
    UI state and resolved in the composable, so the strings move into `core:designsystem` without the
    view models touching `Context`.
16. **The About section's "feedback" row is a no-op.** There is no destination yet, so the row does
    nothing. Floating quick actions drag since the 2026-10-10 rebuild (`detectDragGestures` writes the
    stored fractions). Route: a feedback URL once one exists.
17. **Subtitle selections are remembered for only the most recently opened video.** The current layer
    settings support several external files and embedded-track assignments, and opening a different
    video clears the prior video's references to avoid applying unrelated track indices. There is not
    yet a Room table keyed by media URI, so returning later to an older video does not restore its own
    set. Route: move per-media source assignments into a Room table keyed by the media URI.
18. **ML Kit model download progress is coarse.** `RemoteModelManager.download` only reports
    start/success/failure, so the Settings → Downloads card shows an indeterminate bar while the
    model downloads. The card states the published size of the English–Persian package (about 30 MB)
    instead of a percentage that the SDK cannot produce. Route: none in the public ML Kit API;
    revisit if a progress callback appears.
19. **Repeat-block hold does not invert the auto-repeat setting until release.** A hold runs the
    auto-repeat count once. The chrome button has no release callback, so the setting is not
    temporarily inverted as SHD-2 describes. Route: a release callback on `ChromeIconButton`, and a
    ViewModel pair `beginRepeatHold` and `endRepeatHold`.
20. **The playlist sheet is read-only.** The player now builds a bounded queue from readable recent
    videos, so previous/next transport works, but tapping a playlist row does not jump to it because
    `PlayerController` has no `playIndex(index)`. Route: add that interface method and row selection in
    `Sheets.kt`.
21. **The rebuilt player has not run on a device or an emulator.** Picture-in-picture, rotation, the
    immersive bars and word hit testing on real glyph layouts were reviewed by hand and by the static
    checkers. Route: the 2026-10-11 checklist in PROGRESS.md and `PlayerPopupsSmokeTest` on a device.
22. **Media3 cannot identify the originating soft track for every live cue.** `CueGroup` returns text
    cues without a track reference. When exactly one track is selected or active-cue count matches the
    selected soft-track count, the controller associates them by order; otherwise cues stay untagged and
    are shown in each layer assigned an embedded track. Independent embedded assignment is therefore
    only best-effort for ambiguous multi-track subtitles (PLY-7). Route: find a Media3 renderer callback
    with stable source metadata or parse/expose per-track cues without leaking the built-in subtitle view.
23. **External audio merging has not been exercised on device.** `MergingMediaSource` is used to combine
    an external audio source with its selected video; it is intentionally attached only to that video.
    Different stream lengths, provider MIME metadata and unusual codec containers may need device-level
    handling. Route: exercise common MP3/AAC/FLAC + video pairs after CI, then document unsupported formats.
24. **The ONNX and LiteRT engines compile but have never run on a device.** Both are implemented:
    `core:nlp-onnx` loads the int8 encoders through ONNX Runtime 1.22.0 and `core:nlp-llm` loads the
    LiteRT bundles through MediaPipe's LLM Inference API, `NlpRuntimeBootstrap` registers whichever
    native library actually loads, and `ProfiledNlpEngine` opens the models a profile needs. What is
    not proven is inference itself: no `.onnx` or `.task` file has been executed, because the sandbox
    has no Android SDK and the models are hundreds of megabytes. The selection, layout, pooling,
    tokenizer and installer logic is unit tested; the native call paths are not. Route: install a
    model on a device, run `ProfiledNlpEngine` against a known sentence, and record the output.
25. **The offline translation model has not been downloaded on a real device.** The hand-written
    installer that unpacked the model archive itself is gone (D-81): ML Kit downloads, verifies and
    stores its own models, and the app asks the SDK for the language models of the current pair
    through `RemoteModelManager` and `Translator.downloadModelIfNeeded`. The screen is wired to that
    one path from three places (the player popup, the translation settings, Settings → Downloads),
    but no end-to-end download has been performed on a phone, so the "it works" claim rests on the
    SDK's documented behaviour rather than on an observed run. Route: one device run through
    Settings → Downloads, then record the result in PROGRESS.md.
26. **The rebuilt word card is verified by compilation only.** The header and close button were
    removed (§7) and the alternative phrase rows were added, but no Compose UI test pins the
    layout, so a regression in the card's arrangement would only show up visually. Route: a
    Robolectric Compose test once the popup's state is constructible in a test.
27. **The Room migration is checked statically, not executed.** `tools/check_schema.py` compares
    `MIGRATION_1_2` with the exported v2 schema, but no test opens a v1 database and upgrades it,
    so a SQL statement that is valid DDL yet wrong at runtime would still pass. Route: a
    `MigrationTestHelper` test in `core/data` with `room-testing` on the instrumented test path.
28. **The supplementary CEFR pack is built but not installable from the catalog.** The
    `Build Extra Packs` workflow publishes `sublearn-cefr-v1.sqlite.gz` and
    `cefr-levels-en-v1.tsv.gz` to the packs release, and the app can already import a word list
    through Settings, but the catalog has no entry for it because `ModelKind` has no CEFR kind and
    `visibleEntries()` would show a row the installer could not handle. Route: add a
    `WORDFREQ_CEFR` kind, an importer branch for the two formats, and a catalog entry; until then
    the pack is downloaded from the release page rather than from inside the app.

29. **Automatic player cards need a device pass, and only detect phrases within each live line.**
    File and embedded learning cues drive the new renderer, but cross-cue phrase hints are not yet
    scheduled (simultaneous tracks must not be concatenated). Offline lookups time out rather than
    obstruct playback; missing packs/models yield no automatic translation cards. Equal/below-level
    words are intentionally omitted; with the Manual provider all unlisted words equal the chosen
    level, so import a CEFR list or use My Words for fallback hints. The compact corner bounds and
    reduced-motion animations need phone verification in portrait/landscape and EN/FA. The production
    settings graph and seven selection tests pass in CI run 38923376453 (`a65db3e`); the instrumented
    visibility/overlap tests compile but have not executed. No local Android toolchain exists.

30. **The new Downloads screen has not run on a device.** The screen replaces two older sections and
    three download paths with one (D-81), and it is verified by the static checkers and by reading
    the SDK calls, not by a phone. What still needs a device run: the ML Kit download itself (see
    issue 25), the pack download and import from the release (the catalog now matches the published
    digests, D-83), and the profile chips, which show the advice of `SmartProfileSelector` for the
    memory and storage the device reports. Route: the 2026-10-16 checklist in PROGRESS.md.

31. **The accuracy profile can now be chosen above what the device can run.** The old guard
    compared the profile's floor with the app's *heap*, which is a fraction of the phone's memory,
    so every choice was downgraded and the profiles never ran. The advice now uses the device's own
    memory, storage and installed models, and the screen states what a profile still needs instead
    of switching the choice. That means a user can still pick a profile their phone cannot run: the
    engine then falls back to what it can open, which is the documented behaviour, but the screen
    does not block the choice. Route: if a profile turns out to be too slow on real hardware, gate
    the chip behind the advice rather than the advice behind the chip.
