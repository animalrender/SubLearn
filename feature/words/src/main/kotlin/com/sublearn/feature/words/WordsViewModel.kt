package com.sublearn.feature.words

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sublearn.core.data.MarkedWord
import com.sublearn.core.data.MyWordsRepository
import com.sublearn.core.data.WordStatus
import com.sublearn.core.settings.AppSettings
import com.sublearn.core.settings.SettingsRepository
import com.sublearn.core.translate.TranslationService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** My Words: everything the user marked, with the filters and edits the card offers. */
class WordsViewModel(
    private val myWords: MyWordsRepository,
    private val translation: TranslationService,
    settingsRepository: SettingsRepository,
) : ViewModel() {
    private val _query = MutableStateFlow("")
    private val _filter = MutableStateFlow(WordFilter.ALL)

    val filter: StateFlow<WordFilter> = _filter.asStateFlow()

    val settings: StateFlow<AppSettings> = settingsRepository.settings

    val rows: StateFlow<List<MarkedWord>> = combine(myWords.observeAll(), _query, _filter) { list, query, filter ->
        list.filter { it.matches(query, filter) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val count: StateFlow<Int> = myWords.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun setQuery(value: String) {
        _query.value = value
    }

    fun setFilter(value: WordFilter) {
        _filter.value = value
    }

    fun setStatus(word: MarkedWord, status: WordStatus) {
        viewModelScope.launch { myWords.setStatus(word.id, status) }
    }

    fun setNote(word: MarkedWord, note: String) {
        viewModelScope.launch { myWords.setNote(word.id, note.ifBlank { null }) }
    }

    fun setTranslation(word: MarkedWord, value: String) {
        viewModelScope.launch { myWords.setTranslation(word.id, value.ifBlank { null }) }
    }

    fun remove(word: MarkedWord) {
        viewModelScope.launch { myWords.remove(word.id) }
    }

    /** Fills an empty translation from the on-device engine (LRN-4); an existing one is never overwritten. */
    fun autoTranslate(word: MarkedWord) {
        if (!word.translation.isNullOrBlank()) return
        viewModelScope.launch {
            val text = translation.translate(word.word).getOrNull() ?: return@launch
            myWords.setTranslation(word.id, text)
        }
    }

    private fun MarkedWord.matches(query: String, filter: WordFilter): Boolean {
        if (query.isNotBlank() && !word.contains(query, ignoreCase = true) && !(translation?.contains(query, true) == true)) {
            return false
        }
        return filter.status == null || status == filter.status
    }
}

enum class WordFilter(val status: WordStatus?) {
    ALL(null),
    NEW(WordStatus.NEW),
    LEARNING(WordStatus.LEARNING),
    KNOWN(WordStatus.KNOWN),
}
