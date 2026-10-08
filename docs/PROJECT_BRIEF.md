# Project brief

## One paragraph

SubLearn is a free, open-source Android app for learning English from real video. It is an
MX-Player-class video player first — gestures, PiP, speed, decoder choice, subtitle tracks — with a
learning layer that stays out of the way: two independent subtitle layers you can move and restyle,
tap-to-translate at word/line/block granularity, shadowing tools that repeat a block with a pause
derived from reading time, a personal word list that colours the subtitles you are watching, and an
AI button for the line that will not yield.

## Who it is for

A learner who already watches content in the target language and wants the tools inside the player
instead of in a separate flashcard app. The default configuration assumes an English learner whose
native language is Persian, but nothing in the architecture hard-codes that pair.

## Constraints that shaped the design

- **Offline first.** Everything except the optional AI request works with no network (PLY/GEN-7).
  Translation uses ML Kit's on-device API, which downloads its own models.
- **No monetization, no tracking, no accounts.** Nothing phones home except the provider request the
  user explicitly configures.
- **Real code only.** A control that cannot do something is hidden or labelled, never decorative. This
  is why the decoder list, the embedded-track features and the dictionary entry are written the way
  they are (see [DECISIONS.md](DECISIONS.md)).
- **Clean rights.** GPL reference projects contributed ideas, never code; no third-party dictionary
  database or model file may ever be committed. See
  [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md).

## What "done" means

Every NOW requirement in [PRODUCT_SPEC.md](PRODUCT_SPEC.md) is implemented for real, tested and
verified against [CHECKLIST.md](CHECKLIST.md) on the actual code path; CI is green on `main`; the app
works in light/dark, portrait/landscape, EN and FA (RTL), online and offline; every LATER item has an
interface, a stub, a flag and a doc section; and `v0.1.0` is tagged with a debug APK artifact.
