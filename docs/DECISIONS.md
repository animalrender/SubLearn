# Decisions and assumptions

Every consequential choice, with the reason and the alternative that was rejected. Items tagged
**ASSUMPTION** were made by the agent without a human answer and are the first things to review.

## Product

**D-2 — No bundled dictionary, word list or model file in Git or the APK, ever. (Revised by D-58 / D-62).**
Large binary blobs do not belong in the app package or in git, so they stay outside it: what ships
instead is a `DictionaryProvider` reading an imported or downloaded pack (`sublearn-lexicon-en-fa-v2.sqlite`
or `sublearn-dict-fa-en-v1.sqlite`), a Legacy v1 `.bipe` / `main_db.sqlite` importer, and a frequency
list importer in `core:lexicon`. Enforced by `.gitignore` (`*.sqlite`, `*.db`, `*.bipe`,
`fastdic_plain*`, `assets/dictionaries/`, `assets/models/`). Translation is multi-source via
`TranslationOrchestrator` (offline `PACK`, `ML_KIT`, `LEGACY_V1`, plus optional user-enabled
`ONLINE_GOOGLE` — see D-58 and D-62).

**D-3 — Offline-first with explicit, user-controlled online opt-in. (Revised by D-58 / D-62).**
Core playback, subtitle parsing, dictionary lookup, and phrase detection work with zero network access
once a pack is installed. Network requests happen only when explicitly enabled by the user (pack/model
downloads, opt-in AI helper, or opt-in `ONLINE_GOOGLE` word/phrase lookup). An "Offline only" master
switch in Settings disables all online translation and AI calls immediately.

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
native build and ~4 MB for a mode almost no device needs. The UI
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
`values-fa` provably in sync (472 keys each, checked by `tools/check_resources.py`).

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

**D-29 — Three docked action zones, with the video centre kept clear.** The main toolbar is `DockMode.TOP`,
the dedicated one-line scroll rail is `DockMode.BAR`, and the transport row is `DockMode.PLAYBACK`;
`FLOATING` and `HIDDEN` remain explicit choices. The old `BAR` persisted value becomes the second
row without a migration. Expanded mode labels docked actions, while compact and floating controls stay
icon-only. Dock choice and ordering live in the player action manager rather than relying on drag-only
discovery.

**D-30 — Accessible Recent Videos are the playback queue; external audio is merged only for its video.**
Previous/next video uses the recent list in chronological order and excludes `content://` entries with
no persisted read grant (the current, explicitly opened item is always kept). An external audio source
is merged through Media3 `MergingMediaSource` for the target it was added to; it does not silently
replace the video's own audio or follow unrelated playlist items. The SAF picker persists grants and
Home asks the user to reselect a recent item if its grant is stale.

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

**D-26 — Superseded: play/pause used to sit in a centre cluster.** The 2026-10-11 transport redesign
removed that cluster to meet PLY-3: play/pause, video navigation, subtitle-cue steps and arranged
actions now share the distinct bottom playback row. See D-29 and the current player checklist; this
entry records the old 0.2.0 layout only.

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

**D-31 — Phrase analysis runs on the cue token stream across adjacent cues, projected back onto SubtitleBlock. (Revised by D-45).**
Originally phrase analysis ran per `SubtitleBlock` with a heuristic `CrossBlockBridge`. Because
`BlockBuilder` caps blocks at `maxBlockChars=84`, expressions frequently split across adjacent blocks.
Under D-45, `PhraseEngine` builds a token stream over consecutive cues where `nextCue.startMs - prevCue.endMs <= 1200ms`
and there is no sentence-final punctuation (`.`, `!`, `?`, `…`), runs `DeterministicPhraseDetector` on
the continuous stream, and projects matched spans back onto each `SubtitleBlock` (and individual cue
ranges). `CrossBlockBridge` is removed.

**D-32 — One pass at subtitle-load time, cached in Room.** Phrase analysis is not per-frame. It runs
once when the learning subtitle layer opens, results are written to a `phrase_span` table keyed by
`(sourceKey, packId, packVersion, analyzerVersion, spanId)` (see D-46), and playback only reads from
the cache. Analysis runs on `Dispatchers.Default`, prioritises blocks near the current playhead and is
cancellable.

**D-33 — Lexicon + lemmatiser + deterministic matcher first, models only refine.** The base engine
works without any downloaded model. Models only add POS tagging, sense ranking and literal-vs-idiomatic
disambiguation. No model ever produces free-form Persian translation.

**D-34 — Precision over recall.** A wrong underline is worse than a missing one. The default
confidence threshold for visible underlines is 0.75; below that the match appears only as a
"possible" row in the touch card.

**D-35 — Lexicon pack is a user-supplied or release-downloaded file, never committed.** The format is
SubLearn's own (`sublearn-lexicon-en-fa-v2.sqlite`); a Python converter in `tools/lexicon_builder/`
builds it from the owner's database and publishes it to GitHub Releases via `build-lexicon-pack.yml`.
The pack is downloaded through the Resource Library (`feature:packs`) or imported by direct file pick.
No database or derived file is committed (D-2 still applies).

**D-36 — No statistical collocation table or new model training in v1.** Collocations come only from
those already present in the lexicon database. A future v2 may add a frequency table but that is a
separate phase and decision.

**D-37 — Model weights are never in the APK or the repo.** A JSON catalog lists models and packs with
their source URLs and SHA-256; the Resource Library downloads them on demand from the host. The default
catalog URL points at `docs/catalog/catalog-v2.json` in this repo (raw GitHub URL), and the user can
override it in Settings.

**D-38 — Multi-source translation orchestration instead of ML Kit-only fallback. (Revised by D-58 / D-62).**
Instead of hardcoding ML Kit as the sole translation fallback, `TranslationOrchestrator` coordinates four
pluggable sources (`PACK`, `ML_KIT`, `LEGACY_V1`, `ONLINE_GOOGLE`) in either `PRIORITY_CHAIN` or
`MERGE_ALL` mode. Every translation result carries provenance chips (`SourceBadge`) so the user always
sees whether a meaning came from Lexicon v2, FastDic v1, ML Kit, or Online Google Translate.

**D-39 — ProudVocab is a visual reference only.** The card design, colors and badge shapes are
re-implemented from scratch in Compose using the project's own `Tokens.kt`, `Motion.kt` and
`LocalSubLearnColors`. No CSS or JS is ported.

**D-40 — `WordVisualState.PHRASE` is replaced by three exhaustive states.** `PHRASAL`, `IDIOM` and
`COLLOCATION` replace the single `PHRASE` value so that every `when` in the codebase becomes
exhaustive at compile time. The old `phrases` WordStyleSetting migrates by copying its value to all
three new states.

**D-41 — A new `core:nlp` module is pure Kotlin (no Android).** It owns the Span model, lemmatiser,
pattern compiler, matcher and sense-ranking interfaces. It is JVM-testable with plain `kotlinc` and
has no Compose, Room or Android dependencies.

**D-42 — ONNX Runtime is isolated in `core:nlp-onnx`.** The ONNX dependency and its ABI-specific
native libraries live in a separate module so the APK size impact is measurable and the module can
be dropped entirely. `core:nlp-llm` is similarly isolated for the MediaPipe GenAI `.task` LLM adapter.

## Phase 10–19 Architecture & Product Decisions (D-43 .. D-62)

**D-43 — Lexicon Pack v2 SQLite schema (`format_version = 2`).**
The v2 lexicon pack (`sublearn-lexicon-en-fa-v2.sqlite`) adds `lemma_form(form, lemma, pos, rank)` with
composite primary key `(form, lemma)`, `entry` with `UNIQUE(key, kind)`, `sense` with `gloss_en`,
`domain_fa`, `domain_en`, `cefr`, `is_primary`, and `prior`, `example` with `UNIQUE(sense_id, en)`,
`pattern` with precomputed `anchor_lemma` and `separable`, `word_pos(word, pos, freq)` for dictionary
POS tagging, `entry_extra(entry_id, synonyms, antonyms, word_family, notes)` using compact `' | '`-joined
strings (capped at 20 items per field to avoid row-count bloat), `pron(entry_id, ipa_us, ipa_uk, audio_us, audio_uk)`,
`colloc(entry_id, colloc_entry_id, position)`, and `entry_fts` (FTS4 on `key, headword`).

**D-44 — Converter normalization, intermediate-lemma pruning, and MWE deduplication.**
In `main_db.sqlite`, inflected rows copy their base paradigm's sibling inflection columns (e.g. `broke`
has `past_participle -> broken`; `best` has `comparative -> better`), and every idiom in `english_idioms`
is already a row in `english_words`. `build_lexicon.py` therefore:
1. Prunes intermediate forms using `infinitive` and root-paradigm ownership so `broken -> break (rank 0)`,
   `gone -> go (rank 0)`, `best -> good/well (rank 0)` while preserving homograph self-lemmas (`left -> leave (0), left (1)`;
   `saw -> see (0), saw (1)`).
2. Groups `english_words` by normalized `key` into a single `entry` per key (`IDIOM > PHRASAL > COLLOCATION > PHRASE > WORD`),
   using `english_idioms` only for MWE classification and `colloc` parent-child links, eliminating the 2x
   sense/example duplication.
3. Expands `(or X)` alternatives and optional `(X)` groups into up to 8 concrete patterns per entry,
   replaces `one's`/`someone's` with `POSS`, reflexives with `REFL`, and `someone`/`something` with `{NP}`,
   selects the verb lemma as `anchor_lemma` for `PHRASAL` and lowest-DF content lemma for `IDIOM`/`COLLOCATION`,
   and sets `separable = 1` only for transitive 2-word phrasal verbs with separable particles.

**D-45 — Cue-stream phrase detection across block boundaries.**
Instead of running `DeterministicPhraseDetector` on isolated `SubtitleBlock` texts and patching across
blocks with `CrossBlockBridge`, `PhraseEngine` constructs a continuous token stream across consecutive
cues whose gap is `<= 1200ms` and where the preceding cue does not end with terminal punctuation
(`.`, `!`, `?`, `…`). Matched spans are projected back onto each `SubtitleBlock` and cue character range,
and `CrossBlockBridge` is deleted.

**D-46 — Precompiled pattern cache and Room `phrase_span` composite key.**
`DeterministicPhraseDetector` precompiles `PatternRule` instances once per lexicon load into an
anchor-indexed map (`Map<String, List<CompiledPattern>>`) instead of recompiling regexes per block.
Room caches detected spans in `phrase_span` keyed by `(sourceKey, packId, packVersion, analyzerVersion, spanId)`
so upgrading a lexicon pack or analyzer automatically invalidates stale spans without manual cache clearing.

**D-47 — Dictionary-backed POS tagger (`LexiconPosTagger`).**
A fast, zero-model POS tagger in `core:nlp` uses the `word_pos(word, pos, freq)` table from the v2 lexicon
pack combined with English suffix heuristics (`-ing`, `-ed`, `-ly`, `-tion`, `-ness`, `-able`, `-ous`) and
contextual disambiguation rules (e.g., `DET + X -> NOUN/ADJ`, `TO + X -> VERB`, `VERB + pron + PART -> RP`
vs `PREP + DET + NOUN -> IN`) so separated phrasal verbs (`pick the book up`) and POS-dependent sense
ranking work accurately even in the Base/Light profiles without an ONNX POS model.

**D-48 — Range-based `WordVisualState` and Canvas dashed/dotted underlines.**
Compose `SpanStyle(textDecoration = TextDecoration.Underline)` only draws solid lines. `SubtitleOverlay`
uses `TextLayoutResult` (`onTextLayout`) to query bounding boxes (`getBoundingBox` / `getPathForRange`)
for each styled token or phrase range and draws real `PathEffect.dashPathEffect(floatArrayOf(8f, 5f))`
(dashed) and `floatArrayOf(2f, 4f)` (dotted) underlines on a `drawBehind` Canvas layer.

**D-49 — ProudVocab dual-section touch card.**
When the user taps a word that belongs to a detected `PhraseSpan` (phrasal verb, idiom, or collocation),
the touch popup card renders two sections in one unified surface (`border-radius: 12dp`, dark glass
`#0A0F1E` at 92% alpha, `1.5dp` indigo border `#6366F1` at 35% alpha): the tapped single word at the
top (with IPA, audio button, POS/CEFR badges, word senses, and word bookmark button) separated by a
`1dp` divider (`rgba(255,255,255,0.12)`) from the matched phrase at the bottom (with `PHRASAL` / `IDIOM` /
`COLLOCATION` badge, full phrase Persian meaning, and an independent phrase bookmark button).

**D-50 — Saved phrases keep their phrase-type color by default (`savedPhrasesKeepTypeColor = true`).**
Single saved words turn emerald green (`#10B981`) with a dashed underline. However, coloring saved
phrasal verbs, idioms, and collocations green would erase the visual distinction between purple phrasal
verbs (`#C084FC`), orange idioms (`#FB923C`), and sky-blue collocations (`#38BDF8`). By default,
`savedPhrasesKeepTypeColor = true` keeps the phrase's type color while showing the filled bookmark
indicator in the card; users can toggle this in Settings.

**D-51 — Catalog v2 (`docs/catalog/catalog-v2.json`) and verified-only release entries.**
Catalog v2 unifies packs and AI models in one schema (`version: 2`) with explicit `kind`, `sizeBytes`,
`sha256`, `minRamMb`, `url`, and `verified` fields. The unverified `pos-opennlp-ud` placeholder is
removed. Only entries whose URLs and SHA-256 checksums have been verified are shown by default in release
builds (`verify-catalog.yml` validates every entry).

**D-52 — Six NLP performance profiles (`Base`, `Light`, `Balanced`, `Precise`, `Advanced`, `Smart`).**
Replaces the earlier 4-tier enum with six clear profiles:
- `BASE`: Lexicon + Lemmatiser + Deterministic Matcher (0 MB extra, works on all devices).
- `LIGHT`: Base + `LexiconPosTagger` (`word_pos` + contextual rules, 0 MB extra).
- `BALANCED`: Light + MiniLM sentence embeddings (`minilm-l6-v2`, ~23 MB ONNX) for sense ranking.
- `PRECISE`: Balanced + MobileBERT POS/context (`mobilebert-uncased`, ~25 MB ONNX) for literal-vs-idiomatic scoring.
- `ADVANCED`: Precise + Gemma 2B / Qwen2.5 1.5B MediaPipe `.task` LLM selector (`core:nlp-llm`) for ambiguous lines.
- `SMART`: Automatic profile selection via `DeviceCapabilityProbe` (RAM, CPU cores, storage, battery saver)
  and `ModelRuntimeGuard` (auto-downgrades on OOM, timeout > 500ms, or native crash).

**D-53 — MediaPipe Tasks GenAI (`.task`) for `core:nlp-llm` (Revises earlier llama.cpp assumption).**
Instead of compiling a custom JNI `llama.cpp` bridge for `.gguf` files, `core:nlp-llm` uses Google's
official `com.google.mediapipe:tasks-genai` (`LlmInference`) with `.task` model bundles. The LLM is
strictly constrained to selecting a sense index (`1..N`) or `LITERAL` vs `IDIOMATIC` from lexicon
candidates and never generates free-form Persian text.

**D-54 — WorkManager-backed resumable downloader (`PackDownloadWorker`).**
All large pack and model downloads (> 5 MB) run through `WorkManager` with HTTP `Range` resume support
(`.part` file + ETag/Last-Modified tracking), streaming SHA-256 verification before atomic rename,
free-disk-space preflight check (`>= 2.2x` compressed size), and a configurable Wi-Fi-only guard for
downloads `> 50 MB`.

**D-55 — Automated pack build workflows (`build-lexicon-pack.yml`, `build-extra-packs.yml`, `release-packs.yml`).**
Because `main_db.sqlite` (~98 MB) lives on GitHub Releases of `melonityhub/dictionaryproject`, a manual
`workflow_dispatch` workflow (`build-lexicon-pack.yml`) downloads `main_db.sqlite`, runs `build_lexicon.py`
for both `en-fa` (`sublearn-lexicon-en-fa-v2.sqlite.gz`) and `fa-en` (`sublearn-dict-fa-en-v1.sqlite.gz`),
runs `validate_pack.py --full`, uploads the compressed packs and `build_report.json` to a GitHub Release
on `animalrender/SubLearn`, and updates `docs/catalog/catalog-v2.json` with exact byte sizes and SHA-256
hashes.

**D-56 — Open-data auxiliary packs (`build-extra-packs.yml`).**
Word frequency and CEFR packs (`wordfreq-en-cefr-v1`), WordNet English gloss supplements, and audio
pronunciation packs are built reproducibly in CI from open sources and published to GitHub Releases.

**D-57 — Real-DB integration test workflow in GitHub Actions.**
In addition to fast synthetic and `sample.sqlite` tests on every push, CI / workflow dispatch runs
`build_lexicon.py` + `validate_pack.py --full` + JVM NLP tests against the real converted `main_db.sqlite`
and posts a concise markdown summary table to `$GITHUB_STEP_SUMMARY`.

**D-58 — Separation of Lexicon Pack (`LEXICON_EN_FA`), Translation Sources, and Reverse Dictionary (`DICT_FA_EN`).**
- **Lexicon Pack (`LEXICON_EN_FA`)**: Powers the NLP pipeline (lemmatisation, POS tagging, phrasal/idiom/collocation
  detection, CEFR badges, IPA, examples) AND serves as the `PACK` translation source.
- **Translation Sources (`ML_KIT`, `PACK`, `LEGACY_V1`, `ONLINE_GOOGLE`)**: Provide word/phrase/line
  meanings via `TranslationOrchestrator`.
- **Reverse Dictionary (`DICT_FA_EN`)**: Used in the Dictionary screen when the user types Persian text
  to look up English equivalents (`sublearn-dict-fa-en-v1.sqlite`). Never loaded into the English subtitle
  NLP pipeline.

**D-59 — `TranslationOrchestrator` modes (`PRIORITY_CHAIN` vs `MERGE_ALL`) and latency budgets.**
In `PRIORITY_CHAIN` mode, sources are queried in user-configured priority order (default:
`[PACK, LEGACY_V1, ML_KIT, ONLINE_GOOGLE]`) and the first non-empty result is returned. In `MERGE_ALL`
mode, all enabled offline sources return immediately (`< 50 ms` p95) with source provenance chips
(`Lexicon v2`, `FastDic v1`, `ML Kit`), while `ONLINE_GOOGLE` (if enabled) streams in asynchronously
(`<= 1500 ms` timeout) and caches results in `TranslationCacheDao` with source attribution.

**D-60 — Unified "Library & Resources" screen (`feature:packs`) and real Dictionary tab (Revises earlier split UI).**
Instead of scattering downloads across a "Model Manager" screen, a "Packs" screen, and Settings file
pickers, `feature:packs` presents a unified **Library & Resources** screen with four clear sections:
1. **Dictionaries & Lexicon Packs** (`LEXICON_EN_FA`, `DICT_FA_EN`, `LEGACY_V1` `.bipe`/`main_db.sqlite` import, frequency list import)
2. **Offline Translation Models** (ML Kit EN↔FA model status & download)
3. **Smart NLP Enhancement Models** (Profile selector `Base..Smart` + ONNX / MediaPipe `.task` models)
4. **Storage & Maintenance** (disk usage breakdown, verify integrity, clear caches)
And the bottom-navigation **Dictionary** tab (`feature:dictionary`) is upgraded from a placeholder into
a full bidirectional EN↔FA dictionary lookup screen with FTS4 prefix search, POS/CEFR/domain badges,
IPA, examples, collocations, and one-tap "Save to My Words".

**D-61 — Legacy v1 `.bipe` (`fastdic_plain`) and raw `main_db.sqlite` importer (`LegacyV1Importer`).**
Users who already have files from the desktop `dictionaryproject` app (`main_db.sqlite` or the XOR+zlib
obfuscated `fastdic_plain` / `.bipe` file with key `b"Fastdic@1995"`) can import them directly on-device.
`LegacyV1Importer` streams the de-obfuscation (`4-byte magic 'BIPE' + 4-byte length + XOR 'Fastdic@1995' + zlib inflate`
or raw XOR+zlib) in 64 KB chunks without loading the full 98 MB into heap, validates the SQLite header,
and registers it in `installed_packs` as `LEGACY_V1`.

**D-62 — Optional, privacy-preserving `ONLINE_GOOGLE` translation source.**
`ONLINE_GOOGLE` is disabled by default (`enabledSources` excludes `ONLINE_GOOGLE` until opted in) and is
governed by an "Offline only" master switch. When enabled by the user after a clear privacy notice, it
sends **only** the tapped word or phrase (`<= 60` chars) — never the full subtitle sentence or file
metadata — to the configurable endpoint template (primary `client=dict-chrome-ex`, fallback `client=it`
on `translate.googleapis.com`), parses dictionary POS groups, reverse translations, and primary gloss,
and caches the response in Room (`TranslationCacheDao`).

## Phase 20–25 Decisions (D-63 .. D-68)

**D-63 — Pattern prior as base confidence instead of flat 0.85.**
`computeConfidence` in `DeterministicPhraseDetector` now starts from `pattern.prior` (read from the
lexicon pack's `pattern.prior` column) instead of a hardcoded 0.85. Adjustments:
+0.05 if contiguous ≥3 tokens, -0.15 if gap ≥3, +0.05 if POS agrees, -0.30 if ambiguous
particle/preposition follower. Clamped to [0,1]. This fixes false positives on common patterns
like "in truth", "the swedish", "a lot" which have low priors in the lexicon.

**D-64 — Per-type confidence thresholds in NlpConfig.**
Instead of one global `confidenceThreshold`, each phrase kind has its own:
`phrasalThreshold=0.75`, `idiomThreshold=0.75`, `collocationThreshold=0.65`.
`PHRASE` kind (all closed-class tokens) never gets a visible underline. The threshold is
configurable in Settings/Learning. Analyzer version bumped to 2 to invalidate caches.

**D-65 — Separable phrasal verb detection: particle must be first after verb in pattern.**
The old algorithm accepted any particle token after the verb in the gap, causing false matches
like "put it with" matching "put up with". The fix requires the particle to be the first
literal element after the verb in the compiled pattern, and the remaining pattern elements
must follow immediately after the particle. "that" is now allowed as a single-token gap
pronoun (for "look that up").

**D-66 — Quality filter rules in build_lexicon.py.**
The converter now applies five demotion rules after initial classification:
1. All closed-class tokens → PHRASE (prior 0.25)
2. DET/PREP + proper noun/loanword → PHRASE (prior 0.25)
3. Noun/adj combo without verb + fixed adverb/prep → PHRASE (prior 0.25)
4. PHRASAL with sole particle "to" + motion verb, without explicit code 20 → PHRASE (prior 0.25)
5. COLLOCATION without explicit code 19 (only linked from english_idioms) → prior ≤ 0.50
Demotions are counted and sampled in `build_report.json`.

**D-67 — Overlap resolution keeps alternatives.**
`resolveOverlaps` no longer discards losing spans; they are attached as `PhraseSpan.alternatives`
(up to 2) so the card can show both a winning phrase and an alternative.

**D-68 — Room: no destructive migration fallback.**
`fallbackToDestructiveMigration()` is removed from `SubLearnDatabase.create()`. `exportSchema = true`
is enabled. If a migration fails, the app should surface the error and offer a backup export rather
than silently wiping My Words data.

**D-69 — Android-free install logic lives beside the Android wrapper.**
`MlKitModelInstaller` keeps only what needs a `Context` (paths, HTTP transfer, progress) and
delegates the URL scheme, SHA-256 verification, zip-slip guard and archive validation to
`MlKitInstallLogic`, whose inputs and outputs are plain files and `AppResult`. That is what makes
those rules testable on a JVM: the previous single-class layout could only be exercised on a
device. The metadata type moved out of the class as `MlKitModelMetadata` so both halves, and the
`EN_FA_METADATA` constant, can name it.

**D-70 — The catalog is written from the released bytes, never by hand.**
`tools/update_catalog.py` computes size and SHA-256 from the files the pack workflow just built
and writes them into `docs/catalog/catalog-v2.json`, which the workflow then commits. A hand-typed
hash can silently point at other bytes; deriving it cannot. `tools/check_docs.py` and
`tools/check_schema.py` run in CI for the same reason — the documentation and the exported Room
schema are checked against the code rather than trusted.

**D-71 — Accuracy profiles carry their model requirements as data.**
`NlpModelCatalog` in `core:ai` maps each `AccuracyProfileToken` to the models it adds, with the
runtime, size and RAM floor of each one, and `SmartProfileSelector` never returns a profile whose
runtime this build lacks. The honest consequence is that PRECISE, ADVANCED and SMART are offered,
explain what they need, and refuse to run; `NlpModelRuntimeGuard.check` returns a
`NotImplemented` error instead of quietly falling back to a weaker profile the user did not pick.

**D-72 — The word card has no header and no close button.**
The card is anchored to the word the learner touched, so a title ("Word"/"Line"/"Block") and a
close button were both redundant: the level badge and source chips moved onto the word row and a
tap outside dismisses the card. Losing overlaps from `PhraseSpan.alternatives` became at most two
extra phrase rows, each in its own type colour, and "more" continues into the AI sheet instead of
growing the card.

**D-73 — Sorting and export follow the list, not the screen.**
`WordSort` lives next to `WordFilter` in `WordsViewModel` and every order is stable, with the
marked-at timestamp as the tie-break, so the list cannot reshuffle between recompositions. Export
writes through `ActivityResultContracts.CreateDocument` per format rather than a shared launcher
with a guessed MIME type, which is also why each format gets its own filename.

**D-74 — The engines are registered at startup, not assumed at compile time.**
`core:ai` cannot see `core:nlp-onnx` or `core:nlp-llm`: those modules depend on it. So
`NlpModelRuntime.isAvailable` asks `NlpRuntimeRegistry`, which starts pessimistic (only `NONE`) and
is filled in by `NlpRuntimeBootstrap` from `SubLearnApplication.onCreate`. Each engine is probed
by loading it — ONNX Runtime through `OrtEnvironment.getEnvironment`, MediaPipe by resolving
`LlmInference` — so a device whose ABI the shipped native library does not cover keeps the
pure-Kotlin ranker and is never offered a profile that would fail on the subtitle screen. A build
that links both engines therefore reports `isAvailable` honestly per device rather than per
release.

**D-75 — A profile's models are a download set and a resident set.**
`NlpModelCatalog.modelsFor(profile)` is what the user downloads; `runtimeModelsFor(profile)` is
what stays in memory. SMART downloads four models so it can move between tiers without another
transfer, but keeps only the word-sense encoder and the prompt model: 640 MB of resident weights
would be a crash, not a feature. `ProfiledNlpEngine` walks the resident list strongest-first, keeps
the first embedder that opens, and reports `meetsProfile` — the caller, not the engine, decides
whether a weaker model is acceptable, so "PRECISE is running" is never a claim the engine makes on
its own.

**D-76 — The profile chooses the sense ranker, and the ranker is asked for per lookup.**
`ProfiledSenseRanker.forProfile` is the whole tier table in one place: BASIC/LIGHT/CUSTOM keep
`PriorSenseRanker`, BALANCED/PRECISE/ADVANCED rank with the encoder that `ProfiledNlpEngine`
opened, and SMART puts the prompt model on top through `LlmSenseRanker`. `ModelSenseRanker` scores
the subtitle line against each candidate's *English* gloss, because every encoder in the catalog is
an English WordPiece model — a candidate with no English side is left in prior order instead of
being given a number the model never produced, and any failure (no model, no glosses, a dead
session) falls back to the lexical order rather than hiding the meanings. The ranker itself comes
from `ProfiledNlpEngineCache`, which holds the profile's resident models open across subtitle lines
and reopens them when the profile changes; a profile whose models are absent is *not* cached as a
failure, so installing a model takes effect on the next tap instead of on the next launch.

**D-77 — The quality filters read every tag a token has, and know three more shapes.**
The five demotion rules of D-66 were applied through one "primary" POS tag per token, and the five
errors the pack validator reported on 2026-10-16 all came from that: `look`'s most frequent tag is
NOUN, so `look forward to` was read as a noun combination and its prior dropped to 0.25, while
`truth` carries a verb sense, so `in truth` was not read as a fixed combination at all. The rules
now live in `apply_quality_filters`, a pure function of the entry's data with its own tests, and
they decide on the whole tag set plus the known-verb lemmas. Two shapes were added: a key that opens
with a preposition (and whose head is not a verb) is a prepositional phrase, however its later
tokens are tagged, and a two-token `PHRASAL` whose only "particle" is `to` after a verb of motion is
demoted *even when the source labels it with the phrasal code* — that label is wrong for
`go to`/`come to`/`get to`, and D-34 says a wrong underline is worse than a missing one. Idioms are
exempt from the two shape heuristics, because `a piece of cake` and `in the nick of time` have
exactly those shapes and are what the learner wants underlined. The demotion reason records whether
the source carried the phrasal code, so `build_report.json` shows how often it happens.


**D-78 — Learn is a future reading surface; learning mode belongs to the player. (Revised by D-81.)**
The owner clarified that Learn is only for browser/PDF/image learning and must stay a Coming soon
surface. Pack onboarding, imports, integrity checks, downloads and accuracy profiles move to
`feature:settings/ResourcesSection` and `ResourcesViewModel`; mode, level and popup controls stay in
Settings → Player learning, with a My Words shortcut. The Dictionary screen remains independent in
`feature:learn`; no feature depends on another feature. Settings search matches localized titles and
hints as well as keywords, nested back stays inside Settings, and section/search survive recreation.
Settings/My Words own their toolbar; the app shell must not stack a second toolbar above them.

**D-79 — Automatic cards are passive, offline and scoped to the current learning cues.**
`PlayerViewModel.publish` resolves all active external and embedded lines before scheduling hints.
Candidates come from token/lemma lookups and confident within-line phrase detection; real pack CEFR
wins, otherwise the configured word-level provider applies. Equal-level and known entries are excluded
when above-only is enabled. No new stored settings or migration is needed. At most two compact hints
are displayed at a time, bounded to a corner and using the LEARNING_POPUP fonts, opacity and motion
settings. No model download, network lookup, pause, focus or touch target is introduced. A cue/source,
media, settings change or seek cancels stale work; no subtitle means no hint. Controls, PiP, lists,
sheets, tap popups and AI answers suppress drawing. Missing translations silently omit *optional*
hints, not report successful lookup. Cross-cue automatic phrase hints are deferred (KNOWN_ISSUES 29).

**D-80 — Remove decorative endpoints at the design-system boundary.**
The pinned Material3 already supports `drawStopIndicator` and `gapSize`; the old wrapper's claim
that a BOM upgrade was needed was incorrect. Every progress bar now uses `SubLearnLinearProgress`
(no stop indicator, zero gap) and sliders use `SubLearnSlider` (no endpoint or tick dots, keeping
snapping and the interactive thumb). Settings entry also fixes a Koin runtime failure: the
`NlpModelInstaller` downloader is a function, not a `ModelDownloader` instance; pass the registered
instance's `::download` rather than asking Koin for an unregistered function. `SettingsGraphTest`
resolves the real production graph to guard this failure, which source checks cannot catch.

**D-81 — One downloads screen, one state for every model.**
Settings had three places that claimed to manage downloads: "Library & Resources" (packs, profile
chips, a benchmark report), "Models & Downloads" (the ML Kit model *and* the six accuracy models)
and the translation settings (a second ML Kit button). The same ML Kit model was listed twice with
two different sizes, the accuracy models were offered by the library screen through the *pack*
downloader — one file into the wrong folder, so the runtime could not open them — and the profile
chips showed raw enum names next to a benchmark that silently replaced the user's choice with
whatever the app's *heap* could afford. The screen is now one: **Settings → Downloads** holds the
offline translation model, the offline packs, the accuracy models and the accuracy profile, and
`DownloadsViewModel` owns all four. The state of the translation model comes from one shared
`TranslationModelManager`, so the player, the translation settings and the downloads screen show
the same thing and start the same download. The custom `MlKitModelInstaller` is deleted: ML Kit
downloads, verifies and stores its own models, and a hand-written installer that unpacked the
archive into a directory the SDK does not read is what produced "missing translation_model.pb" while
reporting success. The profile is now *advised*, not enforced — `SmartProfileSelector` compares the
profile with the device's memory, storage and installed models and the screen says what is missing
instead of downgrading the choice behind the user's back.

**D-82 — The version is a file, and the release bumps it by a patch.**
`VERSION` holds the version the next release carries (`3.0.0`). `tools/next_version.py` reads the
highest stable tag and the file, and returns the next patch version, so every merge to `main`
releases `3.0.0 → 3.0.1 → 3.0.2` without anybody editing a build file. `versionCode` is derived
from the name (3.0.0 → 30000), so two releases can never claim the same code. `auto-release.yml`
computes the version, builds it and publishes the stable tag only when that tag is free and the
commit has no stable tag yet; `release.yml` refuses a tag below the `VERSION` floor. The old
`tools/bump_version.py` (a conventional-commit version rule) is deleted: it needed a tag that did
not exist yet, which is the reason no release ever happened.

**D-83 — The catalog describes the published bytes, and the app's copy is checked against it.**
The pack entries in `docs/catalog/catalog-v2.json` carried a size and a SHA-256 that did not match
the assets in the `packs-v2.0.0` release, so every pack download ended in "SHA-256 mismatch" and
the pack could never be installed. The catalog now holds the digests the release API reports for
those assets, `ModelCatalogParser.bundledDefault()` — the copy the app actually reads — is generated
from the same file, and `tools/check_catalog.py` compares the two field by field, so a hand-edited
catalog can no longer drift from the app. `verify-catalog.yml` additionally asks the GitHub API
for the digest of every published asset and fails the build when the catalog disagrees, which turns
the defect the user hit into a red check instead of a broken download.
