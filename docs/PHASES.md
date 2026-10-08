# Phases

The plan the agent works to. Order is fixed; scope inside a phase may move between phases when a
dependency forces it, but only with an entry here explaining the move. Definition of Done for every
phase: the listed deliverables exist on the real code path, CI is green, `docs/CHECKLIST.md` and
`docs/PROGRESS.md` are updated, and the phase PR is merged into `main`.

| # | Name | Deliverables | Spec IDs | Status |
| --- | --- | --- | --- | --- |
| 0 | Foundations | Repo audit, references and license audit, docs skeleton, 16 Gradle modules with version catalog, CI (build/unit tests/lint/assembleDebug + APK artifact), design tokens and motion specs, typed versioned settings, EN/FA + RTL base, LATER stubs with flags | GEN-1..7 (structure) | in review (PR #1) |
| 1 | App shell | Theme, navigation, settings screens, Home with recent videos in Room, opening video/URL/subtitle intents | GEN-2, GEN-5, settings system, entry points | in review (same branch) |
| 2 | Player core | Media3 wrapper, local + URL playback, MX-style overlay with auto-hide, gestures, orientation + lock, decoder choice, track selection, PiP, aspect ratio, speed, playlist, lock | PLY-1..5 | in review |
| 3 | Subtitle engine | Parsers, normalizer, two layers, multi-track per layer, delay, styling hooks, Subtitle List View, layout mode, toggle buttons, dockable quick actions | PLY-6, PLY-7, SUB-1..3, SUB-7 | in review |
| 4 | Interaction | Tap translate (word/line/block), pause/resume rule, ML Kit translation, entertainment cards, My Words + screen | SUB-4, SUB-5, LRN-1 | in review |
| 5 | Shadowing | Repeat block, auto-repeat, stop at end of block, pause formula and settings | SHD-1..3 | in review |
| 6 | Learning mode | Popup pipeline, WordLevelProvider, manual level + frequency list import, word-colouring styles | LRN-2, SUB-5 | in review |
| 7 | AI button | Providers, settings, prompt editor, context builder, loading ring and pause/resume contract | AI-1..4 | in review |
| 8 | Subtitle tools | Batch line-break removal, max-char split, search, no-spoiler mode | SUB-6, SUB-7, PLY-6 | in review |
| 9 | Hardening | Animation and accessibility polish, performance pass, process-death restore, test gaps, README screenshots, `v0.1.0` tag | GEN-4..6, accessibility | not started |
| 10+ | LATER, in order | (a) offline dictionary import + lookup, (b) My Words quiz, (c) update checker via GitHub Releases. One at a time, never breaking NOW; stop after (c) | LATER set | not started |

## Notes on the split actually used

- Phases 0–8 were implemented on one reviewable branch, because the codebase had never been compiled
  and each phase's UI depends on the symbols of the previous one; splitting it earlier would have put
  red commits on `main`-adjacent branches for no benefit. Commit boundaries still follow the phase
  order (`chore(repo)` → `feat(core)` → `feat(features)` → `feat(app)` → docs). See
  [DECISIONS.md](DECISIONS.md) D-20.
- Phase 9 is where the remaining verification work belongs: a device pass on RTL, animation and
  process death, plus the screenshots the README should have.
