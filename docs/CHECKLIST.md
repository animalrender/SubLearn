# Requirement checklist

Status codes: **real** = implemented on the shipped path; **stub** = deliberate LATER stub with flag,
disabled entry and a section in [EXTENSION_POINTS.md](EXTENSION_POINTS.md); **device-pending** = code
complete, needs the on-device pass (phase 9) because there is no emulator or JVM in the agent sandbox;
**ci-pending** = also waiting for the first green compile of this branch; **partial** = part of the
requirement is on the shipped path and the gap has a KNOWN_ISSUES entry.

| ID | Requirement | Phase | Where | Status | How it was verified / to verify |
| --- | --- | --- | --- | --- | --- |
| GEN-1 | Modern, animated UI/UX | 0, 9 | `core/designsystem` (`Motion`, `Tokens`), every `feature:*` screen | real, device-pending | tokens only, no literals; durations named in DESIGN_SYSTEM.md; visual pass on device in phase 9 |
| GEN-2 | Every option exposed as a setting | 1 | `core/settings/AppSettings.kt`, `feature/settings` sections | real | read a feature, find its knob; the settings tree has a section per group and a search box |
| GEN-3 | Fonts per language role **and** per surface | 1 | `core/settings/FontSettings.kt` (`FontSurface` × `SubtitleLayerRole`), `feature/settings/MoreSections.kt` | real | change one surface and confirm no other surface moves; `FontSpec.defaultFor` test |
| GEN-4 | RTL correctness per text run | 1, 9 | `feature/player/SubtitleOverlay.kt`, popups, `values-fa` strings | real, device-pending | 417 EN + 417 FA keys in sync; hit testing uses real `TextLayoutResult`; instrumented test `PlayerPopupsSmokeTest` |
| GEN-5 | Android best practices | 0-2 | SAF everywhere, `data_extraction_rules.xml`, `locales_config.xml`, PiP, process death | real, device-pending | no storage permissions in the manifest; rotation/PiP/restart checks on device |
| GEN-6 | Modular, extensible | 0 | 16 modules, `tools/check_deps.py` | real | a feature cannot see another feature (dependency graph is checked by the tool) |
| GEN-7 | NOW features work offline | 0-8 | `core/translate` (ML Kit), Room, no network in any other path | real | airplane-mode run: play, subtitles, translate after model download, My Words, shadowing, list |
| PLY-1 | Top bar without background, quick actions top-left | 2, 3 | `feature/player/Controls.kt`, `QuickActionBar` | real | chrome has no surface colour; column placement in `QuickActions.kt` defaults |
| PLY-2 | Overlay controls, auto-hide 3 s, tap shows | 2 | `PlayerViewModel.showControls/scheduleControlsHide`, `PlayerSettings.controlsAutoHideMs` (default 3000) | real | `controlsAutoHideMs` unit-verified by reading the setting; behaviour on device |
| PLY-3 | Center play/repeat, seekbar, previous/next by block, corners | 2, 3 | `Controls.kt` (`TopBar`, `BottomControls`, `SeekRow`, `LayoutModeBanner`) | real | block stepping calls `previousBlock/nextBlock` from `SubtitleDocument` |
| PLY-4 | Gestures, all remappable | 2 | `feature/player/Gestures.kt`, `GestureSettings.actionFor/withAction/reset`, settings gesture editor | real | remap volume→seek in settings and confirm; two-finger speed = `PLAYBACK_SPEED` |
| PLY-5 | Orientation + rotation lock | 2 | `PlayerIntent.Orientation`, `OrientationLock`, `app/…/MainActivity.kt` | real, device-pending | sensor values on device; lock icon in chrome |
| PLY-6 | Subtitle List View, panel side by orientation, no-spoiler | 3 | `Sheets.kt` `SubtitleListPanel`, `SubtitleSettings.noSpoilerMode` | real | search filter is `SubtitleListUi.matches` (unit-testable); panel placement on device |
| PLY-7 | Two layers, tab per layer, multiple tracks per layer | 3 | `SubtitleLayerSettings` (embedded + external keys), `Sheets.kt` `LayerOptionsSheet` | partial | two layers with a tab each are real; a layer holds one file or the embedded track, a second file replaces the first (KNOWN_ISSUES 17) |
| SUB-1 | Two toggle buttons, tap toggle / hold invert, per-button size/position | 3 | `QuickActionBar`, `QuickActionSpec {sizeDp, xFraction, yFraction, transparency}`, `onQuickAction(id, inverted)` | real | hold a button: the state inverts until release (`previewInvert`) |
| SUB-2 | Dockable buttons: bar / floating / hidden | 3 | `DockMode {BAR, FLOATING, HIDDEN}`, `QuickActionsSettings` | real | floating drag persists fractions; hidden removes the row |
| SUB-3 | Layout mode adjusts each layer separately | 3 | `PlayerViewModel.setLayoutMode`, `SubtitlePlacement`, `movedBy` | real | drag one layer while the other stays; placement written per role |
| SUB-4 | 1/2/3 tap → word/line/block, pause while shown, tap elsewhere resumes | 4 | `detectMultiTap`, `onLayerTap`, `pauseIfLineIsAboutToChange`, `dismissPopup` | real | `multiTapWindowMs` setting; `wasPlayingBeforePause` decides resume |
| SUB-5 | Word styling by POS / My Words / phrases | 4, 6 | `styleWords` in `SubtitleOverlay.kt`, `WordStyleSettings`, `WordVisualState` | real for My Words + level; POS is **stub** (`pos_analysis`) | styles render from settings; `WordVisualState.PHRASE` never produced yet (documented) |
| SUB-6 | Batch remove line breaks, max-chars split | 8 | `NormalizerConfig`, subtitle tools sheet, `SubtitleRepository` | real | run both on a loaded file and confirm the file itself is untouched |
| SUB-7 | Mid-sentence splits, stray spaces, punctuation, desync, mismatch | 3, 8 | `SubtitleNormalizer`, `BlockBuilder`, per-layer `delayMs` | real | parser/normalizer unit tests (`SrtParserTest`, `SubtitleNormalizerTest`, `BlockBuilderTest`) |
| SHD-1 | Repeat once, hold for auto-repeat | 5 | `PlayerViewModel.toggleRepeatBlock`, `ShadowPlan` | real | tap vs hold; ring animation `Motion.repeatRing` |
| SHD-2 | Count and pause formula from block duration | 5 | `ShadowingMath` (pure) + `ShadowingSettings {pauseBaseMs, pauseDurationMultiplier, pauseMaxMs, preRollMs, minBlockDurationMs}` | real | `ShadowingMathTest` covers clamping, the multiplier and the pre-roll |
| SHD-3 | Stop at end of block, hold to flip | 5 | `shadowing.stopAtEnd`, `STOP_AT_BLOCK_END` quick action | real | `ui.shadowing.stopAtEnd` drives the seek clamp |
| LRN-1 | Entertainment card: word, phrase context, multi-word, bookmark, full details | 4, 7 | `Popups.kt` `PopupLayer`, `toggleMarkInPopup`, `DictionaryPreference` | real; dictionary branch **stub** (`offline_dictionary`) | web fallback opens the Translate window; full-details icon labelled |
| LRN-2 | Learning popups above level, corner float, fade | 6 | `LaterCapabilities` provider chain, `WordLevelSource`, `LearningSettings` | real (manual level + import), auto-detect **stub** | import a frequency list, watch a block, confirm only above-level words pop |
| LRN-3 | English NLP: phrasal/collocation/idiom | — | `WordAnalyzer` stub | stub | see EXTENSION_POINTS §6 |
| AI-1 | Providers + key + editable prompt | 7 | `core/ai/AiProviders.kt` (gemini/openai/anthropic/custom), `AndroidSecretStore`, prompt editor | real | connection-test button in Settings → AI reports provider errors |
| AI-2 | Long-press opens editor; sends selection or block; pause and resume; loading ring | 7 | `QuickActionBar` AI button, `askAi`, `resumeAfterAnswer`, `AiUi.pausedForAnswer` | real | pause only when requested; ring shows while `AiState.BUSY` |
| AI-3 | Answer covers tone, why here, synonyms, elsewhere | 7 | `AiSettings.DEFAULT_PROMPT`, `AiPromptBuilder`, `AiAnswerParser` sections | real | parser splits the four sections; malformed answers fall back to plain text |
| AI-4 | Context: previous N blocks, title, timestamps | 7 | `AiSettings.contextBlocks/includeTitle/includeTimestamps`, `ContextBlock` | real | request dump in a debug log (redacted) |
| APP entry | video → player, PDF → Learn, tabs + side menu | 1 | `MainActivity` intent filters, `AppNavigator`, `SubLearnApp` | real, device-pending | open from a file manager and from a browser link |
| Settings system | typed, versioned, searchable, JSON in/out | 1 | `AppSettings` (SCHEMA_VERSION 3), `feature/settings/SettingsScreen.kt` | real | `SettingsRepositoryTest` covers export/import/merge/reset |
| LATER set | YouTube, PDF, dictionary, level, quiz, updates, resegment, STT, POS, on-device AI, languages | 0 | `FeatureFlag` + `LaterCapabilities` + stubs | stub | each has a section in EXTENSION_POINTS.md |
