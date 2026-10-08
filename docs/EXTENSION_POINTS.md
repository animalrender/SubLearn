# Extension points (everything marked LATER)

Each item below already has: an interface in a `core:*` module, a `NotImplemented…` stub that throws
`NotImplementedInThisBuild(flag)` (never reached from a shipped path), a `FeatureFlag` entry in
`core/common/FeatureFlags.kt`, a disabled "Coming soon" entry in the UI, and this section. Promoting an
item means: flip the flag **together with** the implementation, delete the stub from the DI graph, and
update `docs/CHECKLIST.md` and `docs/PHASES.md`.

`FeatureFlag.enabled` is a single explicit map, so "everything LATER is off" is auditable in one
screen. `LaterCapabilities.statusFor(flag, overrides)` lets a debug build turn one on without touching
release code.

| Flag `id` | Spec | Interface / stub | Where the disabled entry lives |
| --- | --- | --- | --- |
| `youtube` | app entry | (no interface yet — see below) | Home tab row in `app/…/SubLearnApp.kt` |
| `pdf_learning` | app entry | — | side menu / Learn tab |
| `offline_dictionary` | LRN-1 | `DictionaryProvider` / `NotImplementedDictionaryProvider` | Dictionary tab, popup "full details" icon |
| `level_detection` | LRN-2 | `LevelDetector` / `NotImplementedLevelDetector` | Settings → Learning |
| `quiz` | My Words | `QuizEngine` / `NotImplementedQuizEngine` | side menu, My Words header |
| `update_checker` | side menu | `UpdateChecker` / `NotImplementedUpdateChecker` | Settings → About |
| `ai_resegmentation` | SUB-6 | `AiProvider` exists; operation not modelled | subtitle tools sheet |
| `speech_to_text` | subtitle engine | `SpeechToText` / `NotImplementedSpeechToText` | subtitle tools sheet |
| `pos_analysis` | SUB-5, LRN-3 | `WordAnalyzer` / `NotImplementedWordAnalyzer` | Settings → Word styles |
| `on_device_ai` | AI button | new `AiProvider` implementation | Settings → AI → provider |
| `extra_languages` | defaults | data only | Settings → Language |

## 1. Offline dictionary import + lookup  (first stretch item)

**What exists**: `DictionaryEntry` (headword, meanings, synonyms, antonyms, phrasal verbs,
collocations, idioms, word family, CEFR, categories, US/UK pronunciation), `DictionaryProvider.lookup`
and `isAvailable`, `DictionaryPreference { OFFLINE_FIRST, WEB_TRANSLATE_ONLY, OFFLINE_ONLY }` in
settings, `DictionarySections` (12 labels) for the card layout, and a disabled Dictionary tab.

**Rights rule, non-negotiable**: no dictionary database or model file may ever be committed to this
repository (`.gitignore` and the CI hygiene guard enforce it). The dictionary is a **file the user
picks** on their own device. The reference reconstruction project obtained its schema from another
app's source and its loader from reverse-engineered `.bipe` files, so neither the data nor the loader
format may be reproduced here — the importer below defines *our own* format.

**Implementation plan**

1. Storage: `core/data` — a Room database opened over a file in
   `context.getDatabasePath("dictionary")` that was *copied* from the user's picked document, or a
   read-only `SQLiteDatabase.openDatabase(uri, flags = READONLY)` on a copied cache file (SAF URIs are
   not always openable by SQLite directly; copy to cache with a size guard and report progress).
2. Importer `DictionaryImporter` in `core:lexicon` (or `core:data`) with a documented schema, and a
   `DictionaryProvider` implementation `ImportedDictionaryProvider` that answers `lookup` from it.
3. UI: Settings → Dictionary gains "Import a dictionary file" (already scaffolded through
   `OpenDocumentContract`), showing entry count, language pair, and a "remove import" action.

**Documented import schema (v1, ours)** — a single table, case-insensitive `key`, no foreign keys:

```sql
CREATE TABLE entry (
  id INTEGER PRIMARY KEY,
  key TEXT NOT NULL,            -- headword, lower-case, no trailing punctuation
  headword TEXT NOT NULL,
  pos TEXT,                     -- noun / verb / … (used by the card grouping, optional)
  sense TEXT NOT NULL,          -- one meaning, already human readable
  example TEXT,                 -- optional usage sentence
  synonyms TEXT,                -- ' | ' separated
  antonyms TEXT,                -- ' | ' separated
  phrasal TEXT,                 -- ' | ' separated
  collocations TEXT,            -- ' | ' separated
  idioms TEXT,                  -- ' | ' separated
  word_family TEXT,             -- ' | ' separated
  cefr TEXT,                    -- A1|A2|B1|B2|C1|C2 or NULL
  categories TEXT,              -- ' | ' separated topic labels
  us_ipa TEXT,
  uk_ipa TEXT,
  lang TEXT NOT NULL            -- BCP-47 of the *definition* language
);
CREATE INDEX entry_key ON entry (key);
CREATE VIRTUAL TABLE entry_fts USING fts4(key, synonyms, idioms, content=entry);
```

One row per sense is required (senses group by `key` on read, in `id` order). The importer validates
the header, rejects a file whose `lang` disagrees with `DictionarySettings`, reports the row count and
any skipped rows as warnings, and never mutates the user's file. A CSV with the same column names is an
accepted input format too, because that is what people can actually produce.

**Verification**: unit-test the importer against a fixture *generated by the test* (never a committed
database), assert `lookup("reluctant")` returns one entry with its senses ordered, assert a corrupt file
fails with `SubLearnError.Kind.MalformedInput`, and assert `isAvailable()` is false before an import.

## 2. My Words quiz  (second stretch item)

`QuizEngine` with `interface QuizEngine { suspend fun session(words: List<MarkedWord>, mode: QuizMode): Flow<QuizQuestion> }`
and modes `RECALL_TRANSLATION`, `RECALL_WORD`, `LISTENING` (uses the existing TTS path),
`FILL_THE_GAP` (uses the subtitle block the word was saved from, which `MarkedWord` already carries as
`sourceUri` + `sourceStartMs`). Stub today: `NotImplementedQuizEngine`. Persist results in a new
`quiz_result` table in `core:data`; `WordStatus` transitions reuse the same field the My Words screen
already writes, so a quiz never forks the word state.

## 3. Update checker  (third stretch item)

`UpdateChecker` returns `AppResult<UpdateInfo(versionName, notesUrl, apkUrl, releasedAt)>`. Implementation
must call the GitHub Releases REST API only (`/repos/{owner}/{repo}/releases/latest`), compare
`BuildConfig.VERSION_NAME`, and be off by default with a Settings switch — no background polling, no
install-permission prompt unless the user taps install. Never use an undocumented endpoint here; that is
the whole point of the interface. Stub today: `NotImplementedUpdateChecker`.

## 4. YouTube

Deliberately has **no** interface yet: it is not a capability, it is a media source plus a caption
source plus a policy problem. When it lands, it must arrive as (a) a `PlayerController` target type that
Media3 can play through an `HttpDataSource`, and (b) one class that owns caption retrieval, isolated the
way `dual-sub-replay` isolated it, because it relies on an undocumented endpoint and can break without
notice. Keep it out of `core:subtitles`: parsing stays pure, retrieval is a network concern. Until then
`FeatureFlag.YOUTUBE` gates a disabled tab.

## 5. PDF / browser / image learning

Entry point already accepted by the manifest design (`FEATURE_OPEN` with a PDF mime is routed to the
Learn tab instead of the player). Needs: a text extraction path (`PdfRenderer` is in the framework and
sufficient for text PDFs; images need an OCR model, which is a separate licensing and size decision), a
`ReadingRepository` (Room) for passages, and reuse of the same popup pipeline as subtitles: tap a word →
`TranslationProvider` → `MyWordsRepository`. Do not fork the popup UI: `feature:learn` should render the
identical card.

## 6. Level detection and word analysis

`LevelDetector` (automatic CEFR from reading history) and `WordAnalyzer` (POS, phrasal verbs,
collocations, idioms, lemma) both stay in `core:lexicon`. Anything they produce feeds the **existing**
inputs: `WordLevelSource.replaceImported(...)` for levels and `WordVisualState` for styling, so a real
implementation needs no UI change — `WordVisualState.PHRASE` already exists and is simply never produced
today. Keep any NLP model off the APK: an on-device model is a user-supplied file, like the dictionary.

## 7. AI re-segmentation, quote marking, speech-to-text

`AiProvider` already supports a prompt plus block context plus attachments, so re-segmentation is a new
operation on top of it (`SubtitleRepository.applyCandidateBlocks(document, blocks)`), not a new
transport. It must be non-destructive: produce a candidate block list the user accepts or discards, keep
the original file untouched, and reuse `NormalizerConfig` for validation. `SpeechToText` needs its own
interface (already present) because it will wrap a downloaded model, and it feeds the same `Cue` list
the parsers produce — that is why `SubtitleDocument` accepts cues from any producer.

## 8. Additional languages

Data-only; follow the numbered steps in
[ARCHITECTURE.md](ARCHITECTURE.md#adding-a-language). The two things to check rather than assume: the
language must be one ML Kit's on-device translation supports, and its script must be legible in the
device font at the sizes `FontSpec.defaultFor(surface, role)` picks — otherwise ship a font *instruction*
in the README, not a bundled binary whose licence nobody verified.
