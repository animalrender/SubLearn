#!/usr/bin/env python3
"""build_lexicon.py — Convert the owner's dictionary database into SubLearn Lexicon Pack v2.

Usage:
    python3 tools/lexicon_builder/build_lexicon.py \
        --input main_db.sqlite \
        --output-dir dist/ \
        [--direction en-fa|fa-en] \
        [--gzip] \
        [--export-test-json test_json_dir/]

See docs/EXTENSION_POINTS.md (§ 6.1) and docs/DECISIONS.md (D-44) for the v2 schema and rules.
"""

import argparse
import datetime
import gzip
import hashlib
import html
import json
import os
import re
import shutil
import sqlite3
import sys
from collections import Counter, defaultdict

BUILDER_VERSION = "2.0.0"

# ---------------------------------------------------------------------------
# POS & Category mappings (from melonityhub/dictionaryproject fastdic_desktop/fd_data.py)
# ---------------------------------------------------------------------------
POS_MAP = {
    0: None,
    1: "NOUN",       # n
    2: "NOUN",       # n (pl.)
    3: "NOUN",       # n [C]
    4: "NOUN",       # n [U]
    5: "VERB",       # vt
    6: "VERB",       # vi
    7: "ADJ",        # adj
    8: "ADV",        # adv
    9: "PRON",       # pron
    10: "PREP",      # prep
    11: "CONJ",      # conj
    12: "INTJ",      # interj
    13: "DET",       # det
    14: "PART",      # prefix
    15: "PART",      # suffix
    16: "X",         # abbr
    17: "VERB",      # aux
    18: "IDIOM",     # idiom
    19: "COLLOC",    # collocation
    20: "PHRASAL",   # phrasal verb
    21: "VERB",      # modal
    22: "PART",      # particle
    23: "NUM",       # num
    24: "DET",       # art
    25: "PHRASE",    # phrase
    26: "IDIOM",     # proverb
    27: "VERB",      # linking verb
    28: "NOUN",      # proper noun
    29: "ADJ",       # predeterminer
    30: "PREP",      # prepositional phrase
    31: "NOUN",      # compound noun
    32: "ADJ",       # participial adjective
    33: "ADV",       # conjunctive adverb
    34: "IDIOM",     # slang
}

SYN_POS_MAP = {
    "noun": "NOUN",
    "verb": "VERB",
    "adjective": "ADJ",
    "adverb": "ADV",
    "pronoun": "PRON",
    "preposition": "PREP",
    "conjunction": "CONJ",
    "interjection": "INTJ",
    "determiner": "DET",
    "article": "DET",
}

CATEGORIES_FALLBACK = {
    1: ("Botany", "گیاه‌شناسی"), 2: ("Zoology", "جانورشناسی"), 3: ("Chemistry", "شیمی"),
    4: ("Medicine", "پزشکی"), 5: ("Biology", "زیست‌شناسی"), 6: ("Anatomy", "کالبدشناسی"),
    7: ("Law", "حقوق"), 8: ("Music", "موسیقی"), 9: ("Physics", "فیزیک"),
    10: ("Astronomy", "نجوم"), 11: ("Mathematics", "ریاضیات"), 12: ("Geology", "زمین‌شناسی"),
    13: ("Military", "نظامی"), 14: ("Architecture", "معماری"), 15: ("Sports", "ورزش"),
    16: ("Computer", "کامپیوتر"), 17: ("Economics", "اقتصاد"), 18: ("Psychology", "روانشناسی"),
    19: ("Linguistics", "زبان‌شناسی"), 20: ("Philosophy", "فلسفه"), 21: ("Religion", "دین"),
    22: ("Politics", "سیاست"), 23: ("Literature", "ادبیات"), 24: ("Art", "هنر"),
    25: ("Geography", "جغرافیا"), 26: ("History", "تاریخ"), 27: ("Sociology", "جامعه‌شناسی"),
    28: ("Agriculture", "کشاورزی"), 29: ("Nautical", "دریایی"), 30: ("Aviation", "هوانوردی"),
    31: ("Electronics", "الکترونیک"), 32: ("Mechanics", "مکانیک"), 33: ("Metallurgy", "متالورژی"),
    34: ("Textiles", "نساجی"), 35: ("Printing", "چاپ"), 36: ("Photography", "عکاسی"),
    37: ("Cinema", "سینما"), 38: ("Theater", "تئاتر"), 39: ("Culinary", "آشپزی"),
    40: ("Fashion", "مد و لباس"), 41: ("Games", "بازی"), 42: ("Commerce", "بازرگانی"),
    43: ("Finance", "مالی"), 44: ("Accounting", "حسابداری"), 45: ("Insurance", "بیمه"),
    46: ("Banking", "بانکداری"), 47: ("Statistics", "آمار"), 48: ("Logic", "منطق"),
    49: ("Ethics", "اخلاق"), 50: ("Mythology", "اسطوره‌شناسی"), 51: ("Archeology", "باستان‌شناسی"),
    52: ("Anthropology", "مردم‌شناسی"), 53: ("Ecology", "بوم‌شناسی"), 54: ("Meteorology", "هواشناسی"),
    55: ("Mineralogy", "کانی‌شناسی"), 56: ("Paleontology", "دیرینه‌شناسی"), 57: ("Veterinary", "دامپزشکی"),
}

# Baseline irregular forms (form -> list of base lemmas at rank 0)
BASELINE_IRREGULAR_LEMMAS = {
    "am": ["be"], "is": ["be"], "are": ["be"], "was": ["be"], "were": ["be"], "been": ["be"], "being": ["be"],
    "went": ["go"], "gone": ["go"], "goes": ["go"], "going": ["go"],
    "gave": ["give"], "given": ["give"], "gives": ["give"], "giving": ["give"],
    "broke": ["break"], "broken": ["break"], "breaks": ["break"], "breaking": ["break"],
    "took": ["take"], "taken": ["take"], "takes": ["take"], "taking": ["take"],
    "came": ["come"], "comes": ["come"], "coming": ["come"],
    "made": ["make"], "makes": ["make"], "making": ["make"],
    "saw": ["see"], "seen": ["see"], "sees": ["see"], "seeing": ["see"],
    "left": ["leave"], "leaves": ["leave"], "leaving": ["leave"],
    "ran": ["run"], "runs": ["run"], "running": ["run"],
    "had": ["have"], "has": ["have"], "having": ["have"],
    "did": ["do"], "done": ["do"], "does": ["do"], "doing": ["do"],
    "said": ["say"], "says": ["say"], "saying": ["say"],
    "got": ["get"], "gotten": ["get"], "gets": ["get"], "getting": ["get"],
    "knew": ["know"], "known": ["know"], "knows": ["know"], "knowing": ["know"],
    "thought": ["think"], "thinks": ["think"], "thinking": ["think"],
    "told": ["tell"], "tells": ["tell"], "telling": ["tell"],
    "became": ["become"], "becomes": ["become"], "becoming": ["become"],
    "showed": ["show"], "shown": ["show"], "shows": ["show"], "showing": ["show"],
    "felt": ["feel"], "feels": ["feel"], "feeling": ["feel"],
    "put": ["put"], "puts": ["put"], "putting": ["put"],
    "brought": ["bring"], "brings": ["bring"], "bringing": ["bring"],
    "began": ["begin"], "begun": ["begin"], "begins": ["begin"], "beginning": ["begin"],
    "kept": ["keep"], "keeps": ["keep"], "keeping": ["keep"],
    "held": ["hold"], "holds": ["hold"], "holding": ["hold"],
    "wrote": ["write"], "written": ["write"], "writes": ["write"], "writing": ["write"],
    "stood": ["stand"], "stands": ["stand"], "standing": ["stand"],
    "heard": ["hear"], "hears": ["hear"], "hearing": ["hear"],
    "let": ["let"], "lets": ["let"], "letting": ["let"],
    "meant": ["mean"], "means": ["mean"], "meaning": ["mean"],
    "set": ["set"], "sets": ["set"], "setting": ["set"],
    "met": ["meet"], "meets": ["meet"], "meeting": ["meet"],
    "paid": ["pay"], "pays": ["pay"], "paying": ["pay"],
    "sat": ["sit"], "sits": ["sit"], "sitting": ["sit"],
    "spoke": ["speak"], "spoken": ["speak"], "speaks": ["speak"], "speaking": ["speak"],
    "lay": ["lie"], "lain": ["lie"], "lies": ["lie"], "lying": ["lie"],
    "led": ["lead"], "leads": ["lead"], "leading": ["lead"],
    "read": ["read"], "reads": ["read"], "reading": ["read"],
    "grew": ["grow"], "grown": ["grow"], "grows": ["grow"], "growing": ["grow"],
    "lost": ["lose"], "loses": ["lose"], "losing": ["lose"],
    "fell": ["fall"], "fallen": ["fall"], "falls": ["fall"], "falling": ["fall"],
    "sent": ["send"], "sends": ["send"], "sending": ["send"],
    "built": ["build"], "builds": ["build"], "building": ["build"],
    "understood": ["understand"], "understands": ["understand"], "understanding": ["understand"],
    "drew": ["draw"], "drawn": ["draw"], "draws": ["draw"], "drawing": ["draw"],
    "spent": ["spend"], "spends": ["spend"], "spending": ["spend"],
    "cut": ["cut"], "cuts": ["cut"], "cutting": ["cut"],
    "rose": ["rise"], "risen": ["rise"], "rises": ["rise"], "rising": ["rise"],
    "drove": ["drive"], "driven": ["drive"], "drives": ["drive"], "driving": ["drive"],
    "bought": ["buy"], "buys": ["buy"], "buying": ["buy"],
    "wore": ["wear"], "worn": ["wear"], "wears": ["wear"], "wearing": ["wear"],
    "chose": ["choose"], "chosen": ["choose"], "chooses": ["choose"], "choosing": ["choose"],
    "caught": ["catch"], "catches": ["catch"], "catching": ["catch"],
    "threw": ["throw"], "thrown": ["throw"], "throws": ["throw"], "throwing": ["throw"],
    "taught": ["teach"], "teaches": ["teach"], "teaching": ["teach"],
    "ate": ["eat"], "eaten": ["eat"], "eats": ["eat"], "eating": ["eat"],
    "sang": ["sing"], "sung": ["sing"], "sings": ["sing"], "singing": ["sing"],
    "rang": ["ring"], "rung": ["ring"], "rings": ["ring"], "ringing": ["ring"],
    "sank": ["sink"], "sunk": ["sink"], "sinks": ["sink"], "sinking": ["sink"],
    "drank": ["drink"], "drunk": ["drink"], "drinks": ["drink"], "drinking": ["drink"],
    "swam": ["swim"], "swum": ["swim"], "swims": ["swim"], "swimming": ["swim"],
    "flew": ["fly"], "flown": ["fly"], "flies": ["fly"], "flying": ["fly"],
    "rode": ["ride"], "ridden": ["ride"], "rides": ["ride"], "riding": ["ride"],
    "forgot": ["forget"], "forgotten": ["forget"], "forgets": ["forget"], "forgetting": ["forget"],
    "froze": ["freeze"], "frozen": ["freeze"], "freezes": ["freeze"], "freezing": ["freeze"],
    "stole": ["steal"], "stolen": ["steal"], "steals": ["steal"], "stealing": ["steal"],
    "blew": ["blow"], "blown": ["blow"], "blows": ["blow"], "blowing": ["blow"],
    "shook": ["shake"], "shaken": ["shake"], "shakes": ["shake"], "shaking": ["shake"],
    "hid": ["hide"], "hidden": ["hide"], "hides": ["hide"], "hiding": ["hide"],
    "tore": ["tear"], "torn": ["tear"], "tears": ["tear"], "tearing": ["tear"],
    "woke": ["wake"], "woken": ["wake"], "wakes": ["wake"], "waking": ["wake"],
    "slept": ["sleep"], "sleeps": ["sleep"], "sleeping": ["sleep"],
    "sold": ["sell"], "sells": ["sell"], "selling": ["sell"],
    "fought": ["fight"], "fights": ["fight"], "fighting": ["fight"],
    "won": ["win"], "wins": ["win"], "winning": ["win"],
    "hung": ["hang"], "hangs": ["hang"], "hanging": ["hang"],
    "struck": ["strike"], "strikes": ["strike"], "striking": ["strike"],
    "stuck": ["stick"], "sticks": ["stick"], "sticking": ["stick"],
    "swept": ["sweep"], "sweeps": ["sweep"], "sweeping": ["sweep"],
    "wept": ["weep"], "weeps": ["weep"], "weeping": ["weep"],
    "fed": ["feed"], "feeds": ["feed"], "feeding": ["feed"],
    "fled": ["flee"], "flees": ["flee"], "fleeing": ["flee"],
    "shot": ["shoot"], "shoots": ["shoot"], "shooting": ["shoot"],
    "shut": ["shut"], "shuts": ["shut"], "shutting": ["shut"],
    "hurt": ["hurt"], "hurts": ["hurt"], "hurting": ["hurt"],
    "hit": ["hit"], "hits": ["hit"], "hitting": ["hit"],
    "beat": ["beat"], "beaten": ["beat"], "beats": ["beat"], "beating": ["beat"],
    "bit": ["bite"], "bitten": ["bite"], "bites": ["bite"], "biting": ["bite"],
    "dug": ["dig"], "digs": ["dig"], "digging": ["dig"],
    "spun": ["spin"], "spins": ["spin"], "spinning": ["spin"],
    "shone": ["shine"], "shines": ["shine"], "shining": ["shine"],
    "lent": ["lend"], "lends": ["lend"], "lending": ["lend"],
    "bent": ["bend"], "bends": ["bend"], "bending": ["bend"],
    "dealt": ["deal"], "deals": ["deal"], "dealing": ["deal"],
    "dreamt": ["dream"], "dreams": ["dream"], "dreaming": ["dream"],
    "leapt": ["leap"], "leaps": ["leap"], "leaping": ["leap"],
    "knelt": ["kneel"], "kneels": ["kneel"], "kneeling": ["kneel"],
    "forgave": ["forgive"], "forgiven": ["forgive"], "forgives": ["forgive"],
    "forbade": ["forbid"], "forbidden": ["forbid"], "forbids": ["forbid"],
    "mistook": ["mistake"], "mistaken": ["mistake"], "mistakes": ["mistake"],
    "overcame": ["overcome"], "overcomes": ["overcome"], "overcoming": ["overcome"],
    "underwent": ["undergo"], "undergone": ["undergo"], "undergoes": ["undergo"],
    "withdrew": ["withdraw"], "withdrawn": ["withdraw"], "withdraws": ["withdraw"],
    "upheld": ["uphold"], "upholds": ["uphold"],
    "withheld": ["withhold"], "withholds": ["withhold"],
    "arose": ["arise"], "arisen": ["arise"], "arises": ["arise"],
    "awoke": ["awake"], "awoken": ["awake"], "awakes": ["awake"],
    "bore": ["bear"], "borne": ["bear"], "born": ["bear"], "bears": ["bear"],
    "bound": ["bind"], "binds": ["bind"], "binding": ["bind"],
    "bred": ["breed"], "breeds": ["breed"], "breeding": ["breed"],
    "cast": ["cast"], "casts": ["cast"], "casting": ["cast"],
    "clung": ["cling"], "clings": ["cling"], "clinging": ["cling"],
    "crept": ["creep"], "creeps": ["creep"], "creeping": ["creep"],
    "flung": ["fling"], "flings": ["fling"], "flinging": ["fling"],
    "ground": ["grind"], "grinds": ["grind"], "grinding": ["grind"],
    "laid": ["lay"], "lays": ["lay"], "laying": ["lay"],
    "lit": ["light"], "lights": ["light"], "lighting": ["light"],
    "sought": ["seek"], "seeks": ["seek"], "seeking": ["seek"],
    "shed": ["shed"], "sheds": ["shed"], "shedding": ["shed"],
    "slid": ["slide"], "slides": ["slide"], "sliding": ["slide"],
    "split": ["split"], "splits": ["split"], "splitting": ["split"],
    "spread": ["spread"], "spreads": ["spread"], "spreading": ["spread"],
    "sprang": ["spring"], "sprung": ["spring"], "springs": ["spring"],
    "stung": ["sting"], "stings": ["sting"], "stinging": ["sting"],
    "stank": ["stink"], "stunk": ["stink"], "stinks": ["stink"],
    "swore": ["swear"], "sworn": ["swear"], "swears": ["swear"],
    "swung": ["swing"], "swings": ["swing"], "swinging": ["swing"],
    "wound": ["wind"], "winds": ["wind"], "winding": ["wind"],
    "wrung": ["wring"], "wrings": ["wring"], "wringing": ["wring"],
    "died": ["die"], "dies": ["die"], "dying": ["die"],
    "tied": ["tie"], "ties": ["tie"], "tying": ["tie"],
    "picked": ["pick"], "picks": ["pick"], "picking": ["pick"],
    "turned": ["turn"], "turns": ["turn"], "turning": ["turn"],
    "looked": ["look"], "looks": ["look"], "looking": ["look"],
    # Irregular plurals
    "children": ["child"],
    "mice": ["mouse"],
    "feet": ["foot"],
    "teeth": ["tooth"],
    "men": ["man"],
    "women": ["woman"],
    "geese": ["goose"],
    "oxen": ["ox"],
    "lice": ["louse"],
    "leaves": ["leaf", "leave"],
    "lives": ["life", "live"],
    "wives": ["wife"],
    "knives": ["knife"],
    "wolves": ["wolf"],
    "halves": ["half"],
    "selves": ["self"],
    "loaves": ["loaf"],
    "thieves": ["thief"],
    "shelves": ["shelf"],
    "calves": ["calf"],
    "scarves": ["scarf"],
    "phenomena": ["phenomenon"],
    "criteria": ["criterion"],
    "crises": ["crisis"],
    "analyses": ["analysis"],
    "theses": ["thesis"],
    "hypotheses": ["hypothesis"],
    "oases": ["oasis"],
    "parentheses": ["parenthesis"],
    "diagnoses": ["diagnosis"],
    "synopses": ["synopsis"],
    "axes": ["axis", "axe"],
    "appendices": ["appendix"],
    "indices": ["index"],
    "matrices": ["matrix"],
    "vertices": ["vertex"],
    "fungi": ["fungus"],
    "cacti": ["cactus"],
    "nuclei": ["nucleus"],
    "stimuli": ["stimulus"],
    "syllabi": ["syllabus"],
    "alumni": ["alumnus"],
    "radii": ["radius"],
    "larvae": ["larva"],
    "antennae": ["antenna"],
    "formulae": ["formula"],
    "vertebrae": ["vertebra"],
    "bacteria": ["bacterium"],
    "curricula": ["curriculum"],
    "data": ["datum"],
    "media": ["medium"],
    "strata": ["stratum"],
    "memoranda": ["memorandum"],
    "millennia": ["millennium"],
    # Irregular comparatives & superlatives
    "better": ["good", "well"],
    "best": ["good", "well"],
    "worse": ["bad"],
    "worst": ["bad"],
    "farther": ["far"],
    "farthest": ["far"],
    "further": ["far"],
    "furthest": ["far"],
    "less": ["little"],
    "least": ["little"],
    "more": ["much", "many"],
    "most": ["much", "many"],
}

# Homograph inflected forms that are ALSO standalone headwords with distinct lexical senses
# and therefore receive (form, form, rank=1) alongside their (form, base_lemma, rank=0).
HOMOGRAPH_SELF_LEMMAS = {
    "left", "saw", "rose", "wound", "found", "felt", "ground", "bound",
    "bore", "shed", "split", "lit", "even", "still", "just", "right",
    "well", "fine", "light", "match", "bear", "lie", "lay", "fall",
    "spring", "ring", "sink", "stick", "strike", "wind", "lead", "tear",
    "wear", "beat", "hit", "cut", "put", "set", "let", "run", "read",
    "drive", "ride", "fly", "draw", "break", "show", "play", "work",
}

# Pure past participles & function inflections that must NEVER get a self-lemma (rank=1)
PURE_INFLECTION_FORMS = {
    "broken", "taken", "given", "gone", "seen", "done", "written", "driven",
    "ridden", "spoken", "stolen", "chosen", "frozen", "forgotten", "gotten",
    "fallen", "beaten", "eaten", "bitten", "hidden", "shaken", "woken",
    "worn", "torn", "sworn", "borne", "flown", "grown", "known", "shown",
    "thrown", "blown", "drawn", "withdrawn", "sung", "rung", "sunk", "drunk",
    "swum", "begun", "sprung", "stunk", "been", "went", "gave", "came",
    "took", "ran", "ate", "was", "were", "am", "is", "are", "had", "has",
    "did", "does", "children", "mice", "feet", "teeth", "men", "women",
    "geese", "oxen", "lice", "broke", "worse", "worst",
}

STOPWORDS = {
    "a", "an", "the", "of", "in", "on", "at", "to", "for", "with", "by", "from",
    "up", "down", "out", "off", "over", "into", "onto", "upon", "about", "after",
    "before", "under", "between", "through", "during", "without", "within", "along",
    "across", "behind", "beyond", "around", "against", "among", "towards", "toward",
    "and", "or", "but", "nor", "so", "yet", "as", "if", "than", "that", "whether",
    "while", "although", "though", "because", "since", "unless", "until",
    "i", "me", "my", "mine", "myself", "you", "your", "yours", "yourself",
    "he", "him", "his", "himself", "she", "her", "hers", "herself",
    "it", "its", "itself", "we", "us", "our", "ours", "ourselves",
    "they", "them", "their", "theirs", "themselves",
    "one", "ones", "one's", "someone", "somebody", "something", "anyone", "anybody",
    "anything", "everyone", "everybody", "everything", "no", "nobody", "nothing",
    "this", "these", "those", "some", "any", "each", "every", "all", "both",
    "other", "another", "such", "what", "which", "who", "whom", "whose",
    "be", "am", "is", "are", "was", "were", "been", "being",
    "have", "has", "had", "having", "do", "does", "did", "doing", "done",
    "will", "would", "shall", "should", "can", "could", "may", "might", "must",
    "not", "very", "too", "also", "just", "only", "even", "more", "most",
    "poss", "refl", "{np}", "{np?}",
}

# Closed-class POS tags for LexiconPosTagger accuracy (RP vs IN, DET, PRON, AUX, CONJ)
CLOSED_CLASS_POS = {
    # Determiners
    "a": ("DET", 10000), "an": ("DET", 8000), "the": ("DET", 20000),
    "this": ("DET", 5000), "that": ("DET", 5000), "these": ("DET", 3000), "those": ("DET", 3000),
    "some": ("DET", 4000), "any": ("DET", 4000), "every": ("DET", 3000), "each": ("DET", 3000),
    "no": ("DET", 4000), "all": ("DET", 4000), "both": ("DET", 2000),
    # Possessives / Pronouns
    "my": ("PRON", 5000), "your": ("PRON", 5000), "his": ("PRON", 5000), "her": ("PRON", 5000),
    "its": ("PRON", 4000), "our": ("PRON", 4000), "their": ("PRON", 4000),
    "i": ("PRON", 8000), "me": ("PRON", 5000), "you": ("PRON", 8000), "he": ("PRON", 6000),
    "him": ("PRON", 5000), "she": ("PRON", 6000), "it": ("PRON", 8000), "we": ("PRON", 5000),
    "us": ("PRON", 4000), "they": ("PRON", 6000), "them": ("PRON", 5000),
    "myself": ("PRON", 2000), "yourself": ("PRON", 2000), "himself": ("PRON", 2000),
    "herself": ("PRON", 2000), "itself": ("PRON", 2000), "ourselves": ("PRON", 1500),
    "themselves": ("PRON", 2000),
    # Prepositions (IN)
    "of": ("PREP", 15000), "at": ("PREP", 8000), "from": ("PREP", 8000), "into": ("PREP", 6000),
    "with": ("PREP", 9000), "by": ("PREP", 7000), "for": ("PREP", 9000), "to": ("PREP", 12000),
    "between": ("PREP", 3000), "among": ("PREP", 2000), "against": ("PREP", 3000),
    "without": ("PREP", 3000), "within": ("PREP", 2500), "during": ("PREP", 3000),
    "before": ("PREP", 4000), "after": ("PREP", 4000), "under": ("PREP", 3500),
    "upon": ("PREP", 2000), "towards": ("PREP", 2000), "toward": ("PREP", 2000),
    # Particles / Adverbs (PART)
    "up": ("PART", 6000), "down": ("PART", 5000), "out": ("PART", 6000), "off": ("PART", 5000),
    "away": ("PART", 4000), "back": ("PART", 5000), "over": ("PART", 4000), "on": ("PART", 5000),
    "in": ("PART", 5000), "along": ("PART", 2000), "through": ("PART", 3000), "around": ("PART", 3000),
    "aback": ("ADV", 1000), "apart": ("ADV", 2000), "aside": ("ADV", 2000), "ahead": ("ADV", 2000),
    # Conjunctions
    "and": ("CONJ", 15000), "or": ("CONJ", 8000), "but": ("CONJ", 8000), "because": ("CONJ", 5000),
    "while": ("CONJ", 4000), "although": ("CONJ", 3000), "though": ("CONJ", 3000),
    "if": ("CONJ", 6000), "unless": ("CONJ", 2000), "until": ("CONJ", 3000),
}

# Particles that can be separated in transitive phrasal verbs
SEPARABLE_PARTICLES = {
    "up", "down", "out", "off", "on", "in", "over", "away", "back", "around", "through"
}

# Prepositions/particles that can form phrasal verbs (both separable and inseparable)
PHRASAL_PARTICLES_AND_PREPS = SEPARABLE_PARTICLES | {
    "into", "after", "for", "with", "from", "at", "to", "of", "about",
    "upon", "against", "without", "by", "under", "across", "along", "ahead",
    "apart", "aside", "forward", "forth", "round", "together", "onto",
}

# Known intransitive 2-word phrasal verbs that should have separable = 0
INTRANSITIVE_PHRASALS = {
    "pass away", "grow up", "show up", "come in", "go on", "get by",
    "sit down", "stand up", "fall down", "watch out", "look out",
    "hang on", "hold on", "carry on", "move on", "get along",
    "come back", "go back", "stay up", "wait up", "wake up",
    "get up", "bow out", "chip in", "drop in", "drop out", "eat out",
    "fade away", "fall apart", "fall out", "fall through", "fit in",
    "get ahead", "get away", "give in", "go ahead", "go away",
    "go out", "hang out", "hold out", "keep on", "lie down",
    "look ahead", "look back", "pan out", "pass out", "pull through",
    "run away", "run out", "set off", "set out", "settle down",
    "show off", "shut up", "slow down", "speak up", "Step down",
    "step down", "take off", "turn up", "wear off", "work out",
}

# Common verbs that can start a phrasal verb
COMMON_PHRASAL_VERBS = {
    "act", "add", "allow", "answer", "ask", "back", "bail", "bank", "be", "bear", "beat",
    "bend", "blow", "boil", "book", "bottle", "Toss", "bounce", "bow", "branch", "break",
    "athe", "bring", "brush", "build", "bump", "burn", "burst", "butt", "buy", "call",
    "calm", "care", "carry", "cash", "catch", "cast", "chalk", "change", "charge", "chase",
    "check", "cheer", "chew", "chip", "choke", "chop", "clean", "clear", "climb", "close",
    "cloud", "come", "conjure", "cool", "cop", "count", "cover", "crack", "creep", "crop",
    "cross", "cry", "cut", "ash", "deal", "die", "dig", "dish", "dive", "do", "oz", "drag",
    "draw", "dream", "dress", "drink", "drive", "drop", "drum", "dry", "ease", "eat",
    "edge", "egg", "end", "explain", "face", "fade", "fall", "farm", "feed", "feel",
    "fend", "figure", "fill", "find", "finish", "fire", "fish", "fit", "fix", "flare",
    "flesh", "flip", "flood", "fly", "fold", "follow", "fool", "freak", "freeze", "freshen",
    "fry", "gang", "gear", "get", "give", "gloss", "go", "grow", "hand", "hang", "hash",
    "have", "head", "heal", "hear", "heat", "help", "hide", "hit", "hold", "hook", "hunt",
    "hurry", "iron", "jack", "jam", "jot", "jump", "keep", "kick", "kill", "knock",
    "ash", "laugh", "lay", "lead", "leak", "lean", "leave", "let", "lie", "lift", "light",
    "line", "link", "listen", "live", "load", "lock", "log", "look", "lose", "luck",
    "make", "map", "mark", "measure", "meet", "melt", "mess", "miss", "mix", "ef", "mop",
    "mount", "move", "mull", "narrow", "nod", "note", "open", "opt", "own", "pack", "pair",
    "pan", "pass", "patch", "pay", "peel", "pencil", "perk", "phase", "pick", "piece",
    "Stress", "pile", "pin", "pipe", "pitch", "plan", "play", "plug", "point", "polish",
    "pop", "pour", "opa", "press", "print", "pull", "pump", "push", "put", "quiet",
    "rack", "rain", "rake", "rat", "reach", "read", "reel", "rein", "rely", "rent", "ride",
    "ring", "rip", "rise", "roll", "root", "rope", "rough", "round", "rub", "rule", "run",
    "rush", "save", "scale", "scrape", "scratch", "screen", "screw", "seal", "see", "seek",
    "sell", "send", "serve", "set", "settle", "shake", "shape", "shave", "shoot", "shop",
    "shout", "show", "shrug", "shut", "side", "sign", "sing", "sink", "sit", "size",
    "sleep", "slice", "slide", "slip", "slow", "smash", "smooth", "snap", "sneak", "soak",
    "sock", "sort", "sound", "speak", "speed", "spell", "spend", "spill", "spin", "spit",
    "split", "spread", "spring", "square", "squeeze", "stack", "stamp", "stand", "stare",
    "start", "stay", "steal", "steam", "steer", "step", "stick", "stir", "stock", "stop",
    "storm", "straighten", "stretch", "strike", "string", "strip", "stumble", "suck",
    "sum", "umm", "allow", "swear", "sweep", "swell", "swim", "swing", "switch", "tack",
    "take", "talk", "amp", "tap", "tear", "tell", "think", "throw", "id", "tie", "tip",
    "ire", "tone", "top", "toss", "touch", "oom", "osto", "track", "trade", "trail",
    "trip", "try", "tuck", "tune", "turn", "use", "usher", "wait", "wake", "walk", "warm",
    "wash", "watch", "water", "wave", "wear", "weed", "weigh", "whip", "wind", "wipe",
    "wire", "wish", "work", "wrap", "write", "zero", "zip", "zone", "zoom",
}

# Not verbs when appearing at start of 2-word phrase
NON_VERB_FIRST_WORDS = {
    "a", "an", "the", "as", "at", "by", "for", "from", "in", "inside", "outside",
    "into", "of", "off", "on", "onto", "out", "over", "prior", "up", "down",
    "with", "without", "because", "according", "due", "next", "close", "far",
    "ahead", "apart", "aside", "away", "back", "all", "no", "not", "more", "most",
    "less", "least", "good", "bad", "high", "low", "early", "late", "old", "new",
    "young", "first", "last", "same", "other", "another", "each", "every", "some",
    "any", "many", "much", "few", "little", "own", "such", "very", "too", "so",
}

V2_SCHEMA_SQL = """
CREATE TABLE IF NOT EXISTS meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS lemma_form (
    form   TEXT NOT NULL,
    lemma  TEXT NOT NULL,
    pos    TEXT,
    rank   INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (form, lemma)
);
CREATE INDEX IF NOT EXISTS idx_lemma_form ON lemma_form(form);

CREATE TABLE IF NOT EXISTS entry (
    id        INTEGER PRIMARY KEY,
    key       TEXT NOT NULL,
    headword  TEXT NOT NULL,
    kind      TEXT NOT NULL CHECK(kind IN ('WORD','PHRASAL','IDIOM','COLLOCATION','PHRASE')),
    pos       TEXT,
    cefr      TEXT,
    register  TEXT,
    prior     REAL NOT NULL DEFAULT 1.0,
    flags     INTEGER NOT NULL DEFAULT 0,
    UNIQUE(key, kind)
);
CREATE INDEX IF NOT EXISTS idx_entry_key ON entry(key);
CREATE INDEX IF NOT EXISTS idx_entry_kind ON entry(kind);

CREATE TABLE IF NOT EXISTS sense (
    id         INTEGER PRIMARY KEY,
    entry_id   INTEGER NOT NULL REFERENCES entry(id),
    pos        TEXT,
    gloss      TEXT NOT NULL DEFAULT '',
    gloss_fa   TEXT NOT NULL DEFAULT '',
    gloss_en   TEXT,
    domain     TEXT,
    domain_fa  TEXT,
    domain_en  TEXT,
    register   TEXT,
    cefr       TEXT,
    is_primary INTEGER NOT NULL DEFAULT 0,
    prior      REAL NOT NULL DEFAULT 0.5,
    position   INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_sense_entry ON sense(entry_id);

CREATE TABLE IF NOT EXISTS example (
    id       INTEGER PRIMARY KEY,
    sense_id INTEGER NOT NULL REFERENCES sense(id),
    en       TEXT,
    fa       TEXT,
    UNIQUE(sense_id, en)
);
CREATE INDEX IF NOT EXISTS idx_example_sense ON example(sense_id);

CREATE TABLE IF NOT EXISTS pattern (
    id           INTEGER PRIMARY KEY,
    entry_id     INTEGER NOT NULL REFERENCES entry(id),
    pattern      TEXT NOT NULL,
    anchor_lemma TEXT NOT NULL,
    separable    INTEGER NOT NULL DEFAULT 0,
    min_tokens   INTEGER NOT NULL DEFAULT 2,
    max_tokens   INTEGER NOT NULL DEFAULT 6,
    prior        REAL NOT NULL DEFAULT 1.0
);
CREATE INDEX IF NOT EXISTS idx_pattern_anchor ON pattern(anchor_lemma);
CREATE INDEX IF NOT EXISTS idx_pattern_entry ON pattern(entry_id);

CREATE TABLE IF NOT EXISTS word_pos (
    word TEXT NOT NULL,
    pos  TEXT NOT NULL,
    rank INTEGER NOT NULL DEFAULT 0,
    freq INTEGER NOT NULL DEFAULT 1,
    PRIMARY KEY (word, pos)
);
CREATE INDEX IF NOT EXISTS idx_word_pos_word ON word_pos(word);

CREATE TABLE IF NOT EXISTS entry_extra (
    entry_id    INTEGER PRIMARY KEY REFERENCES entry(id),
    synonyms    TEXT,
    antonyms    TEXT,
    family      TEXT,
    word_family TEXT,
    notes       TEXT
);

CREATE TABLE IF NOT EXISTS pron (
    entry_id INTEGER PRIMARY KEY REFERENCES entry(id),
    ipa_us   TEXT,
    ipa_uk   TEXT,
    audio_us TEXT,
    audio_uk TEXT
);

CREATE TABLE IF NOT EXISTS colloc (
    entry_id        INTEGER NOT NULL REFERENCES entry(id),
    colloc_entry_id INTEGER NOT NULL REFERENCES entry(id),
    position        INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (entry_id, colloc_entry_id)
);
CREATE INDEX IF NOT EXISTS idx_colloc_entry ON colloc(entry_id);

CREATE TABLE IF NOT EXISTS relation (
    entry_id INTEGER NOT NULL REFERENCES entry(id),
    kind     TEXT NOT NULL,
    target   TEXT NOT NULL
);

CREATE VIRTUAL TABLE IF NOT EXISTS entry_fts USING fts4(
    key,
    headword,
    tokenize=unicode61
);
"""


def strip_html(text: str) -> str:
    if not text:
        return ""
    cleaned = re.sub(r"<[^>]+>", " ", text)
    cleaned = html.unescape(cleaned)
    return re.sub(r"\s+", " ", cleaned).strip()


def normalize_key(word: str) -> str:
    """Normalize an English word or phrase into a lookup key."""
    if not word:
        return ""
    key = word.strip().lower()
    key = key.replace("\u2019", "'").replace("\u2018", "'").replace("\u00a0", " ")
    # Collapse word(s) -> word, key(-)in -> key in
    key = re.sub(r"([a-z])\(e?s\)\b", r"\1", key)
    key = key.replace("(-)", " ")
    # Strip leading/trailing punctuation
    key = re.sub(r'^[^\w\']+|[^\w\')]+$', '', key)
    key = re.sub(r"\s+", " ", key)
    return key.strip()


def normalize_persian_key(word: str) -> str:
    """Normalize a Persian word or phrase for lookup."""
    if not word:
        return ""
    key = word.strip()
    # Arabic Yeh/Kaf -> Persian Yeh/Kaf
    key = key.replace("\u064a", "\u06cc").replace("\u0649", "\u06cc").replace("\u0643", "\u06a9")
    # Remove Arabic diacritics / harakat / tatweel
    key = re.sub(r"[\u064b-\u065f\u0670\u0640]", "", key)
    # Normalize ZWNJ and spaces
    key = key.replace("\u00a0", " ")
    key = re.sub(r"\s+", " ", key)
    return key.strip()


def _replace_placeholders(text: str) -> str:
    """Replace pronoun/NP placeholders in a pattern string."""
    s = text.strip()
    # Strip leading "to " (unless "to be ")
    if s.lower().startswith("to be "):
        s = "BE " + s[6:]
    elif s.lower().startswith("to "):
        s = s[3:]
    elif s.lower().startswith("be "):
        s = "BE " + s[3:]

    # Possessives
    s = re.sub(r"\b(one's|someone's|somebody's)\b", "POSS", s, flags=re.IGNORECASE)
    # Reflexives
    s = re.sub(
        r"\b(oneself|himself|herself|yourself|themselves|ourselves|itself)\b",
        "REFL",
        s,
        flags=re.IGNORECASE,
    )
    # Noun phrase slots
    s = re.sub(
        r"\b(someone|somebody|something|sb|sth)\b",
        "{NP}",
        s,
        flags=re.IGNORECASE,
    )
    # Clean punctuation other than { } ? ' -
    s = re.sub(r"[,.;:!?\"/]", " ", s)
    s = re.sub(r"\s+", " ", s).strip()
    return s


def convert_to_patterns(headword: str, max_variants: int = 8) -> tuple[list[str], str | None]:
    """Expand a headword into up to `max_variants` concrete pattern strings.

    Returns (patterns, rejection_reason). If rejected, patterns is [] and rejection_reason is set.
    """
    if not headword or not headword.strip():
        return [], "empty"

    raw = headword.strip()
    raw = raw.replace("\u2019", "'").replace("\u2018", "'")

    # Reject ellipsis, exclamation, digits, etc.
    if "..." in raw or "…" in raw:
        return [], "ellipsis"
    if any(ch.isdigit() for ch in raw):
        return [], "digits"
    if "etc." in raw.lower() or "!" in raw or "?" in raw:
        return [], "punctuation_or_etc"

    # Check balanced parentheses
    depth = 0
    for ch in raw:
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth < 0:
                return [], "unbalanced_parens"
    if depth != 0:
        return [], "unbalanced_parens"

    # Collapse word(s) -> word and key(-)in -> key in
    s = re.sub(r"([A-Za-z])\(e?s\)\b", r"\1", raw)
    s = s.replace("(-)", " ")

    # Normalize "(to be)" at start -> optional BE
    s = re.sub(r"^\(to be\)\s+", "(BE) ", s, flags=re.IGNORECASE)

    # Normalize "(someone or something)" or "(something or someone)" -> "({NP})"
    s = re.sub(
        r"\(\s*(?:someone|somebody|something|sb|sth)(?:\s+or\s+(?:someone|somebody|something|sb|sth))*\s*\)",
        "({NP})",
        s,
        flags=re.IGNORECASE,
    )

    # Parse segments of text and parenthetical groups (...)
    segments = []
    pos = 0
    for m in re.finditer(r"\(([^()]+)\)", s):
        if m.start() > pos:
            segments.append(("text", s[pos:m.start()]))
        segments.append(("paren", m.group(1).strip()))
        pos = m.end()
    if pos < len(s):
        segments.append(("text", s[pos:]))

    # Expand segments into candidate strings
    candidates = [""]
    for kind, val in segments:
        if kind == "text":
            candidates = [c + " " + val for c in candidates]
        else:
            # Check if "(or X)" alternative
            if re.match(r"^or\b", val, flags=re.IGNORECASE):
                alt_body = re.sub(r"^or\s+", "", val, flags=re.IGNORECASE).strip()
                # Split by " or " or ","
                alts = [
                    a.strip()
                    for a in re.split(r"(?:,\s*or\s+|\s+or\s+|,\s*)", alt_body, flags=re.IGNORECASE)
                    if a.strip()
                ]
                new_candidates = []
                for c in candidates:
                    c_words = c.strip().split()
                    new_candidates.append(c)
                    for alt in alts:
                        alt_words = alt.split()
                        if len(alt_words) >= 3 and len(c_words) >= len(alt_words):
                            # Full phrase alternative or long suffix replacement
                            prefix = c_words[:-len(alt_words)]
                            new_candidates.append(" ".join(prefix + alt_words))
                        elif c_words:
                            # Replace the single word immediately before "(or ...)"
                            new_candidates.append(" ".join(c_words[:-1] + alt_words))
                        else:
                            new_candidates.append(alt)
                candidates = new_candidates[:max_variants * 2]
            else:
                # Optional parenthetical "(X)" -> without X and with X
                new_candidates = []
                for c in candidates:
                    new_candidates.append(c)
                    new_candidates.append(c + " " + val)
                candidates = new_candidates[:max_variants * 2]

    final_patterns = []
    seen = set()
    for cand in candidates:
        pat = _replace_placeholders(cand)
        # Lowercase literal words while keeping POSS, REFL, {NP}, BE
        tokens = []
        for tok in pat.split():
            if tok in ("POSS", "REFL", "{NP}", "{NP?}", "BE"):
                tokens.append(tok)
            else:
                tokens.append(tok.lower())
        if not (2 <= len(tokens) <= 8):
            continue
        pat_str = " ".join(tokens)
        # Ensure no unexpanded parens or "or " remain
        if "(" in pat_str or ")" in pat_str or "or " in pat_str.lower():
            continue
        if pat_str not in seen:
            seen.add(pat_str)
            final_patterns.append(pat_str)
        if len(final_patterns) >= max_variants:
            break

    if not final_patterns:
        return [], "no_valid_pattern_after_expansion"
    return final_patterns, None


def convert_to_pattern(headword: str) -> str:
    """Backwards-compatible single-pattern helper for unit tests."""
    pats, _ = convert_to_patterns(headword, max_variants=8)
    if pats:
        return pats[0]
    return _replace_placeholders(re.sub(r"\([^)]*\)", "", headword)).lower().strip()


def find_anchor_lemma(
    pattern: str,
    lemma_map: dict[str, str] | None = None,
    kind: str | None = None,
    doc_freq: dict[str, int] | None = None,
) -> str:
    """Find the best anchor lemma for a pattern (Requirement A6).

    - For PHRASAL: anchor is ALWAYS the verb lemma (first non-placeholder token).
    - For IDIOM / COLLOCATION: anchor is the content word with lowest document frequency.
    """
    raw_tokens = pattern.split()
    tokens = []
    for t in raw_tokens:
        if t in ("POSS", "REFL", "{NP}", "{NP?}", "BE"):
            continue
        tl = t.lower()
        if not any(c.isalpha() for c in tl):
            continue
        lem = lemma_map.get(tl, tl) if lemma_map else tl
        tokens.append(lem)

    if not tokens:
        return raw_tokens[0].lower() if raw_tokens else ""

    if kind == "PHRASAL":
        # First verb token is the anchor (e.g. "do over" -> "do", "get off" -> "get")
        return tokens[0]

    content_tokens = [t for t in tokens if t not in STOPWORDS]
    pool = content_tokens if content_tokens else tokens

    if doc_freq:
        return min(pool, key=lambda w: (doc_freq.get(w, 0), -len(w)))

    # Without doc_freq, prefer the longest content word (ties broken by last position, e.g. "cake" in "piece of cake")
    best = pool[-1]
    for t in pool:
        if len(t) > len(best):
            best = t
    return best


def is_separable_phrasal(pattern: str, pos_codes: set[int]) -> bool:
    """Determine if a PHRASAL pattern is separable (Requirement A6).

    Separable ONLY when:
    - kind == PHRASAL
    - particle in SEPARABLE_PARTICLES
    - not a 3-word phrasal verb (unless middle token is {NP}/POSS/REFL)
    - not in INTRANSITIVE_PHRASALS and not purely vi (pos=6 without pos=5)
    """
    if pattern.lower() in INTRANSITIVE_PHRASALS:
        return False

    tokens = pattern.split()
    # Case 1: explicit slot between verb and particle, e.g. "help {NP} out"
    if len(tokens) == 3 and tokens[1] in ("{NP}", "{NP?}", "POSS", "REFL"):
        return tokens[2].lower() in SEPARABLE_PARTICLES

    # 3+ literal word phrasals ("come up with", "look forward to") are never separable
    if len(tokens) != 2:
        return False

    verb, particle = tokens[0].lower(), tokens[1].lower()
    if particle not in SEPARABLE_PARTICLES:
        return False

    # If explicitly tagged intransitive (6) and NOT transitive (5), it's inseparable
    if 6 in pos_codes and 5 not in pos_codes:
        return False

    return True


def build_lemma_forms(
    src_conn: sqlite3.Connection,
    word_id_to_word: dict[int, str],
    word_to_pos: dict[str, str],
) -> list[tuple[str, str, str | None, int]]:
    """Build deduplicated, intermediate-pruned (form, lemma, pos, rank) rows (Requirement A1)."""
    cur = src_conn.cursor()
    rows = cur.execute("""
        SELECT english_word_id, english_word,
               simple_past, past_participle, infinitive, plural,
               present_participle, third_person_singular,
               comparative, superlative, english_word_id_parent
        FROM english_words
    """).fetchall()

    # Step 1: Identify true root paradigms so copied sibling columns on inflected rows are not misattributed
    row_by_id = {r["english_word_id"]: r for r in rows}

    # Map each word_id to its true root word_id via `infinitive`
    inf_root_id: dict[int, int] = {}
    for r in rows:
        wid = r["english_word_id"]
        inf_id = r["infinitive"]
        if inf_id and inf_id in row_by_id and inf_id != wid:
            w_norm = normalize_key(r["english_word"])
            inf_norm = normalize_key(row_by_id[inf_id]["english_word"])
            if inf_norm.startswith("to "):
                inf_norm = inf_norm[3:].strip()
            if inf_norm and inf_norm != w_norm and " " not in inf_norm:
                inf_root_id[wid] = inf_id

    # Collect inflection targets owned by true root rows (rows without a distinct infinitive)
    downward_cols = [
        "simple_past", "past_participle", "present_participle",
        "third_person_singular", "plural", "comparative", "superlative",
    ]
    root_owned_targets: dict[int, set[int]] = defaultdict(set)
    target_to_true_roots: dict[int, set[int]] = defaultdict(set)

    for r in rows:
        wid = r["english_word_id"]
        if wid in inf_root_id:
            continue
        for col in downward_cols:
            tid = r[col]
            if tid and tid in row_by_id and tid != wid:
                root_owned_targets[wid].add(tid)
                target_to_true_roots[tid].add(wid)

    # Raw candidate map: form -> set of base lemmas
    raw_lemmas: dict[str, set[str]] = defaultdict(set)

    # Seed with baseline irregulars
    for form, lemmas in BASELINE_IRREGULAR_LEMMAS.items():
        for lem in lemmas:
            raw_lemmas[form].add(lem)

    for r in rows:
        wid = r["english_word_id"]
        w_norm = normalize_key(r["english_word"])
        if not w_norm or " " in w_norm:
            continue

        # Upward link 1: infinitive
        if wid in inf_root_id:
            root_w = normalize_key(row_by_id[inf_root_id[wid]]["english_word"])
            if root_w.startswith("to "):
                root_w = root_w[3:].strip()
            if root_w and " " not in root_w and root_w != w_norm:
                raw_lemmas[w_norm].add(root_w)

        # Upward link 2: english_word_id_parent
        par_id = r["english_word_id_parent"]
        if par_id and par_id in row_by_id and par_id != wid:
            par_w = normalize_key(row_by_id[par_id]["english_word"])
            if par_w and " " not in par_w and par_w != w_norm:
                raw_lemmas[w_norm].add(par_w)

        # Downward links
        for col in downward_cols:
            tid = r[col]
            if not tid or tid not in row_by_id or tid == wid:
                continue
            t_norm = normalize_key(row_by_id[tid]["english_word"])
            if not t_norm or " " in t_norm or t_norm == w_norm:
                continue

            # Check if `wid` is actually an inflected form copying a sibling target from its root
            if wid in inf_root_id:
                root_id = inf_root_id[wid]
                root_w = normalize_key(row_by_id[root_id]["english_word"])
                if root_w.startswith("to "):
                    root_w = root_w[3:].strip()
                if tid in root_owned_targets.get(root_id, set()) or not t_norm.startswith(w_norm[:3]):
                    if root_w and " " not in root_w and root_w != t_norm:
                        raw_lemmas[t_norm].add(root_w)
                    continue

            # Also check if `wid` is a target of some root B and `tid` is ALSO a target of that same root B
            sibling_roots = target_to_true_roots.get(wid, set()) & target_to_true_roots.get(tid, set())
            if sibling_roots:
                for b_id in sibling_roots:
                    b_norm = normalize_key(row_by_id[b_id]["english_word"])
                    if b_norm and " " not in b_norm and b_norm != t_norm:
                        raw_lemmas[t_norm].add(b_norm)
                continue

            # Also if `wid` is a superlative/comparative target of B and points back to comparative/superlative
            if col in ("comparative", "superlative") and wid in target_to_true_roots:
                for b_id in target_to_true_roots[wid]:
                    b_norm = normalize_key(row_by_id[b_id]["english_word"])
                    if b_norm and " " not in b_norm and b_norm != t_norm:
                        raw_lemmas[t_norm].add(b_norm)
                continue

            raw_lemmas[t_norm].add(w_norm)

    # Step 2: Prune intermediate forms!
    # If form -> mid and mid -> base (where mid != base), replace mid with base
    # unless mid is an explicit baseline lemma for form.
    baseline_lemma_set = {
        (f, l) for f, ls in BASELINE_IRREGULAR_LEMMAS.items() for l in ls
    }

    pruned_lemmas: dict[str, set[str]] = defaultdict(set)
    for form, cands in raw_lemmas.items():
        # If form is in BASELINE_IRREGULAR_LEMMAS, use the clean baseline set directly
        if form in BASELINE_IRREGULAR_LEMMAS:
            for b_lem in BASELINE_IRREGULAR_LEMMAS[form]:
                pruned_lemmas[form].add(b_lem)
            continue

        resolved = set()
        for cand in cands:
            if cand == form:
                continue
            if cand in raw_lemmas and (form, cand) not in baseline_lemma_set:
                # Follow 1-2 hops to base lemma
                deeper = {d for d in raw_lemmas[cand] if d != cand and d != form}
                if deeper:
                    resolved.update(deeper)
                else:
                    resolved.add(cand)
            else:
                resolved.add(cand)
        if resolved:
            pruned_lemmas[form] = resolved

    # Step 3: Build final rows with rank=0 for base lemmas and rank=1 for homograph self-lemmas
    result: list[tuple[str, str, str | None, int]] = []
    seen_pairs: set[tuple[str, str]] = set()

    for form in sorted(pruned_lemmas.keys()):
        lemmas = sorted(pruned_lemmas[form])
        pos = word_to_pos.get(form)
        for lem in lemmas:
            if lem == form:
                continue
            if (form, lem) not in seen_pairs:
                seen_pairs.add((form, lem))
                lem_pos = word_to_pos.get(lem) or pos
                result.append((form, lem, lem_pos, 0))

        # Add rank=1 self-lemma if `form` is a known homograph headword and not a pure participle/inflection
        if form in HOMOGRAPH_SELF_LEMMAS and form not in PURE_INFLECTION_FORMS:
            if (form, form) not in seen_pairs:
                seen_pairs.add((form, form))
                result.append((form, form, pos, 1))

    return result


def build_fa_en_pack(src_conn: sqlite3.Connection, output_dir: str, gzip_output: bool = False) -> dict:
    """Build the reverse Persian-to-English dictionary pack (sublearn-dict-fa-en-v1.sqlite)."""
    os.makedirs(output_dir, exist_ok=True)
    output_path = os.path.join(output_dir, "sublearn-dict-fa-en-v1.sqlite")
    if os.path.exists(output_path):
        os.remove(output_path)

    dst_conn = sqlite3.connect(output_path)
    dst_conn.executescript("PRAGMA journal_mode=OFF; PRAGMA synchronous=OFF;")
    dst_conn.executescript(V2_SCHEMA_SQL)

    cur = src_conn.cursor()

    # Categories
    cat_map: dict[int, tuple[str, str]] = dict(CATEGORIES_FALLBACK)
    try:
        for r in cur.execute("SELECT id, english_category_name, persian_category_name FROM category_names"):
            cat_map[r["id"]] = (r["english_category_name"] or "", r["persian_category_name"] or "")
    except sqlite3.OperationalError:
        pass

    p_words = cur.execute("SELECT * FROM persian_words").fetchall()
    p_details = cur.execute("SELECT * FROM persian_details").fetchall()

    details_by_wid: dict[int, list] = defaultdict(list)
    for d in p_details:
        details_by_wid[d["persian_word_id"]].append(d)

    pos_by_did: dict[int, list[int]] = defaultdict(list)
    try:
        for r in cur.execute("SELECT persian_detail_id, pos FROM persian_pos"):
            if r["pos"] is not None:
                pos_by_did[r["persian_detail_id"]].append(r["pos"])
    except sqlite3.OperationalError:
        pass

    cat_by_did: dict[int, list[int]] = defaultdict(list)
    try:
        for r in cur.execute("SELECT persian_detail_id, category FROM persian_categories"):
            if r["category"] is not None:
                cat_by_did[r["persian_detail_id"]].append(r["category"])
    except sqlite3.OperationalError:
        pass

    sents_by_did: dict[int, list] = defaultdict(list)
    try:
        for r in cur.execute("SELECT * FROM persian_sentences"):
            sents_by_did[r["persian_detail_id"]].append(r)
    except sqlite3.OperationalError:
        pass

    syn_by_wid: dict[int, list[str]] = defaultdict(list)
    try:
        for r in cur.execute("SELECT persian_word_id, synonym_antonym FROM persian_synonyms_antonyms"):
            if r["synonym_antonym"]:
                syn_by_wid[r["persian_word_id"]].append(r["synonym_antonym"].strip())
    except sqlite3.OperationalError:
        pass

    entries_by_key: dict[str, list] = defaultdict(list)
    for pw in p_words:
        key = normalize_persian_key(pw["persian_word"])
        if key:
            entries_by_key[key].append(pw)

    entry_id = 0
    sense_id = 0
    example_id = 0

    for key, pw_list in entries_by_key.items():
        all_details = []
        for pw in pw_list:
            all_details.extend(details_by_wid.get(pw["persian_word_id"], []))
        valid_details = [
            d for d in all_details
            if (d["english_meaning"] and d["english_meaning"].strip())
            or (d["persian_meaning"] and d["persian_meaning"].strip())
        ]
        if not valid_details:
            continue

        entry_id += 1
        headword = pw_list[0]["persian_word"].strip()
        kind = "PHRASE" if " " in key else "WORD"
        first_did = valid_details[0]["persian_detail_id"]
        first_pos_codes = pos_by_did.get(first_did, [])
        entry_pos = POS_MAP.get(first_pos_codes[0]) if first_pos_codes else None

        dst_conn.execute(
            "INSERT INTO entry(id, key, headword, kind, pos, cefr, register, prior) VALUES (?, ?, ?, ?, ?, NULL, NULL, 0.5)",
            (entry_id, key, headword, kind, entry_pos),
        )

        phonetic = next((d["persian_phonetic"] for d in valid_details if d["persian_phonetic"]), None)
        if phonetic:
            dst_conn.execute(
                "INSERT OR REPLACE INTO pron(entry_id, ipa_us, ipa_uk, audio_us, audio_uk) VALUES (?, ?, NULL, NULL, NULL)",
                (entry_id, phonetic),
            )

        syns = []
        for pw in pw_list:
            syns.extend(syn_by_wid.get(pw["persian_word_id"], []))
        if syns:
            syn_str = " | ".join(dict.fromkeys(syns[:20]))
            dst_conn.execute(
                "INSERT OR REPLACE INTO entry_extra(entry_id, synonyms, antonyms, family, word_family, notes) VALUES (?, ?, NULL, NULL, NULL, NULL)",
                (entry_id, syn_str),
            )

        for idx, d in enumerate(valid_details):
            sense_id += 1
            did = d["persian_detail_id"]
            d_pos_codes = pos_by_did.get(did, [])
            s_pos = POS_MAP.get(d_pos_codes[0]) if d_pos_codes else entry_pos
            cats = cat_by_did.get(did, [])
            dom_en, dom_fa = (cat_map.get(cats[0], (None, None)) if cats else (None, None))
            gloss_en = (d["english_meaning"] or "").strip()
            gloss_fa = (d["persian_meaning"] or gloss_en).strip()
            target_gloss = gloss_en or gloss_fa

            dst_conn.execute(
                """INSERT INTO sense(
                    id, entry_id, pos, gloss, gloss_fa, gloss_en, domain, domain_fa, domain_en,
                    register, cefr, is_primary, prior, position
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?, 0.5, ?)""",
                (
                    sense_id, entry_id, s_pos, target_gloss, gloss_fa, gloss_en or None,
                    dom_fa or dom_en, dom_fa, dom_en,
                    1 if idx == 0 else 0, d["position"] or idx,
                ),
            )
            seen_ex = set()
            for s in sents_by_did.get(did, []):
                en_s = (s["english_sentence"] or "").strip()
                fa_s = (s["persian_sentence"] or "").strip()
                if (en_s or fa_s) and en_s not in seen_ex:
                    seen_ex.add(en_s)
                    example_id += 1
                    dst_conn.execute(
                        "INSERT OR IGNORE INTO example(id, sense_id, en, fa) VALUES (?, ?, ?, ?)",
                        (example_id, sense_id, en_s or None, fa_s or None),
                    )

    dst_conn.execute("INSERT INTO entry_fts(rowid, key, headword) SELECT id, key, headword FROM entry")
    now = datetime.datetime.now(datetime.timezone.utc).isoformat()
    meta_rows = [
        ("schema_version", "2"),
        ("format_version", "2"),
        ("pack_id", "dict-fa-en-v1"),
        ("direction", "fa-en"),
        ("src_lang", "fa"),
        ("source_lang", "fa"),
        ("gloss_lang", "en"),
        ("entry_count", str(entry_id)),
        ("sense_count", str(sense_id)),
        ("pattern_count", "0"),
        ("lemma_count", "0"),
        ("built_at", now),
        ("builder_version", BUILDER_VERSION),
    ]
    dst_conn.executemany("INSERT OR REPLACE INTO meta(key, value) VALUES (?, ?)", meta_rows)
    dst_conn.commit()
    dst_conn.execute("VACUUM")
    dst_conn.close()

    sha256 = _compute_sha256(output_path)
    size_bytes = os.path.getsize(output_path)
    gz_path = None
    gz_sha256 = None
    gz_size = None
    if gzip_output:
        gz_path = output_path + ".gz"
        with open(output_path, "rb") as f_in, gzip.open(gz_path, "wb", compresslevel=9) as f_out:
            shutil.copyfileobj(f_in, f_out)
        gz_sha256 = _compute_sha256(gz_path)
        gz_size = os.path.getsize(gz_path)

    return {
        "builder_version": BUILDER_VERSION,
        "direction": "fa-en",
        "built_at": now,
        "output_file": output_path,
        "output_size_bytes": size_bytes,
        "output_sha256": sha256,
        "gzip_file": gz_path,
        "gzip_size_bytes": gz_size,
        "gzip_sha256": gz_sha256,
        "stats": {
            "entries_created": entry_id,
            "senses_created": sense_id,
            "examples_created": example_id,
        },
    }


def _compute_sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def build_lexicon(
    input_path: str,
    output_dir: str,
    direction: str = "en-fa",
    gzip_output: bool = False,
    export_test_json_dir: str | None = None,
) -> dict:
    """Convert the source SQLite database into a SubLearn v2 lexicon pack."""
    if not os.path.exists(input_path):
        raise FileNotFoundError(f"Source database not found: {input_path}")

    src_conn = sqlite3.connect(input_path)
    src_conn.row_factory = sqlite3.Row

    if direction == "fa-en":
        res = build_fa_en_pack(src_conn, output_dir, gzip_output=gzip_output)
        src_conn.close()
        return res

    os.makedirs(output_dir, exist_ok=True)
    output_path = os.path.join(output_dir, "sublearn-lexicon-en-fa-v2.sqlite")
    if os.path.exists(output_path):
        os.remove(output_path)

    dst_conn = sqlite3.connect(output_path)
    dst_conn.executescript("PRAGMA journal_mode=OFF; PRAGMA synchronous=OFF;")
    dst_conn.executescript(V2_SCHEMA_SQL)

    cur = src_conn.cursor()

    stats = {
        "source_words": 0,
        "entries_created": 0,
        "word_entries": 0,
        "phrasal_entries": 0,
        "idiom_entries": 0,
        "collocation_entries": 0,
        "phrase_entries": 0,
        "senses_created": 0,
        "examples_created": 0,
        "patterns_created": 0,
        "separable_patterns": 0,
        "lemma_forms_created": 0,
        "word_pos_created": 0,
        "pron_created": 0,
        "entry_extra_created": 0,
        "colloc_created": 0,
        "rejected_patterns": 0,
    }
    rejected_samples = []

    # 0. Load category_names from DB (all 82 categories) + fallback
    cat_map: dict[int, tuple[str, str]] = dict(CATEGORIES_FALLBACK)
    try:
        for r in cur.execute("SELECT id, english_category_name, persian_category_name FROM category_names"):
            cat_map[r["id"]] = (
                (r["english_category_name"] or "").strip(),
                (r["persian_category_name"] or "").strip(),
            )
    except sqlite3.OperationalError:
        pass

    # 1. Load english_words, english_details, english_pos, english_categories, english_sentences
    words = cur.execute("SELECT * FROM english_words").fetchall()
    stats["source_words"] = len(words)
    word_id_to_word = {r["english_word_id"]: r["english_word"] for r in words}

    details = cur.execute("SELECT * FROM english_details ORDER BY position, english_detail_id").fetchall()
    details_by_wid: dict[int, list] = defaultdict(list)
    for d in details:
        details_by_wid[d["english_word_id"]].append(d)

    pos_by_did: dict[int, list[int]] = defaultdict(list)
    for r in cur.execute("SELECT english_detail_id, pos FROM english_pos"):
        if r["pos"] is not None:
            pos_by_did[r["english_detail_id"]].append(int(r["pos"]))

    cat_by_did: dict[int, list[int]] = defaultdict(list)
    for r in cur.execute("SELECT english_detail_id, category FROM english_categories"):
        if r["category"] is not None:
            cat_by_did[r["english_detail_id"]].append(int(r["category"]))

    sents_by_did: dict[int, list] = defaultdict(list)
    for r in cur.execute("SELECT * FROM english_sentences ORDER BY position, english_sentence_id"):
        sents_by_did[r["english_detail_id"]].append(r)

    # Load synonyms, antonyms, and English definitions (gloss_en)
    syn_ant_by_wid: dict[int, list] = defaultdict(list)
    try:
        for r in cur.execute("SELECT * FROM english_synonyms_antonyms ORDER BY position, english_synonyms_antonyms_id"):
            syn_ant_by_wid[r["english_word_id"]].append(r)
    except sqlite3.OperationalError:
        pass

    # Load word families properly: group words by english_word_family_id
    family_groups: dict[int, list[str]] = defaultdict(list)
    word_to_family_ids: dict[str, list[int]] = defaultdict(list)
    try:
        for r in cur.execute("SELECT english_word_family_id, word FROM english_word_families_words"):
            fid = r["english_word_family_id"]
            w_str = (r["word"] or "").strip()
            if fid is not None and w_str:
                family_groups[fid].append(w_str)
                word_to_family_ids[normalize_key(w_str)].append(fid)
    except sqlite3.OperationalError:
        pass

    # Load pronunciations (US and UK)
    us_pron_by_wid: dict[int, tuple[str | None, str | None]] = {}
    uk_pron_by_wid: dict[int, tuple[str | None, str | None]] = {}
    try:
        for r in cur.execute("SELECT english_word_id, phonetic, filename FROM english_american_audios"):
            wid = r["english_word_id"]
            if wid not in us_pron_by_wid:
                us_pron_by_wid[wid] = ((r["phonetic"] or "").strip() or None, (r["filename"] or "").strip() or None)
    except sqlite3.OperationalError:
        pass
    try:
        for r in cur.execute("SELECT english_word_id, phonetic, filename FROM english_british_audios"):
            wid = r["english_word_id"]
            if wid not in uk_pron_by_wid:
                uk_pron_by_wid[wid] = ((r["phonetic"] or "").strip() or None, (r["filename"] or "").strip() or None)
    except sqlite3.OperationalError:
        pass

    # Load english_idioms join table: parent english_word_id -> english_idiom_word_id
    idiom_word_ids: set[int] = set()
    idiom_links: list[tuple[int, int, int]] = []
    try:
        for r in cur.execute("SELECT english_word_id, english_idiom_word_id, position FROM english_idioms"):
            p_wid = r["english_word_id"]
            i_wid = r["english_idiom_word_id"]
            pos_idx = r["position"] or 0
            if i_wid is not None:
                idiom_word_ids.add(int(i_wid))
                if p_wid is not None:
                    idiom_links.append((int(p_wid), int(i_wid), int(pos_idx)))
    except sqlite3.OperationalError:
        pass

    # Build word_pos frequencies for single words
    word_pos_counter: dict[tuple[str, str], int] = Counter()
    for w, (p_tag, freq) in CLOSED_CLASS_POS.items():
        word_pos_counter[(w, p_tag)] += freq

    for w_row in words:
        wid = w_row["english_word_id"]
        key = normalize_key(w_row["english_word"])
        if not key or " " in key:
            continue
        for d in details_by_wid.get(wid, []):
            for p_code in pos_by_did.get(d["english_detail_id"], []):
                mapped = POS_MAP.get(p_code)
                if mapped in ("NOUN", "VERB", "ADJ", "ADV", "PRON", "PREP", "CONJ", "DET", "INTJ", "PART", "NUM"):
                    word_pos_counter[(key, mapped)] += 2
        for sa in syn_ant_by_wid.get(wid, []):
            sa_pos = SYN_POS_MAP.get((sa["pos"] or "").strip().lower())
            if sa_pos:
                word_pos_counter[(key, sa_pos)] += 1

    # Primary POS per word
    word_to_primary_pos: dict[str, str] = {}
    by_word_tags: dict[str, list[tuple[str, int]]] = defaultdict(list)
    for (w, p_tag), freq in word_pos_counter.items():
        by_word_tags[w].append((p_tag, freq))
    for w, tag_list in by_word_tags.items():
        tag_list.sort(key=lambda x: -x[1])
        word_to_primary_pos[w] = tag_list[0][0]

    # Insert word_pos rows with both rank (0, 1, ...) and freq
    word_pos_rows = []
    for w, tag_list in sorted(by_word_tags.items()):
        for rank_idx, (p_tag, freq) in enumerate(tag_list):
            word_pos_rows.append((w, p_tag, rank_idx, freq))
    dst_conn.executemany(
        "INSERT OR REPLACE INTO word_pos(word, pos, rank, freq) VALUES (?, ?, ?, ?)",
        word_pos_rows,
    )
    stats["word_pos_created"] = len(word_pos_rows)

    # 2. Build lemma_form table (Requirement A1)
    lemma_rows = build_lemma_forms(src_conn, word_id_to_word, word_to_primary_pos)
    dst_conn.executemany(
        "INSERT OR REPLACE INTO lemma_form(form, lemma, pos, rank) VALUES (?, ?, ?, ?)",
        lemma_rows,
    )
    stats["lemma_forms_created"] = len(lemma_rows)

    # Primary lemma map (rank=0) for anchor selection
    lemma_map: dict[str, str] = {}
    for form, lem, _, rank in lemma_rows:
        if rank == 0 and form not in lemma_map:
            lemma_map[form] = lem

    # Known verbs set for classifying unlabelled verb+particle phrasal verbs
    known_verbs = set(COMMON_PHRASAL_VERBS)
    for (w, p_tag) in word_pos_counter:
        if p_tag == "VERB":
            known_verbs.add(w)

    # 3. Group english_words by normalized key so each key produces at most ONE entry (Requirement A3)
    grouped_words: dict[str, list] = defaultdict(list)
    for w_row in words:
        key = normalize_key(w_row["english_word"])
        if key:
            grouped_words[key].append(w_row)

    # Helper to classify entry kind & prior (Requirement A5)
    def classify_entry(key: str, w_rows: list, all_pos_codes: set[int]) -> tuple[str, float]:
        is_multi = " " in key
        in_idioms = any(r["english_word_id"] in idiom_word_ids for r in w_rows)

        if not is_multi and not in_idioms:
            return "WORD", 0.50

        # Priority order: IDIOM > PHRASAL > COLLOCATION > PHRASE > WORD
        if all_pos_codes & {18, 26, 34}:
            return "IDIOM", 0.85
        if 20 in all_pos_codes:
            return "PHRASAL", 0.90
        if 19 in all_pos_codes:
            return "COLLOCATION", 0.70
        if all_pos_codes & {25, 30}:
            # Check if it's actually a verb + particle phrasal verb even if tagged 25
            tokens = key.split()
            first_lem = lemma_map.get(tokens[0], tokens[0]) if tokens else ""
            if (
                2 <= len(tokens) <= 3
                and first_lem in known_verbs
                and first_lem not in NON_VERB_FIRST_WORDS
                and tokens[1] in PHRASAL_PARTICLES_AND_PREPS
            ):
                return "PHRASAL", 0.80
            return "PHRASE", 0.30

        # Unlabelled multi-word or in_idioms entry
        tokens = [t for t in re.sub(r"\([^)]*\)", "", key).split() if t]
        first_lem = lemma_map.get(tokens[0], tokens[0]) if tokens else ""
        is_verb_particle = (
            2 <= len(tokens) <= 3
            and first_lem in known_verbs
            and first_lem not in NON_VERB_FIRST_WORDS
            and tokens[1] in PHRASAL_PARTICLES_AND_PREPS
            and (len(tokens) == 2 or tokens[2] in PHRASAL_PARTICLES_AND_PREPS)
        )

        if is_verb_particle:
            return "PHRASAL", 0.80

        if in_idioms:
            # Check if figurative idiom structure (>=3 tokens with idiom markers) vs collocation
            if len(tokens) >= 3 and (
                "one's" in key or "someone" in key or "something" in key
                or "(or " in w_rows[0]["english_word"].lower()
                or any(t in ("like", "as", "the", "a", "an") for t in tokens[1:])
            ):
                return "IDIOM", 0.75
            return "COLLOCATION", 0.50

        # Not in english_idioms and no MWE pos code
        if len(tokens) <= 3 and (all_pos_codes & {1, 2, 3, 4, 7, 8, 31, 32}):
            return "COLLOCATION", 0.50

        # Free-usage example or long unlabelled phrase -> PHRASE with low prior <= 0.40 (no pattern)
        return "PHRASE", 0.35

    # Compute document frequency of content lemmas across multi-word keys for anchor selection
    doc_freq: dict[str, int] = Counter()
    for key in grouped_words:
        if " " in key:
            for tok in set(key.split()):
                lem = lemma_map.get(tok, tok)
                doc_freq[lem] += 1

    # 4. Insert entries, senses, examples, pronunciations, extras, and patterns
    entry_id = 0
    sense_id = 0
    example_id = 0
    pattern_id = 0
    wid_to_entry_id: dict[int, int] = {}

    cefr_order = {"A1": 1, "A2": 2, "B1": 3, "B2": 4, "C1": 5, "C2": 6}

    for key, w_rows in grouped_words.items():
        # Collect all details across w_rows
        all_details = []
        seen_detail_ids = set()
        for r in w_rows:
            for d in details_by_wid.get(r["english_word_id"], []):
                did = d["english_detail_id"]
                if did not in seen_detail_ids:
                    seen_detail_ids.add(did)
                    all_details.append(d)

        valid_details = [
            d for d in all_details
            if (d["persian_meaning"] and d["persian_meaning"].strip())
        ]
        if not valid_details:
            continue

        all_pos_codes: set[int] = set()
        for d in valid_details:
            all_pos_codes.update(pos_by_did.get(d["english_detail_id"], []))

        kind, entry_prior = classify_entry(key, w_rows, all_pos_codes)

        # Pick best headword (prefer one with parentheses if it's an idiom/phrasal so pattern expansion sees "(or ...)")
        headword = w_rows[0]["english_word"].strip()
        for r in w_rows:
            hw = r["english_word"].strip()
            if "(" in hw and "(" not in headword:
                headword = hw

        # Entry-level POS, CEFR, register
        entry_pos = None
        if kind in ("WORD", "COLLOCATION", "PHRASE"):
            for d in valid_details:
                for pc in pos_by_did.get(d["english_detail_id"], []):
                    mapped = POS_MAP.get(pc)
                    if mapped in ("NOUN", "VERB", "ADJ", "ADV", "PRON", "PREP", "CONJ", "DET", "INTJ", "PART", "NUM"):
                        entry_pos = mapped
                        break
                if entry_pos:
                    break
            if not entry_pos and kind == "WORD":
                entry_pos = word_to_primary_pos.get(key)
        elif kind == "PHRASAL":
            entry_pos = "VERB"

        entry_cefr = None
        for d in valid_details:
            c = (d["cefr"] or "").strip().upper()
            if c in cefr_order:
                if entry_cefr is None or cefr_order[c] < cefr_order[entry_cefr]:
                    entry_cefr = c

        # Entry register from first detail that has one
        entry_register = None

        entry_id += 1
        for r in w_rows:
            wid_to_entry_id[r["english_word_id"]] = entry_id

        # Collect English definitions (gloss_en), synonyms, antonyms from english_synonyms_antonyms
        all_syn_rows = []
        for r in w_rows:
            all_syn_rows.extend(syn_ant_by_wid.get(r["english_word_id"], []))

        defs_en = [
            (sa["definitions"] or "").strip()
            for sa in all_syn_rows
            if (sa["definitions"] or "").strip()
        ]
        syns_list = []
        ants_list = []
        for sa in all_syn_rows:
            if sa["synonyms"]:
                for item in sa["synonyms"].split(","):
                    it = item.strip()
                    if it and it.lower() != key and it not in syns_list:
                        syns_list.append(it)
            if sa["antonyms"]:
                for item in sa["antonyms"].split(","):
                    it = item.strip()
                    if it and it.lower() != key and it not in ants_list:
                        ants_list.append(it)

        # Insert senses & examples
        has_explicit_primary = any(d["most_common"] == 1 for d in valid_details)

        for s_idx, d in enumerate(valid_details):
            did = d["english_detail_id"]
            gloss_fa = d["persian_meaning"].strip()
            d_pos_codes = pos_by_did.get(did, [])
            s_pos = None
            for pc in d_pos_codes:
                mapped = POS_MAP.get(pc)
                if mapped in ("NOUN", "VERB", "ADJ", "ADV", "PRON", "PREP", "CONJ", "DET", "INTJ", "PART", "NUM"):
                    s_pos = mapped
                    break
            if not s_pos:
                s_pos = entry_pos

            # Categories -> domain_en, domain_fa
            d_cats = cat_by_did.get(did, [])
            dom_en_parts = []
            dom_fa_parts = []
            for cid in d_cats:
                c_en, c_fa = cat_map.get(cid, ("", ""))
                if c_en and c_en not in dom_en_parts:
                    dom_en_parts.append(c_en)
                if c_fa and c_fa not in dom_fa_parts:
                    dom_fa_parts.append(c_fa)
            domain_en = "، ".join(dom_en_parts) if dom_en_parts else None
            domain_fa = "، ".join(dom_fa_parts) if dom_fa_parts else None
            domain = domain_fa or domain_en

            # Register (0=informal/british, 1=formal/american)
            reg_parts = []
            fi = d["formal_informal"]
            if fi is not None:
                if int(fi) == 1:
                    reg_parts.append("formal")
                elif int(fi) == 0:
                    reg_parts.append("informal")
            ba = d["british_american"]
            if ba is not None:
                if int(ba) == 0:
                    reg_parts.append("british")
                elif int(ba) == 1:
                    reg_parts.append("american")
            s_register = ",".join(reg_parts) if reg_parts else None
            if s_register and not entry_register:
                entry_register = s_register

            s_cefr = (d["cefr"] or "").strip().upper()
            if s_cefr not in cefr_order:
                s_cefr = entry_cefr

            is_primary = 1 if (d["most_common"] == 1 or (not has_explicit_primary and s_idx == 0)) else 0
            s_prior = min(1.0, entry_prior + (0.10 if is_primary else 0.0))
            gloss_en = defs_en[s_idx] if s_idx < len(defs_en) else (defs_en[0] if defs_en and s_idx == 0 else None)

            sense_id += 1
            stats["senses_created"] += 1
            dst_conn.execute(
                """INSERT INTO sense(
                    id, entry_id, pos, gloss, gloss_fa, gloss_en, domain, domain_fa, domain_en,
                    register, cefr, is_primary, prior, position
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                (
                    sense_id, entry_id, s_pos, gloss_fa, gloss_fa, gloss_en,
                    domain, domain_fa, domain_en,
                    s_register, s_cefr, is_primary, s_prior,
                    d["position"] if d["position"] is not None else s_idx,
                ),
            )

            # Examples (deduplicated per sense via UNIQUE(sense_id, en))
            seen_ex = set()
            for sent in sents_by_did.get(did, []):
                en_s = (sent["english_sentence"] or "").strip()
                fa_s = (sent["persian_sentence"] or "").strip()
                if not en_s and not fa_s:
                    continue
                dedup_key = en_s.lower() if en_s else fa_s
                if dedup_key in seen_ex:
                    continue
                seen_ex.add(dedup_key)
                example_id += 1
                stats["examples_created"] += 1
                dst_conn.execute(
                    "INSERT OR IGNORE INTO example(id, sense_id, en, fa) VALUES (?, ?, ?, ?)",
                    (example_id, sense_id, en_s or None, fa_s or None),
                )

        # Insert entry row
        dst_conn.execute(
            """INSERT INTO entry(id, key, headword, kind, pos, cefr, register, prior)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
            (entry_id, key, headword, kind, entry_pos, entry_cefr, entry_register, entry_prior),
        )
        stats["entries_created"] += 1
        if kind == "WORD":
            stats["word_entries"] += 1
        elif kind == "PHRASAL":
            stats["phrasal_entries"] += 1
        elif kind == "IDIOM":
            stats["idiom_entries"] += 1
        elif kind == "COLLOCATION":
            stats["collocation_entries"] += 1
        else:
            stats["phrase_entries"] += 1

        # Insert pron row if US or UK pronunciation exists
        ipa_us, audio_us, ipa_uk, audio_uk = None, None, None, None
        for r in w_rows:
            wid = r["english_word_id"]
            if wid in us_pron_by_wid and not ipa_us:
                ipa_us, audio_us = us_pron_by_wid[wid]
            if wid in uk_pron_by_wid and not ipa_uk:
                ipa_uk, audio_uk = uk_pron_by_wid[wid]
        if ipa_us or ipa_uk or audio_us or audio_uk:
            dst_conn.execute(
                "INSERT OR REPLACE INTO pron(entry_id, ipa_us, ipa_uk, audio_us, audio_uk) VALUES (?, ?, ?, ?, ?)",
                (entry_id, ipa_us, ipa_uk, audio_us, audio_uk),
            )
            stats["pron_created"] += 1

        # Insert entry_extra row (synonyms, antonyms, word_family, notes)
        wf_words = []
        for fid in word_to_family_ids.get(key, []):
            for fw in family_groups.get(fid, []):
                if normalize_key(fw) != key and fw not in wf_words:
                    wf_words.append(fw)
        notes = None
        for r in w_rows:
            if r["word_description_html"]:
                notes = strip_html(r["word_description_html"])
                if notes:
                    break
        if syns_list or ants_list or wf_words or notes:
            wf_str = " | ".join(wf_words[:20]) if wf_words else None
            dst_conn.execute(
                """INSERT OR REPLACE INTO entry_extra(entry_id, synonyms, antonyms, family, word_family, notes)
                   VALUES (?, ?, ?, ?, ?, ?)""",
                (
                    entry_id,
                    " | ".join(syns_list[:20]) if syns_list else None,
                    " | ".join(ants_list[:20]) if ants_list else None,
                    wf_str,
                    wf_str,
                    notes,
                ),
            )
            stats["entry_extra_created"] += 1

        # Generate patterns for PHRASAL, IDIOM, COLLOCATION (with prior > 0.40)
        if kind in ("PHRASAL", "IDIOM", "COLLOCATION") and entry_prior > 0.40:
            pats, rej_reason = convert_to_patterns(headword, max_variants=8)
            if not pats:
                stats["rejected_patterns"] += 1
                if len(rejected_samples) < 50:
                    rejected_samples.append({"headword": headword, "reason": rej_reason or "unknown"})
            else:
                for pat in pats:
                    anchor = find_anchor_lemma(pat, lemma_map=lemma_map, kind=kind, doc_freq=doc_freq)
                    sep = 1 if (kind == "PHRASAL" and is_separable_phrasal(pat, all_pos_codes)) else 0
                    tok_count = len(pat.split())
                    min_tok = tok_count
                    max_tok = tok_count + (3 if sep else (2 if "{NP}" in pat else 0))
                    pattern_id += 1
                    stats["patterns_created"] += 1
                    if sep:
                        stats["separable_patterns"] += 1
                    dst_conn.execute(
                        """INSERT INTO pattern(id, entry_id, pattern, anchor_lemma, separable, min_tokens, max_tokens, prior)
                           VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                        (pattern_id, entry_id, pat, anchor, sep, min_tok, max_tok, entry_prior),
                    )

    # 5. Populate colloc table from english_idioms links
    seen_colloc = set()
    for p_wid, i_wid, pos_idx in idiom_links:
        p_eid = wid_to_entry_id.get(p_wid)
        i_eid = wid_to_entry_id.get(i_wid)
        if p_eid and i_eid and p_eid != i_eid and (p_eid, i_eid) not in seen_colloc:
            seen_colloc.add((p_eid, i_eid))
            dst_conn.execute(
                "INSERT OR IGNORE INTO colloc(entry_id, colloc_entry_id, position) VALUES (?, ?, ?)",
                (p_eid, i_eid, pos_idx),
            )
            stats["colloc_created"] += 1

    # 6. Populate entry_fts
    dst_conn.execute("INSERT INTO entry_fts(rowid, key, headword) SELECT id, key, headword FROM entry")

    # 7. Populate meta
    now = datetime.datetime.now(datetime.timezone.utc).isoformat()
    src_sha256 = _compute_sha256(input_path)
    meta_rows = [
        ("schema_version", "2"),
        ("format_version", "2"),
        ("pack_id", "lexicon-en-fa-v2"),
        ("direction", "en-fa"),
        ("src_lang", "en"),
        ("source_lang", "en"),
        ("gloss_lang", "fa"),
        ("entry_count", str(stats["entries_created"])),
        ("sense_count", str(stats["senses_created"])),
        ("pattern_count", str(stats["patterns_created"])),
        ("lemma_count", str(stats["lemma_forms_created"])),
        ("built_at", now),
        ("builder_version", BUILDER_VERSION),
        ("source_db_sha256", src_sha256),
    ]
    dst_conn.executemany("INSERT OR REPLACE INTO meta(key, value) VALUES (?, ?)", meta_rows)

    dst_conn.commit()
    dst_conn.execute("VACUUM")

    # Optional: export test JSON for pure-JVM Kotlin tests
    if export_test_json_dir:
        _export_test_json(dst_conn, export_test_json_dir)

    dst_conn.close()
    src_conn.close()

    output_sha256 = _compute_sha256(output_path)
    output_size = os.path.getsize(output_path)

    gz_path = None
    gz_sha256 = None
    gz_size = None
    if gzip_output:
        gz_path = output_path + ".gz"
        with open(output_path, "rb") as f_in, gzip.open(gz_path, "wb", compresslevel=9) as f_out:
            shutil.copyfileobj(f_in, f_out)
        gz_sha256 = _compute_sha256(gz_path)
        gz_size = os.path.getsize(gz_path)

    return {
        "builder_version": BUILDER_VERSION,
        "direction": "en-fa",
        "built_at": now,
        "input_file": os.path.basename(input_path),
        "input_sha256": src_sha256,
        "output_file": output_path,
        "output_size_bytes": output_size,
        "output_sha256": output_sha256,
        "gzip_file": gz_path,
        "gzip_size_bytes": gz_size,
        "gzip_sha256": gz_sha256,
        "stats": stats,
        "rejected_samples": rejected_samples,
    }


def _export_test_json(conn: sqlite3.Connection, out_dir: str) -> None:
    os.makedirs(out_dir, exist_ok=True)
    cur = conn.cursor()

    lemmas = [
        {"form": r[0], "lemma": r[1], "pos": r[2], "rank": r[3]}
        for r in cur.execute("SELECT form, lemma, pos, rank FROM lemma_form").fetchall()
    ]
    with open(os.path.join(out_dir, "lemma_forms.json"), "w", encoding="utf-8") as f:
        json.dump(lemmas, f, ensure_ascii=False)

    entries = [
        {
            "id": r[0], "key": r[1], "headword": r[2], "kind": r[3],
            "pos": r[4], "cefr": r[5], "register": r[6], "prior": r[7],
        }
        for r in cur.execute("SELECT id, key, headword, kind, pos, cefr, register, prior FROM entry").fetchall()
    ]
    with open(os.path.join(out_dir, "entries.json"), "w", encoding="utf-8") as f:
        json.dump(entries, f, ensure_ascii=False)

    patterns = [
        {
            "id": r[0], "entryId": r[1], "pattern": r[2], "anchorLemma": r[3],
            "separable": bool(r[4]), "minTokens": r[5], "maxTokens": r[6],
        }
        for r in cur.execute(
            "SELECT id, entry_id, pattern, anchor_lemma, separable, min_tokens, max_tokens FROM pattern"
        ).fetchall()
    ]
    with open(os.path.join(out_dir, "patterns.json"), "w", encoding="utf-8") as f:
        json.dump(patterns, f, ensure_ascii=False)

    senses = [
        {
            "id": r[0], "entryId": r[1], "pos": r[2], "glossFa": r[3], "glossEn": r[4],
            "domainFa": r[5], "domainEn": r[6], "register": r[7], "cefr": r[8],
            "isPrimary": bool(r[9]), "prior": r[10], "position": r[11],
        }
        for r in cur.execute(
            """SELECT id, entry_id, pos, gloss_fa, gloss_en, domain_fa, domain_en,
                      register, cefr, is_primary, prior, position FROM sense"""
        ).fetchall()
    ]
    with open(os.path.join(out_dir, "senses.json"), "w", encoding="utf-8") as f:
        json.dump(senses, f, ensure_ascii=False)

    word_pos = [
        {"word": r[0], "pos": r[1], "freq": r[2]}
        for r in cur.execute("SELECT word, pos, freq FROM word_pos").fetchall()
    ]
    with open(os.path.join(out_dir, "word_pos.json"), "w", encoding="utf-8") as f:
        json.dump(word_pos, f, ensure_ascii=False)


def main():
    parser = argparse.ArgumentParser(description="Build SubLearn Lexicon Pack v2")
    parser.add_argument("--input", required=True, help="Path to source SQLite database (main_db.sqlite)")
    parser.add_argument("--output-dir", default=".", help="Directory for output files")
    parser.add_argument("--output", default=None, help="Optional explicit path for the output .sqlite file")
    parser.add_argument("--report", default=None, help="Optional explicit path for the JSON build report")
    parser.add_argument("--direction", choices=["en-fa", "fa-en"], default="en-fa", help="Pack direction")
    parser.add_argument("--gzip", action="store_true", help="Also write .sqlite.gz compressed pack")
    parser.add_argument("--export-test-json", default=None, help="Directory to export JSON fixtures for JVM tests")
    args = parser.parse_args()

    try:
        out_dir = os.path.dirname(os.path.abspath(args.output)) if args.output else args.output_dir
        report = build_lexicon(
            args.input,
            out_dir,
            direction=args.direction,
            gzip_output=args.gzip,
            export_test_json_dir=args.export_test_json,
        )
        if args.output and os.path.abspath(report["output_file"]) != os.path.abspath(args.output):
            shutil.copyfile(report["output_file"], args.output)
            if args.gzip and report.get("gzip_file"):
                shutil.copyfile(report["gzip_file"], args.output + ".gz")

        report_name = "build_report.json" if args.direction == "en-fa" else "build_report_fa_en.json"
        report_path = args.report or os.path.join(out_dir, report_name)
        with open(report_path, "w", encoding="utf-8") as f:
            json.dump(report, f, ensure_ascii=False, indent=2)

        print(f"Build complete ({args.direction}):")
        print(f"  Output: {report['output_file']} ({report['output_size_bytes']:,} bytes)")
        print(f"  SHA-256: {report['output_sha256']}")
        for k, v in report["stats"].items():
            print(f"  {k}: {v:,}")
    except Exception as e:
        print(f"Error: {e}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
