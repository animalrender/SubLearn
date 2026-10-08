# Third-party notices

App license: **Apache-2.0** ([LICENSE](LICENSE)). Every runtime dependency below is Apache-2.0, MIT
or BSD compatible with it, so no notice or relicensing obligation is created for downstream users
beyond the Apache-2.0 attribution itself.

Audited 2026-10-08 against the version catalog ([gradle/libs.versions.toml](gradle/libs.versions.toml)).
Only what is actually declared is listed; anything removed from the catalog was removed *because* it
was unused (an unused dependency is an unaudited one).

## Runtime libraries

| Library | Version | License | Used by |
| --- | --- | --- | --- |
| AndroidX Core (core-ktx) | 1.15.0 | Apache-2.0 | app, features |
| AndroidX Activity Compose | 1.9.3 | Apache-2.0 | app, feature:home, player, settings |
| AndroidX Lifecycle (runtime, viewmodel, runtime-compose, process) | 2.8.7 | Apache-2.0 | app, features |
| AndroidX Navigation Compose | 2.8.5 | Apache-2.0 | app, feature:player (test dep only) |
| AndroidX Window | 1.3.0 | Apache-2.0 | app (large-screen folding) |
| AndroidX ProfileInstaller | 1.4.1 | Apache-2.0 | app |
| Jetpack Compose BOM + ui, foundation, material3, ui-tooling | 2024.12.01 | Apache-2.0 | every UI module |
| Material Icons Extended (`androidx.compose.material:material-icons-extended`) | BOM | Apache-2.0 | features (icons only; no icon font is bundled) |
| Media3 (ExoPlayer, common, ui, session, datasource-okhttp) | 1.5.1 | Apache-2.0 | core:player, feature:player |
| Room (runtime, ktx, compiler via KSP) | 2.6.1 | Apache-2.0 | core:data — FTS4 comes from room-runtime; there is **no** `androidx.room:room-fts` artifact |
| DataStore Preferences | 1.1.1 | Apache-2.0 | core:settings, core:security |
| Koin (core, android, android-compose) | 3.5.6 | Apache-2.0 | app, features |
| OkHttp | 4.12.0 | Apache-2.0 | core:ai (also used by Media3's okhttp data source) |
| kotlinx-serialization-json | 1.7.3 | Apache-2.0 | core:settings, core:subtitles, core:ai |
| kotlinx-coroutines (core, android, test) | 1.9.0 | Apache-2.0 | everywhere |
| ML Kit **Cloud AI Translation** (`com.google.mlkit:translate`) | 17.0.3 | Google ML Kit Terms of Use — **see the open item below** | core:translate |
| ML Kit Language Identification (`com.google.mlkit:language-id`) | 16.5.5 | Google ML Kit Terms of Use — same open item | core:translate |
| JUnit 4 | 4.13.2 | EPL-2.0 (was EPL-1.0) | test only |
| Robolectric | 4.14.1 | MIT | test only |
| AndroidX Test (ext-junit, runner), Espresso core, Compose UI test | 1.2.1 / 1.6.2 / 3.6.1 / BOM | Apache-2.0 | test only |
| Kotlin stdlib + kotlin-compose compiler plugin, KSP | 2.0.21 / 2.0.21-1.0.28 | Apache-2.0 | build |

Build tooling: Android Gradle Plugin 8.7.3 (Apache-2.0), Gradle 8.11.1 (Apache-2.0), Temurin JDK 17
(GPL-2.0 with Classpath Exception — a toolchain, not a linked library, so it imposes nothing on the
app). GitHub Actions used: `actions/checkout`, `actions/setup-java`, `actions/upload-artifact`,
`gradle/actions/setup-gradle`, `android-actions/setup-android`,
`reactivecircus/android-emulator-runner` — all MIT, build-time only and not shipped in the APK.

## Bundled content

- **Fonts**: none. The app uses the platform font stack plus whatever the user imports in
  Settings → Fonts (a SAF-picked `.ttf`/`.otf` stays in the user's own storage; SubLearn never
  redistributes it). Persian fallback relies on the device font, which is why `values-fa` sets
  line-height rather than a family.
- **Icons**: no image assets. Every glyph is a Material Icons vector drawn at build time.
- **Launcher icon**: `app/src/main/res/drawable/ic_launcher_foreground.xml` — original geometry by
  this project (a subtitle bubble over a play triangle), no third-party artwork.
- **Word lists / frequency data / CEFR data**: none bundled on purpose. `core:lexicon` reads a
  user-supplied CSV/JSON file (`FrequencyListImporter`) whose format is documented in
  [docs/EXTENSION_POINTS.md](docs/EXTENSION_POINTS.md). Shipping a list would require verifying the
  licence of the list itself, which most frequency corpora do not make clear.
- **Dictionaries and translation models**: never shipped — see below.

## Open item: ML Kit terms (ACTION REQUIRED before any public release)

ML Kit artifacts do not carry an SPDX OSI license in their POMs; they are distributed under the
[Google ML Kit Terms of Use](https://developers.google.com/ml-kit/terms), and the translation models
it downloads at runtime are Google assets that stay on the user's device and are never
redistributed by this repository. SubLearn only uses the **official public API**, so nothing in the
repo depends on a reverse-engineered format — but a project that ships as free software should state
these terms explicitly rather than imply they are Apache-2.0. Tracked in
[docs/AGENT_REQUESTS.md](docs/AGENT_REQUESTS.md) as `REQ-1`; the release notes must link the terms.

## Deliberately not used

| Source | Status | Why |
| --- | --- | --- |
| `hoangkien1703/dual-sub-replay` (MIT) | Ideas only | Its architecture, cue-merging approach and CI shape were adopted; no code was copied, so no notice is required. MIT would have permitted reuse with attribution — the Apache-2.0 re-implementation is cleaner to audit. |
| `kgurniak91/yall-mp` (GPL-3.0) | Ideas only | Timeline editing and preset ideas re-implemented independently. No file, no snippet, no translation unit was taken; GPL code in an Apache project would force relicensing. |
| `arianneorpilla/jidoujisho` (GPL-3.0) | Ideas only | Same reason. Its selection and swipe-to-repeat gestures are re-implemented from the described behaviour. |
| `melonityhub/dictionaryproject` | **Schema not shipped, data never** | Its README states the schema and queries came from a third-party Android dictionary app's Java source and that the model loader was reverse-engineered from `.bipe` files. That makes both the data and the loader unsafe to redistribute. SubLearn therefore treats an offline dictionary as a **user-supplied local import** with its own documented schema, and translates only through ML Kit. Logged as a rights question in `docs/AGENT_REQUESTS.md`. |
| SubX Player (closed-source, commercial) | UX reference only | No code or asset access; only publicly observable behaviour was used as a target for the player UX. |

## Attribution format used by this project

Per Apache-2.0 §4 the notices above are sufficient for redistribution; each `core:*` source file
keeps its own copyright header line, and nothing else is required. If a later phase vendors source
code (rather than depending on an artifact), add a `NOTICE` file entry naming the project, its license
and the upstream commit, and list the files it applies to.
