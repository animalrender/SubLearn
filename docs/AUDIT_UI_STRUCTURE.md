# UI & Architecture Structure Audit (Pre-Phase 18)

This audit documents how Packs, Models, Dictionaries, and Translation Settings were previously
scattered across screens and why Phase 17a/18 consolidates them into a clear, non-overlapping
information architecture (D-58, D-59, D-60).

## 1. Previous UI Structure & Issues Identified

| Screen / Route | Previous Responsibility | UX & Architectural Issue |
| --- | --- | --- |
| `AppRoute.Dictionary` (`feature:dictionary`) | Placeholder "Coming soon" stub gated by `FeatureFlag.OFFLINE_DICTIONARY = false` | Prominent bottom-bar tab did nothing even when a lexicon pack was imported. |
| `AppRoute.Packs` (`feature:packs`) | Combined "Packs & Models" screen mixing lexicon packs and AI models in a single flat list | Confused users about what is required for dictionary lookup vs optional NLP disambiguation; lacked `.bipe` / `main_db.sqlite` import and reverse FA→EN dictionary status. |
| `Settings → Learning & Translation` (`feature:settings`) | Had `DictionaryPreference` (`OFFLINE_FIRST`, `WEB_TRANSLATE_ONLY`, `OFFLINE_ONLY`) and `fallbackToOnline` boolean | Treated "dictionary" and "translation" as a single toggle; did not distinguish between Lexicon Pack v2 (`PACK`), Legacy FastDic v1 (`LEGACY_V1`), ML Kit (`ML_KIT`), and Online Google Translate (`ONLINE_GOOGLE`). |
| `PlayerScreen` (`feature:player`) | Word tap popup showed a single meaning string and only styled individual words (not multi-token phrase ranges); underline style setting (`SOLID`/`DASHED`/`DOTTED`) rendered only solid lines | Missed ProudVocab's dual-section word+phrase popup card, source provenance badges, and real Canvas dashed/dotted underlines. |

## 2. Unified Information Architecture (D-58, D-60)

### 2.1 Bottom Navigation Tabs
1. **Home (`AppRoute.Home`)**: Recent videos, open video/subtitle files, quick status banner if no lexicon pack is installed.
2. **My Words (`AppRoute.Words`)**: Saved single words and multi-word expressions (`PHRASAL`, `IDIOM`, `COLLOCATION`), status filters (`NEW`, `LEARNING`, `KNOWN`), kind filters, search, JSON/CSV/Anki TSV export & import, and "Jump to video cue".
3. **Dictionary (`AppRoute.Dictionary`)**: Full interactive EN→FA and FA→EN dictionary lookup screen powered by `ImportedDictionaryProvider` (`entry_fts` prefix search, headword details, IPA, POS/CEFR/domain badges, examples, synonyms/antonyms/word family, collocations, and one-tap bookmark).
4. **Settings (`AppRoute.Settings`)**: Clean preference sections with direct navigation to **Library & Resources (`AppRoute.Packs`)**.

### 2.2 Unified "Library & Resources" Screen (`feature:packs`, D-60)
Organized into four distinct, self-explanatory sections:
1. **Dictionaries & Lexicon Packs**
   - `SubLearn Lexicon EN→FA v2` (`LEXICON_EN_FA`): Download from Release or import local `.sqlite`/`.sqlite.gz`.
   - `Persian→English Dictionary v1` (`DICT_FA_EN`): Download or import reverse dictionary for Persian lookups.
   - `Legacy FastDic v1 Importer` (`LEGACY_V1`): Import `main_db.sqlite` or `.bipe` (`fastdic_plain`) directly from device storage.
   - `CEFR & Word Frequency Pack` (`WORDFREQ_CEFR`): Open CEFR/frequency supplement.
2. **Offline Translation Models**
   - Official Google ML Kit EN↔FA on-device neural translation model (~30 MB), with download/delete controls.
3. **Smart NLP Enhancement Models (Optional)**
   - Active NLP Profile selector (`Base`, `Light`, `Balanced`, `Precise`, `Advanced`, `Smart`) with "Run Device Benchmark" action.
   - Downloadable ONNX models (`MiniLM-L6-v2`, `MobileBERT`) and MediaPipe GenAI `.task` LLM (`Gemma 2B` / `Qwen2.5 1.5B`) with RAM guard badges.
4. **Storage & Maintenance**
   - Breakdown of installed pack/model disk usage, SHA-256 integrity verification, and translation/phrase cache management.

### 2.3 Translation & Lookup Settings (`TranslationSettings`, D-58, D-59, D-62)
- **Offline-Only Master Switch (`offlineOnly`)**: Immediately disables `ONLINE_GOOGLE` and cloud AI requests when turned on.
- **Translation Mode (`TranslationMode`)**:
  - `PRIORITY_CHAIN`: Queries enabled sources in priority order (`PACK → LEGACY_V1 → ML_KIT → ONLINE_GOOGLE`) and returns the first hit.
  - `MERGE_ALL`: Queries all enabled offline sources in parallel (`< 50 ms` p95) and streams `ONLINE_GOOGLE` asynchronously if enabled, displaying source provenance chips on each meaning.
- **Online Google Translate (`ONLINE_GOOGLE`)**: Off by default; shows a clear privacy disclosure when enabled (sends only the tapped word/phrase, never the subtitle sentence); supports custom endpoint host/template for proxy use.
