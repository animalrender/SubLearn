#!/usr/bin/env bash
#
# scripts/ui-snapshot/run.sh: see and drive the running app (docs/UI_VERIFICATION.md).
#
#   run      (default) Install the debug APK on the connected emulator, install Maestro, run every
#            flow in .maestro/*.yaml and then .maestro/_scratch/*.yaml one by one, and collect the
#            evidence into out/. android-emulator-runner executes its `script:` input line by line
#            with sh, so all the logic lives in this file and the workflow calls it as one line.
#   install-maestro
#            Only install Maestro. The workflow does this before the emulator boots, so that the
#            315 MB download and unzip do not compete with a freshly booted emulator for the CPU.
#   publish  Write out/STATUS.md and force-push out/ as ONE orphan commit to the branch
#            `ui-snapshots` using GITHUB_TOKEN. Safe to run after a failed build or a dead emulator.
#   verdict  Exit non-zero unless the build, every flow and the crash scan are clean.
#
# Nothing here uses `set -e`: a failing flow must not stop the flows after it, and every exit code
# that matters is written down (out/results.tsv) instead of being swallowed.

set -uo pipefail
# C for grep, awk and sort: logcat is not always valid UTF-8. The JVM is the exception (see MAESTRO).
export LC_ALL=C

cd "$(dirname "${BASH_SOURCE[0]}")/../.." || exit 2
ROOT=$PWD
OUT=${UI_OUT_DIR:-$ROOT/out}
PKG=com.sublearn.app.debug
PKG_RE='com\.sublearn\.app\.debug'
APK_DIR=${UI_APK_DIR:-$ROOT/app/build/outputs/apk/debug}
FLOW_TIMEOUT=${UI_FLOW_TIMEOUT:-240}      # seconds for one flow
BUDGET=${UI_BUDGET_SECONDS:-840}          # seconds for the whole run, so STATUS.md is written in time
CONTEXT=40                                # lines of logcat kept after every crash match
SNAPSHOT_BRANCH=ui-snapshots
START_EPOCH=$(date +%s)
# Maestro prints the hierarchy with the JVM's default charset, which follows the locale: under C every
# Persian label and every arrow in a dump would come out as "?". So it, and only it, gets UTF-8.
MAESTRO=(env LC_ALL=C.UTF-8 maestro)

BRANCH=${GITHUB_REF_NAME:-$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)}
SHA=$(git rev-parse HEAD 2>/dev/null || echo unknown)
RUN_URL=local
if [ -n "${GITHUB_RUN_ID:-}" ] && [ -n "${GITHUB_REPOSITORY:-}" ]; then
  RUN_URL="${GITHUB_SERVER_URL:-https://github.com}/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID (attempt ${GITHUB_RUN_ATTEMPT:-1})"
fi

log() {
  mkdir -p "$OUT"
  printf '[ui-snapshot %s] %s\n' "$(date -u +%H:%M:%S)" "$*" | tee -a "$OUT/run.log" >&2
}

# An infrastructure failure (no APK, install failed, no Maestro): recorded, shown in STATUS.md, fatal.
fatal() {
  log "FATAL: $*"
  printf '%s\n' "$*" >>"$OUT/fatal.txt"
}

# ---------------------------------------------------------------------------------------------
# The logcat scan. One awk pass over logcat-full.txt (threadtime format) writes two files:
#   logcat-app.txt  W/E/F lines of our pids and of anything that names our package, plus every
#                   line that is crash evidence at any level;
#   app-crash.txt   CONTEXT lines around each REAL crash match (absent when there is none),
# and prints the number of matches. A real app crash is only one of:
#   FATAL EXCEPTION followed by "Process: com.sublearn.app.debug"; "ANR in com.sublearn.app.debug";
#   "Process com.sublearn.app.debug (pid N) has died"; "Fatal signal" or "backtrace:" from our pid, or
#   a tombstone header that names our package (native crashes have no FATAL EXCEPTION); an
#   am_crash / am_anr event naming our package. Other processes are never flagged: the emulator's
#   Google apps throw plenty of their own errors, and processes like com.android.externalstorage
#   "have died" all the time.
# ---------------------------------------------------------------------------------------------
read -r -d '' SCAN_AWK <<'AWK'
function flag(n, why) { if (!(n in hit)) { hit[n] = why; nhit++ } }
function flush_window(   j) {
  if (wend == 0) return
  print "=== " reasons " | logcat-full.txt lines " wstart "-" wend " ===" > crash_out
  for (j = wstart; j <= wend; j++) print lines[j] > crash_out
  print "" > crash_out
}
BEGIN {
  n = split(pids, parr, " ")
  for (i = 1; i <= n; i++) ours[parr[i]] = 1
  pkgre = ENVIRON["PKG_RE"]
  fatal_at = 0
  print "# logcat-app.txt: W/E/F lines of pid(s) [" pids "] and of anything naming " pkg "; crash evidence at any level" > app_out
}
{
  lines[NR] = $0
  pid = $3; lvl = $5
  is_ours = (pid in ours)
  noisy = (lvl == "W" || lvl == "E" || lvl == "F" || lvl == "A")
  evidence = 0

  if ($0 ~ /FATAL EXCEPTION/) fatal_at = NR
  if (fatal_at > 0 && NR - fatal_at <= 5 && $0 ~ ("Process: " pkgre "([:, ]|$)")) {
    flag(fatal_at, "FATAL EXCEPTION in " pkg); evidence = 1; fatal_at = 0
  }
  if ($0 ~ ("ANR in " pkgre "([ :(]|$)")) { flag(NR, "ANR in " pkg); evidence = 1 }
  if ($0 ~ ("Process " pkgre " \\(pid [0-9]+\\) has died")) { flag(NR, pkg " process has died"); evidence = 1 }
  if (is_ours && $0 ~ /Fatal signal [0-9]+/) { flag(NR, "native crash (Fatal signal)"); evidence = 1 }
  if (is_ours && $0 ~ /backtrace:/) { flag(NR, "native crash (backtrace)"); evidence = 1 }
  if ($0 ~ (">>> " pkgre "(:[A-Za-z0-9_.]+)? <<<")) { flag(NR, "native crash (tombstone for " pkg ")"); evidence = 1 }
  if ($0 ~ /am_(crash|anr):/ && $0 ~ pkgre) { flag(NR, "am_crash/am_anr event"); evidence = 1 }

  if ((is_ours && noisy) || ($0 ~ pkgre && noisy) || evidence) print $0 > app_out
}
END {
  wstart = 0; wend = 0; reasons = ""
  for (i = 1; i <= NR; i++) {
    if (!(i in hit)) continue
    s = i - 5; if (s < 1) s = 1
    e = i + ctx; if (e > NR) e = NR
    if (wend > 0 && s <= wend + 1) {
      if (e > wend) wend = e
      reasons = reasons "; " hit[i] " @" i
    } else {
      flush_window()
      wstart = s; wend = e; reasons = hit[i] " @" i
    }
  }
  flush_window()
  print nhit + 0
}
AWK

# ---------------------------------------------------------------------------------------------
# Device and tool setup
# ---------------------------------------------------------------------------------------------
pick_apk() {
  # With ABI splits there is one APK per ABI plus a universal one: take the exact x86_64 build for
  # the emulator, then the universal one, never a name guessed in advance.
  local pattern found
  for pattern in '*x86_64*debug*.apk' '*universal*debug*.apk' '*debug*.apk'; do
    found=$(find "$APK_DIR" -maxdepth 1 -type f -name "$pattern" | sort | head -n 1)
    if [ -n "$found" ]; then
      printf '%s\n' "$found"
      return 0
    fi
  done
  return 1
}

install_apk() {
  local apk result
  if ! apk=$(pick_apk); then
    fatal "no debug APK under $APK_DIR (found: $(find "$APK_DIR" -maxdepth 1 -printf '%f ' 2>&1))"
    return 1
  fi
  log "installing $apk"
  result=$(adb install -r -t -g "$apk" </dev/null 2>&1)
  printf '%s\n' "$result" >>"$OUT/install.log"
  if ! grep -q '^Success' <<<"$result"; then
    fatal "adb install failed for $apk: $result"
    return 1
  fi
  if ! adb shell pm list packages "$PKG" </dev/null 2>/dev/null | tr -d '\r' | grep -qx "package:$PKG"; then
    fatal "$apk installed, but package $PKG is not on the device (is it a debug build?)"
    return 1
  fi
}

install_maestro() {
  export MAESTRO_CLI_NO_ANALYTICS=1 MAESTRO_DISABLE_UPDATE_CHECK=true MAESTRO_CLI_ANALYSIS_NOTIFICATION_DISABLED=true
  export PATH="$PATH:$HOME/.maestro/bin"
  if ! command -v maestro >/dev/null 2>&1; then
    log "installing Maestro${MAESTRO_VERSION:+ $MAESTRO_VERSION}"
    curl -Ls "https://get.maestro.mobile.dev" | bash >"$OUT/maestro-install.txt" 2>&1
  fi
  if ! command -v maestro >/dev/null 2>&1; then
    fatal "Maestro is not installed; see maestro-install.txt"
    return 1
  fi
  "${MAESTRO[@]}" --version </dev/null 2>&1 | tail -n 1 >"$OUT/maestro-version.txt"
  log "maestro $(cat "$OUT/maestro-version.txt")"
}

# The emulator's own apps (Pixel Launcher, Play services) now and then stop responding while the
# freshly booted system is busy, and Android puts an "isn't responding" dialog over whatever is on
# screen. Maestro then sees only the dialog: in one run all 8 flows failed on their first assertion.
# Layer 1: do not show such dialogs at all (our own crashes and ANRs are judged from logcat anyway).
hide_system_dialogs() {
  adb shell settings put global hide_error_dialogs 1 </dev/null >/dev/null 2>&1
}

# Layer 2, before every flow: a dialog that got through (or one raised for another reason) is removed
# by stopping the process it names. Never ours: that one is evidence, and the crash scan reports it.
clear_system_dialogs() {
  local who
  while IFS= read -r who; do
    [ -n "$who" ] && [ "$who" != "$PKG" ] || continue
    log "an \"isn't responding\" dialog of $who covers the screen; stopping that process"
    adb shell am force-stop "$who" </dev/null >/dev/null 2>&1
  done < <(adb shell dumpsys window windows </dev/null 2>/dev/null | tr -d '\r' |
    grep -aoE 'Application (Not Responding|Error): [A-Za-z0-9_.]+' | sed -E 's/^.*: //' | sort -u)
}

# Maestro's launchApp with clearState runs `pm clear` and, about a second later, `am start`. When the app
# of the PREVIOUS flow is still on screen, `pm clear` removes its task, and the activity manager gives
# that removal a destroy timeout of about a second (logcat: "Destroy timeout of remove-task") that
# kills "the process of the task". If the new process has been forked by then, the timeout kills the
# NEW one: it never attaches, the screen stays black, and the flow dies waiting for its first frame
# (2 of 8 flows in one run). So the previous app is wiped here, and the timeout is given time to expire,
# before Maestro starts anything.
quiesce_app() {
  adb shell pm clear "$PKG" </dev/null >/dev/null 2>&1
  adb shell input keyevent KEYCODE_HOME </dev/null >/dev/null 2>&1
  sleep "${UI_QUIESCE_SECONDS:-3}"
}

# The signature of that race in a flow's logcat: our new process was killed as part of a removed task, or
# it never attached. A flow that failed like this says nothing about the app and is retried once.
launch_race() {
  grep -aqE "Process ProcessRecord\{[^}]*:$PKG_RE/[^}]*\} failed to attach|Killing [0-9]+:$PKG_RE/[^ ]+ \(adj -10000\): remove task" "$1/logcat-full.txt"
}

describe_device() {
  {
    echo "model:    $(adb shell getprop ro.product.model </dev/null | tr -d '\r')"
    echo "android:  $(adb shell getprop ro.build.version.release </dev/null | tr -d '\r') (sdk $(adb shell getprop ro.build.version.sdk </dev/null | tr -d '\r'))"
    echo "abi:      $(adb shell getprop ro.product.cpu.abi </dev/null | tr -d '\r')"
    echo "screen:   $(adb shell wm size </dev/null | tr -d '\r' | tail -n 1), $(adb shell wm density </dev/null | tr -d '\r' | tail -n 1)"
    echo "app:      $(adb shell dumpsys package "$PKG" </dev/null | tr -d '\r' | grep -m1 versionName | sed 's/^ *//')"
    echo "load:     $(adb shell cat /proc/loadavg </dev/null | tr -d '\r') (1, 5, 15 minutes; running/total; last pid)"
    echo "runner:   $(. /etc/os-release 2>/dev/null && echo "$PRETTY_NAME"), image ${ImageOS:-?} ${ImageVersion:-?}, $(nproc) cpus"
  } >"$OUT/device.txt" 2>&1
}

# ---------------------------------------------------------------------------------------------
# Evidence for one flow
# ---------------------------------------------------------------------------------------------
summarize_hierarchy() {
  # One line per node that carries text, a content description or a hint: the labels a flow can use.
  printf 'clickable\ttext\tcontent-desc\thint\tbounds\n'
  jq -r '
    def clean: gsub("[\\t\\r\\n]+"; " ");
    .. | objects | select(has("attributes"))
    | . as $n
    | [ (if $n.clickable == true then "clickable" else "-" end),
        (($n.attributes.text // "") | clean),
        (($n.attributes.accessibilityText // "") | clean),
        (($n.attributes.hintText // "") | clean),
        ($n.attributes.bounds // "") ]
    | select(.[1] != "" or .[2] != "" or .[3] != "")
    | @tsv' "$1"
}

capture_screen() {
  local dir=$1
  # What the screen looks like right now, whatever the flow did or failed to do.
  adb exec-out screencap -p </dev/null >"$dir/final.png" 2>/dev/null
  [ -s "$dir/final.png" ] || rm -f "$dir/final.png"

  # The final-screen hierarchy. Maestro writes its own dump only for some steps, so take one here.
  if timeout 120 "${MAESTRO[@]}" hierarchy </dev/null >"$dir/hierarchy.raw" 2>"$dir/hierarchy.err"; then
    sed -n '/^[[:space:]]*[{[]/,$p' "$dir/hierarchy.raw" >"$dir/hierarchy.json"
  fi
  rm -f "$dir/hierarchy.raw"
  if [ -s "$dir/hierarchy.json" ] && { ! command -v jq >/dev/null 2>&1 || jq -e . "$dir/hierarchy.json" >/dev/null 2>&1; }; then
    command -v jq >/dev/null 2>&1 && summarize_hierarchy "$dir/hierarchy.json" >"$dir/hierarchy-text.txt" 2>>"$dir/hierarchy.err"
  else
    log "maestro hierarchy gave nothing usable for $(basename "$dir"); falling back to uiautomator dump"
    rm -f "$dir/hierarchy.json"
    if timeout 60 adb shell uiautomator dump /sdcard/ui-window.xml </dev/null >>"$dir/hierarchy.err" 2>&1; then
      adb pull /sdcard/ui-window.xml "$dir/hierarchy.xml" </dev/null >>"$dir/hierarchy.err" 2>&1
    fi
  fi
  [ -s "$dir/hierarchy.err" ] || rm -f "$dir/hierarchy.err"
}

# Prints the number of real crash matches. Writes logcat-full.txt, logcat-app.txt, pids.txt and,
# when there is a match, app-crash.txt.
capture_logcat() {
  local dir=$1 full=$1/logcat-full.txt pids max=30000000 size
  adb logcat -b all -d -v threadtime </dev/null >"$full" 2>"$dir/logcat.err" ||
    adb logcat -d -v threadtime </dev/null >"$full" 2>>"$dir/logcat.err"
  size=$(wc -c <"$full")
  if [ "$size" -gt "$max" ]; then
    log "logcat of $(basename "$dir") is $size bytes; keeping the last $max"
    tail -c "$max" "$full" >"$full.tail" && mv "$full.tail" "$full"
  fi
  [ -s "$dir/logcat.err" ] || rm -f "$dir/logcat.err"

  # Our pids: every "Start proc <pid>:com.sublearn.app.debug" (there are several after restarts, and
  # one more for the :model_test process), plus whatever is running now.
  pids=$({
    grep -aoE "Start proc [0-9]+:$PKG_RE" "$full" | sed -E 's/^Start proc ([0-9]+):.*/\1/'
    adb shell ps -A -o PID,NAME </dev/null 2>/dev/null | tr -d '\r' | awk -v p="$PKG" 'NR > 1 && index($2, p) == 1 { print $1 }'
  } | sort -un | tr '\n' ' ')
  pids=${pids% }
  printf '%s\n' "$pids" >"$dir/pids.txt"

  PKG_RE=$PKG_RE awk -v pids="$pids" -v pkg="$PKG" -v ctx="$CONTEXT" \
    -v app_out="$dir/logcat-app.txt" -v crash_out="$dir/app-crash.txt" "$SCAN_AWK" "$full"
}

collect_shots() {
  # takeScreenshot output lands in a layout that depends on the Maestro version (a timestamped run
  # folder in 2.x, the working directory in 1.x). Move every one of them to shots/<name>.png.
  local dir=$1 file
  mkdir -p "$dir/shots"
  while IFS= read -r -d '' file; do
    mv -f "$file" "$dir/shots/$(basename "$file")"
  done < <({
    find "$dir" -type f -name '*.png' -path '*/takeScreenshot/*' -print0
    find "$dir" -maxdepth 1 -type f -name '*.png' ! -name final.png -print0
  })
  rmdir "$dir/shots" 2>/dev/null
  return 0
}

shrink_pngs() {
  # Full-size screenshots of a 1080x2400 screen are too heavy to publish on every run: 600 px wide.
  local dir=$1 file tool=convert
  command -v convert >/dev/null 2>&1 || tool=magick
  if ! command -v "$tool" >/dev/null 2>&1; then
    log "ImageMagick is missing: screenshots stay full size"
    return 0
  fi
  while IFS= read -r -d '' file; do
    if "$tool" "$file" -resize '600x>' "$file.small.png" </dev/null 2>/dev/null; then
      mv -f "$file.small.png" "$file"
    else
      rm -f "$file.small.png"
    fi
  done < <(find "$dir" -type f -name '*.png' -print0)
}

# ---------------------------------------------------------------------------------------------
# Flows
# ---------------------------------------------------------------------------------------------
list_flows() {
  local file
  for file in .maestro/*.yaml; do
    [ -f "$file" ] && [ "$(basename "$file")" != config.yaml ] && printf '%s\n' "$file"
  done
  for file in .maestro/_scratch/*.yaml; do
    [ -f "$file" ] && printf '%s\n' "$file"
  done
  return 0
}

flow_name() {
  case $1 in
    .maestro/_scratch/*) echo "scratch-$(basename "$1" .yaml)" ;;
    *) basename "$1" .yaml ;;
  esac
}

# One attempt: sets ATTEMPT_CODE, ATTEMPT_SECS and ATTEMPT_HITS and leaves the evidence in $dir.
prepare_player_fixture() {
  local orientation=${1:-LANDSCAPE}
  local fixture="$ROOT/app/src/debug/assets/SubLearn-UI-Fixture.mp4"
  local path="/sdcard/Android/data/$PKG/files/ui-fixtures"
  local uri="content://$PKG.fileprovider/ui-fixtures/SubLearn-UI-Fixture.mp4"
  if [ ! -s "$fixture" ]; then
    log "player fixture is missing: $fixture"
    return 1
  fi
  adb shell mkdir -p "$path" </dev/null || return 1
  adb push "$fixture" "$path/SubLearn-UI-Fixture.mp4" </dev/null || return 1
  adb shell am start -W -n "$PKG/com.sublearn.app.MainActivity" \
    -a android.intent.action.VIEW -t video/mp4 -d "$uri" \
    --grant-read-uri-permission --es com.sublearn.app.debug.extra.orientation "$orientation" </dev/null
}

attempt_flow() {
  local file=$1 dir=$2 limit=$3 started hits flow
  mkdir -p "$dir"
  quiesce_app
  clear_system_dialogs
  adb logcat -b all -c </dev/null >/dev/null 2>&1 || adb logcat -c </dev/null >/dev/null 2>&1
  flow=$(basename "$file")
  case "$flow" in
    *player-portrait*) prepare_player_fixture PORTRAIT ;;
    *player*) prepare_player_fixture LANDSCAPE ;;
  esac
  started=$(date +%s)
  timeout --kill-after=20 "$limit" "${MAESTRO[@]}" test "$file" --test-output-dir "$dir" </dev/null 2>&1 |
    tee "$dir/maestro-console.txt"
  ATTEMPT_CODE=${PIPESTATUS[0]}
  ATTEMPT_SECS=$(($(date +%s) - started))

  # Screen first, logcat last: the log then covers everything up to the moment we looked.
  capture_screen "$dir"
  hits=$(capture_logcat "$dir")
  ATTEMPT_HITS=${hits:-0}
  collect_shots "$dir"
  shrink_pngs "$dir"
}

run_flow() {
  local file=$1 name dir code secs hits note=- result limit left
  name=$(flow_name "$file")
  dir=$OUT/maestro/$name
  mkdir -p "$dir"

  left=$((BUDGET - ($(date +%s) - START_EPOCH)))
  if [ "$left" -le 30 ]; then
    log "flow $name: NOT RUN, the ${BUDGET}s budget is used up"
    printf '%s\tFAIL\t%s\t%s\t%s\t%s\n' "$name" "not-run" 0 0 "the ${BUDGET}s time budget was used up before it started" >>"$OUT/results.tsv"
    echo "FAIL (not run: the ${BUDGET}s time budget was used up before it started)" >"$dir/result.txt"
    return 0
  fi
  limit=$FLOW_TIMEOUT
  [ "$limit" -gt "$left" ] && limit=$left

  log "flow $name: start ($file, at most ${limit}s)"
  attempt_flow "$file" "$dir" "$limit"
  code=$ATTEMPT_CODE secs=$ATTEMPT_SECS hits=$ATTEMPT_HITS

  # A launch race says nothing about the app: retry once, keep the first attempt's evidence next to the
  # second's. A crash is never retried away, and any other failure is a real failure.
  left=$((BUDGET - ($(date +%s) - START_EPOCH)))
  if [ "$code" -ne 0 ] && [ "$hits" -eq 0 ] && [ "$left" -gt 90 ] && launch_race "$dir"; then
    log "flow $name: the app never started (launch race, see quiesce_app); retrying once"
    mv "$dir" "$dir.first" && mkdir -p "$dir" && mv "$dir.first" "$dir/attempt-1"
    [ "$limit" -gt "$left" ] && limit=$left
    attempt_flow "$file" "$dir" "$limit"
    code=$ATTEMPT_CODE secs=$((secs + ATTEMPT_SECS)) hits=$ATTEMPT_HITS
    note="second attempt: the first one never started the app (launch race); its evidence is in attempt-1/"
  fi

  result=PASS
  [ "$code" -eq 0 ] || result=FAIL
  {
    echo "result:  $result"
    echo "exit:    $code"
    echo "seconds: $secs"
    echo "app crash matches: $hits"
    echo "app pids: $(cat "$dir/pids.txt" 2>/dev/null)"
    [ "$note" = - ] || echo "note:    $note"
  } >"$dir/result.txt"
  printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$name" "$result" "$code" "$secs" "$hits" "$note" >>"$OUT/results.tsv"
  log "flow $name: $result (exit $code, ${secs}s, $hits app crash match(es))"
}

# ---------------------------------------------------------------------------------------------
# STATUS.md: the one file to read first. Rebuilt from the files on disk and the step outcomes, so
# it is right even when the run died half way.
# ---------------------------------------------------------------------------------------------
write_status() {
  local status=$OUT/STATUS.md total=0 passed=0 failed=0 crashes=0 name result code secs hits crash_word build_line emu_line dialog line
  mkdir -p "$OUT"
  if [ -s "$OUT/results.tsv" ]; then
    while IFS=$'\t' read -r name result code secs hits _; do
      total=$((total + 1))
      if [ "$result" = PASS ]; then passed=$((passed + 1)); else failed=$((failed + 1)); fi
      crashes=$((crashes + ${hits:-0}))
    done <"$OUT/results.tsv"
  fi

  if [ "$total" -eq 0 ]; then
    crash_word="unknown (no flow ran)"
  elif [ "$crashes" -gt 0 ]; then
    crash_word=yes
  else
    crash_word=no
  fi
  case ${BUILD_OUTCOME:-} in
    success) build_line=success ;;
    "") build_line="unknown (not reported)" ;;
    skipped) build_line="skipped (an earlier step failed)" ;;
    *) build_line="FAILED (step outcome: $BUILD_OUTCOME)" ;;
  esac
  case ${EMULATOR_OUTCOME:-} in
    success) emu_line=success ;;
    "") emu_line="unknown (not reported)" ;;
    skipped) emu_line="skipped (an earlier step failed)" ;;
    *) emu_line="FAILED (step outcome: $EMULATOR_OUTCOME)" ;;
  esac

  {
    echo "# UI snapshot"
    echo
    echo "branch: $BRANCH"
    echo "sha: $SHA"
    echo "time: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "run: $RUN_URL"
    echo "build: $build_line"
    echo "emulator step: $emu_line"
    echo "app crash: $crash_word"
    echo "flows: $total run, $passed PASS, $failed FAIL"
    [ -s "$OUT/device.txt" ] && { echo; echo "## Device"; echo; sed 's/^/    /' "$OUT/device.txt"; }
    [ -s "$OUT/maestro-version.txt" ] && echo "maestro: $(cat "$OUT/maestro-version.txt")"
    if [ -s "$OUT/fatal.txt" ]; then
      echo
      echo "## The run was aborted"
      echo
      sed 's/^/    /' "$OUT/fatal.txt"
    fi

    echo
    echo "## Flows"
    echo
    if [ "$total" -eq 0 ]; then
      echo "none ran"
    else
      while IFS=$'\t' read -r name result code secs hits note; do
        line="- $result $name (${secs}s, exit $code, $hits app crash match(es))"
        if [ -n "$note" ] && [ "$note" != - ]; then line="$line; $note"; fi
        echo "$line"
      done <"$OUT/results.tsv"
    fi

    echo
    echo "## Screenshots: open these (paths are relative to the root of this branch)"
    echo
    if [ "$total" -eq 0 ]; then
      echo "none"
    else
      while IFS=$'\t' read -r name _; do
        for png in "$OUT/maestro/$name/shots/"*.png "$OUT/maestro/$name/final.png"; do
          [ -f "$png" ] && echo "- ${png#"$OUT"/}"
        done
      done <"$OUT/results.tsv"
    fi

    if [ "$failed" -gt 0 ]; then
      echo
      echo "## Failed flows: the end of the Maestro console"
      while IFS=$'\t' read -r name result code secs hits _; do
        [ "$result" = PASS ] && continue
        echo
        echo "### $name"
        echo
        dialog=$(grep -aiE "isn't responding|keeps stopping|has stopped" "$OUT/maestro/$name/hierarchy-text.txt" 2>/dev/null | head -n 1 | cut -f2,3 | tr -d '\t')
        if [ -n "$dialog" ]; then
          echo "A system dialog is on screen at the end of this flow: \"$dialog\". It covers the app, so every"
          echo "selector fails behind it. That is emulator noise, not an app crash, unless it names $PKG."
          echo
        fi
        if [ -s "$OUT/maestro/$name/maestro-console.txt" ]; then
          tail -n 25 "$OUT/maestro/$name/maestro-console.txt" | sed 's/^/    /'
        else
          echo "    (no console output: $result)"
        fi
      done <"$OUT/results.tsv"
    fi

    echo
    echo "## App crash evidence"
    echo
    if [ "$total" -eq 0 ]; then
      echo "not scanned: no flow ran."
    elif [ "$crashes" -eq 0 ]; then
      echo "none: no FATAL EXCEPTION, ANR, process death, native crash or am_crash/am_anr event for $PKG."
    else
      echo "Matching lines, first 60 per flow; the whole windows are in maestro/<flow>/app-crash.txt."
      while IFS=$'\t' read -r name result code secs hits _; do
        [ "${hits:-0}" -gt 0 ] || continue
        echo
        echo "### $name"
        echo
        head -n 60 "$OUT/maestro/$name/app-crash.txt" 2>/dev/null | sed 's/^/    /'
      done <"$OUT/results.tsv"
    fi

    if [ "${BUILD_OUTCOME:-success}" != success ]; then
      echo
      echo "## The build failed: errors and the end of out/build.log"
      echo
      if [ -s "$OUT/build.log" ]; then
        grep -nE '^e: |error:|FAILURE:|What went wrong|FAILED' "$OUT/build.log" | head -n 40 | sed 's/^/    /'
        echo
        echo "    ---- last 80 lines ----"
        tail -n 80 "$OUT/build.log" | sed 's/^/    /'
      else
        echo "    (no build.log was written)"
      fi
    fi
  } >"$status"
}

# ---------------------------------------------------------------------------------------------
# Modes
# ---------------------------------------------------------------------------------------------
mode_run() {
  local file count=0 any_bad=0 name result
  mkdir -p "$OUT/maestro"
  : >"$OUT/results.tsv"
  rm -f "$OUT/fatal.txt"
  trap write_status EXIT

  export PATH="$PATH:${ANDROID_HOME:-/usr/local/lib/android/sdk}/platform-tools"
  timeout 180 adb wait-for-device </dev/null
  hide_system_dialogs
  adb devices -l </dev/null >"$OUT/adb-devices.txt" 2>&1
  adb logcat -b all -G 16M </dev/null >/dev/null 2>&1 || adb logcat -G 16M </dev/null >/dev/null 2>&1

  if install_apk && install_maestro; then
    while IFS= read -r file; do
      count=$((count + 1))
      run_flow "$file"
    done < <(list_flows)
    [ "$count" -gt 0 ] || fatal "no flow found in .maestro/*.yaml or .maestro/_scratch/*.yaml"
  fi

  describe_device
  du -sh "$OUT" 2>/dev/null | sed 's/^/out size: /' | tee -a "$OUT/run.log" >&2
  write_status
  trap - EXIT

  if [ -s "$OUT/fatal.txt" ]; then any_bad=1; fi
  while IFS=$'\t' read -r name result _; do
    [ "$result" = PASS ] || any_bad=1
  done <"$OUT/results.tsv"
  grep -qx 'app crash: yes' "$OUT/STATUS.md" && any_bad=1
  [ "$any_bad" -eq 0 ]
}

mode_publish() {
  local remote tmp
  mkdir -p "$OUT"
  write_status
  {
    echo "Generated by .github/workflows/ui-snapshot.yml and scripts/ui-snapshot/run.sh."
    echo "Force-pushed as one orphan commit on every run; never edit it, never merge it."
    echo "Read STATUS.md first and check that its sha is the commit you pushed."
    echo "How to use it: docs/UI_VERIFICATION.md on the main branch."
  } >"$OUT/README.txt"
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then cat "$OUT/STATUS.md" >>"$GITHUB_STEP_SUMMARY"; fi

  if [ -n "${UI_SNAPSHOT_REMOTE:-}" ]; then
    remote=$UI_SNAPSHOT_REMOTE
  elif [ -n "${GITHUB_TOKEN:-}" ] && [ -n "${GITHUB_REPOSITORY:-}" ]; then
    remote="https://x-access-token:${GITHUB_TOKEN}@github.com/${GITHUB_REPOSITORY}.git"
  else
    log "refusing to publish: GITHUB_TOKEN and GITHUB_REPOSITORY are not both set"
    return 1
  fi

  tmp=$(mktemp -d)
  cp -a "$OUT/." "$tmp/"
  rm -rf "$tmp/.git"
  (
    cd "$tmp" || exit 1
    git init -q
    git symbolic-ref HEAD "refs/heads/$SNAPSHOT_BRANCH"
    git config user.name "ui-snapshot"
    git config user.email "ui-snapshot@users.noreply.github.com"
    git add -A
    git commit -q -m "UI snapshot of $BRANCH at ${SHA:0:12}" -m "$RUN_URL"
    git push --force "$remote" "HEAD:refs/heads/$SNAPSHOT_BRANCH"
  )
  local code=$?
  rm -rf "$tmp"
  if [ "$code" -eq 0 ]; then
    log "published $SNAPSHOT_BRANCH for $BRANCH at $SHA"
  else
    log "PUBLISH FAILED (exit $code): the results of this run are NOT on $SNAPSHOT_BRANCH"
  fi
  return "$code"
}

mode_verdict() {
  local problems=() name result code expected ran
  mkdir -p "$OUT"
  [ -s "$OUT/STATUS.md" ] || write_status

  [ "${BUILD_OUTCOME:-}" = success ] || problems+=("the build did not succeed (step outcome: ${BUILD_OUTCOME:-unknown})")
  [ "${EMULATOR_OUTCOME:-}" = success ] || problems+=("the emulator step did not succeed (step outcome: ${EMULATOR_OUTCOME:-unknown})")
  if [ -s "$OUT/fatal.txt" ]; then problems+=("the run was aborted: $(head -n 1 "$OUT/fatal.txt")"); fi

  ran=0
  if [ -s "$OUT/results.tsv" ]; then
    while IFS=$'\t' read -r name result code _; do
      ran=$((ran + 1))
      [ "$result" = PASS ] || problems+=("flow $name: $result (exit $code)")
    done <"$OUT/results.tsv"
  fi
  expected=$(list_flows | wc -l)
  [ "$ran" -gt 0 ] || problems+=("no flow produced a result")
  [ "$ran" -ge "$expected" ] || problems+=("only $ran of $expected flows produced a result")
  if grep -qx 'app crash: yes' "$OUT/STATUS.md"; then problems+=("STATUS.md reports an app crash"); fi

  # Annotations are the one channel a sandbox without log access can still read (check-run annotations).
  echo "::notice title=UI snapshot::$(grep -m1 '^sha: ' "$OUT/STATUS.md") | $(grep -m1 '^app crash: ' "$OUT/STATUS.md") | $(grep -m1 '^flows: ' "$OUT/STATUS.md")"
  if [ "${#problems[@]}" -gt 0 ]; then
    for name in "${problems[@]}"; do
      echo "::error title=UI snapshot is not clean::$name"
    done
    log "VERDICT: NOT CLEAN (${#problems[@]} problem(s))"
    return 1
  fi
  log "VERDICT: clean: $ran flow(s) passed, no app crash"
}

mode_install_maestro() {
  mkdir -p "$OUT"
  install_maestro
}

case ${1:-run} in
  run) mode_run ;;
  install-maestro) mode_install_maestro ;;
  publish) mode_publish ;;
  verdict) mode_verdict ;;
  *)
    echo "usage: $0 [run|install-maestro|publish|verdict]" >&2
    exit 2
    ;;
esac
