# Decisions and assumptions

Every consequential choice, with the reason and the alternative that was rejected. Items tagged
**ASSUMPTION** were made by the agent without a human answer and are the first things to review.

## Product and licensing

**D-1 — Apache-2.0 for the app. ASSUMPTION-lite.** Every shipped dependency is Apache-2.0/MIT/BSD
compatible, and a patent grant matters for a project that will take outside contributions of
decoder/ML code. MIT was rejected for that grant; GPL was impossible (it would be compatible with the
references but would forbid the Apache-2.0 AndroidX samples we may vendor later). Canonical text from
the SPDX/choosealicense source, not typed from memory.

**D-2 — No bundled dictionary, word list or model file, ever.** The reconstruction repo we were told
to study got its schema from another app's Java source and its loader from reverse-engineered `.bipe`
files, so neither its data nor its loader may be redistributed. What ships instead: `DictionaryProvider`
reading a **user-picked local file** with a documented schema, and an importer in `core:lexicon` for a
user-picked frequency list. Enforced by `.gitignore` (`*.sqlite`, `*.db`, `*.bipe`, `fastdic_plain*`,
`assets/dictionaries/`, `assets/models/`) and by the CI hygiene guard. Translation is official ML Kit
only, whose models the Play services download themselves and which we never repackage.

**D-3 — Offline-first is a hard feature boundary.** No NOW feature may require network except the
opt-in AI request. Level popups therefore work off "words not marked as known" when nothing is
imported, and the imported-list provider is a *settings choice*, not a silent download.

## Platform

**D-4 — minSdk 26 (Android 8.0). ASSUMPTION.** Chosen because `java.time` needs 26 (otherwise a
desugaring or ThreeTen dependency for two formatters), Keystore-backed symmetric crypto is
predictable from 23 but stable from 26, and edge-to-edge plus `WindowInsetsControllerCompat`
behaviour are only trustworthy at 26+. Media3 needs 21 and Compose needs 21, so this costs nothing on
the library side. If a wide-device legacy floor matters, drop to 24 and add core library desugaring.

**D-5 — target/compile SDK 35, JDK 17 toolchain.** AGP 8.7.3 is the newest release that still works
with the pinned Gradle 8.11.1 and Kotlin 2.0.21 without a Kotlin/Compose plugin mismatch.

**D-6 — Koin instead of Hilt.** Hilt's KAPT/KSP + AGP plugin adds a build step per module and couples
the graph to annotations; SubLearn's graph is small, hand-written and easier to audit in a PR
(`app/di/AppModule.kt`, one `subLearnModules` list). The KDoc in each module's `*.kts` explains what
would change if Hilt were preferred later.

**D-7 — Hand-rolled navigator instead of Navigation Compose. ASSUMPTION.** The app has one Activity,
six destinations and no deep-link graph; `AppNavigator` holds a `StateFlow<AppRoute>` plus the pending
`PlayerStart`. That keeps PiP handoff, `onNewIntent`, and "leave the player without stopping playback"
explicit instead of buried in nav-argument plumbing, and it avoids a second back-stack competing with
predictive back. Revisit if nested graphs or per-destination state restoration appear.

**D-8 — SubLearn paints the subtitle layers itself; the player's `SubtitleView` is force-hidden.**
Required by SUB-4/SUB-5: per-word hit testing, styling and per-layer placement are impossible on the
player's own view, and leaving it visible causes double rendering. Embedded tracks still work: they
arrive as live cues and fill a layer (`LayerSource.PLAYER_CUES`), which is why the list view, block
stepping and batch tools are file-only and show an explanatory empty state rather than nothing.

**D-9 — Decoder SW/HW/HW+ mapping, documented and honest.** `HARDWARE` = MediaCodec defaults with
decoder fallback disabled; `HARDWARE_PLUS` = defaults with `setEnableDecoderFallback(true)` (that is
what "HW+" means in practice on Android: try hardware, fall back per-stream); `SOFTWARE` = a
`MediaCodecSelector` that asks for software decoders. Media3's FFmpeg extension was rejected: extra
native build, ~4 MB, and it changes the license surface for a mode almost no device needs. The UI
therefore never advertises "any codec": the sheet explains what each choice does.

## Architecture

**D-10 — One immutable UI state per screen, written only by the view model** (`PlayerUi` in
`feature:player`, `WordsUi`, `SettingsViewModel` state). Recomposition sees a consistent snapshot, and
a preview/test can construct one literal. The state file also owns the *derived* facts the screen needs
(`canStepBySubtitle`, `matches`, `currentBlock`) so composables contain no logic.

**D-11 — `PlayerController` stays media-only.** Subtitle text, delay, layer stacking, popups and
shadowing live in `feature:player`'s view model, because they need app state (settings, Room,
translation). `FakePlayerController` then covers all player tests without an ExoPlayer instance.

**D-12 — Parsers → normalizer → block builder are three pure stages.** Text fixes (tags, whitespace,
punctuation, mid-sentence splits, max chars) never touch parsing, and block grouping never touches
rendering. Each is unit-tested in isolation, which is the only reason the styling layer can stay dumb.

**D-13 — Settings are one `@Serializable AppSettings` with a schema version.** Migration is a
`DataStore`-level `TransformSpec`-style upgrade; adding a field with a default does *not* need a bump
(currently SCHEMA_VERSION 3), removing or reshaping one does. JSON export/import is the same
serialization, which is what makes the settings screen and the file format impossible to drift apart.

**D-14 — Secrets: Keystore AES-GCM, ciphertext in DataStore, never plaintext on disk.**
`AndroidSecretStore` + `AesGcmCipher` in `core:security`; no deprecated `EncryptedSharedPreferences`,
no SQLCipher. Keys are excluded from backups via `data_extraction_rules.xml`, are never logged, and
the AI providers read them only at request time through `SecretStore`.

**D-15 — String resources live only in `core:designsystem` (EN + FA).** A feature adding a string in
its own module would be invisible to the other features and to the RTL audit; the single owner keeps
`values-fa` provably in sync (418 keys each).

**D-16 — Enum labels are exhaustive `when` in `feature/settings/Labels.kt`, no `else`.** Adding a
`GestureAction` entry must break the build until it has a name; a silent default would ship an
untranslated row.

**D-17 — CI pins actions by commit SHA with `persist-credentials: false`, and `permissions:
contents: read`.** Supply-chain hygiene plus the guarantee that no workflow can push back to the repo.
The instrumented suite is a separate manual workflow because GitHub's standard runners do not reliably
expose KVM; CI still compiles the instrumented sources via `assembleDebugAndroidTest` so API drift in
tests is caught automatically.

**D-18 — RTL is decided per text run, not per app locale.** Subtitle layers and popups pass an
explicit `TextDirection` derived from the content, because a Persian gloss inside an English card (and
an English word inside a Persian subtitle) is the normal case, not an edge case.

## Scope discipline

**D-19 — LATER items get interface + stub + flag + disabled UI row + doc section, and nothing else.**
No placeholder lists, no fake numbers. `FeatureFlag` gates them so promoting one is a settings change
plus an implementation, never a refactor of the NOW features.

**D-20 — Phase 0 and 1 landed on one reviewable branch instead of two stacked PRs. ASSUMPTION.** The
first real compile of this codebase is CI (no JVM in the sandbox); splitting uncompiled UI code from
the modules it calls across two PRs would only have doubled the red runs. Phase boundaries are still
kept in the commit history (`chore(repo)` / `feat(core)` / `feat(features)` / `feat(app)` / docs).
