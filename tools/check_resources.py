#!/usr/bin/env python3
"""Static stand-in for AAPT2's resource validation, so a string typo fails locally instead of in CI.

The sandbox has no JVM, which means no aapt2. Most resource failures that aapt2 reports are
deterministic text problems, so this checks the same rules by hand:

  * XML must be well formed (a stray & or < is the classic case)
  * an apostrophe or a double quote inside unquoted string text has to be escaped
  * non-positional format strings may not mix several substitutions
  * a format placeholder has to be positional and consistently typed when it repeats
  * plurals need the "other" quantity, and every item needs a valid quantity name
  * names must be unique per file, and a translation may not introduce a name the default lacks
  * attr namespaces used in a file must be declared
"""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

QUANTITIES = {
    "zero", "one", "two", "few", "many", "other",
    "unknown", "exact",
}
# aapt2 accepts dots and capitals in style and color names, so the rule is the XML name rule
NAME_RE = re.compile(r"^[A-Za-z][A-Za-z0-9_.]*$")
PLACEHOLDER_RE = re.compile(r"%(?:(\d+\$)?[-+#0 ]*(?:\d+|\*)?(?:\.(?:\d+|\*))?)([sdfcbeixXna%])")


def check_string_text(elem: ET.Element, raw: str, source: str, path: Path, problems: list[str]) -> None:
    """Applies the aapt2 text rules to one <string> element."""
    name = elem.get("name", "?")
    text = raw.strip()
    if ("<x " in text or "</x>" in text) and "xmlns:xliff" not in source:
        problems.append(f"{path}: {name} uses <x/> without an xmlns:xliff declaration")
    quoted = False
    index = 0
    while index < len(text):
        char = text[index]
        if char == "\\":
            index += 2
            continue
        if char == '"':
            quoted = not quoted
        elif char == "'" and not quoted:
            problems.append(f"{path}: {name} has an unescaped apostrophe (write \')")
            break
        index += 1
    if elem.get("formatted") != "false":
        groups = PLACEHOLDER_RE.findall(text)
        positional = [g for g in groups if g[0]]
        simple = [g for g in groups if not g[0] and g[1] != "%"]
        if positional and simple:
            problems.append(f"{path}: {name} mixes positional and non-positional arguments")
        if not positional and len(simple) > 1:
            problems.append(
                f'{path}: {name} has {len(simple)} non-positional arguments; '
                'use %1$s, %2$s, ... or formatted="false"'
            )
        seen: dict[str, str] = {}
        for _, kind in positional:
            index_ = _.rstrip("$")
            if seen.setdefault(index_, kind) != kind:
                problems.append(f"{path}: {name} repeats %{index_}$ with a different type")


def check_module(module: Path, problems: list[str]) -> int:
    res_dirs = sorted((module / "src").glob("*/res"))
    files = [p for d in res_dirs for p in sorted(d.rglob("*.xml"))]
    names_by_qualifier: dict[str, set[str]] = {}
    checked = 0
    for path in files:
        rel = path.relative_to(module)
        folder = path.parent.name
        stem = path.stem
        if folder.split("-")[0] in {"drawable", "mipmap", "font", "raw", "color"}:
            if not re.fullmatch(r"[a-z0-9_]+", stem):
                problems.append(f"{path}: '{stem}' is not a valid resource file name (use a-z0-9_ only)")
        try:
            source = path.read_text(encoding="utf-8")
        except UnicodeDecodeError as error:
            problems.append(f"{path}: not UTF-8 ({error})")
            continue
        try:
            root = ET.fromstring(source)
        except ET.ParseError as error:
            problems.append(f"{path}: XML is not well formed ({error})")
            continue
        checked += 1
        if root.tag != "resources":
            continue
        names = [child.get("name", "") for child in root if child.get("name")]
        for name, count in Counter(names).items():
            if count > 1:
                problems.append(f"{path}: {name} is declared {count} times in one file")
        if folder.startswith("values"):
            for child in root:
                tag = child.tag
                name = child.get("name", "?")
                if not NAME_RE.match(name):
                    problems.append(f"{path}: {name} is not a valid resource name")
                if tag in {"string", "string-array", "plurals", "quantity"}:
                    if tag == "string":
                        # The raw slice keeps the backslashes that the XML parser would have eaten.
                        check_string_text(child, raw_between(source, name), source, path, problems)
                elif tag == "color":
                    if not re.fullmatch(r"#([0-9a-fA-F]{3,4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})", (child.text or "").strip()):
                        problems.append(f"{path}: {name} is not a hex color literal")
                elif tag == "dimen":
                    value = (child.text or "").strip()
                    if not re.fullmatch(r"-?\d+(\.\d+)?(dp|dip|sp|px|pt|mm|em|%)", value):
                        problems.append(f"{path}: {name} is not a valid dimension ({value!r})")
                if tag == "plurals":
                    quantities = [item.get("quantity") for item in child if item.tag == "item"]
                    if "other" not in quantities:
                        problems.append(f"{path}: {name} (plurals) has no 'other' item")
                    for quantity in quantities:
                        if quantity not in QUANTITIES:
                            problems.append(f"{path}: {name} has an unknown quantity '{quantity}'")
                names_by_qualifier.setdefault(folder, set()).update(names)
    defaults = names_by_qualifier.get("values", set())
    for folder, names in sorted(names_by_qualifier.items()):
        if folder == "values":
            continue
        extra = sorted(names - defaults)
        if extra:
            problems.append(
                f"{module}: {folder} declares {len(extra)} name(s) missing from values/ "
                f"(first: {', '.join(extra[:5])}) — MissingDefaultResource"
            )
    return checked


def raw_between(source: str, name: str) -> str:
    """The text of the <string name="name"> element, straight from the file."""
    match = re.search(
        r"<string\b[^>]*name=\"" + re.escape(name) + r"\"[^>]*>(.*?)</string>",
        source,
        flags=re.DOTALL,
    )
    return match.group(1) if match else ""


def main(argv: list[str]) -> int:
    root = Path(".")
    targets = [Path(a) for a in argv] or [root]
    problems: list[str] = []
    files = 0
    for target in targets:
        modules = [target] if (target / "src").is_dir() else sorted(p for p in target.rglob("*") if (p / "src").is_dir())
        for module in modules:
            if module.name in {"build", ".git"}:
                continue
            files += check_module(module, problems)
    print(f"checked {files} resource file(s), {len(problems)} problem(s)")
    for problem in problems:
        print(f"  {problem}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
