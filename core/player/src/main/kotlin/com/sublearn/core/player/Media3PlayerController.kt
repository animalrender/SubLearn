package com.sublearn.core.player

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
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
 * - Playback pauses when the audio route changes in either direction (D-84). Media3's
 *   `handleAudioBecomingNoisy` covers an unplugged headset; an [AudioDeviceCallback] covers the
 *   other half — a headset or a car that *connects* mid-film would otherwise grab the sound at a
 *   different volume and the learner loses the line.
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
    private var mediaSourceFactory: DefaultMediaSourceFactory? = null
    private var attachedView: PlayerView? = null
    private var ticker: Job? = null
    private var decoder: DecoderMode = initialDecoder
    private var aspect: AspectMode = AspectMode.FIT
    private var customAspect = 16 to 9
    private var muted = false
    private var volumePercent = 100
    private var lastNonZeroVolumePercent = 100
    private var speedPercent = 100
    private var externalAudioUri: String? = null
    private var externalAudioName: String? = null
    private var externalAudioTargetUri: String? = null
    private var pendingTarget: MediaTarget? = null
    private var pendingPlaylist: List<MediaTarget> = emptyList()
    private var pendingIndex = 0
    private var lastSavedPositionUri: String? = null
    private var lastSavedPositionMs = -1L
    private val audioManager: AudioManager? = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var audioRouteCallback: AudioDeviceCallback? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            val mediaTransition = events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)
            var targetChanged = false
            if (mediaTransition) {
                val previous = _state.value
                val nextIndex = player.currentMediaItemIndex
                val nextTarget = pendingPlaylist.getOrNull(nextIndex)
                if (nextTarget != null && nextTarget.uri != previous.target?.uri) {
                    previous.target?.uri?.let { uri -> savePosition(uri, previous.positionMs) }
                    pendingIndex = nextIndex
                    pendingTarget = nextTarget
                    val externalForTarget = externalAudioName.takeIf { externalAudioTargetUri == nextTarget.uri }
                    _state.value = previous.copy(
                        target = nextTarget,
                        playlistIndex = nextIndex,
                        positionMs = player.currentPosition.coerceAtLeast(0L),
                        durationMs = 0L,
                        bufferedMs = 0L,
                        isEnded = false,
                        externalAudioName = externalForTarget,
                    )
                    _embeddedCues.value = emptyList()
                    targetChanged = true
                }
            }
            // Refresh target and track groups together before the ViewModel observes the new item.
            if (targetChanged || events.contains(Player.EVENT_TRACKS_CHANGED)) publishTracks(player)
            publishSnapshot()
            if (events.containsAny(Player.EVENT_PLAYBACK_STATE_CHANGED, Player.EVENT_IS_PLAYING_CHANGED)) {
                if (player.isPlaying) startTicker() else stopTicker()
            }
            if (events.contains(Player.EVENT_VIDEO_SIZE_CHANGED)) {
                val size = player.videoSize
                _state.value = _state.value.copy(
                    videoWidth = size.width,
                    videoHeight = size.height,
                    videoRotationDegrees = videoRotationDegrees(player),
                )
            }
            if (events.contains(Player.EVENT_CUES)) {
                // CueGroup does not expose a source id. When one live cue per selected soft track is
                // reported, retain that best-effort association; otherwise keep cues untagged.
                val texts = player.currentCues.cues.orEmpty()
                    .mapNotNull { cue -> cue?.text?.toString()?.takeIf { it.isNotBlank() } }
                val selected = _state.value.textTracks.filter { it.isSelected }.map { it.ref }
                val cues = if (selected.size == 1) {
                    texts.map { EmbeddedCue(it, trackRef = selected.single()) }
                } else if (selected.isNotEmpty() && selected.size == texts.size) {
                    texts.zip(selected).map { (text, ref) -> EmbeddedCue(text, trackRef = ref) }
                } else {
                    texts.map { EmbeddedCue(it) }
                }
                _embeddedCues.value = cues
            }
            if (mediaTransition && !targetChanged) flushPositionNow()
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
        watchAudioRoute()
    }

    /**
     * Pauses when an external output appears or disappears.
     *
     * The platform calls `onAudioDevicesAdded` once with the current list right after registering,
     * which is not a route change; the first callback is therefore swallowed. Built-in outputs are
     * ignored too, so a phone that merely re-enumerates its speaker never pauses the film.
     */
    private fun watchAudioRoute() {
        val manager = audioManager ?: return
        val callback = object : AudioDeviceCallback() {
            private var primed = false

            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                if (!primed) {
                    primed = true
                    return
                }
                if (addedDevices.orEmpty().any { it.isExternalOutput() }) pauseForRouteChange()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                primed = true
                if (removedDevices.orEmpty().any { it.isExternalOutput() }) pauseForRouteChange()
            }
        }
        manager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        audioRouteCallback = callback
    }

    private fun pauseForRouteChange() {
        val exo = player ?: return
        if (!exo.playWhenReady) return
        exo.playWhenReady = false
        flushPositionNow()
        logger?.log(SubLearnLogger.Level.INFO, TAG, "paused: the audio output changed")
    }

    private fun AudioDeviceInfo.isExternalOutput(): Boolean =
        isSink && type !in BUILT_IN_OUTPUT_TYPES

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
            // Prefer the platform's software decoders; fall back to the full list rather than failing
            // when a format only has a hardware decoder, because "no video" is worse than "hardware".
            renderers.setMediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                val candidates = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
                candidates.filter { it.softwareOnly }.ifEmpty { candidates }
            }
        }
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
        val dataSourceFactory = DefaultDataSource.Factory(context, http)
        val sourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        mediaSourceFactory = sourceFactory
        val built = ExoPlayer.Builder(context)
            .setRenderersFactory(renderers)
            .setMediaSourceFactory(sourceFactory)
            .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(seekBackMs)
            .setSeekForwardIncrementMs(seekForwardMs)
            .build()
        built.addListener(listener)
        built.volume = if (muted) 0f else volumePercent / VOLUME_PERCENT
        built.playbackParameters = built.playbackParameters.withSpeed(speedPercent / 100f)
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
        view.setKeepContentOnPlayerReset(true)
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
        if (externalAudioTargetUri != null && externalAudioTargetUri != target.uri) {
            externalAudioUri = null
            externalAudioName = null
            externalAudioTargetUri = null
            _state.value = _state.value.copy(externalAudioName = null)
        }
        pendingTarget = target
        pendingPlaylist = listOf(target)
        pendingIndex = 0
        applyPlaylist(target.startPositionMs)
    }

    override suspend fun setPlaylist(targets: List<MediaTarget>, startIndex: Int) {
        require(targets.isNotEmpty()) { "a playlist needs at least one item" }
        if (externalAudioTargetUri != null && targets.none { it.uri == externalAudioTargetUri }) {
            externalAudioUri = null
            externalAudioName = null
            externalAudioTargetUri = null
        }
        pendingPlaylist = targets
        pendingIndex = startIndex.coerceIn(0, targets.lastIndex)
        pendingTarget = targets[pendingIndex]
        applyPlaylist(targets[pendingIndex].startPositionMs)
    }

    private fun applyPlaylist(startPositionMs: Long) {
        val exo = player ?: return
        val factory = mediaSourceFactory ?: return
        val sources = pendingPlaylist.map { target ->
            val video = factory.createMediaSource(target.toMediaItem())
            val audioUri = externalAudioUri.takeIf { externalAudioTargetUri == target.uri }
            if (audioUri == null) {
                video
            } else {
                val audioItem = MediaItem.Builder()
                    .setUri(Uri.parse(audioUri))
                    .setMediaId("external-audio:$audioUri")
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(externalAudioName.orEmpty()).build())
                    .build()
                MergingMediaSource(video, factory.createMediaSource(audioItem))
            }
        }
        exo.setMediaSources(sources, pendingIndex, startPositionMs.coerceAtLeast(0L))
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
            externalAudioName = externalAudioName.takeIf { externalAudioTargetUri == pendingTarget?.uri },
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
        if (exo.hasNextMediaItem()) exo.seekToNextMediaItem()
    }

    override fun playPrevious() {
        val exo = player ?: return
        // MX-style: if the current item started recently, go to the previous item instead.
        if (exo.currentPosition > 2_000L) {
            exo.seekTo(0L)
            return
        }
        if (exo.hasPreviousMediaItem()) exo.seekToPreviousMediaItem()
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
        player?.let { it.playbackParameters = it.playbackParameters.withSpeed(speedPercent / 100f) }
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
        if (ref.type == PlayerTrackType.TEXT) {
            setSelectedTextTracks(listOf(ref))
            return
        }
        val exo = player ?: return
        val group = exo.currentTracks.groups.getOrNull(ref.groupId) ?: return
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.getMediaTrackGroup(), ref.trackIndex))
            .build()
        publishTracks(exo)
    }

    override fun setSelectedTextTracks(refs: List<TrackRef>) {
        val exo = player ?: return
        val builder = exo.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, refs.isEmpty())
        refs.filter { it.type == PlayerTrackType.TEXT }
            .groupBy { it.groupId }
            .forEach { (groupId, selected) ->
                val group = exo.currentTracks.groups.getOrNull(groupId) ?: return@forEach
                if (group.type != C.TRACK_TYPE_TEXT) return@forEach
                val indexes = selected.map { it.trackIndex }.distinct().filter { it in 0 until group.length }
                if (indexes.isNotEmpty()) {
                    builder.addOverride(TrackSelectionOverride(group.getMediaTrackGroup(), indexes))
                }
            }
        exo.trackSelectionParameters = builder.build()
        publishTracks(exo)
    }

    override fun clearTrackOverride(type: PlayerTrackType) {
        val exo = player ?: return
        val mediaType = trackTypeOf(type)
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(mediaType)
            .setTrackTypeDisabled(mediaType, false)
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
        if (!muted && volumePercent == 0) volumePercent = lastNonZeroVolumePercent
        player?.volume = if (muted) 0f else volumePercent / VOLUME_PERCENT
        _state.value = _state.value.copy(volumePercent = volumePercent, isMuted = muted)
    }

    override fun setExternalAudioTrack(uri: String?, displayName: String?) {
        val targetUri = _state.value.target?.uri ?: return
        val exo = player ?: return
        val position = exo.currentPosition.coerceAtLeast(0L)
        val wasPlaying = exo.isPlaying
        externalAudioUri = uri
        externalAudioName = if (uri == null) null else {
            displayName?.takeIf { it.isNotBlank() } ?: DocumentNames.read(context, Uri.parse(uri))
        }
        externalAudioTargetUri = if (uri == null) null else targetUri
        _state.value = _state.value.copy(externalAudioName = externalAudioName)
        applyPlaylist(position)
        if (wasPlaying) player?.playWhenReady = true
    }

    override fun setVolume(percent: Int) {
        volumePercent = percent.coerceIn(0, VOLUME_PERCENT_INT)
        if (volumePercent > 0) {
            lastNonZeroVolumePercent = volumePercent
            muted = false
        } else {
            muted = true
        }
        player?.volume = if (muted) 0f else volumePercent / VOLUME_PERCENT
        _state.value = _state.value.copy(volumePercent = volumePercent, isMuted = muted)
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
            volumePercent = volumePercent,
            isMuted = muted || volumePercent == 0,
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

    private fun savePosition(uri: String, positionMs: Long) {
        if (positionMs <= 0L) return
        lastSavedPositionUri = uri
        lastSavedPositionMs = positionMs
        scope.launch { positionSink?.save(uri, positionMs) }
    }

    private fun flushPositionNow() {
        val exo = player ?: return
        val uri = _state.value.target?.uri ?: return
        savePosition(uri, exo.currentPosition.coerceAtLeast(0L))
    }

    override suspend fun flushPosition() {
        val exo = player ?: return
        val uri = _state.value.target?.uri ?: return
        val position = exo.currentPosition.coerceAtLeast(0L)
        if (position > 0 && (uri != lastSavedPositionUri || position != lastSavedPositionMs)) {
            positionSink?.save(uri, position)
            lastSavedPositionUri = uri
            lastSavedPositionMs = position
        }
    }

    override fun release() {
        stopTicker()
        flushPositionNow()
        audioRouteCallback?.let { callback -> audioManager?.unregisterAudioDeviceCallback(callback) }
        audioRouteCallback = null
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
        const val VOLUME_PERCENT = 100f
        const val VOLUME_PERCENT_INT = 100
        const val USER_AGENT = "SubLearn/1.0 (+https://github.com/animalrender/SubLearn) Media3"

        /**
         * Outputs that are part of the phone itself. `TYPE_BUILTIN_SPEAKER_SAFE` (24) is API 30, so
         * it is spelled as its value to keep the set valid on minSdk 26.
         */
        val BUILT_IN_OUTPUT_TYPES: Set<Int> = setOf(
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_TELEPHONY,
            AudioDeviceInfo.TYPE_FM,
            AudioDeviceInfo.TYPE_REMOTE_SUBMIX,
            24,
        )
    }
}
