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
   an agent that cannot download logs can read them through the API. The first green CI run is
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
   with fallback". The Media3 FFmpeg extension was rejected (native build, size, licence surface), so a
   codec no device codec list supports genuinely fails — the sheet explains the mapping rather than
   pretending. See D-9 in [DECISIONS.md](DECISIONS.md).
8. **No word list is bundled**, so with nothing imported the level popups fall back to "words not marked
   as known" and `LevelProviderToken.IMPORTED_FREQUENCY_LIST` reports that no list is loaded. Verified
   permissive frequency data is a licence question first (see `REQ-4`).
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
15. **Some status messages are English literals in view models**: `PlayerViewModel`'s "no subtitle
    file", "no block here", "no AI key", "no media here", "resumed at …" and "repeat x/y" toasts,
    `SettingsViewModel`'s import/export status lines and the `LaterCapabilities` descriptions; the
    quick-actions section shows `id.key` with underscores replaced instead of a translated label.
    Route: a `UiText` (resource id + args) carried in the UI state and resolved in the composable, so
    the strings move into `core:designsystem` without the view models touching `Context`.
16. **Floating quick actions cannot be dragged yet.** The `floating` dock mode renders and the
    position is stored in settings, but `Controls.kt` never wires `onDragged`; the About section's
    "feedback" row is a no-op for the same reason (no destination yet). Route: `pointerInput` drag on
    the floating cluster writing `QuickActionsSettings.floatingX/Y`, and a feedback URL once one exists.
17. **One file per layer (PLY-7 is partial).** A layer shows either one subtitle file or the player's
    embedded track; loading a second file into the same layer replaces the first. The remembered
    `externalFileKeys` belong to the video that is currently open: they are cleared when another video
    opens, and a file added under Settings → Subtitles is loaded when the player comes back to the same
    video (or by Subtitle tools → Apply). Route: a per-video subtitle table in Room keyed by the
    media URI, then real stacking order inside a layer.
18. **ML Kit model download progress is coarse.** `RemoteModelManager.download` only reports
    start/success/failure, so the Settings → Translation row shows an indeterminate bar while the
    model downloads. Route: none in the public ML Kit API; revisit if a progress callback appears.
