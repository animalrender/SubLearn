# UI verification: seeing and driving the running app

The agent sandbox has no JVM, no emulator, and no way to read the Actions log or artifact hosts, so a
change to a screen cannot be verified locally by compiling it. This file is the single source of truth
for the on-device check that closes that gap. CI builds the debug APK, boots an Android emulator, drives
the app with [Maestro](https://docs.maestro.dev) flows, and publishes screenshots, view-hierarchy dumps
and logs to the orphan branch `ui-snapshots`, which the sandbox reads with plain `git`. Player flows
use the debug-only embedded-subtitle fixture and FileProvider launch path; external-file-picker behaviour
is still outside automation.

| Piece | Where |
| --- | --- |
| Workflow | `.github/workflows/ui-snapshot.yml` |
| Everything the job does on the emulator, and the publishing | `scripts/ui-snapshot/run.sh` |
| Baseline flows (permanent, at most 10, numbered) | `.maestro/NN-name.yaml` |
| Per-change flows (run after the baseline, deleted before the merge) | `.maestro/_scratch/*.yaml` |
| Results | the branch `ui-snapshots`, one orphan commit, replaced on every run |
| Why it is built this way | [DECISIONS.md](DECISIONS.md) D-94 |

## a) Purpose, and when it is mandatory

The check lets you do three things from the sandbox: **see** what the user would see (screens, text,
layout, colours, RTL), **drive** the app the way a user does (tap, type, scroll, go back), and **read
its own log** for crashes.

It is **mandatory for any change the user can see or operate**: a screen, a dialog, a string, a layout,
spacing, a colour, typography, the theme, an icon, an animation, navigation, a setting row, an empty or
error state, how Persian (RTL) renders. It is also mandatory for anything that can stop the app from
starting even when no pixel changes (the Koin graph, `Application`/`MainActivity`, the manifest, Room
schema and migrations, DataStore, native libraries, R8 rules), because the baseline flows start the app
on a clean install. Not required: documentation-only changes, tooling that does not touch the app, pure
JVM logic with no screen on its path (unit tests are the check there). If in doubt, run it; it costs
about eight minutes of waiting.

## b) How to trigger a check

Push an **empty last commit** whose message contains `[ui]`:

```bash
git commit --allow-empty -m "chore: ui check [ui]"
git push origin <your-branch>
gh run list --branch <your-branch> --workflow ui-snapshot.yml --limit 3 \
  --json databaseId,status,conclusion,headSha --jq '.[] | [.databaseId, .status, .conclusion, .headSha] | @tsv'
gh run watch <run-id> --exit-status     # or poll: gh run view <run-id> --json status,conclusion,jobs
```

- The job runs for `workflow_dispatch`, or for a push whose **tip commit** says `[ui]`. Every other push
  skips it (cheap). A branch named `main` or `ui-snapshots` never triggers it.
- The commit is empty and last so that nothing changes between the code and the check: the run's sha is
  the sha you will merge. Do not write `[ui]` in any other commit message by accident.
- **Confirm that a run exists for your branch and that its `headSha` equals `git rev-parse HEAD`.** If no
  run appears after a minute or two, say so; do not assume one is coming. Causes: the marker is missing
  from the tip commit, or the workflow file is invalid YAML (GitHub then shows "Invalid workflow file").
  The comparison in one line:
  `[ "$(gh run list --branch <b> --workflow ui-snapshot.yml --limit 1 --json headSha --jq '.[0].headSha')" = "$(git rev-parse HEAD)" ] && echo same`.
  A run whose conclusion is `skipped` is a push without the marker: its job never started.
- `gh workflow run` fails with 403 in the sandbox, so the push marker is the only trigger from here. A
  human can start it from the Actions tab (UI snapshot, Run workflow) once the workflow is on `main`.
- It takes about **eight minutes** in this repository (measured: 7 m 52 s for 8 flows): checkout and
  setup 35 s, `assembleDebug` 37 s with a warm Gradle cache (allow up to ten minutes on a cold one),
  Maestro 7 s, emulator boot about 90 s, 15 to 50 s per flow, publish 3 s. There is one concurrency
  group per ref with `cancel-in-progress`, so a newer marked push replaces the run still in progress; a
  push without the marker gets a group of its own and skips the job (observed: it did not cancel a run
  that was working on the previous marked commit).
- A red job still publishes. Failed build, failed flows, a dead emulator: the publish step runs anyway
  and the branch carries the reason.

## c) How to read the results

```bash
rm -rf /tmp/snap
git clone --depth 1 -b ui-snapshots https://github.com/animalrender/SubLearn.git /tmp/snap
cat /tmp/snap/STATUS.md
```

Or without cloning, with the contents API (add `-H 'Accept: application/vnd.github.raw'` for the bytes):

```bash
gh api -H 'Accept: application/vnd.github.raw' 'repos/animalrender/SubLearn/contents/STATUS.md?ref=ui-snapshots'
gh api -H 'Accept: application/vnd.github.raw' \
  'repos/animalrender/SubLearn/contents/maestro/01-home/shots/01-home.png?ref=ui-snapshots' > /tmp/01-home.png
```

**Check that the result is yours before you read anything else.** `STATUS.md` starts with `branch:` and
`sha:`. The `sha:` must equal `git rev-parse HEAD` of the commit you are judging. The branch is one
shared pointer that every run of every branch overwrites, so a result from an older commit (or from
another session's branch) is the main trap. A mismatch means the result is not about your change: wait
for your run, or trigger one.

A second, independent channel is the job's check-run annotations, readable even if the branch is not:

```bash
JOB=$(gh run view <run-id> --json jobs --jq '.jobs[0].databaseId')
gh api "repos/animalrender/SubLearn/check-runs/$JOB/annotations" --jq '.[] | [.annotation_level, .title, .message] | @tsv'
```

It shows one `UI snapshot` notice (`sha | app crash | flows`) and, when the run is not clean, one
`UI snapshot is not clean` error per problem. Do not loop on `gh run view --log` or `gh run download`:
the log and artifact hosts are blocked from the sandbox.

What is on the branch (`<flow>` is the file name without `.yaml`; scratch flows are `scratch-<name>`):

| Path | What it is |
| --- | --- |
| `STATUS.md` | Read first: branch, sha, time, build, `app crash: yes/no`, PASS/FAIL per flow, the screenshots to open, the end of the console of every failed flow, the crash lines, the build log tail when the build failed |
| `results.tsv` | One tab-separated row per flow: name, `PASS`/`FAIL`, exit code, seconds, number of app-crash matches, a note (`-` when there is none, e.g. "second attempt") |
| `device.txt`, `run.log`, `build.log`, `maestro-install.txt`, `adb-devices.txt` | The device and runner image, the script's own log, the Gradle log, the setup logs |
| `maestro/<flow>/shots/<name>.png` | The `takeScreenshot` images of the flow, 600 px wide. **Open these** |
| `maestro/<flow>/final.png` | The screen at the end of the flow, whatever the flow did or failed to do |
| `maestro/<flow>/hierarchy.json` | The full view hierarchy of that final screen (`maestro hierarchy`) |
| `maestro/<flow>/hierarchy-text.txt` | The same, one line per node that has text, a description or a hint: `clickable`, text, content-desc, hint, bounds. The labels a flow can use |
| `maestro/<flow>/logcat-app.txt` | Warnings, errors and fatals of **our** pids, plus anything that names our package and every crash line. **Judge crashes from this** |
| `maestro/<flow>/logcat-full.txt` | Everything the emulator logged, third-party noise included. Never judge the app from it |
| `maestro/<flow>/app-crash.txt` | Only when a real crash was found: each match with about 40 lines around it |
| `maestro/<flow>/result.txt`, `maestro-console.txt`, `pids.txt` | PASS/FAIL, exit code and seconds; what Maestro printed; our process ids |
| `maestro/<flow>/<timestamp>/<flow>/` | Maestro's own bundle: `commands.json` (status and error of every step), `logs/`, and for a failing step its `screenshots/` and `screen-hierarchy/` |
| `maestro/<flow>/attempt-1/` | Only after a retry (see item 6 of the checklist): everything the first, failed attempt left |

## d) Review checklist

1. **Open the PNG images and look at them.** A green Maestro run proves only that the selectors found
   their text, not that the screen is right. The final screenshot of a failed flow shows the state it got
   stuck in: that is how the keyboard-suggestion trap in section e) was found.
2. **Compare what you see with the user's request**, screen by screen: text clipped or overlapping,
   wrong colours or spacing, a missing element, an element in the wrong place, a screen that is empty
   when it should not be, an error dialog or a status line in the error colour, an empty state that
   reads wrong.
3. **RTL and Persian.** Anything with text or layout also gets a look in Persian (recipe in section e):
   the layout must mirror (menu button, chips and tabs swap sides), the text must run right to left,
   and an arrow in a sentence must point the way the sentence reads.
4. **Crashes: only from `logcat-app.txt` and the `app crash:` line of `STATUS.md`.** The emulator's Google
   apps (Play services, Cronet, MDD, the quick-search box) log many `E` lines and even
   `NullPointerException`s that are not ours, and processes such as `com.android.externalstorage`
   "have died" all the time. `STATUS.md` reports a crash only for a real one: `FATAL EXCEPTION` followed by
   `Process: com.sublearn.app.debug`; `ANR in com.sublearn.app.debug`; `Process com.sublearn.app.debug (pid N)
   has died`; `Fatal signal` or `backtrace:` from one of our pids (native crashes from ONNX or ML Kit have
   no `FATAL EXCEPTION`), or a tombstone header naming our package; an `am_crash` or `am_anr` event for
   our package. Other processes are never flagged.
5. **A system dialog over the app is emulator noise, not an app crash.** The emulator sometimes raises
   "Pixel Launcher isn't responding" right after it boots, and Maestro then sees only the dialog (one run
   lost all eight flows to it). The script now switches such dialogs off (`hide_error_dialogs`) and removes
   one that gets through by stopping the process it names, never ours; neither has had to act on a real
   dialog yet. If `STATUS.md` says a system dialog is on screen, re-run once, and report it if it keeps
   happening (section f, [KNOWN_ISSUES.md](KNOWN_ISSUES.md) 36).
6. **A flow that never saw the app: look at its `final.png`.** A completely black screen with only the status
   bar, and a failure on the very first assertion, is usually a launch race, not your change: Android
   killed the app's new process right after the start (logcat: `Killing ... (adj -10000): remove task`,
   then `failed to attach`). The script avoids it (it wipes the previous app and waits before each flow)
   and retries a flow once when it finds exactly that signature; `STATUS.md` then says "second attempt"
   and the first attempt is in `attempt-1/`. A crash is never retried away.
7. **Player coverage.** The debug-only fixture-backed flows are intended to drive the real Player surface,
   embedded subtitle selection/rendering, portrait and landscape chrome, scrub, and Player sheets. Their
   PASS status and screenshots must be checked against the exact branch SHA before calling them verified.
   The system file picker and external subtitle/audio attachment flows are not driven; see the limits at
   the end. Screenshots are 600 px wide, so use `hierarchy-text.txt` for exact strings and bounds, not for
   visual layout.

## e) How to write flows

A flow is a Maestro YAML file: a header with the app id, `---`, then the steps. Always the debug id.

```yaml
appId: com.sublearn.app.debug
---
- launchApp:
    clearState: true              # a clean install: English, no data, no saved state
- extendedWaitUntil:              # launchApp returns before the first Compose frame:
    visible: "Open a video"       # wait for a known text before EVERY screenshot
    timeout: 60000
- tapOn: "Learn"                  # by text, or by content description (same selector: "Back")
- tapOn:
    text: "Settings"
    below: "Quiz"                 # disambiguate: above, below, leftOf, rightOf, index
- tapOn:
    text: "Might not be there"
    optional: true                # a navigation tap that may not exist: the flow goes on
- inputText: "theme"
- hideKeyboard                    # sends the Back key: use it only while the keyboard is up
- scrollUntilVisible:
    element: "Subtitle lab"
    timeout: 60000
- assertVisible: "No videos yet.*"
- takeScreenshot: 01-home         # lands in maestro/<flow>/shots/01-home.png
- back                            # the system back key
```

**Selectors.** A text selector is a regular expression that must match the **whole** text, ignoring case:
`Downloads.*` is a prefix match, and `(`, `?`, `+` or `.` in a label are regex characters (a `.` still
matches itself). It matches the text, the content description and the hint of a node.

**Finding labels, never guessing them.** English strings are in
`core/designsystem/src/main/res/values/strings.xml`, Persian in `values-fa/strings.xml`; look the key up
with `grep -n 'name="home_open_file"'`. What is really on screen, with format arguments resolved, is in
`maestro/<flow>/hierarchy-text.txt` of any earlier run. Read both before you write a selector.

**Traps verified in this app.**

- **The soft keyboard is part of the hierarchy, listed before the app, and its suggestion strip is
  clickable.** After `inputText: "download"` the selector `Downloads.*` tapped the suggestion
  "downloads" instead of the row, which rewrote the query and emptied the list. Wait for and tap text
  only the app can show (a row's subtitle), or `hideKeyboard` first (checked: it closes the keyboard and
  leaves the screen where it was).
- **Compose puts `clickable` on the parent of the text.** `hierarchy-text.txt` shows `-` for most text
  nodes. That does not mean the text cannot be tapped: `tapOn` taps the middle of the text, inside the
  clickable parent.
- **The menu button and the drawer entry are both called "Settings".** Open the drawer with
  `tapOn: "Settings"`, then pick the entry with `below: "Quiz"` (only the drawer has "Quiz").
- **A placeholder disappears when the field has text.** The Settings search box says "Search settings"
  only while it is empty, and the query survives going into a section and back.
- **`clearState: true` resets everything**, including the language, so a flow can switch to Persian and
  the next flow still starts in English. The runner also wipes the app and presses Home before every flow,
  so every flow starts from the launcher; never rely on what the previous flow left on screen.
- **Non-ASCII labels are fine** in flows and in the dumps: the script runs Maestro under a UTF-8 locale,
  because under `LC_ALL=C` every non-ASCII character in a dump (the en dash of "A–Z", the space in the
  clock) came out as `?`, and Persian labels would have too.

**Recipe: reach Settings from Home.**

```yaml
- tapOn: "Settings"                 # the menu button (content description "Settings")
- extendedWaitUntil:
    visible: "Quiz"
    timeout: 15000
- tapOn:
    text: "Settings"
    below: "Quiz"                   # the drawer entry
- extendedWaitUntil:
    visible: "Search settings"
    timeout: 20000
```

**Recipe: look at a screen in Persian (RTL).** Settings, Appearance, App language, the chip `fa`. The
activity is recreated and lands on Home in Persian; navigate from there with the Persian labels.

```yaml
- tapOn: "Appearance.*"
- scrollUntilVisible:
    element: "App language"
    timeout: 40000
- tapOn:
    text: "fa"                      # "My language" below has a second "fa"
    below: "App language"
- extendedWaitUntil:
    visible: "باز کردن ویدیو"        # home_open_file in values-fa
    timeout: 30000
- takeScreenshot: persian-home
- tapOn: "فرهنگ لغت"                 # tab_dictionary in values-fa
```

**Batching.** One round trip costs about eight minutes, so do not spend one per question. Write the
flows for **every screen and every state your change touches** and run them together: one flow per
screen, a screenshot at each state (empty, filled, error, Persian). Add `optional: true` to taps you are
unsure about and assert afterwards, so a single run teaches you about several selectors. A failing flow
fails only itself; the flows after it still run, and the failed flow's `final.png` and
`hierarchy-text.txt` usually answer the next question. Fix every failing flow from one reading, then run
again.

## f) Stop rules

- **Done** means: all flows pass, `app crash: no`, and you have **opened the images** and they match
  the request. Say what you saw, not that the run was green.
- If the same failure repeats three times without new insight, stop repeating it and change the
  approach: read `hierarchy-text.txt` and `commands.json` instead of guessing, and try a different
  selector, a different route to the screen, or a smaller flow.
- If the infrastructure itself is broken (no run appears, the emulator does not boot, the push to
  `ui-snapshots` is rejected, the results cannot be read, every flow fails behind a system dialog), stop
  and report it honestly in [KNOWN_ISSUES.md](KNOWN_ISSUES.md) with the run id and the error. Do not
  improvise a substitute and do not merge a change you could not check.
- Never claim success without evidence: a sha that matches, a status line, and images you looked at.

## g) Cleanup before the merge

- Delete `.maestro/_scratch/`, or promote a scratch flow that has lasting regression value to
  `.maestro/NN-name.yaml` (keep the baseline at ten flows or fewer, each with a number).
- Remove your clones and downloads: `rm -rf /tmp/snap /tmp/*.png`.
- Keep `main` free of artifacts: no PNG, video or log is committed anywhere except `ui-snapshots`
  (`/out/` is in `.gitignore` for this reason), and nothing on `ui-snapshots` is ever merged.

## h) Merge gate

**The UI check is the last step before the merge, and after the merge the session is over**: nothing can
be changed afterwards, so whatever is not clean has to be fixed first.

1. Finish the code, the docs and the cleanup in section g).
2. `git commit --allow-empty -m "chore: ui check [ui]"`, push, and confirm that a run exists and that its
   `headSha` is your `HEAD`.
3. Wait for the UI check **and** for `CI` on the same sha to finish green.
4. Read `STATUS.md` (its `sha:` equals your `HEAD`, `app crash: no`, every flow PASS) and open the PNGs.
5. Only then merge.

## How it works (reference)

The workflow runs on `ubuntu-24.04` (pinned: `ubuntu-latest` moves to 26.04 on 2026-10-19 and this setup
was proven on 24.04). Steps: checkout (full history), JDK 17, Android SDK platform 35 and build-tools
35.0.0, Gradle, `./gradlew assembleDebug` (log in `out/build.log`), stop the Gradle daemons, install
Maestro, enable KVM, install ImageMagick, boot the emulator (API 34, `google_apis`, x86_64, profile
`pixel_6` so the screen is 1080x2400, 4 GB) and run `bash scripts/ui-snapshot/run.sh`, then publish and
judge with `run.sh publish` and `run.sh verdict` (both `if: always()`). The last step fails the job when
the build failed, any flow failed, no flow ran, or `STATUS.md` says `app crash: yes`: a failed script
never looks green. `android-emulator-runner` runs its `script:` input line by line with `sh`, which is
why all the logic is in the script file.

`run.sh run` installs the exact x86_64 debug APK it finds under `app/build/outputs/apk/debug`, runs every
`.maestro/*.yaml` and then every `.maestro/_scratch/*.yaml` one by one (continuing after a failure). Before
each flow it wipes the app and presses Home (waiting three seconds, see the launch race above), removes a
leftover system dialog and runs `adb logcat -c`; after it, it collects the screen, the hierarchy and the logs,
scans the app's own logcat for a real crash, shrinks the PNGs, and appends to `results.tsv`. A flow that
failed with the launch-race signature and no crash is run once more. `run.sh publish`
rewrites `STATUS.md` from the files on disk and the step outcomes (so it is right even if the run died),
and force-pushes `out/` as one orphan commit to `ui-snapshots` with `GITHUB_TOKEN`; the job has
`contents: write` for that and for nothing else.

Knobs, all environment variables (set them in the job's `env:`): `MAESTRO_VERSION` pins Maestro (verified
with 2.11.0; set it if a newer release breaks the flows), `UI_FLOW_TIMEOUT` (240 s per flow) and
`UI_BUDGET_SECONDS` (840 s for the whole run; flows that do not start in time are reported as not run).
The `ui-snapshots` branch is generated: never edit it, merge it or delete it by hand.

**Limits.** The debug-only fixture is a synthetic 3-minute clip with one embedded English subtitle
track; the Player flows use it to test controls and subtitle rendering without depending on a user's
media library. A debug-only startup extra extends control auto-hide to ten minutes so selectors and
screenshots are stable; these flows do **not** verify the normal three-second auto-hide timer. Mark
Player coverage verified only when the exact-sha UI run passes and its screenshots are reviewed.
The Android system file picker is not driven, so external subtitle/audio attachment, SAF grants and
real-library resume remain unverified. Downloads are looked at, not performed. The emulator is x86_64
with a software GPU and animations off, so it says nothing about performance, animation timing,
picture-in-picture, sensors or Bluetooth. Release (R8) builds are not run by the UI workflow.
