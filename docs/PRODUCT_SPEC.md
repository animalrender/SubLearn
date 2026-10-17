# Product specification

The English, ID-numbered copy of the requirements the project was commissioned with, kept verbatim in
meaning so that `docs/CHECKLIST.md` can point at an ID for every claim of completion. Scope words:
**NOW** = required shipped scope; this label alone does not claim implementation or verification.
**LATER** = designed for (module, interface, stub, flag, disabled entry) but not implemented. Check
`docs/CHECKLIST.md` and `docs/KNOWN_ISSUES.md` for actual implementation and verification status.

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
- Learn is reserved for browser/PDF/image learning (LATER) and shows only "Coming soon".
  Video learning modes, level, popup preferences, accuracy profiles and resource management live in Settings.
- YouTube and Dictionary are LATER: a flag, a disabled "coming soon" entry, an interface.

## Player (MX-Player-like; local files and URL/streams)

- **PLY-1** Top bar without a solid background: back + file title (left), More (…) with MX-like items
  including PiP. Audio, subtitle and decoder actions remain quick actions rather than fixed controls.
  Quick actions can be docked in the top toolbar,
  one horizontally scrollable second toolbar row, the bottom playback row, or floated over/hidden.
  The manager supports reordering and docking; expanded docked rows show labels, compact and floating
  actions do not. Overflow cues reveal more actions without letting a row spill off-screen.
- **PLY-2** Controls overlay the video and auto-hide after 3 s idle; a single tap shows them. Top and
  bottom chrome, including the second toolbar, fade in/out through the reduce-motion-aware motion tokens.
- **PLY-3** Keep the picture centre clear of transport buttons. Bottom: a thin seek line with buffer
  progress and a small thumb; elapsed time at the left, total or remaining time at the right on the same
  line (tap the right clock to toggle). Dragging previews the video frame and subtitles before release.
  One distinct, horizontally scrollable action row below it contains play/pause, previous/next video,
  previous/next subtitle cue and user-arranged playback actions. No transport controls float over the
  centre unless the user explicitly docks/floats an action there. The lock remains at the edge.
- **PLY-4** Gestures: left vertical swipe = brightness; right vertical = volume; horizontal = seek;
  double tap anywhere except buttons/subtitle text = pause (setting can switch it to seek); two-finger
  swipe up = playback speed shortcut. All remappable.
- **PLY-5** Manual portrait/landscape/reverse-landscape orientation and automatic full-sensor rotation.
- **PLY-6** Subtitle List View (toggle in options): landscape = right-side panel (like YouTube
  comments); portrait = below the player; current-line highlight, auto-scroll, tap-to-seek, search.
  Long-press on its toggle = no-spoiler mode (upcoming blocks hidden).
- **PLY-7** Two independent subtitle layers: Learning (primary) and Translation (secondary). Subtitle
  management has a dedicated tab per layer. Every embedded soft-subtitle track appears in both tabs
  with a checkbox, so multiple tracks may be assigned to either layer independently; each tab also
  supports multiple external files, with per-source enable/remove controls and MX-like layer options.
  Audio management stays in its own surface.
- **PLY-8** Recent Videos must reopen through a valid persistent SAF read grant; a stale grant returns
  the user to the picker instead of exposing Media3's opaque `ERROR_CODE_IO_UNSPECIFIED`. The previous/
  next video transport navigates the readable recent-video queue.
- **PLY-9** Audio remains separate from subtitle management: select embedded audio tracks, add/remove an
  external audio file, set player volume, and mute/unmute independently of system volume.
- **PLY-10** Player prompts/status messages, including missing AI/API setup prompts, use the app's
  translucent turquoise surface with high-contrast dark text.

- **PLY-11** Opening the player starts playback immediately. A previously watched file with a valid
  saved position resumes there and offers a small, temporary bottom "Start over" action; ignoring it
  must not pause playback. A first-time file does not show this prompt. Remember-position/resume
  settings and end-of-file handling remain respected.

## Subtitle controls

- **SUB-1** Two toggle buttons (learning, translation), in Quick Actions by default: tap = toggle
  visibility; hold = temporarily invert until release. Size, transparency and position configurable per
  button.
- **SUB-2** Buttons are dockable in the top toolbar, the second toolbar row, or playback row; they may
  also free-float over video or be disabled/hidden. Each docked row is a single horizontal scroll area.
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
- **LRN-2** Player learning mode (not the Learn tab): everything above, plus words/phrases of the current block above the user's
  level appear with translations as low-opacity, soft-shadow popup cards in a corner, floating up from
  the bottom and fading out. Animated and pleasant. Level data comes from a `WordLevelProvider`: NOW
  ships a manual-level setting and a default provider using a frequency/CEFR list the user imports;
  otherwise popups are driven by "words not marked as known".
  Entertainment and Off never show automatic cards; tap lookup remains separate.
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

NOW = the active product requirements above. Completion is only claimed when the corresponding
checklist row and its CI/device verification are green; assigning an item to NOW is not evidence that
it is already implemented or tested.

LATER (do not implement; module + interface + `NotImplemented` stub + feature flag + disabled
"Coming soon" entry + a section in [EXTENSION_POINTS.md](EXTENSION_POINTS.md)): YouTube section;
PDF/browser/image learning; Dictionary section and offline dictionary import; automatic level detection;
quiz; update checker; AI re-segmentation of subtitles and AI quote marking; offline speech-to-text
subtitle generation; POS/phrasal/collocation/idiom/CEFR detection (offline NLP); on-device AI models;
additional languages.

After all NOW work is done, green and merged, LATER items may be promoted as stretch work **one at a
time, in this order, never breaking NOW**: (1) offline dictionary import + lookup, (2) My Words quiz,
(3) update checker via GitHub Releases. Stop after (3).
