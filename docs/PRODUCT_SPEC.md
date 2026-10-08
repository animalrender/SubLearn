# Product specification

The English, ID-numbered copy of the requirements the project was commissioned with, kept verbatim in
meaning so that `docs/CHECKLIST.md` can point at an ID for every claim of completion. Scope words:
**NOW** = real, implemented, tested and polished in the shipped paths. **LATER** = designed for (module,
interface, stub, flag, disabled entry) but not implemented.

## General

- **GEN-1** Best-in-class, modern, animated UI/UX; the design direction is chosen and justified
  ([DESIGN_SYSTEM.md](DESIGN_SYSTEM.md): quiet chrome, loud content).
- **GEN-2** Everything customizable: whenever a feature has several options, expose them as settings.
- **GEN-3** Font style (family, size, color, weight…) configurable separately per language role
  (learning / native) **and** per surface (app menus, learning subtitles, translation subtitles,
  translation popups, word cards, AI answers…). Settings never bleed between surfaces.
- **GEN-4** RTL correctness for Persian everywhere (direction per text run, not only per app).
- **GEN-5** Android best practices (permissions only when needed, SAF/scoped storage, lifecycle, PiP,
  rotation, process death).
- **GEN-6** Modular and extensible; a new feature must not break old ones.
- **GEN-7** NOW features work fully offline (translation after the on-device model is downloaded).

## App entry

- Opened with a video → straight into the player. Opened with a PDF → learning section (LATER).
- Opened normally → modern tabbed navigation: Home, YouTube, Learn, Dictionary + side menu (Level,
  My Words, Quiz, Check for updates, Settings). Home = player entry + recent local videos.
- YouTube and Dictionary are LATER: a flag, a disabled "coming soon" entry, an interface.

## Player (MX-Player-like; local files and URL/streams)

- **PLY-1** Top bar without background: back + file title (left), audio, subtitle, decoder SW/HW/HW+,
  More (…) with MX-like items including PiP. Quick Actions column under the top bar, top-left.
- **PLY-2** All controls overlay the video, auto-hide after 3 s idle, single tap shows them.
- **PLY-3** Center: play/pause + repeat-current-subtitle-block. Bottom: seekbar (buffer indicator,
  elapsed left / total right); right under it previous / play-pause / next (subtitle blocks). Corners:
  lock; Subtitle List View, playlist, aspect ratio and other MX-like modes.
- **PLY-4** Gestures: left vertical swipe = brightness; right vertical = volume; horizontal = seek;
  double tap anywhere except buttons/subtitle text = pause (setting can switch it to seek); two-finger
  swipe up = playback speed shortcut. All remappable.
- **PLY-5** Landscape/portrait + rotation lock.
- **PLY-6** Subtitle List View (toggle in options): landscape = right-side panel (like YouTube
  comments); portrait = below the player; current-line highlight, auto-scroll, tap-to-seek, search.
  Long-press on its toggle = no-spoiler mode (upcoming blocks hidden).
- **PLY-7** Two independent subtitle layers: Learning (primary) and Translation (secondary). Subtitle
  options have one tab per layer; each layer accepts multiple tracks (SRT, embedded soft, external)
  with MX-like options per layer.

## Subtitle controls

- **SUB-1** Two toggle buttons (learning, translation), in Quick Actions by default: tap = toggle
  visibility; hold = temporarily invert until release. Size, transparency and position configurable per
  button.
- **SUB-2** Buttons are dockable: in a bar, free-floating, or disabled/hidden.
- **SUB-3** Quick action "Layout mode": adjust height/position of each layer separately. Subtitle text
  has no gestures except translation, so adjustment never conflicts.
- **SUB-4** Tap on learning subtitle: 1 tap = translate word, 2 = line, 3 = block. While a translation
  shows, playback pauses; tapping elsewhere dismisses it and resumes.
- **SUB-5** Word styling: color/style words by (a) part of speech, (b) My Words, (c) phrasal
  verbs/collocations; each with its own style (underline, dotted underline, box, background, bold,
  color…). Styles and settings are NOW; POS/phrasal detection is LATER behind `WordAnalyzer`.
- **SUB-6** Subtitle management quick action: batch-remove line breaks inside blocks; max characters
  per block (split). LATER: AI re-segmentation by word timing preferring cuts at punctuation; AI marking
  of quotes.
- **SUB-7** Handle known problems: cues split mid-sentence across blocks, stray spaces/newlines,
  punctuation, desync between layers, position mismatch.

## Shadowing

- **SHD-1** Repeat button: tap = repeat the current block once; press-and-hold then release =
  auto-repeat mode.
- **SHD-2** Configurable repeat count and pause between repeats (formula using the block duration as a
  variable, with an adjustable multiplier).
- **SHD-3** "Stop at end of block" toggle: like play/pause but auto-pauses when the block ends; hold to
  flip temporarily.

## Learning and dictionary

- **LRN-1** Entertainment mode: tap word → simple translation; if part of a phrase, phrase/contextual
  translation under it; multi-word selection; bookmark; an icon opens a full-details overlay (offline
  dictionary when available, otherwise a Google Translate window; the default is a setting). Card style
  inspired by proudvocab.
- **LRN-2** Learning mode: everything above, plus words/phrases of the current block above the user's
  level appear with translations as low-opacity, soft-shadow popup cards in a corner, floating up from
  the bottom and fading out. Animated and pleasant. Level data comes from a `WordLevelProvider`: NOW
  ships a manual-level setting and a default provider using a permissively licensed frequency/CEFR list
  **if its license can be verified**; otherwise popups are driven by "words not marked as known".
  Automatic level detection stays LATER.
- **LRN-3** English only: phrasal verbs, collocations, idioms, word level via offline lightweight NLP —
  LATER (`WordAnalyzer` stub).

## AI button

- **AI-1** For hard blocks (idioms, story/film context). Prompt editable in Settings; API key in
  Settings; providers Gemini (default), ChatGPT, Claude.
- **AI-2** Long-press opens the prompt editor screen. Sends the selected text if any, otherwise the
  whole block. Player pauses until the result shows (user may resume); the button shows a circular
  loading ring.
- **AI-3** Answer covers: tone, why it is used here, how it differs from synonyms, where else it is
  used.
- **AI-4** Context sent: previous N blocks (default 10) + film title + timestamps (configurable).

## Scope boundary

NOW = everything above, fully implemented, real, tested, polished (UI and logic), with no demo data in
shipped paths.

LATER (do not implement; module + interface + `NotImplemented` stub + feature flag + disabled
"Coming soon" entry + a section in [EXTENSION_POINTS.md](EXTENSION_POINTS.md)): YouTube section;
PDF/browser/image learning; Dictionary section and offline dictionary import; automatic level detection;
quiz; update checker; AI re-segmentation of subtitles and AI quote marking; offline speech-to-text
subtitle generation; POS/phrasal/collocation/idiom/CEFR detection (offline NLP); on-device AI models;
additional languages.

After all NOW work is done, green and merged, LATER items may be promoted as stretch work **one at a
time, in this order, never breaking NOW**: (1) offline dictionary import + lookup, (2) My Words quiz,
(3) update checker via GitHub Releases. Stop after (3).
