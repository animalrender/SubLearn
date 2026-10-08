package com.sublearn.core.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import com.sublearn.core.common.SubLearnLogger
import com.sublearn.core.settings.AspectMode
import com.sublearn.core.settings.DecoderMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Writes the last known position somewhere durable (Home and resume-on-open read it back). */
fun interface PositionSink {
    suspend fun save(uri: String, positionMs: Long)
}

/**
 * Media3/ExoPlayer implementation.
 *
 * Notable decisions (see docs/DECISIONS.md):
 * - Decoder modes are a real mapping over [DefaultRenderersFactory], rebuilt on change, so a
 *   control is never shown that does nothing. Software mode filters MediaCodec to software-only
 *   decoders, which is what the FFmpeg extension would otherwise be needed for.
 * - Subtitle text is mirrored out through [embeddedCues] instead of the built-in subtitle view, so
 *   the Compose overlay can hit-test words (PLY-7, SUB-4).
 */
@OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class Media3PlayerController(
    private val context: Context,
    private val logger: SubLearnLogger? = null,
    private val positionSink: PositionSink? = null,
    private val initialDecoder: DecoderMode = DecoderMode.HARDWARE,
    private val seekBackMs: Long = 10_000L,
    private val seekForwardMs: Long = 10_000L,
) : PlayerController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(PlaybackState(decoder = initialDecoder))
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: Flow<PlayerEvent> = _events.asSharedFlow()
    private val _embeddedCues = MutableStateFlow<List<EmbeddedCue>>(emptyList())
    override val embeddedCues: Flow<List<EmbeddedCue>> = _embeddedCues

    private var player: ExoPlayer? = null
    private var attachedView: PlayerView? = null
    private var ticker: Job? = null
    private var decoder: DecoderMode = initialDecoder
    private var aspect: AspectMode = AspectMode.FIT
    private var customAspect = 16 to 9
    private var muted = false
    private var speedPercent = 100
    private var pendingTarget: MediaTarget? = null
    private var pendingPlaylist: List<MediaTarget> = emptyList()
    private var pendingIndex = 0
    private var lastSavedPositionMs = -1L

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publishSnapshot()
            if (events.containsAny(Player.EVENT_PLAYBACK_STATE_CHANGED, Player.EVENT_IS_PLAYING_CHANGED)) {
                if (player.isPlaying) startTicker() else stopTicker()
            }
            if (events.contains(Player.EVENT_TRACKS_CHANGED)) publishTracks(player)
            if (events.contains(Player.EVENT_VIDEO_SIZE_CHANGED)) {
                val size = player.videoSize
                _state.value = _state.value.copy(
                    videoWidth = size.width,
                    videoHeight = size.height,
                    videoRotationDegrees = videoRotationDegrees(player),
                )
            }
            if (events.contains(Player.EVENT_CUES)) {
                // currentCues is a CueGroup whose list entries are nullable, so nothing here assumes otherwise.
                _embeddedCues.value = player.currentCues.cues.orEmpty()
                    .mapNotNull { cue -> cue?.text?.toString()?.takeIf { it.isNotBlank() } }
                    .map { EmbeddedCue(it) }
            }
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) flushPositionNow()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            publishSnapshot()
            if (playbackState == Player.STATE_ENDED) {
                flushPositionNow()
                _events.tryEmit(PlayerEvent.Ended)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val message = error.errorCodeName + ": " + (error.message ?: "playback failed")
            logger?.log(SubLearnLogger.Level.ERROR, TAG, message, error)
            _state.value = _state.value.copy(error = message, isPlaying = false)
            _events.tryEmit(PlayerEvent.Failed(message, recoverable = true))
        }
    }

    init {
        buildPlayer()
    }

    // ---------------------------------------------------------------- lifecycle

    private fun buildPlayer() {
        player?.let { old ->
            old.removeListener(listener)
            attachedView?.player = null
            old.release()
        }
        val renderers = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            .setEnableDecoderFallback(decoder != DecoderMode.HARDWARE)
        if (decoder == DecoderMode.SOFTWARE) {
            // The platform names its software codecs after a well-known prefix; Media3's info object has
            // no "is software" flag in this media3 version, so the name is the honest test.
            renderers.setMediaCodecSelector { format, requiresSecure, _ ->
                val candidates = MediaCodecSelector.DEFAULT.getDecoderInfos(format, requiresSecure, true)
                candidates.filter { it.name.startsWith("OMX.google.") || it.name.startsWith("c2.android.") }
                    .ifEmpty { candidates }
            }
        }
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
        val dataSourceFactory = DefaultDataSource.Factory(context, http)
        val built = ExoPlayer.Builder(context)
            .setRenderersFactory(renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(seekBackMs)
            .setSeekForwardIncrementMs(seekForwardMs)
            .build()
        built.addListener(listener)
        built.volume = if (muted) 0f else 1f
        built.playbackParameters = built.playbackParameters.setSpeed(speedPercent / 100f)
        player = built
        attachedView?.let { view ->
            view.player = built
            applyAspect(view)
        }
        _state.value = _state.value.copy(decoder = decoder)
    }

    override fun attachView(view: PlayerView) {
        attachedView = view
        view.player = player
        view.useController = false
        view.controllerAutoShow = false
        view.keepContentOnPlayerReset = true
        // SubLearn paints its own layers so word taps work: the built-in cue view must stay hidden.
        view.subtitleView?.visibility = android.view.View.GONE
        view.setShutterBackgroundColor(android.graphics.Color.BLACK)
        applyAspect(view)
    }

    override fun detachView(view: PlayerView) {
        if (attachedView === view) attachedView = null
        view.player = null
    }

    private fun applyAspect(view: PlayerView) {
        view.resizeMode = aspect.toResizeMode(customAspect.first, customAspect.second)
    }

    // ---------------------------------------------------------------- media

    override suspend fun open(target: MediaTarget) {
        pendingTarget = target
        pendingPlaylist = listOf(target)
        pendingIndex = 0
        applyPlaylist(target.startPositionMs)
    }

    override suspend fun setPlaylist(targets: List<MediaTarget>, startIndex: Int) {
        require(targets.isNotEmpty()) { "a playlist needs at least one item" }
        pendingPlaylist = targets
        pendingIndex = startIndex.coerceIn(0, targets.lastIndex)
        pendingTarget = targets[pendingIndex]
        applyPlaylist(targets[pendingIndex].startPositionMs)
    }

    private fun applyPlaylist(startPositionMs: Long) {
        val exo = player ?: return
        val items = pendingPlaylist.map { it.toMediaItem() }
        exo.setMediaItems(items, pendingIndex, startPositionMs.coerceAtLeast(0L))
        exo.prepare()
        _state.value = _state.value.copy(
            target = pendingTarget,
            playlist = pendingPlaylist,
            playlistIndex = pendingIndex,
            error = null,
            positionMs = startPositionMs,
            durationMs = 0L,
            bufferedMs = 0L,
            isEnded = false,
        )
        publishTracks(exo)
    }

    private fun MediaTarget.toMediaItem(): MediaItem {
        val builder = MediaItem.Builder()
            .setUri(Uri.parse(uri))
            .setMediaId(uri)
        if (!mimeType.isNullOrBlank()) builder.setMimeType(mimeType)
        if (subtitleAttachments.isNotEmpty()) {
            builder.setSubtitleConfigurations(
                subtitleAttachments.map { attachment ->
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(attachment.uri))
                        .setMimeType(attachment.mimeType)
                        .setLabel(attachment.label)
                        .apply { attachment.language?.let { setLanguage(it) } }
                        .build()
                },
            )
        }
        return builder.build()
    }

    // ---------------------------------------------------------------- transport

    override fun play() {
        player?.playWhenReady = true
    }

    override fun pause() {
        player?.playWhenReady = false
        flushPositionNow()
    }

    override fun togglePlayPause() {
        val exo = player ?: return
        if (exo.isPlaying) {
            exo.playWhenReady = false
            flushPositionNow()
        } else {
            if (exo.playbackState == Player.STATE_ENDED) exo.seekTo(0L)
            exo.playWhenReady = true
        }
    }

    override fun seekTo(positionMs: Long) {
        val exo = player ?: return
        val duration = exo.duration
        val clamped = if (duration > 0) positionMs.coerceIn(0L, duration) else maxOf(0L, positionMs)
        exo.seekTo(clamped)
        _state.value = _state.value.copy(positionMs = clamped)
    }

    override fun seekBy(deltaMs: Long) = seekTo((player?.currentPosition ?: 0L) + deltaMs)

    override fun playNext() {
        val exo = player ?: return
        if (exo.hasNextMediaItem()) {
            exo.seekToNextMediaItem()
            pendingIndex = exo.currentMediaItemIndex
            _state.value = _state.value.copy(playlistIndex = pendingIndex, target = pendingPlaylist.getOrNull(pendingIndex))
            flushPositionNow()
        }
    }

    override fun playPrevious() {
        val exo = player ?: return
        // MX-style: if the current item started recently, go to the previous item instead.
        if (exo.currentPosition > 2_000L) {
            exo.seekTo(0L)
            return
        }
        if (exo.hasPreviousMediaItem()) {
            exo.seekToPreviousMediaItem()
            pendingIndex = exo.currentMediaItemIndex
            _state.value = _state.value.copy(playlistIndex = pendingIndex, target = pendingPlaylist.getOrNull(pendingIndex))
        }
    }

    override fun setRepeat(repeat: PlaylistRepeat) {
        player?.repeatMode = when (repeat) {
            PlaylistRepeat.OFF -> Player.REPEAT_MODE_OFF
            PlaylistRepeat.ONE -> Player.REPEAT_MODE_ONE
            PlaylistRepeat.ALL -> Player.REPEAT_MODE_ALL
        }
        _state.value = _state.value.copy(repeat = repeat)
    }

    override fun setSpeedPercent(percent: Int) {
        speedPercent = percent.coerceIn(MIN_SPEED_PERCENT, MAX_SPEED_PERCENT)
        player?.let { it.playbackParameters = it.playbackParameters.setSpeed(speedPercent / 100f) }
        _state.value = _state.value.copy(speedPercent = speedPercent)
    }

    override fun nudgeSpeed(deltaPercent: Int, minPercent: Int, maxPercent: Int) {
        val clampedMin = maxOf(minPercent, MIN_SPEED_PERCENT)
        val clampedMax = minOf(maxPercent, MAX_SPEED_PERCENT)
        val next = (speedPercent + digitalWritePercent(deltaPercent, clampedMin, clampedMax)).coerceIn(clampedMin, clampedMax)
        setSpeedPercent(next)
    }

    /** Keeps the step inside the configured range even when the user drags past an edge. */
    private fun digitalWritePercent(delta: Int, min: Int, max: Int): Int =
        delta.coerceIn(min - speedPercent, max - speedPercent)

    // ---------------------------------------------------------------- options

    override fun setAspect(mode: AspectMode, customWidth: Int, customHeight: Int) {
        aspect = mode
        customAspect = customWidth.coerceAtLeast(1) to customHeight.coerceAtLeast(1)
        attachedView?.let { applyAspect(it) }
        _state.value = _state.value.copy(aspect = mode, customAspectWidth = customAspect.first, customAspectHeight = customAspect.second)
    }

    override suspend fun setDecoderMode(mode: DecoderMode) {
        if (mode == decoder) {
            _events.tryEmit(PlayerEvent.DecoderChanged(mode, applied = true))
            return
        }
        val resumeAt = player?.currentPosition ?: 0L
        val wasPlaying = player?.isPlaying == true
        decoder = mode
        val previousState = _state.value
        buildPlayer()
        _state.value = previousState.copy(decoder = mode, positionMs = resumeAt)
        pendingTarget?.let { applyPlaylist(resumeAt) }
        if (wasPlaying) player?.playWhenReady = true
        _events.tryEmit(PlayerEvent.DecoderChanged(mode, applied = true))
    }

    override fun selectTrack(ref: TrackRef) {
        val exo = player ?: return
        val group = exo.currentTracks.groups.getOrNull(ref.groupId) ?: return
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setOverrideForType(DefaultTrackSelector.TrackSelectionOverride(group.getMediaTrackGroup(), ref.trackIndex))
            .build()
        publishTracks(exo)
    }

    override fun clearTrackOverride(type: PlayerTrackType) {
        val exo = player ?: return
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(trackTypeOf(type))
            .build()
        publishTracks(exo)
    }

    override fun setTextTracksEnabled(enabled: Boolean) {
        val exo = player ?: return
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled)
            .build()
        if (!enabled) _embeddedCues.value = emptyList()
    }

    override fun setWakeMode(keepScreenOn: Boolean) {
        player?.setWakeMode(if (keepScreenOn) C.WAKE_MODE_NETWORK else C.WAKE_MODE_LOCAL)
    }

    override fun setMuted(muted: Boolean) {
        this.muted = muted
        player?.volume = if (muted) 0f else 1f
    }

    // ---------------------------------------------------------------- internals

    private fun trackTypeOf(type: PlayerTrackType): Int = when (type) {
        PlayerTrackType.AUDIO -> C.TRACK_TYPE_AUDIO
        PlayerTrackType.TEXT -> C.TRACK_TYPE_TEXT
        PlayerTrackType.VIDEO -> C.TRACK_TYPE_VIDEO
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                publishSnapshot()
                delay(PROGRESS_TICK_MS)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
        publishSnapshot()
    }

    private fun publishSnapshot() {
        val exo = player ?: return
        _state.value = _state.value.copy(
            isPlaying = exo.isPlaying,
            isBuffering = exo.playbackState == Player.STATE_BUFFERING,
            isReady = exo.playbackState == Player.STATE_READY,
            isEnded = exo.playbackState == Player.STATE_ENDED,
            positionMs = exo.currentPosition.coerceAtLeast(0L),
            durationMs = if (exo.isCurrentMediaItemLive) 0L else maxOf(0L, exo.duration),
            bufferedMs = exo.bufferedPosition.coerceAtLeast(0L),
            isLive = exo.isCurrentMediaItemLive,
            speedPercent = (exo.playbackParameters.speed * 100).toInt(),
            repeat = when (exo.repeatMode) {
                Player.REPEAT_MODE_ONE -> PlaylistRepeat.ONE
                Player.REPEAT_MODE_ALL -> PlaylistRepeat.ALL
                else -> PlaylistRepeat.OFF
            },
        )
    }

    /**
     * Rotation of the video track that is actually selected. Media3 1.4 keeps the angle on the track
     * format rather than on `videoSize`, and before the selection settles the first video group still
     * carries the right value, so it is the fallback: the subtitle overlay has to rotate with the
     * picture, otherwise a rotated video puts the text over the wrong line (PLY-8).
     */
    private fun videoRotationDegrees(player: Player): Int {
        var fallback = 0
        for (group in player.currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_VIDEO) continue
            val degrees = group.getTrackFormat(0).rotationDegrees
            if (group.isSelected) return degrees
            if (fallback == 0) fallback = degrees
        }
        return fallback
    }

    private fun publishTracks(exo: Player) {
        val tracks = exo.currentTracks
        val audio = ArrayList<TrackInfo>()
        val text = ArrayList<TrackInfo>()
        tracks.groups.forEachIndexed { groupIndex, group ->
            when (group.type) {
                C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_TEXT -> {
                    val target = if (group.type == C.TRACK_TYPE_AUDIO) audio else text
                    for (trackIndex in 0 until group.length) {
                        val format = group.getTrackFormat(trackIndex)
                        target += TrackInfo(
                            ref = TrackRef(
                                groupId = groupIndex,
                                trackIndex = trackIndex,
                                type = if (group.type == C.TRACK_TYPE_AUDIO) PlayerTrackType.AUDIO else PlayerTrackType.TEXT,
                            ),
                            label = format.label ?: "",
                            language = format.language,
                            mimeType = format.sampleMimeType,
                            isDefault = format.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0,
                            isSelected = group.isTrackSelected(trackIndex),
                            isSupported = group.isTrackSupported(trackIndex),
                        )
                    }
                }
            }
        }
        _state.value = _state.value.copy(audioTracks = audio, textTracks = text)
        _events.tryEmit(PlayerEvent.TracksChanged(audio.size, text.size))
    }

    private fun flushPositionNow() {
        val exo = player ?: return
        val position = exo.currentPosition
        if (position <= 0) return
        lastSavedPositionMs = position
        val uri = _state.value.target?.uri ?: return
        scope.launch { positionSink?.save(uri, position) }
    }

    override suspend fun flushPosition() {
        val exo = player ?: return
        val uri = _state.value.target?.uri ?: return
        val position = exo.currentPosition.coerceAtLeast(0L)
        if (position > 0 && position != lastSavedPositionMs) positionSink?.save(uri, position)
    }

    override fun release() {
        stopTicker()
        flushPositionNow()
        attachedView?.let { it.player = null }
        attachedView = null
        player?.let {
            it.removeListener(listener)
            it.release()
        }
        player = null
        _embeddedCues.value = emptyList()
    }

    private companion object {
        const val TAG = "Player"
        const val PROGRESS_TICK_MS = 120L
        const val MIN_SPEED_PERCENT = 25
        const val MAX_SPEED_PERCENT = 400
        const val USER_AGENT = "SubLearn/1.0 (+https://github.com/animalrender/SubLearn) Media3"
    }
}
