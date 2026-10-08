package com.sublearn.core.player

import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.sublearn.core.settings.AspectMode
import com.sublearn.core.settings.DecoderMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The single seam between SubLearn's UI and the playback engine.
 *
 * Everything the player screens do goes through this interface, which is what lets Compose tests
 * run against [FakePlayerController] instead of a real decoder (ENGINEERING REQUIREMENTS). The
 * only Media3 type visible here is [PlayerView], for surface attachment; state is plain data.
 */
interface PlayerController {
    val state: StateFlow<PlaybackState>
    val events: Flow<PlayerEvent>

    /** Live text cues produced by the player for embedded subtitle tracks. */
    val embeddedCues: Flow<List<EmbeddedCue>>

    fun attachView(view: PlayerView)

    fun detachView(view: PlayerView)

    suspend fun open(target: MediaTarget)

    suspend fun setPlaylist(targets: List<MediaTarget>, startIndex: Int = 0)

    fun play()

    fun pause()

    fun togglePlayPause()

    fun seekTo(positionMs: Long)

    fun seekBy(deltaMs: Long)

    fun playNext()

    fun playPrevious()

    fun setRepeat(repeat: PlaylistRepeat)

    fun setSpeedPercent(percent: Int)

    fun nudgeSpeed(deltaPercent: Int, minPercent: Int, maxPercent: Int)

    fun setAspect(mode: AspectMode, customWidth: Int = 16, customHeight: Int = 9)

    /** Rebuilds the renderers when the mode really differs; reports whether it took effect. */
    suspend fun setDecoderMode(mode: DecoderMode)

    fun selectTrack(ref: TrackRef)

    fun clearTrackOverride(type: PlayerTrackType)

    fun setTextTracksEnabled(enabled: Boolean)

    /** Sets the volume/brightness-style dim on the surface; the overlay handles its own dimming. */
    fun setWakeMode(keepScreenOn: Boolean)

    fun setMuted(muted: Boolean)

    /** Persist the current position; called on pause, on leaving and on process-death signals. */
    suspend fun flushPosition()

    fun release()
}

/** Maps our aspect modes onto Media3's [AspectRatioFrameLayout] resize modes. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
fun AspectMode.toResizeMode(customWidth: Int = 16, customHeight: Int = 9): Int = when (this) {
    AspectMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    AspectMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    AspectMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    AspectMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    AspectMode.CUSTOM_RATIO -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
}

/** File extension -> Media3 mime type for the subtitle attachments we hand to the player. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
object SubtitleMime {
    fun of(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
        "vtt" -> androidx.media3.common.MimeTypes.TEXT_VTT
        "ass", "ssa" -> androidx.media3.common.MimeTypes.TEXT_SSA
        "ttml", "dfxp" -> androidx.media3.common.MimeTypes.APPLICATION_TTML
        "mp4vtt" -> androidx.media3.common.MimeTypes.APPLICATION_MP4VTT
        else -> androidx.media3.common.MimeTypes.APPLICATION_SUBRIP
    }
}
