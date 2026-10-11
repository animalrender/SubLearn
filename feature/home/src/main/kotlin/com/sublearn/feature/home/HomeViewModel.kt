package com.sublearn.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sublearn.core.data.RecentVideoEntity
import com.sublearn.core.data.RecentVideoRepository
import com.sublearn.core.player.MediaAccess
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Home's state: the recent list, plus the two cases a stored URI can be in.
 *
 * A `content://` URI only stays readable while its grant lasts (PLY-12). Before a row is handed to
 * the player it is checked here, because opening a stale URI fails inside the decoder with
 * `ERROR_CODE_IO_UNSPECIFIED`, which tells the user nothing; instead Home offers to locate the file
 * again and moves the entry to the new URI.
 */
class HomeViewModel(
    private val recentVideos: RecentVideoRepository,
    private val mediaAccess: MediaAccess,
) : ViewModel() {
    val recent: StateFlow<List<RecentVideoRow>> = recentVideos.observeRecent(limit = 40)
        .map { list -> list.map { it.toRow() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** A row the user asked for that could not be read; the screen offers to pick the file again. */
    private val _unavailable = MutableStateFlow<RecentVideoRow?>(null)
    val unavailable: StateFlow<RecentVideoRow?> = _unavailable.asStateFlow()

    /** Videos that passed the readability check and should be opened now. */
    private val _openRequests = MutableSharedFlow<OpenRequest>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val openRequests: SharedFlow<OpenRequest> = _openRequests.asSharedFlow()

    /** A tap on a recent card: the check decides between playing it and asking where it went. */
    fun requestOpen(row: RecentVideoRow) {
        viewModelScope.launch {
            if (mediaAccess.isReadable(row.uri)) {
                _openRequests.tryEmit(OpenRequest(row.uri, row.title))
            } else {
                _unavailable.value = row
            }
        }
    }

    fun dismissUnavailable() {
        _unavailable.value = null
    }

    /**
     * The file was picked again. The old entry is replaced rather than added, so the list does not
     * grow a duplicate for the same video, and the saved position moves with it.
     */
    fun relink(newUri: String, pickedName: String) {
        val previous = _unavailable.value
        viewModelScope.launch {
            val saved = previous?.let { runCatching { recentVideos.find(it.uri) }.getOrNull() }
            previous?.let { recentVideos.remove(it.uri) }
            val title = previous?.title?.takeIf { it.isNotBlank() } ?: MediaInfo.titleFromFileName(pickedName)
            recentVideos.touch(
                uri = newUri,
                title = title,
                durationMs = saved?.durationMs ?: 0L,
                positionMs = saved?.lastPositionMs ?: 0L,
            )
            _unavailable.value = null
            _openRequests.tryEmit(OpenRequest(newUri, title))
        }
    }

    fun forget(uri: String) {
        viewModelScope.launch { recentVideos.remove(uri) }
    }

    fun forgetAll() {
        viewModelScope.launch { recentVideos.clearAll() }
    }
}

/** A video Home wants the player to open. */
data class OpenRequest(val uri: String, val title: String)

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
