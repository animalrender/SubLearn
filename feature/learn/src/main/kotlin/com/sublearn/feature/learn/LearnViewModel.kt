package com.sublearn.feature.learn

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sublearn.core.data.InstalledPackEntity
import com.sublearn.core.data.InstalledPackRepository
import com.sublearn.core.data.MarkedWord
import com.sublearn.core.data.MyWordsRepository
import com.sublearn.core.data.WordStatus
import com.sublearn.core.lexicon.DictionaryEntry
import com.sublearn.core.lexicon.DictionaryProvider
import com.sublearn.core.settings.CefrLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LearnViewModel(
    installedPacksRepo: InstalledPackRepository,
    private val dictionaryProvider: DictionaryProvider,
    private val myWords: MyWordsRepository,
) : ViewModel() {
    val installedPacks: StateFlow<List<InstalledPackEntity>> = installedPacksRepo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Dictionary search state (D-55, D-58)
    private val _dictQuery = MutableStateFlow("")
    val dictQuery: StateFlow<String> = _dictQuery.asStateFlow()

    private val _dictDirection = MutableStateFlow("en-fa")
    val dictDirection: StateFlow<String> = _dictDirection.asStateFlow()

    private val _dictEntry = MutableStateFlow<DictionaryEntry?>(null)
    val dictEntry: StateFlow<DictionaryEntry?> = _dictEntry.asStateFlow()

    private val _dictSearchResults = MutableStateFlow<List<DictionaryEntry>>(emptyList())
    val dictSearchResults: StateFlow<List<DictionaryEntry>> = _dictSearchResults.asStateFlow()

    fun setDictDirection(direction: String) {
        _dictDirection.value = direction
        val q = _dictQuery.value
        if (q.isNotBlank()) searchDictionary(q)
    }

    fun searchDictionary(query: String) {
        _dictQuery.value = query
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _dictEntry.value = null
            _dictSearchResults.value = emptyList()
            return
        }
        viewModelScope.launch {
            val exact = runCatching { dictionaryProvider.lookup(trimmed)?.getOrNull() }.getOrNull()
            val results = runCatching { dictionaryProvider.search(trimmed, 20)?.getOrNull().orEmpty() }.getOrDefault(emptyList())
            _dictEntry.value = exact ?: results.firstOrNull()
            _dictSearchResults.value = results
        }
    }

    fun selectDictionaryEntry(entry: DictionaryEntry) {
        _dictQuery.value = entry.headword
        viewModelScope.launch {
            val full = runCatching { dictionaryProvider.lookup(entry.headword)?.getOrNull() }.getOrNull()
            _dictEntry.value = full ?: entry
        }
    }

    fun bookmarkDictionaryEntry(entry: DictionaryEntry) {
        val repo = myWords
        viewModelScope.launch {
            val primaryGloss = entry.meanings.firstOrNull()
            val firstEx = entry.examples.firstOrNull()
            repo.mark(
                MarkedWord(
                    id = 0L,
                    word = entry.headword,
                    isPhrase = entry.headword.contains(' '),
                    translation = primaryGloss,
                    contextText = firstEx?.first,
                    contextTranslation = firstEx?.second?.ifBlank { null },
                    sourceTitle = "Dictionary",
                    sourceStartMs = -1L,
                    status = WordStatus.LEARNING,
                    level = entry.cefr?.takeIf { it != CefrLevel.UNKNOWN }?.name,
                    note = entry.synonyms.take(5).joinToString(", ").ifBlank { null },
                    markedAt = System.currentTimeMillis(),
                    reviewCount = 0,
                    lemma = entry.headword.lowercase(),
                    lexiconEntryId = entry.entryId,
                    sourceKind = "PACK",
                ),
            )
            _statusMessage.value = "Saved '${entry.headword}' to My Words"
        }
    }

}
