# SubLearn documentation

Every document in `docs/` is listed here with its role. `tools/check_docs.py` fails when a file
in `docs/` is missing from this index or when an entry points at a file that no longer exists, so
the list cannot rot.

## Current state

| Document | Role |
| --- | --- |
| [PROGRESS.md](PROGRESS.md) | What has been built, per phase, with the checks that were run. The place to look for "is X done". |
| [DECISIONS.md](DECISIONS.md) | Every decision that changes behaviour, numbered `D-N`, with the reasoning and what it replaced. |
| [KNOWN_ISSUES.md](KNOWN_ISSUES.md) | Known defects, limitations and the workarounds in place. Read before reporting a bug. |
| [AGENT_REQUESTS.md](AGENT_REQUESTS.md) | Outstanding requests and the questions they answer. |
| [CHECKLIST.md](CHECKLIST.md) | Pre-release verification steps. |
| [MANUAL_TEST.md](MANUAL_TEST.md) | Manual test script for the paths that automated tests cannot cover. |

## Design and specification

| Document | Role |
| --- | --- |
| [PRODUCT_SPEC.md](PRODUCT_SPEC.md) | What the app is meant to do, with the `NOW` / `LATER` split that `FeatureFlag` mirrors. |
| [PROJECT_BRIEF.md](PROJECT_BRIEF.md) | Short statement of the product and its audience. |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Module map, layering rules and the data flow between them. |
| [DESIGN_SYSTEM.md](DESIGN_SYSTEM.md) | Tokens, typography and the rules for adding UI. |
| [EXTENSION_POINTS.md](EXTENSION_POINTS.md) | Where a new feature must plug in, and how a LATER flag is flipped. |
| [PHASES.md](PHASES.md) | The phase plan and numbering. |
| [PACKS.md](PACKS.md) | The pack, model and translation-source model the app exposes, in Persian. |
| [REFERENCES.md](REFERENCES.md) | External sources and reference repositories the code follows. |

## Historical records

These describe how the code reached its current shape. They are kept for the reasoning, not as a
description of today's code.

| Document | Role |
| --- | --- |
| [AUDIT_UI_STRUCTURE.md](AUDIT_UI_STRUCTURE.md) | The UI inventory before the Phase 17/18 consolidation, and why the screens were merged. |
| [GAPS_AND_ASSUMPTIONS.md](GAPS_AND_ASSUMPTIONS.md) | Ambiguities found during the source-data audit and the decisions taken to resolve them. |
| [IMPROVEMENTS.md](IMPROVEMENTS.md) | Fixes discovered while auditing the reference repositories. |
| [PACK_RESEARCH.md](PACK_RESEARCH.md) | Candidate datasets and models evaluated for the catalog, with licences and sizes. |

## Data

| Path | Role |
| --- | --- |
| [catalog/catalog-v2.json](catalog/catalog-v2.json) | The catalog the app reads: packs and models with sizes and hashes. Refreshed by `tools/update_catalog.py` on every pack release. |
| [catalog/catalog-v1.json](catalog/catalog-v1.json) | The previous catalog, kept so older installs can still be read. |
