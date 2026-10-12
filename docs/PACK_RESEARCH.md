# Open Data & AI Model Research for SubLearn Packs (`docs/PACK_RESEARCH.md`)

This document evaluates candidate datasets, dictionary packs, and on-device NLP/AI models for
SubLearn's catalog (`docs/catalog/catalog-v2.json`), covering license compatibility, file size, RAM
footprint, and runtime suitability on Android (`minSdk 26`).

## 1. Lexicon & Dictionary Packs

| Pack ID | Kind | Source & License | Compressed Size | Uncompressed Size | Purpose |
| --- | --- | --- | --- | --- | --- |
| `lexicon-en-fa-v2` | `LEXICON_EN_FA` | `melonityhub/dictionaryproject` (`main_db.sqlite` v1.0.0) converted via `build_lexicon.py` v2 | ~34 MB (`.sqlite.gz`) | ~88 MB (`.sqlite`) | Primary EN→FA lexicon (~88k entries, ~136k senses, ~135k examples, ~50k lemma forms, MWE patterns, POS frequencies, IPA). |
| `dict-fa-en-v1` | `DICT_FA_EN` | `melonityhub/dictionaryproject` (`persian_*` tables) converted via `build_lexicon.py --direction fa-en` | ~12 MB (`.sqlite.gz`) | ~32 MB (`.sqlite`) | Reverse FA→EN dictionary for looking up Persian words in the Dictionary tab. |
| `wordfreq-en-cefr-v1` | `WORDFREQ_CEFR` | Open CEFR-J + SUBTLEX-US / ProudVocab `cefr_data.js` word list (CC-BY / Public Domain) | ~180 KB (`.json.gz`) | ~650 KB | Supplementary CEFR (`A1..C2`) and subtitle frequency ranks for words not tagged in the lexicon. |

## 2. Offline Neural Machine Translation (`ML_KIT`)

| Component | Source | Size | Runtime | Notes |
| --- | --- | --- | --- | --- |
| Google ML Kit Translate (`en` ↔ `fa`) | `com.google.mlkit:translate:17.0.3` | ~30 MB per language pair | Play Services / TFLite | Managed directly via `RemoteModelManager`; zero APK size bloat; works 100% offline once downloaded. |

## 3. On-Device NLP & AI Models (`core:nlp-onnx`, `core:nlp-llm`)

| Model ID | Kind | Format & Runtime | Size | Min RAM | Purpose & Selection Rationale |
| --- | --- | --- | --- | --- | --- |
| `embed-minilm-l6-v2-q8` | `SENTENCE_EMBEDDING` | INT8 quantized ONNX (`onnxruntime-android`) | ~23 MB | 3 GB | Used in `BALANCED` and `PRECISE` profiles to rank senses by cosine similarity between the subtitle cue and sense glosses/examples (`EmbeddingSenseRanker`). Fast (<12 ms per cue on mid-range ARM64). |
| `pos-mobilebert-en-q8` | `POS_TAGGER` | INT8 quantized ONNX (`onnxruntime-android`) | ~25 MB | 4 GB | Used in `PRECISE` profile (`OnnxPosTagger`) to refine contextual POS tags and literal-vs-idiomatic confidence scores beyond `LexiconPosTagger`. |
| `llm-gemma2-2b-it-cpu-int4` | `LLM_SELECTOR` | MediaPipe GenAI `.task` (`com.google.mediapipe:tasks-genai`) | ~1.35 GB | 6 GB | Used in `ADVANCED` profile (`MediaPipeLlmSelector`) to disambiguate hard idiomatic lines by choosing a sense index (`1..N`) or `LITERAL`/`IDIOMATIC`. |

### Rejected Candidates
- **`pos-opennlp-ud` (Apache OpenNLP UD POS bin)**: Removed in Catalog v2 (D-51). `LexiconPosTagger`
  (backed by `word_pos` from `lexicon-en-fa-v2` + contextual rules) achieves higher accuracy on
  subtitle phrasal verbs (`RP` vs `IN`) at 0 MB extra download size, while `pos-mobilebert-en-q8`
  covers neural contextual disambiguation.
- **Raw `.gguf` via custom `llama.cpp` JNI**: Replaced by MediaPipe Tasks GenAI (`.task`) (D-53) to
  avoid maintaining custom CMake/NDK builds across ABIs while gaining hardware GPU/NPU delegation.
