package com.sublearn.core.player

import androidx.media3.ui.PlayerView
import com.sublearn.core.settings.AspectMode
import com.sublearn.core.settings.DecoderMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Deterministic player used by unit/Compose tests and by Compose previews.
 *
 * Time only moves when a test calls [advance], so repeat/pause timing assertions never race a real
 * decoder. It implements the same contract as the Media3 controller, including track lists and
 * errors, so a test can drive every player screen.
 */
class FakePlayerController(
    initialState: PlaybackState = PlaybackState(durationMs = 60_000L, videoWidth = 1_920, videoHeight = 1_080),
) : PlayerController {
    private val _state = MutableStateFlow(initialState)
    override val state: StateFlow<PlaybackState> = _state
    private val _events = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 32)
    override val events: Flow<PlayerEvent> = _events.asSharedFlow()
    private val _cues = MutableStateFlow<List<EmbeddedCue>>(emptyList())
    override val embeddedCues: Flow<List<EmbeddedCue>> = _cues

    val calls: MutableList<String> = mutableListOf()
    var attachedViews: Int = 0
        private set

    override fun attachView(view: PlayerView) {
        attachedViews++
        calls += "attachView"
    }

    override fun detachView(view: PlayerView) {
        attachedViews--
        calls += "detachView"
    }

    override suspend fun open(target: MediaTarget) {
        calls += "open:${target.uri}"
        _state.value = _state.value.copy(
            target = target,
            playlist = listOf(target),
            playlistIndex = 0,
            positionMs = target.startPositionMs,
            error = null,
            isPlaying = true,
        )
    }

    override suspend fun setPlaylist(targets: List<MediaTarget>, startIndex: Int) {
        calls += "setPlaylist:${targets.size}@$startIndex"
        _state.value = _state.value.copy(
            playlist = targets,
            playlistIndex = startIndex,
            target = targets.getOrNull(startIndex),
            positionMs = targets.getOrNull(startIndex)?.startPositionMs ?: 0L,
        )
    }

    override fun play() {
        calls += "play"
        _state.value = _state.value.copy(isPlaying = true, error = null)
    }

    override fun pause() {
        calls += "pause"
        _state.value = _state.value.copy(isPlaying = false)
    }

    override fun togglePlayPause() = if (_state.value.isPlaying) pause() else play()

    override fun seekTo(positionMs: Long) {
        calls += "seekTo:$positionMs"
        _state.value = _state.value.copy(positionMs = positionMs.coerceIn(0L, maxOf(positionMs, _state.value.durationMs)))
    }

    override fun seekBy(deltaMs: Long) = seekTo(_state.value.positionMs + deltaMs)

    override fun playNext() {
        val next = _state.value.playlistIndex + 1
        if (next < _state.value.playlist.size) {
            calls += "playNext:$next"
            _state.value = _state.value.copy(playlistIndex = next, target = _state.value.playlist[next], positionMs = 0L)
        }
    }

    override fun playPrevious() {
        val previous = _state.value.playlistIndex - 1
        if (previous >= 0) {
            calls += "playPrevious:$previous"
            _state.value = _state.value.copy(playlistIndex = previous, target = _state.value.playlist[previous], positionMs = 0L)
        }
    }

    override fun setRepeat(repeat: PlaylistRepeat) {
        calls += "setRepeat:$repeat"
        _state.value = _state.value.copy(repeat = repeat)
    }

    override fun setSpeedPercent(percent: Int) {
        calls += "setSpeed:$percent"
        _state.value = _state.value.copy(speedPercent = percent.coerceIn(25, 400))
    }

    override fun nudgeSpeed(deltaPercent: Int, minPercent: Int, maxPercent: Int) {
        setSpeedPercent((_state.value.speedPercent + deltaPercent).coerceIn(minPercent, maxPercent))
    }

    override fun setAspect(mode: AspectMode, customWidth: Int, customHeight: Int) {
        calls += "setAspect:$mode"
        _state.value = _state.value.copy(aspect = mode, customAspectWidth = customWidth, customAspectHeight = customHeight)
    }

    override suspend fun setDecoderMode(mode: DecoderMode) {
        calls += "setDecoder:$mode"
        _state.value = _state.value.copy(decoder = mode)
        _events.tryEmit(PlayerEvent.DecoderChanged(mode, applied = true))
    }

    override fun selectTrack(ref: TrackRef) {
        calls += "selectTrack:${ref.groupId}/${ref.trackIndex}"
        _state.value = _state.value.copy(
            audioTracks = _state.value.audioTracks.map { it.copy(isSelected = it.ref == ref) },
            textTracks = _state.value.textTracks.map { it.copy(isSelected = it.ref == ref) },
        )
    }

    override fun clearTrackOverride(type: PlayerTrackType) {
        calls += "clearTrackOverride:$type"
    }

    override fun setTextTracksEnabled(enabled: Boolean) {
        calls += "textTracks:$enabled"
        if (!enabled) _cues.value = emptyList()
    }

    override fun setWakeMode(keepScreenOn: Boolean) {
        calls += "wake:$keepScreenOn"
    }

    override fun setMuted(muted: Boolean) {
        calls += "mute:$muted"
    }

    override suspend fun flushPosition() {
        calls += "flushPosition:${_state.value.positionMs}"
    }

    override fun release() {
        calls += "release"
    }

    /** Test-only helpers. */
    fun advance(deltaMs: Long) {
        val state = _state.value
        if (!state.isPlaying) return
        val next = state.positionMs + deltaMs
        if (state.durationMs > 0 && next >= state.durationMs) {
            _state.value = state.copy(positionMs = state.durationMs, isPlaying = false, isEnded = true)
            _events.tryEmit(PlayerEvent.Ended)
        } else {
            _state.value = state.copy(positionMs = next, bufferedMs = maxOf(state.bufferedMs, next))
        }
    }

    fun setBuffered(ms: Long) {
        _state.value = _state.value.copy(bufferedMs = ms)
    }

    fun emitEmbeddedCues(vararg texts: String) {
        _cues.value = texts.map { EmbeddedCue(it) }
    }

    fun failWith(message: String) {
        _state.value = _state.value.copy(error = message, isPlaying = false)
        _events.tryEmit(PlayerEvent.Failed(message))
    }

    fun withTracks(audio: List<TrackInfo> = emptyList(), text: List<TrackInfo> = emptyList()) {
        _state.value = _state.value.copy(audioTracks = audio, textTracks = text)
    }

    fun lastCall(): String? = calls.lastOrNull()
}
