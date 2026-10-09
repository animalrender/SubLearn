# Requests and open questions for the owner

Anything the agent could not resolve alone, or should not decide alone. Each entry: what was needed,
what was tried, suspected cause, next step, severity. Nothing is blocked silently — if a request is
blocking, the workaround used in the meantime is stated too.

---

### REQ-1 — Confirm ML Kit's terms are acceptable for a free-software release
- **Phase/task**: 0 (license audit), blocking for a public release.
- **Needed**: the exact redistribution terms for `com.google.mlkit:translate` 17.0.3 and
  `language-id` 16.5.5, and whether stating "ML Kit Terms of Use" in `THIRD_PARTY_NOTICES.md` is enough.
- **Tried**: reading the POMs for an SPDX field (ML Kit artifacts declare no OSI license); checking the
  terms page (network to that host is blocked in the sandbox); comparing with how other FOSS Android
  apps list it.
- **Suspected cause**: ML Kit is Apache-2.0-ish source but the *service and downloaded models* are
  Google assets under their own terms.
- **Workaround used**: only the official public API is used; no model file is bundled or referenced; the
  notice file states the terms instead of implying Apache-2.0.
- **Next step**: owner confirms the wording, or asks for a non-ML Kit translation path behind
  `TranslationProvider` (an interface already exists for exactly this).
- **Severity**: medium (release-blocking for a store listing, not for the APK build).

### REQ-2 — Decide the dictionary question on the record
- **Phase/task**: 0 (license safety), affects LRN-1 full details.
- **Needed**: confirmation that SubLearn must never ship a dictionary database or a loader derived from
  the reverse-engineered `.bipe` format, and that "user picks a local file" is the accepted design.
- **Tried**: reading `dictionaryproject`'s README (it documents the provenance itself: schema/queries
  from a third-party app's Java source, loader reverse-engineered from model files); checking whether a
  permissively licensed English dictionary with the same sections exists (none verifiable from inside
  this sandbox).
- **Suspected cause**: the reconstruction is a research artefact, not a redistributable dataset.
- **Workaround used**: `.gitignore` blocks `*.sqlite`, `*.db`, `*.bipe`, `fastdic_plain*`,
  `assets/dictionaries/`, `assets/models/`; CI fails on any tracked match; `DictionaryProvider` is a
  stub behind `FeatureFlag.OFFLINE_DICTIONARY`; our own import schema is documented in
  [EXTENSION_POINTS.md](EXTENSION_POINTS.md).
- **Next step**: owner confirms; if they hold rights to the data, it still stays out of the repo and
  ships as a separate user download with its own licence file.
- **Severity**: high (rights, not code).

### REQ-3 — A way to compile before pushing (JDK or a self-hosted runner)
- **Phase/task**: every phase; the loop in AGENTS.md.
- **Needed**: `./gradlew assembleDebug test lintDebug` locally, or a runner where the agent can execute
  Gradle.
- **Tried**: (1) installing a JDK from apt — no mirror reachable (only github.com, codeload,
  api.github.com, registry.npmjs.org, pypi.org are allowed); (2) downloading a Temurin tarball — host not
  reachable; (3) running Gradle from the wrapper with a system JVM — no JVM exists at all.
- **Suspected cause**: deliberate egress allowlist in the sandbox.
- **Workaround used**: four static checkers in `tools/` (name, style, dependency-declaration,
  resources), a pure-JVM kotlinc harness for the non-Android modules (a JRE from the `jdk4py` wheel on
  pypi and `kotlin-compiler` from npm are reachable; Gradle, Maven Central and the Android SDK are
  not), and a CI loop that publishes the first Gradle errors as the "Gradle failure report" check run
  (`tools/ci_failure_report.py`, D-22) because neither the Actions log host nor the artifact host is
  reachable from here. Cost: each Android iteration is minutes instead of seconds.
- **Next step**: either allow `download-java` hosts, or register a self-hosted runner with a JDK;
  otherwise accept the CI loop as the build step.
- **Severity**: medium (it is the main reason red commits appear on the branch).

### REQ-4 — Approve a frequency/CEFR list we are allowed to point users at
- **Phase/task**: 6 (LRN-2 level popups).
- **Needed**: a permissively licensed word-frequency or CEFR word list. The spec says use one "if you can
  verify its license", otherwise drive popups from "words not marked as known".
- **Tried**: checking well-known corpora's licences (most are research-licensed, non-commercial or
  unclear for redistribution), checking whether the sandbox can reach any licence text to verify rather
  than remember (it cannot).
- **Suspected cause**: word lists are usually released without a machine-readable licence.
- **Workaround used**: nothing bundled; `FrequencyListImporter` reads a user-picked CSV/JSON (documented
  format) and `KnownWordsLevelProvider` is the default when nothing is imported.
- **Next step**: owner picks a list they can confirm (or links one in the README as "download it
  yourself"), then we add a `settings` shortcut to import it.
- **Severity**: medium (feature quality, not correctness).

### REQ-5 — Time/scope confirmation for view-model tests
- **Phase/task**: 9 (test gaps).
- **Needed**: view-model tests driving `FakePlayerController` (auto-hide, repeat plan, popup pause,
  quick-action invert-on-hold) and a couple of Compose UI tests on device.
- **Tried**: writing the player view model so it *can* be tested this way (single snapshot writer,
  controller behind an interface, no Android types in the reducer).
- **Suspected cause**: none — this is sequencing. The first compile of 150 files had to come first.
- **Next step**: land them in phase 9 on top of green CI; they should not be written against code that
  has not compiled yet.
- **Severity**: medium (highest-value new tests available).

### REQ-6 — Release signing and where the APK goes
- **Phase/task**: 9 (v0.1.0).
- **Needed**: a decision on the release key (keystore never in the repo), whether `v0.1.0` ships a
  signed release APK or only the debug artifact CI produces today, and whether F-Droid metadata
  (`fastlane/`) should be added.
- **Tried**: wiring `release` to the debug signing config as a placeholder that *builds* and is
  documented in the build file, so no keystore is ever needed to reproduce a build.
- **Suspected cause**: not an agent decision (secrets).
- **Workaround used (2026-10-09)**: `app/build.gradle.kts` creates a `release` signing config only when
  `SUBLEARN_KEYSTORE_PATH`, `SUBLEARN_KEYSTORE_PASSWORD`, `SUBLEARN_KEY_ALIAS` and
  `SUBLEARN_KEY_PASSWORD` are set, and `release.yml` fills them from the repository secrets
  `SUBLEARN_KEYSTORE_BASE64` (the `.jks` as base64), `SUBLEARN_KEYSTORE_PASSWORD`,
  `SUBLEARN_KEY_ALIAS` and `SUBLEARN_KEY_PASSWORD`. Without them the release APKs are debug-signed and
  the release notes say so (D-21). F-Droid metadata is not added.
- **Next step**: owner adds the four secrets (Settings → Secrets and variables → Actions); the next tag
  is then properly signed. Users of a debug-signed build must uninstall before installing it.
- **Severity**: low for the tag, high for a store listing.

### REQ-7 — Device for the verification pass and README screenshots
- **Phase/task**: 9.
- **Needed**: a physical device or an emulator the agent can drive, to verify RTL rendering, animation,
  PiP, process death and gestures, and to take the screenshots the README should have.
- **Tried**: instrumented tests as the substitute (need KVM; kept as a manual workflow),
  Robolectric/Compose unit tests (real font measurement is missing, so hit testing cannot be trusted
  there).
- **Next step**: owner runs `.github/workflows/instrumented.yml`, or supplies screenshots/notes.
- **Severity**: medium (GEN-1/GEN-4 claims cannot be honestly closed without it).

### REQ-8 — Confirm the branch strategy after the workspace reset
- **Phase/task**: 1 (git protocol).
- **Needed**: the intended shape — one branch per phase with a PR each — versus what exists now.
- **Tried**: the earlier phase branches and their history were lost when this sandbox was re-cloned
  (only `main`'s `hello world` commit survived on the remote); the code was recovered from the workspace,
  the commits were not. The whole implementation now lives on this session's pinned branch with a PR.
- **Suspected cause**: no pushes had happened for the uncommitted work before the reset.
- **Workaround used**: phase boundaries are preserved as commit groups on one branch (D-20), CI is green
  or red per push, `main` untouched.
- **Next step**: merge PR #1 to `main` when green (owner's self-merge preference respected); later phases
  go on their own `phase/N-name` branches as planned.
- **Severity**: low (process, not product).

### REQ-9 — Sign-off on the offline dictionary import schema
- **Phase/task**: stretch item 1.
- **Needed**: approval of the one-row-per-sense schema and CSV acceptance written in
  [EXTENSION_POINTS.md](EXTENSION_POINTS.md) before anyone writes an importer or a converter tool.
- **Tried**: matching the sections the reference card shows (meanings, synonyms/antonyms, phrasal verbs,
  collocations, idioms, word family, CEFR, categories) with a schema that a user can actually produce.
- **Next step**: owner confirms; then the importer, its fixtures and the Settings UI land together.
- **Severity**: low.
