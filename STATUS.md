# UI snapshot

branch: arena/905d3128-sublearn
sha: d30d15732ca0ee4bf545c36b753e3044c183e619
time: 2026-10-24T13:32:49Z
run: https://github.com/animalrender/SubLearn/actions/runs/40097758443 (attempt 1)
build: success
emulator step: success
app crash: no
flows: 8 run, 8 PASS, 0 FAIL

## Device

    model:    sdk_gphone64_x86_64
    android:  14 (sdk 34)
    abi:      x86_64
    screen:   Physical size: 1080x2400, Physical density: 420
    app:      versionName=3.0.0-debug
    load:     9.12 6.96 3.74 3/2809 16264 (1, 5, 15 minutes; running/total; last pid)
    runner:   Ubuntu 24.04.5 LTS, image ubuntu24 20261023.334.3, 4 cpus
maestro: 2.11.0

## Flows

- PASS 01-home (18s, exit 0, 0 app crash match(es))
- PASS 02-settings (40s, exit 0, 0 app crash match(es))
- PASS 03-my-words (15s, exit 0, 0 app crash match(es))
- PASS 04-learn (13s, exit 0, 0 app crash match(es))
- PASS 05-dictionary (13s, exit 0, 0 app crash match(es))
- PASS 06-player-landscape (63s, exit 0, 0 app crash match(es))
- PASS 07-player-portrait (48s, exit 0, 0 app crash match(es))
- PASS 08-player-menus (103s, exit 0, 0 app crash match(es))

## Screenshots: open these (paths are relative to the root of this branch)

- maestro/01-home/shots/01-home.png
- maestro/01-home/final.png
- maestro/02-settings/shots/02-settings-downloads-bottom.png
- maestro/02-settings/shots/02-settings-downloads.png
- maestro/02-settings/shots/02-settings-root.png
- maestro/02-settings/shots/02-settings-search.png
- maestro/02-settings/final.png
- maestro/03-my-words/shots/03-my-words.png
- maestro/03-my-words/final.png
- maestro/04-learn/shots/04-learn.png
- maestro/04-learn/final.png
- maestro/05-dictionary/shots/05-dictionary.png
- maestro/05-dictionary/final.png
- maestro/06-player-landscape/shots/06-player-landscape-controls.png
- maestro/06-player-landscape/shots/06-player-landscape-embedded-subtitle.png
- maestro/06-player-landscape/shots/06-player-landscape-scrub.png
- maestro/06-player-landscape/final.png
- maestro/07-player-portrait/shots/07-player-portrait-controls.png
- maestro/07-player-portrait/shots/07-player-portrait-layout-mode.png
- maestro/07-player-portrait/shots/07-player-portrait-scrub.png
- maestro/07-player-portrait/final.png
- maestro/08-player-menus/shots/08-player-action-layout-sheet.png
- maestro/08-player-menus/shots/08-player-aspect-ratio-sheet.png
- maestro/08-player-menus/shots/08-player-audio-track-sheet.png
- maestro/08-player-menus/shots/08-player-compact-action-rail.png
- maestro/08-player-menus/shots/08-player-cross-dock-drop.png
- maestro/08-player-menus/shots/08-player-expanded-action-rail.png
- maestro/08-player-menus/shots/08-player-layout-mode.png
- maestro/08-player-menus/shots/08-player-subtitle-options-sheet.png
- maestro/08-player-menus/final.png

## App crash evidence

none: no FATAL EXCEPTION, ANR, process death, native crash or am_crash/am_anr event for com.sublearn.app.debug.
