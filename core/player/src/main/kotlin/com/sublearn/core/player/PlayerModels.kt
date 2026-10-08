package com.sublearn.core.player

import com.sublearn.core.settings.AspectMode
import com.sublearn.core.settings.DecoderMode

/** Something the player can open: a SAF uri, a file path or a stream URL. */
data class MediaTarget(
    val uri: String,
    val title: String,
    val mimeType: String? = null,
    val startPositionMs: Long = 0L,
    /** External subtitle files to hand to the player; SubLearn renders them itself. */
    val subtitleAttachments: List<SubtitleAttachment> = emptyList(),
    val isStream: Boolean = uri.startsWith("http://") || uri.startsWith("https://"),
) {
    val displayName: String
        get() = title.ifBlank { uri.substringAfterLast('/').ifBlank { uri } }
}

/** An external subtitle file attached to a media item. */
data class SubtitleAttachment(
    val uri: String,
    val label: String,
    val language: String? = null,
    /** Media3/IANA mime type; `SubtitleMime.of(fileName)` maps SubLearn formats to it. */
    val mimeType: String,
)

enum class PlayerTrackType {
    AUDIO,
    TEXT,
    VIDEO,
    ;

    companion object {
        fun fromKey(key: String): PlayerTrackType = entries.firstOrNull { it.name == key } ?: AUDIO
    }
}

/** Identifies one track inside the current media item. */
data class TrackRef(val groupId: Int, val trackIndex: Int, val type: PlayerTrackType)

data class TrackInfo(
    val ref: TrackRef,
    val label: String,
    val language: String?,
    val mimeType: String?,
    val isDefault: Boolean,
    val isSelected: Boolean,
    val isSupported: Boolean,
) {
    val displayLabel: String get() = label.ifBlank { language ?: "Track ${ref.trackIndex + 1}" }
}

/** Cues the player renders for an embedded track; SubLearn shows them in its own layers. */
data class EmbeddedCue(val text: String, val isFromPlayer: Boolean = true)

/** Repeat behaviour of the playlist, mirroring Media3 so the UI can stay declarative. */
enum class PlaylistRepeat { OFF, ONE, ALL }

/** The complete immutable snapshot the player UI renders. */
data class PlaybackState(
    val target: MediaTarget? = null,
    val playlist: List<MediaTarget> = emptyList(),
    val playlistIndex: Int = 0,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val isReady: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedMs: Long = 0L,
    val isLive: Boolean = false,
    val speedPercent: Int = 100,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val videoRotationDegrees: Int = 0,
    val aspect: AspectMode = AspectMode.FIT,
    val customAspectWidth: Int = 16,
    val customAspectHeight: Int = 9,
    val decoder: DecoderMode = DecoderMode.HARDWARE,
    val audioTracks: List<TrackInfo> = emptyList(),
    val textTracks: List<TrackInfo> = emptyList(),
    val audioSessionId: Int = 0,
    val repeat: PlaylistRepeat = PlaylistRepeat.OFF,
    val error: String? = null,
    val isEnded: Boolean = false,
) {
    val progressFraction: Float
        get() = if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)

    val bufferedFraction: Float
        get() = if (durationMs <= 0L) 0f else (bufferedMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)

    val hasVideo: Boolean get() = videoWidth > 0 && videoHeight > 0

    val aspectRatio: Float
        get() = if (videoHeight <= 0) 16f / 9f else videoWidth.toFloat() / videoHeight.toFloat()

    fun trackOf(type: PlayerTrackType, groupId: Int, trackIndex: Int): TrackInfo? =
        (if (type == PlayerTrackType.AUDIO) audioTracks else textTracks)
            .firstOrNull { it.ref.groupId == groupId && it.ref.trackIndex == trackIndex }
}

/** One-shot signals: never modelled as state because they must be consumed once. */
sealed interface PlayerEvent {
    data class Failed(val message: String, val recoverable: Boolean = true) : PlayerEvent
    data object Ended : PlayerEvent
    data class DecoderChanged(val mode: DecoderMode, val applied: Boolean) : PlayerEvent
    data class TracksChanged(val audioCount: Int, val textCount: Int) : PlayerEvent
    data class PositionRestored(val positionMs: Long) : PlayerEvent
}
