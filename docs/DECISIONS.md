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
`values-fa` provably in sync (446 keys each, checked by `tools/check_resources.py`).

**D-16 — Enum labels are exhaustive `when` in `feature/settings/Labels.kt`, no `else`.** Adding a
`GestureAction` entry must break the build until it has a name; a silent default would ship an
untranslated row.

**D-17 — CI pins actions by commit SHA with `persist-credentials: false`, and `permissions:
contents: read`.** Supply-chain hygiene plus the guarantee that no workflow can push back to the repo.
The instrumented suite is a separate manual workflow because GitHub's standard runners do not reliably
expose KVM; CI still compiles the instrumented sources via `assembleDebugAndroidTest` so API drift in
tests is caught automatically. Amended by D-21/D-22/D-28: the two release workflows (`release.yml`
and `auto-release.yml`) are the only ones that hold `contents: write` (to create a tag and upload
assets), `auto-release.yml` also holds `actions: read` (to fetch the APK artifact of the CI run it
reacts to), and the three of them hold `checks: write` (to publish the failure report). CI itself is
still read-only and never sees a signing secret.

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

## Release and verification

**D-21 — Releases are built by `release.yml` only, split per ABI plus universal, shrunk but not
obfuscated, and debug-signed until a keystore exists.** A `v*` tag (or a manual run with a `tag`
input, which creates the tag on the chosen branch head) builds `assembleRelease`, checks that the tag
equals `versionName`, and uploads `SubLearn-<tag>-{arm64-v8a,armeabi-v7a,x86_64,universal}.apk` plus
`SHA256SUMS` to the GitHub Release; the notes come from the matching `CHANGELOG.md` section
(`tools/release_notes.py`). R8 shrinking is on (resources and dead code; ML Kit and Media3 are large)
but `-dontobfuscate` keeps stack traces readable without a mapping file, which matters more for a
preview that nobody can symbolicate for the user. Signing reads `SUBLEARN_KEYSTORE_*` from the
environment (repository secrets in Actions) and falls back to the debug key so a release always
builds; `REQ-6` tracks the real key. CI runs `assembleRelease` on every push so R8 breakage is found
before a tag, never at release time.

**D-22 — Compile errors travel through a Check Run, not the log.** The sandbox can reach
`api.github.com` but not the Actions log or artifact hosts, so both workflows end with
`tools/ci_failure_report.py`, which posts the first errors of the Gradle log as the output text of a
"Gradle failure report" / "Release failure report" check run on the commit. Annotations alone were
not enough (ten per step, and dependency-resolution failures have none).

**D-23 — Koin binds interfaces explicitly; nothing is resolved by concrete type.**
`single<TranslationProvider> { MlKitTranslationProvider(logger = get()) }`, `single<HttpJsonClient>
{ OkHttpJsonClient() }` and `bind X::class` for the storage and repository implementations, so
feature modules only ever ask for the interface and the fakes slot in without touching the graph.

**D-24 — ML Kit progress is status-only.** `RemoteModelManager.download` has no byte progress, so
`TranslationProgress` carries a `Status` and the UI shows an indeterminate bar instead of a fake
percentage (KNOWN_ISSUES 18).

**D-25 — One hit registry and an explicit layer order for the player.** Everything drawn over the
picture is laid out in one screen in a fixed order (picture, subtitle layers, gesture surface, chrome,
feedback, list, popups, sheets). Subtitle plates, words, chrome, popups and the list report their
coordinates to one `PlayerHitRegistry`, and the gesture surface asks it what a touch means. Subtitle
text never consumes pointer input itself. The alternative, a pointer handler per element, gave the
overlapping-layer bugs that the rebuild removes; its cost is one more class to read, which the
registry's KDoc explains.

**D-26 — Play and pause are centred, not in the bottom row.** PLY-3 lists play/pause among the bottom
controls. The rebuild puts replay, play/pause and forward in one centred cluster, as MX Player does, so
the thumb reaches the main action without leaving the picture. The bottom row keeps the seek bar, the
subtitle steps and the repeat block. This is a deliberate wording deviation from PLY-3.

**D-27 — The player owns its window effects and restores them on leave.** `PlayerScreen` hides the
system bars for the whole screen (they return on swipe), sets the orientation from
`PlayerSettings.orientationLock`, and keeps the screen on while `keepScreenOn` is set. Its
`DisposableEffect`s restore the bars, reset the orientation to unspecified, clear the brightness
override and clear keep-screen-on when the screen leaves. `ON_STOP` is a save point for the position,
and `ON_START` after a stop rebuilds the chrome. Only requests that need the Activity cross the
`PlayerIntent` seam (brightness, volume, PiP, share, navigation).

**D-28 — A green CI run publishes the APKs; nobody pushes a tag by hand.** `auto-release.yml` listens
for a successful CI run (`workflow_run`, pushes only — a `pull_request` run would publish the same
commit twice) and decides the channel from the commit itself. On `main`, if `versionName` has no
`v<version>` tag yet, that is the stable release and it is created on the tested commit, exactly as a
hand-pushed tag would have; every other green push — `main` again at the same version, `phase/**`,
`arena/**` — refreshes one rolling pre-release per branch, tagged `dev-<branch>`, whose assets are
replaced each time and whose release is deleted when the branch is gone. So a merge is the whole
release procedure, and a branch build is downloadable as a pre-release instead of a 14-day artifact
that needs a GitHub login.

The APKs are **not** rebuilt by default: CI already ran `assembleRelease` on that commit, so the
workflow downloads that artifact and publishes the very bytes the checks passed on, which also keeps
the extra cost near zero instead of a second fifteen-minute build. The exception is signing — the
keystore stays out of CI (REQ-6), so when `SUBLEARN_KEYSTORE_BASE64` exists the commit is rebuilt
inside the release workflow and signed there. `release.yml` is unchanged and remains the way to
publish a tag by hand (or to re-publish an old one); a tag created by `auto-release.yml` does not
trigger it, because events raised with `GITHUB_TOKEN` do not start new workflow runs, so there is no
double release. Two consequences worth remembering: `workflow_run` only ever runs the copy of the
file on the default branch, so changes to the pipeline take effect after they are merged; and
`versionCode` is derived from `versionName` in `app/build.gradle.kts` so that a release bumps one
number only.
