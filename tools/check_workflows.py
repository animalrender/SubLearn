#!/usr/bin/env python3
"""check_workflows.py — Make sure every workflow file is valid YAML.

A workflow with a YAML error never runs: GitHub reports it as "Invalid workflow file" and the
release simply does not happen, with no log to read. The mistake is easy to make and invisible in
a diff, because the broken part is a shell script inside a block scalar whose indentation is what
broke it.

The check needs a YAML parser. PyYAML is present on the GitHub runners (so CI enforces it) but not
in every sandbox; when it is missing the check says so and passes, rather than pretending to have
checked something.

Usage:
    python3 tools/check_workflows.py [--repo-root .]
"""

from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

WORKFLOW_DIR = os.path.join(".github", "workflows")


def check_workflows(repo_root: str) -> tuple[list[str], str]:
    """Returns the problems and a note about whether the check really ran."""
    try:
        import yaml  # type: ignore[import-not-found]
    except ImportError:
        return [], "skipped: PyYAML is not installed, so the workflows were not parsed"

    directory = Path(repo_root) / WORKFLOW_DIR
    if not directory.is_dir():
        return [f"{WORKFLOW_DIR} not found"], ""

    problems: list[str] = []
    files = sorted(directory.glob("*.yml")) + sorted(directory.glob("*.yaml"))
    if not files:
        return [f"{WORKFLOW_DIR} has no workflow file"], ""

    for path in files:
        source = path.read_text(encoding="utf-8")
        try:
            document = yaml.safe_load(source)
        except Exception as error:  # yaml.YAMLError and anything the parser raises
            problems.append(f"{path}: invalid YAML: {str(error)[:200]}")
            continue
        if not isinstance(document, dict):
            problems.append(f"{path}: the top level of a workflow must be a mapping")
            continue
        # A workflow without a trigger never runs either, and it is as invisible as broken YAML.
        if "on" not in document and True not in document:
            problems.append(f"{path}: no 'on:' trigger, so the workflow never runs")
        jobs = document.get("jobs")
        if not isinstance(jobs, dict) or not jobs:
            problems.append(f"{path}: no job to run")

    return problems, f"checked {len(files)} workflow file(s)"


def check_shell_blocks(repo_root: str, workflows: list[Path]) -> list[str]:
    """Every `run:` block has to be a valid shell script.

    `bash -n` parses without executing, so nothing in the workflow runs here. GitHub expressions
    are replaced by a placeholder first, because `${{ ... }}` is not shell syntax.
    """
    import re
    import subprocess

    problems: list[str] = []
    for path in workflows:
        source = path.read_text(encoding="utf-8")
        try:
            import yaml  # type: ignore[import-not-found]
        except ImportError:
            return []
        document = yaml.safe_load(source) or {}
        for job_name, job in (document.get("jobs") or {}).items():
            for step in job.get("steps", []):
                script = step.get("run")
                if not script:
                    continue
                cleaned = re.sub(r"\$\{\{[^}]*\}\}", "EXPR", script)
                result = subprocess.run(
                    ["bash", "-n"], input=cleaned, capture_output=True, text=True,
                )
                if result.returncode != 0:
                    problems.append(
                        f"{path}: the run block of '{step.get('name', job_name)}' is not valid shell: "
                        f"{result.stderr.strip()[:160]}",
                    )
    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Check the workflow files")
    parser.add_argument("--repo-root", default=".")
    args = parser.parse_args(argv)

    problems, note = check_workflows(args.repo_root)
    print(note)
    if not problems:
        directory = Path(args.repo_root) / WORKFLOW_DIR
        files = sorted(directory.glob("*.yml")) + sorted(directory.glob("*.yaml"))
        problems += check_shell_blocks(args.repo_root, files)
    if problems:
        print(f"WORKFLOW CHECK FAILED ({len(problems)} problems):", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        return 1

    print("WORKFLOW CHECK PASSED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
