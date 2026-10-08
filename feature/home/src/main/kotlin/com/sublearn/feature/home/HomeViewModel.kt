package com.sublearn.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sublearn.core.data.RecentVideoEntity
import com.sublearn.core.data.RecentVideoRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(
    private val recentVideos: RecentVideoRepository,
) : ViewModel() {
    val recent: StateFlow<List<RecentVideoRow>> = recentVideos.observeRecent(limit = 40)
        .map { list -> list.map { it.toRow() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun forget(uri: String) {
        viewModelScope.launch { recentVideos.remove(uri) }
    }

    fun forgetAll() {
        viewModelScope.launch { recentVideos.clearAll() }
    }
}

/** UI-facing shape of a recent video, with the strings already resolved. */
data class RecentVideoRow(
    val uri: String,
    val title: String,
    val progressFraction: Float,
    val positionLabel: String,
    val durationLabel: String,
    val isOpenable: Boolean,
)

private fun RecentVideoEntity.toRow(): RecentVideoRow {
    val duration = durationMs.coerceAtLeast(0L)
    val progress = if (duration > 0L) (lastPositionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
    return RecentVideoRow(
        uri = uri,
        title = title,
        progressFraction = progress,
        positionLabel = clock(lastPositionMs),
        durationLabel = if (duration > 0L) clock(duration) else "",
        isOpenable = isPlayable,
    )
}

private fun clock(ms: Long): String {
    val total = ms / 1000L
    val hours = total / 3600L
    val minutes = (total % 3600L) / 60L
    val seconds = total % 60L
    return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
