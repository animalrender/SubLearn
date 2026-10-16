# Requests and open questions for the owner

Anything the agent could not resolve alone, or should not decide alone. Each entry: what was needed,
what was tried, suspected cause, next step, severity. Nothing is blocked silently — if a request is
blocking, the workaround used in the meantime is stated too.

---

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

### REQ-4 — Pick a frequency/CEFR list to point users at
- **Phase/task**: 6 (LRN-2 level popups).
- **Needed**: a word-frequency or CEFR word list in a machine-readable form.
- **Tried**: checking the well-known corpora; most are research-only dump formats with no stable
  download, and the sandbox cannot reach the hosts that carry them.
- **Suspected cause**: word lists are usually published as papers, not as datasets.
- **Workaround used**: nothing bundled; `FrequencyListImporter` reads a user-picked CSV/JSON (documented
  format) and `KnownWordsLevelProvider` is the default when nothing is imported.
- **Next step**: owner picks a list (or links one in the README as "download it yourself"), then we add
  a `settings` shortcut to import it.
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
- **Update (2026-10-11)**: `auto-release.yml` reads the same four secrets and decides from them how a
  build is produced (D-28). While they are absent it republishes the APKs CI already built and tested
  (debug-signed, and the notes now name the key instead of hedging); the moment
  `SUBLEARN_KEYSTORE_BASE64` exists it rebuilds the released commit inside the release workflow and
  signs it there, so the key never enters CI. Nothing else has to change when the key arrives.
- **Next step**: owner adds the four secrets (Settings → Secrets and variables → Actions); the next
  release is then properly signed. Users of a debug-signed build must uninstall before installing it.
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

---

### REQ-10 — Lexicon pack hosting URL — **resolved 2026-10-16**
- **Phase/task**: 10 (Lexicon pack).
- **Needed**: the URL where the owner will host `sublearn-lexicon-en-fa-v2.sqlite` after running the
  converter. The Model Manager catalog will point at this URL as the default download source.
- **Resolution**: the packs are published in the `packs-v2.0.0` release of this repository, and the
  catalog now carries their real size and SHA-256, read from the release API. Settings → Downloads
  downloads them from there and verifies them (D-83).
- **What remains**: the catalog's pack entries must keep matching the published assets. That is
  checked by `tools/check_catalog.py` (the app's copy against the catalog) and by
  `verify-catalog.yml` (the catalog against the release), so a re-published pack that changes its
  bytes fails the build instead of failing on the user's phone.

### REQ-12 — Verify catalog entries for models
- **Phase/task**: 11 (Model manager).
- **Needed**: for each model in the catalog, the sandbox cannot reach Hugging Face, GitHub model
  repos or CDN hosts to verify file size and SHA-256. Entries are written with
  `verified: false` and only visible in debug builds until verified.
- **Workaround**: unverified entries carry a clear label in the UI and a log in this file.
- **Next step**: owner or a future agent with network access verifies each entry.

### REQ-13 — A real video + subtitle for manual testing
- **Phase/task**: 12–16 (phrase detection, popup card, profiles).
- **Needed**: a short video with an English SRT file that contains phrasal verbs, idioms and
  collocations, for manual testing of the underline and popup card.
- **Workaround**: the synthetic eval set in `tools/eval/` is used for automated testing; manual
  testing waits for the owner's video.
- **Next step**: owner provides a video + subtitle pair.

### REQ-12.1 — Run the catalog verification workflow
- **Phase/task**: 11 (Model manager).
- **Needed**: the agent sandbox cannot reach model hosting sites (Hugging Face, etc.) to verify
  catalog entries. A GitHub Actions workflow `verify-catalog.yml` has been created that runs on
  GitHub's servers where network access is available.
- **How to run**: Go to Actions → "Verify Model Catalog" → "Run workflow". The workflow fetches
  each catalog entry's URL and checks its size and hash, and produces two artifacts:
  - `catalog_verification_report.json` — summary of what was verified and what wasn't
  - `catalog-v1.verified.json` — the catalog with updated `verified` flags
- **Update (2026-10-16)**: the workflow now also asks the GitHub API for the digest of every
  published release asset and fails when the catalog disagrees with it. That check is what turned
  the "SHA mismatched" download into a red build (D-83), and it runs on every push that touches
  `docs/catalog/catalog-v2.json`.
- **Next step**: download the artifacts and share the report with the agent so it can update
  the catalog entries that were verified.
- **Severity**: medium (needed before models can be shown as available in release builds).
