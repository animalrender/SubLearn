# Known issues

Honest list of what is not right yet, including consequences of decisions. Nothing here is hidden in a
TODO comment; each entry has a route to being fixed.

## Build and verification

1. **This branch has not been compiled locally, and cannot be**: the agent sandbox has no JVM. CI runs
   the first real compile; `docs/PROGRESS.md` records the run status, and red→green iteration happens on
   this PR. Review the first green commit range carefully: that is where mechanical mistakes live.
2. **The four static checkers are not a type checker.** `tools/check_sources.py` (brace balance, 140
   columns, no `FIXME`), `tools/check_symbols.py` (every `com.sublearn.*` import resolves to a declared
   name, generated `R`/`BuildConfig` skipped), `tools/check_deps.py` (declared dependencies vs imports,
   catalog alias existence, cross-module visibility) and `tools/check_resources.py` (the aapt2 text rules
   that make `packageDebugResources` fail) catch a lot, but not argument types or `when` exhaustiveness.
3. **No view-model-level tests yet.** `FakePlayerController` exists precisely to make them cheap; that
   gap is `REQ-5` in [AGENT_REQUESTS.md](AGENT_REQUESTS.md). 18 test classes cover the pure and
   Android-adjacent logic today.

## Behaviour that is intentional but surprising

4. **Embedded subtitle tracks cannot drive the list view, seek-by-subtitle or the batch tools.** A
   layer fed by player cues has no lookahead, so those actions are unavailable and the panel says why
   (pick a file instead). See [ARCHITECTURE.md](ARCHITECTURE.md) "two layer sources".
5. **ASS/SSA styling is ignored** (colours, positions, karaoke tags); we take text and timing only.
   Otherwise a fansub's own styling would override the per-surface fonts (GEN-3) and layer placement.
6. **Software decoder is "prefer software MediaCodec", not an FFmpeg decoder.** `HW+` means "hardware
   with fallback". The Media3 FFmpeg extension was rejected (native build, size, licence surface), so a
   codec no device codec list supports genuinely fails — the sheet explains the mapping rather than
   pretending. See D-9 in [DECISIONS.md](DECISIONS.md).
7. **No word list is bundled**, so with nothing imported the level popups fall back to "words not marked
   as known" and `LevelProviderToken.IMPORTED_FREQUENCY_LIST` reports that no list is loaded. Verified
   permissive frequency data is a licence question first (see `REQ-4`).
8. **Offline dictionary is disabled by flag.** The card's "full details" opens the web translation
   window; the Dictionary tab is a labelled "coming soon" entry. That is the LATER contract, not a bug.
9. **Sidecar auto-load needs a folder grant.** SAF gives per-file permission only, so the first use
   asks for the video's folder (`sidecarTreeUri`); declining it degrades to manual picking.

## Smaller things

10. `resourceConfigurations` is deprecated in AGP 8.7 in favour of `androidResources.localeFilters`;
    changing it now would need the min-version of that API to be checked against our AGP, so the
    deprecation warning stays and is listed here rather than silenced in a lint baseline.
11. **Instrumented tests are not run in CI automatically** (no KVM on standard runners); they compile
    there (`assembleDebugAndroidTest`) and run in the manual `instrumented.yml` workflow.
12. **README has no screenshots yet** (phase 9 owns them). Adding pretty fakes would be worse than
    waiting; see `REQ-7`.
13. Gradle 8.11.1 is reported as out-of-date by `setup-gradle`. It is the newest line compatible with
    AGP 8.7.3 + Kotlin 2.0.21 as pinned in the catalog; bumping all three is one deliberate commit, not
    a drive-by.
