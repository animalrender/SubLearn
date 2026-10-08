package com.sublearn.feature.learn

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sublearn.core.lexicon.WordLevelSource
import com.sublearn.core.settings.AppSettings
import com.sublearn.core.settings.CefrLevel
import com.sublearn.core.settings.LearningMode
import com.sublearn.core.settings.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LearnViewModel(
    private val settingsRepository: SettingsRepository,
    wordLevels: WordLevelSource,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = settingsRepository.settings

    val levelState = wordLevels.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, WordLevelSource.Status())

    fun setMode(mode: LearningMode) = update { it.copy(learning = it.learning.copy(mode = mode)) }

    fun setLevel(level: CefrLevel) = update { it.copy(learning = it.learning.copy(manualLevel = level)) }

    fun setPopupCount(count: Int) = update { it.copy(learning = it.learning.copy(maxPopupCards = count.coerceIn(1, 10))) }

    fun setPopupLifetime(ms: Long) = update { it.copy(learning = it.learning.copy(popupLifetimeMs = ms.coerceIn(1_500L, 15_000L))) }

    private fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settingsRepository.update(transform) }
    }
}
