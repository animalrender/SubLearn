# Design system

SubLearn's look is "quiet chrome, loud content": the video and the subtitles are the interface, and
everything else is a translucent layer that gets out of the way in 160–260 ms. That single sentence
decides most of the tokens below — it is why the player chrome has no solid bars (PLY-1), why the
subtitle backdrop is a rounded scrim rather than a box, and why the learning popups rise and fade
instead of sliding.

Everything visual is a token in `core:designsystem`. A feature must not contain a literal colour,
size, duration or type scale; `tools/check_sources.py` plus review keep that honest.

## Layout and shape — `Tokens.kt` → `Dimens`

| Token | Value | Used for |
| --- | --- | --- |
| `xs / sm / md / lg / xl / xxl / huge` | 4 / 8 / 12 / 16 / 24 / 32 / 48 dp | spacing scale; `lg` is the default screen gutter |
| `radiusSm / radiusMd / radiusLg / radiusPill` | 8 / 14 / 22 / 999 dp | cards use `radiusMd`, subtitle backdrop uses the per-font corner radius, chips use `radiusPill` |
| `touchTarget` | 44 dp | every tappable thing is at least this; the 40 dp icon button sits inside a 44 dp hit box |
| `iconButton / iconButtonLarge` | 40 / 52 dp | chrome and lock affordance |
| `playerEdgeInset` | 12 dp | keeps chrome out of the system gesture areas |
| `topBarHeight / secondActionRailTop / bottomChromeReserve` | 56 / 64 / 128 dp | reserves a stable slot for the top bar, second action rail and floating controls |
| `seekbarTrack / seekbarThumb` | 4 / 14 dp | thin bottom timeline and thumb token |
| `dockGap` | 4 dp | spacing in the one-line action rails |
| `subtitleBottomSafe` | 24 dp | floor under the lowest subtitle layer |
| `listPanelMinWidth` | 260 dp | the subtitle list never squeezes below this in landscape |
| `cardElevation` | 6 dp | one elevation step for floating cards, `tonalElevation` rather than shadow |
| `strokeWidth` | 1.5 dp | outlines on the backdrop and on selected quick actions |

`Palette` holds the raw ARGB constants (scrims, backdrops, dark/light surfaces) — they exist only to
build `ColorScheme` and `SubLearnColors`; a component reads `LocalSubLearnColors.current`, never
`Palette`.

## Player transport and action docks

- The main `TopChrome` hosts actions docked in `DockMode.TOP`; `DockMode.BAR` is a separate, single-line
  horizontally scrollable rail below it; `DockMode.PLAYBACK` joins the bottom transport row. Every rail
  reports an overflow arrow when content continues off-screen; rows never wrap or spill past the viewport.
- The seek timeline and elapsed / total-or-remaining clocks share one row. The row below is the only
  playback-action row; it always has play/pause, previous/next video and previous/next subtitle actions,
  followed by user-arranged controls. The picture centre is reserved for the video and subtitle plates.
- Expanded docked actions include a short label; compact and floating actions are icon-only. Floating
  buttons clamp their stored fractions to keep the full touch target inside the video bounds.
- `ChromeVisibility` uses `Motion.controlsEnter/controlsExit`, so top, bottom and second-row chrome all
  fade consistently and respect the app's reduce-motion preference.

## Motion — `Motion.kt`

| Constant | ms | Meaning |
| --- | --- | --- |
| `MICRO_MS` | 90 | press feedback, toggle colour change |
| `SHORT_MS` | 160 | control fade, list-row highlight |
| `OVERLAY_MS` | 220 | chrome enter/exit (`controlsEnter` / `controlsExit`) |
| `STANDARD_MS` | 260 | sheet content, card appearance |
| `POPUP_RISE_MS` | 260 | learning popup rising into place (`popupEnter`) |
| `SHEET_MS` | 320 | modal sheet |
| `LONG_MS` | 420 | one-shot emphasis (repeat ring, `repeatRing`) |
| `POPUP_FADE_MS` | 520 | a learning popup's visible lifetime before it fades (`popupExit`) |

Named transitions instead of ad-hoc `animateFloatAsState` calls: `popupEnter/popupExit` (LRN-2's float-up
and fade), `controlsEnter/controlsExit` (PLY-2 fade + slight translation), `cardEnter`, and
`gentleSpring` for anything that should settle rather than stop. `repeatRing` is the ring animation on
the repeat button while a block plan runs (SHD-1).

**Reduce motion** is a first-class input: every transition helper takes `reduceMotion: Boolean` and
returns a fade (or nothing) instead of movement, and `LocalReduceMotion` reads the system setting *or*
the in-app override in Settings → Appearance. A screen that animates without going through these
helpers is a bug.

## Colour

`SubLearnTheme(settings)` derives everything from `AppSettings.appearance`:

- **Theme mode** `SYSTEM | LIGHT | DARK | AMOLED`; AMOLED uses pure black surfaces
  (`AMOLED_BACKGROUND = 0xFF000000`) and drops card tonal elevation to keep battery and burn-in risk
  down.
- **Accent** `TEAL | INDIGO | AMBER | ROSE | FOREST | VIOLET | SKY`, each with a separate dark and light
  value (`AccentPair`), because a single accent that reads well on white rarely survives on a video
  frame behind a 40 %-alpha scrim.
- `MaterialTheme.colorScheme` is built from those, and the app-specific roles live in
  **`SubLearnColors`**: `playerScrim` (the gradient under the chrome), `subtitleBackdrop`, `subtitleText` (always light, because the backdrop is always dark), `letterbox`
  (black around the picture in both themes),
  `cardBackdrop`, `strongBackdrop` (chrome over bright scenes), `promptBackdrop` and `promptText`
  (translucent turquoise player prompts with a dark foreground), `overlayOutline`, `controlIdle`,
  `controlActive`, `controlDisabled`, `listCurrentRow`, `listHoverRow`,
  `levelBadgeBackgroundAlpha`.

The rule for new roles: if the value depends on being legible *over arbitrary video*, it goes in
`SubLearnColors`, not into `colorScheme`, because Material roles are not designed for that case.

## Type — `Typography.kt` + `core:settings` fonts

Material type roles are remapped once (`displaySmall` … `labelSmall`) with the line-height and
letter-spacing the Persian fallback needs. On top of that, GEN-3 is served by `FontSpec` in
`core:settings`, per **surface** (`FontSurface`: app menus, subtitle learning, subtitle translation,
translation popup, word card, AI answer, subtitle list, player chrome, learning popup) and per **role**
(`SubtitleLayerRole.LEARNING | NATIVE`):

```
family, customFamilyPath, sizeSp, weight, italic, colorArgb, backgroundColorArgb,
backgroundAlpha, cornerRadiusDp, outlineAlpha, decoration, letterSpacingEm, lineHeightEm, maxLines
```

`FontSpec.toTextStyle()` is the only bridge into Compose, so a surface never re-implements font logic.
Settings never bleed: a font change is written to exactly one `(surface, role)` key, and
`FontSpec.defaultFor(surface, role)` supplies the per-surface defaults (bigger line height for
subtitles, none for chrome). `LocalAppFontScale` multiplies everything and is *not* stored per surface,
so the system font-size setting and the in-app scale compose instead of fighting.

## Components in `core:designsystem`

| Component | Contract |
| --- | --- |
| `SubtitleBackdrop(modifier, alpha, colorArgb, cornerRadiusDp, contentPadding*)` | the rounded scrim behind subtitle text and popups; alpha comes from the *layer* setting so transparency is per layer, not global |
| `BadgePill` | CEFR level, format, status chips; background alpha from `levelBadgeBackgroundAlpha` (`BADGE_ALPHA = 0.20f`) |
| `LevelBadge` | level only; takes the CEFR name as stored on a word so no lookup is needed at draw time |
| `Motion`, `Dimens`, `Palette`, `SubLearnColors` | the token objects themselves |

New shared components go here with a KDoc line stating which spec ID they serve. Anything that needs
`AppSettings` receives the value, not a repository.

## Passive player hints and continuous tracks (D-79, D-80)

`LearningCardsOverlay` uses LEARNING_POPUP fonts per language role, the subtitle backdrop/text roles,
soft elevation and the configured corner/opacity/rise/fade. Two hints at most occupy a bounded corner;
they are non-interactive and hidden while player controls, modal surfaces, lists or PiP are visible.
`Dimens.learningCard*` caps their size; imported offsets are clamped to the viewport. Compact video
regions display one hint. Measured subtitle plate bounds take priority: hints try alternative corners
and omit the batch when no unobstructed corner exists.

Use `SubLearnLinearProgress` / `SubLearnLinearProgressIndeterminate` for every linear progress bar:
zero gap and no stop dot. Use `SubLearnSlider` for sliders: no endpoint or tick dots, but retain its
thumb, snapping, gestures and accessibility. These APIs exist in the current Material3 BOM.

## Accessibility and i18n checklist for a new screen

1. Every interactive element has a `contentDescription` from `core:designsystem` strings (EN + FA).
2. 44 dp minimum touch target; `iconButtonLarge` for anything reachable while holding a phone.
3. Text scales with `LocalAppFontScale` and the system font size — no fixed `dp` font sizes.
4. Direction comes from content (`TextDirection`), and RTL layouts are mirrored by using `start`/`end`
   rather than `left`/`right` — the CI lint config plus review enforce this.
5. Motion respects `LocalReduceMotion`; nothing flashes faster than 3 Hz.
6. Contrast: chrome text over video sits on `playerScrim`; subtitle text uses `SubtitleBackdrop`; player
   prompts use the turquoise `promptBackdrop` plus dark `promptText` so they remain readable in both themes.
7. Long-press alternatives exist for every hold-to-invert behaviour (SUB-1), announced by
   `tooltip`/`semantics` so it is discoverable without a manual.
