#!/usr/bin/env python3
"""Two dependency checks, both of which CI would otherwise pay a build for.

1. A module must declare the library whose AndroidX package it imports — an `import androidx.compose…`
   in a module without Compose is the most common mistake when a feature is scaffolded by hand.
2. A build script must not reference a version-catalog alias that does not exist — that fails the
   Kotlin DSL compile of the whole build, before any module is even configured.

Usage: python3 tools/check_deps.py
Exit code is non-zero when anything is reported.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

# import prefix -> version-catalog alias that must be declared by the module
RULES = [
    ("androidx.compose.material3.", "compose-material3"),
    ("androidx.compose.foundation.", "compose-foundation"),
    ("androidx.compose.ui.", "compose-ui"),
    ("androidx.compose.runtime.", "compose-runtime"),
    ("androidx.compose.material.icons.", "compose-material-icons"),
    ("androidx.activity.compose.", "androidx-activity-compose"),
    ("androidx.activity.result.", "androidx-activity-compose"),
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
    ("com.squareup.okhttp3.", "okhttp"),
    ("com.google.mlkit.", "mlkit-translate"),
    ("com.google.android.gms.", "mlkit-language-id"),
    ("org.junit.", "junit"),
    ("org.robolectric.", "robolectric"),
]


def module_of(path: Path) -> Path:
    """feature/player for feature/player/src/main/kotlin/… – everything before `src`."""
    parts = path.parts
    return Path(*parts[: parts.index("src")])


def catalog_keys(root: Path, kind: str) -> set[str]:
    text = (root / "gradle" / "libs.versions.toml").read_text()
    return set(re.findall(rf"^([a-zA-Z0-9-]+)\s*=\s*\{{\s*{kind}", text, re.M))


def aliases_in(text: str, catalog: set[str], namespace: str = "") -> set[str]:
    """Every `libs.<a>.<b>` accessor used in a build script, minus catalog namespaces."""
    found = set()
    for raw in re.findall(rf"libs\.{namespace}([a-zA-Z0-9.]+)", text):
        key = raw.replace(".", "-")
        if key.startswith("versions-") or key.startswith("plugins-"):
            continue
        found.add(key)
    return found


def main() -> int:
    root = Path(".")
    libraries = catalog_keys(root, "module")
    plugins = catalog_keys(root, "plugins")
    sources = sorted(p for p in root.rglob("*.kt") if "build" not in p.parts and "/src/" in str(p))
    modules = sorted({module_of(p) for p in sources})
    problems: list[str] = []

    for module in modules:
        build = module / "build.gradle.kts"
        text = "\n".join(p.read_text() for p in sources if module_of(p) == module)
        if not build.exists():
            problems.append(f"{module}: no build.gradle.kts")
            continue
        script = build.read_text()
        declared = {a.replace(".", "-") for a in re.findall(r"libs\.([a-zA-Z0-9.]+)", script)}
        declared -= {a for a in declared if a.startswith(("versions-", "plugins-"))}

        for prefix, alias in RULES:
            if f"import {prefix}" in text and alias not in declared:
                problems.append(f"{module}: imports {prefix}* but does not declare libs.{alias.replace('-', '.')}")

        for key in aliases_in(script, libraries):
            if key not in libraries:
                problems.append(f"{module}: build script uses libs.{key.replace('-', '.')} but the catalog has no such library")
        for key in re.findall(r"libs\.plugins\.([a-zA-Z0-9.]+)", script):
            if key.replace(".", "-") not in plugins:
                problems.append(f"{module}: build script uses libs.plugins.{key} but the catalog has no such plugin")

    root_build = root / "build.gradle.kts"
    if root_build.exists():
        for key in re.findall(r"libs\.plugins\.([a-zA-Z0-9.]+)", root_build.read_text()):
            if key.replace(".", "-") not in plugins:
                problems.append(f"root: build script uses libs.plugins.{key} but the catalog has no such plugin")

    for problem in problems:
        print(problem)
    print(f"{len(modules)} modules checked, {len(problems)} missing dependencies")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
