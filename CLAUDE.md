# Claude Code

Read [AGENTS.md](AGENTS.md) first; it is the single entry point for this repository, including the
hard repository rules, the module boundaries and the per-task loop. Two things that surprise
agents here:

- `./gradlew` cannot run in the sandbox (no JVM). Use `python3 tools/check_sources.py`,
  `tools/check_symbols.py`, `tools/check_deps.py`, `tools/check_resources.py`, then let GitHub
  Actions compile and read the "Gradle failure report" check run (see AGENTS.md).
- Never write a setting, label or string in a feature module: settings live in `core:settings`,
  enum labels in `feature/settings/Labels.kt`, strings only in `core:designsystem` (EN + FA).

To see or drive the running app (screenshots, view hierarchy, logs) use the UI check, mandatory for any visible or operable change: [docs/UI_VERIFICATION.md](docs/UI_VERIFICATION.md).
