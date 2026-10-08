# Copilot instructions

This repository's rules live in [AGENTS.md](../AGENTS.md) — read it before editing.

Short version: Kotlin + Jetpack Compose + Media3, 16 Gradle modules, `core:*` owns logic and design
tokens, `feature:*` owns screens only and never depends on another feature. All UI strings live in
`core:designsystem` (`values/` and `values-fa/`). No hardcoded colors, sizes or durations; use the
tokens in `core/designsystem`. Never copy code from the GPL reference projects, never commit
third-party dictionaries, model files, keystores or secrets. Do not invent APIs: check the real
declaration in `core:*` before using a type, and run `python3 tools/check_symbols.py` after editing.
