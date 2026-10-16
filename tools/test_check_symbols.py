#!/usr/bin/env python3
"""Tests for the enum-entry half of `check_symbols.py`.

The check exists because `SubLearnError.Kind.ModelFailure` was written, used in seven places, and
passed every offline check this sandbox can run: the constant reads as if it exists, and only
`./gradlew compileDebugKotlin` would have rejected it. These tests keep the detector honest in both
directions — it must catch the missing constant and stay silent on real code.
"""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import check_symbols  # noqa: E402


class EnumEntryCheckTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def write(self, relative: str, text: str) -> None:
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    def test_flags_a_constant_that_is_not_declared(self) -> None:
        self.write("core/common/src/main/kotlin/K.kt", "package a\n\nenum class Kind { NotFound, ModelMissing }\n")
        self.write("feature/x/src/main/kotlin/B.kt", "package b\n\nval c = Kind.ModelFailure\n")

        problems = check_symbols.unknown_enum_entries(self.root)

        self.assertEqual(1, len(problems))
        self.assertIn("Kind.ModelFailure", problems[0])

    def test_accepts_a_declared_constant(self) -> None:
        self.write("core/common/src/main/kotlin/K.kt", "package a\n\nenum class Kind { NotFound, ModelMissing }\n")
        self.write("feature/x/src/main/kotlin/B.kt", "package b\n\nval c = Kind.ModelMissing\n")

        self.assertEqual([], check_symbols.unknown_enum_entries(self.root))

    def test_accepts_one_line_and_indented_enums_alike(self) -> None:
        self.write(
            "core/settings/src/main/kotlin/S.kt",
            "package a\n\n"
            "enum class ThemeMode { SYSTEM, LIGHT, DARK }\n\n"
            "enum class CefrLevel { A1, A2, B1, C1, UNKNOWN }\n\n"
            "enum class Long {\n    FIRST,\n    SECOND,\n}\n",
        )
        self.write(
            "feature/y/src/main/kotlin/C.kt",
            "package b\n\n"
            "val a = ThemeMode.AMOLED\n"
            "val b = CefrLevel.A2\n"
            "val c = Long.SECOND\n",
        )

        problems = check_symbols.unknown_enum_entries(self.root)

        self.assertEqual(1, len(problems))
        self.assertIn("ThemeMode.AMOLED", problems[0])

    def test_ignores_a_name_that_is_not_only_an_enum(self) -> None:
        """`Sections.ALL` is an object constant in one module and an enum entry in another."""
        self.write("core/settings/src/main/kotlin/S.kt", "package a\n\nobject Sections {\n    val ALL = 1\n}\n")
        self.write(
            "feature/settings/src/main/kotlin/S.kt",
            "package b\n\nprivate enum class Sections(val id: String) { PLAYER(\"p\") }\n",
        )
        self.write("feature/settings/src/main/kotlin/U.kt", "package b\n\nval s = Sections.ALL\n")

        self.assertEqual([], check_symbols.unknown_enum_entries(self.root))

    def test_ignores_noise_that_is_not_a_reference(self) -> None:
        self.write("core/common/src/main/kotlin/K.kt", "package a\n\nenum class Kind { NotFound }\n")
        self.write(
            "feature/x/src/main/kotlin/B.kt",
            "package b\n\n"
            "// Kind.Missing in a comment\n"
            'val s = "Kind.AlsoMissing in a string"\n',
        )

        self.assertEqual([], check_symbols.unknown_enum_entries(self.root))


if __name__ == "__main__":
    unittest.main()
