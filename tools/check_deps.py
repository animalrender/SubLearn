#!/usr/bin/env python3
"""Report Gradle dependencies a module is missing for the AndroidX packages it imports.

Local Gradle cannot run in the agent sandbox, so this catches the second most common build failure
after a typo: `import androidx.compose...` in a module that never declared Compose. The map below is
intentionally small; extend it when a new library group appears in the code.

Usage: python3 tools/check_deps.py
"""
from __future__ import annotations

import re
from pathlib import Path

# import prefix -> (gradle alias, extra note)
RULES = [
    ("androidx.compose.material3.", "compose-material3"),
    ("androidx.compose.foundation.", "compose-foundation"),
    ("androidx.compose.ui.", "compose-ui"),
    ("androidx.compose.runtime.", "compose-runtime"),
    ("androidx.compose.material.icons.", "compose-material-icons"),
    ("androidx.activity.compose.", "androidx-activity-compose"),
    ("androidx.lifecycle.compose.", "androidx-lifecycle-runtime-compose"),
    ("androidx.lifecycle.ViewModel", "androidx-lifecycle-viewmodel"),
    ("androidx.lifecycle.viewModelScope", "androidx-lifecycle-viewmodel"),
    ("androidx.lifecycle.process", "androidx-lifecycle-process"),
    ("androidx.core.", "androidx-core-ktx"),
    ("androidx.datastore.", "datastore-preferences"),
    ("androidx.room.", "room-runtime"),
    ("androidx.navigation.", "androidx-navigation-compose"),
    ("androidx.window.", "androidx-window"),
    ("androidx.media3.", "media3-exoplayer"),
    ("org.koin.core.module", "koin-core"),
    ("org.koin.dsl", "koin-core"),
    ("org.koin.android", "koin-android"),
    ("org.koin.compose", "koin-compose"),
    ("kotlinx.coroutines.", "coroutines-core"),
    ("kotlinx.serialization.", "kotlinx-serialization-json"),
    ("androidx.activity.result.", "androidx-activity-compose"),
    ("com.squareup.okhttp3.", "okhttp"),
    ("com.google.mlkit.", "mlkit-translate"),
    ("com.google.android.gms.", "mlkit-language-id"),
    ("org.junit.", "junit"),
    ("org.robolectric.", "robolectric"),
    ("kotlinx.coroutines.test", "coroutines-test"),
]

TEST_DIRS = ("/src/test/", "/src/androidTest/")


def aliases_used(build_file: Path) -> set[str]:
    if not build_file.exists():
        return set()
    text = build_file.read_text()
    return set(re.findall(r"libs\.([a-zA-Z0-9.]+)", text))


def alias_key(alias: str) -> str:
    return alias.replace(".", "-").removesuffix("-get")


def module_of(path: Path) -> Path:
    """feature/player from feature/player/src/main/kotlin/... – everything before `src`."""
    parts = path.parts
    return Path(*parts[: parts.index("src")])


def main() -> int:
    root = Path(".")
    problems = []
    sources = sorted({p for p in root.rglob("*.kt") if "build" not in p.parts and "/src/" in str(p)})
    modules = sorted({module_of(path) for path in sources})
    for module in modules:
        build = module / "build.gradle.kts"
        declared = {alias_key(a) for a in aliases_used(build)}
        text = "\n".join(path.read_text() for path in sources if module_of(path) == module)
        for prefix, alias in RULES:
            needle = f"import {prefix}"
            if needle in text and alias not in declared:
                # junit/robolectric only matter for test sources, which are already covered by the
                # `testImplementation` lines that every module declares; report them anyway.
                problems.append(f"{module}: imports {prefix}* but does not declare libs.{alias.replace('-', '.')}")
    for problem in problems:
        print(problem)
    print(f"{len(modules)} modules checked, {len(problems)} missing dependencies")
    return 1 if problems else 0


if __name__ == "__main__":
    raise SystemExit(main())
