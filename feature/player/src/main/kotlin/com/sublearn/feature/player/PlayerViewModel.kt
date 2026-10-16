package com.sublearn.feature.player

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sublearn.core.ai.AiAnswer
import com.sublearn.core.ai.AiAnswerParser
import com.sublearn.core.ai.AiAskConfig
import com.sublearn.core.ai.AiAssistant
import com.sublearn.core.data.MarkedWord
import com.sublearn.core.data.MyWordsRepository
import com.sublearn.core.data.RecentVideoRepository
import com.sublearn.core.data.WordStatus
import com.sublearn.core.designsystem.WordCardTokens
import com.sublearn.core.designsystem.R
import com.sublearn.core.lexicon.WordLevelSource
import com.sublearn.core.player.MediaTarget
import com.sublearn.core.player.PlaybackState
import com.sublearn.core.player.PlayerController
import com.sublearn.core.player.PlayerTrackType
import com.sublearn.core.player.PlaylistRepeat
import com.sublearn.core.player.TrackRef
import com.sublearn.core.security.SecretStore
import com.sublearn.core.settings.AiProviderToken
import com.sublearn.core.settings.AppSettings
import com.sublearn.core.settings.AspectMode
import com.sublearn.core.settings.DecoderMode
import com.sublearn.core.settings.DockMode
import com.sublearn.core.settings.DoubleTapAction
import com.sublearn.core.settings.GestureAction
import com.sublearn.core.settings.GestureSlot
import com.sublearn.core.settings.CefrLevel
import com.sublearn.core.settings.LearningMode
import com.sublearn.core.settings.OrientationLock
import com.sublearn.core.settings.QuickActionId
import com.sublearn.core.settings.QuickActionSpec
import com.sublearn.core.settings.SettingsRepository
import com.sublearn.core.settings.SubtitleLayerSettings
import com.sublearn.core.settings.SubtitleSettings
import com.sublearn.core.settings.SubtitlePlacement
import com.sublearn.core.subtitles.LoadedTrack
import com.sublearn.core.subtitles.NormalizerConfig
import com.sublearn.core.subtitles.ShadowingMath
import com.sublearn.core.subtitles.ShadowingPauseConfig
import com.sublearn.core.subtitles.SubtitleBlock
import com.sublearn.core.subtitles.SubtitleDocument
import com.sublearn.core.subtitles.SubtitleFile
import com.sublearn.core.subtitles.SubtitleLanguage
import com.sublearn.core.subtitles.TrackRole
import com.sublearn.core.translate.TranslationProgress
import com.sublearn.core.translate.TranslationService
import com.sublearn.core.translate.WordTranslation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.util.LinkedHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The brain of the player screen.
 *
 * It owns the subtitle documents for both layers, the shadowing state machine, the gesture
 * feedback and the popup/answer state, and it is the only place that writes subtitle settings and
 * [PlayerUi]. [PlayerController] stays media-only (D-11), which keeps this class testable against
 * `FakePlayerController`.
 *
 * Gestures reach this class as *intents* ("seek by this drag", "volume is now 0.4"), never as raw
 * pointer data, and every intent is resolved through the user's gesture mapping here. That is why
 * a remapped gesture works the same whether it came from the surface, a button or a double tap.
 */
class PlayerViewModel(
    private val application: Application,
    /** Exposed so the screen can attach and detach the Media3 surface. */
    val controller: PlayerController,
    private val settingsRepository: SettingsRepository,
    private val subtitles: com.sublearn.core.subtitles.SubtitleRepository,
    private val fileSource: SafSubtitleFileSource,
    private val translation: TranslationService,
    private val myWords: MyWordsRepository,
    private val recentVideos: RecentVideoRepository,
    private val wordLevels: WordLevelSource,
    private val ai: AiAssistant,
    private val secrets: SecretStore,
    private val lexiconAccessProvider: () -> com.sublearn.core.nlp.LexiconAccess? = { null },
    /**
     * The sense ranker for the profile selected right now, or null to keep the lexicon's own
     * order (`D-76`). Asked for per popup rather than captured, because the user can change
     * profile while a video is open.
     */
    private val senseRankerProvider: () -> com.sublearn.core.nlp.SenseRanker? = { null },
    private val orchestrator: com.sublearn.core.translate.TranslationOrchestrator? = null,
    private val phraseSpanCacheDao: com.sublearn.core.data.PhraseSpanCacheDao? = null,
) : ViewModel() {
    private val cueStreamAnalyzer = com.sublearn.core.nlp.CueStreamPhraseAnalyzer()
    private val fallbackLemmatiser = com.sublearn.core.nlp.Lemmatiser()
    private val fallbackPosTagger = com.sublearn.core.nlp.LexiconPosTagger()
    private val _ui = MutableStateFlow(PlayerUi())
    val ui: StateFlow<PlayerUi> = _ui.asStateFlow()

    /**
     * One-shot requests the Activity must carry out. Overflow drops the oldest request rather than
     * refusing a new one, so a long brightness drag can never block a volume change.
     */
    private val _intent = MutableSharedFlow<PlayerIntent>(
        extraBufferCapacity = INTENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val intents: SharedFlow<PlayerIntent> = _intent

    val playback: StateFlow<PlaybackState> get() = controller.state

    private val externalTracks = TrackRole.entries.associateWith { LinkedHashMap<String, LoadedTrack>() }
    private val subtitleLoadTokens = mutableMapOf<Pair<TrackRole, String>, Long>()
    private var nextSubtitleLoadToken = 0L
    private var mediaGeneration = 0L
    private var plan: ShadowPlan? = null
    private var pendingAdvance: Job? = null
    private var aiJob: Job? = null
    private var popupJob: Job? = null
    private var autoHideJob: Job? = null
    private var lockJob: Job? = null
    private var hudJob: Job? = null
    private var messageJob: Job? = null
    private var learningCardsJob: Job? = null
    private var learningCardsKey: Pair<List<String>, AppSettings>? = null
    private var learningCardsPositionMs = 0L
    private var wordStateJob: Job? = null
    private var wordStateKey: List<String> = emptyList()
    private var openedTarget: MediaTarget? = null
    private var embeddedCues: List<com.sublearn.core.player.EmbeddedCue> = emptyList()
    private var lastTrackListSignature: String? = null
    private var lastAppliedEmbeddedKeys: String? = null
    private var pendingEmbeddedDefaultAssignment = false
    private var scrubWasPlaying = false
    private var aiKeyPresent = false
    private var checkedKeyRef: String? = null
    private var wasPlaying = false

    /** The block a shadowing run already handled, so reaching its end once never re-triggers it. */
    private var handledBlockId: Long? = null

    private var seekActive = false
    private var seekOriginMs = 0L
    private var seekTargetMs = 0L
    private var twoFingerActive = false
    private var twoFingerStartSpeed = 100

    /** Speed to restore when a long press ends; null while no long press is active. */
    private var holdPreviousSpeed: Int? = null

    /** A quick action whose hold is temporarily inverting it; release undoes the inversion. */
    private var peekHeld: QuickActionId? = null

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings -> onSettings(settings) }
        }
        viewModelScope.launch {
            controller.state.collect { state -> onPosition(state) }
        }
        viewModelScope.launch {
            controller.embeddedCues.collect { cues ->
                embeddedCues = cues.filter { it.text.isNotBlank() }
                publish()
            }
        }
        viewModelScope.launch {
            controller.events.collect { event ->
                when (event) {
                    is com.sublearn.core.player.PlayerEvent.Failed -> message(event.message)
                    is com.sublearn.core.player.PlayerEvent.Ended -> {
                        plan = null
                        pendingAdvance?.cancel()
                        openedTarget?.let { recentVideos.touch(it.uri, it.title, playback.value.durationMs, it.startPositionMs) }
                        showControls()
                    }
                    else -> Unit
                }
            }
        }
    }

    // ------------------------------------------------------------------ opening

    /** Loads the media, restores the saved position and pulls in sidecar subtitle files. */
    fun open(uri: String, title: String, subtitleUris: List<PickedSubtitle> = emptyList()) {
        viewModelScope.launch {
            val currentState = controller.state.value
            val stillLoaded = openedTarget?.uri == uri && currentState.target?.uri == uri && currentState.error == null
            if (stillLoaded) {
                // Coming back to the video that is already playing: keep playback and the loaded layers.
                subtitleUris.forEach { picked -> loadFile(picked.role, picked.uri, picked.name) }
                // Restore every remembered file-backed track, not just the last one in each layer.
                TrackRole.entries.forEach { role ->
                    val keys = _ui.value.settings.subtitleLayer(role).externalFileKeys
                    keys.filterNot { it in externalTracks.getValue(role) }.forEach { key ->
                        if (subtitleUris.none { it.role == role && it.uri == key }) {
                            loadFile(role, key, key.substringAfterLast('/'))
                        }
                    }
                }
                _ui.value = _ui.value.copy(opening = false)
                publish()
                return@launch
            }
            _ui.value = _ui.value.copy(opening = true)
            mediaGeneration += 1L
            subtitleLoadTokens.clear()
            val previousTargetUri = openedTarget?.uri
            val previousSettings = _ui.value.settings
            val restoreRememberedFiles = previousSettings.subtitles.rememberedMediaUri == null ||
                previousSettings.subtitles.rememberedMediaUri == uri
            val settings = if (restoreRememberedFiles) {
                previousSettings.copy(subtitles = previousSettings.subtitles.copy(rememberedMediaUri = uri))
            } else {
                previousSettings.copy(subtitles = clearSubtitleSources(previousSettings.subtitles, uri))
            }
            // Update the local snapshot before the controller can publish tracks for the new media.
            _ui.value = _ui.value.copy(settings = settings)
            if (!restoreRememberedFiles || previousTargetUri != uri) {
                TrackRole.entries.forEach { role -> externalTracks.getValue(role).clear() }
            }
            settingsRepository.update { current ->
                val subtitles = if (restoreRememberedFiles) {
                    current.subtitles.copy(rememberedMediaUri = uri)
                } else {
                    clearSubtitleSources(current.subtitles, uri)
                }
                current.copy(subtitles = subtitles)
            }
            lastTrackListSignature = null
            lastAppliedEmbeddedKeys = null
            pendingEmbeddedDefaultAssignment = false
            val saved = if (settings.player.rememberPosition && settings.player.resumeOnOpen) {
                runCatching { recentVideos.find(uri) }.getOrNull()
            } else {
                null
            }
            val position = saved?.lastPositionMs?.takeIf { it > RESUME_THRESHOLD_MS && it < (saved.durationMs - 3_000L) } ?: 0L
            val target = MediaTarget(uri = uri, title = title, startPositionMs = position)
            openedTarget = target
            controller.clearTrackOverride(PlayerTrackType.TEXT)
            val playlist = recentPlaylist(target)
            controller.setPlaylist(playlist, playlist.indexOfFirst { it.uri == uri }.coerceAtLeast(0))
            // The default speed and aspect are per session, so they are applied once per opened video.
            if (settings.player.defaultSpeedPercent != NORMAL_SPEED) controller.setSpeedPercent(settings.player.defaultSpeedPercent)
            controller.setAspect(settings.player.aspect, settings.player.customAspectWidth, settings.player.customAspectHeight)
            if (settings.subtitles.listPanelEnabled) setListOpen(true)
            _ui.value = _ui.value.copy(opening = false)
            subtitleUris.forEach { picked -> loadFile(picked.role, picked.uri, picked.name) }
            if (subtitleUris.isEmpty()) {
                if (restoreRememberedFiles) {
                    TrackRole.entries.forEach { role ->
                        settings.subtitleLayer(role).externalFileKeys.forEach { key ->
                            loadFile(role, key, key.substringAfterLast('/'))
                        }
                    }
                }
                if (settings.subtitles.autoLoadExternal) loadSidecars(uri)
            }
            if (saved != null && position > 0L) {
                message(application.getString(R.string.player_msg_resumed, clock(position)))
            }
            publish()
        }
    }

    private fun clearSubtitleSources(settings: SubtitleSettings, mediaUri: String): SubtitleSettings {
        val cleared = TrackRole.entries.fold(settings) { acc, role ->
            val layer = acc.layer(role).copy(
                externalFileKeys = emptyList(),
                disabledExternalFileKeys = emptyList(),
                embeddedTrackIndexes = emptyList(),
                embeddedTrackKeys = emptyList(),
            )
            acc.updated(role, layer)
        }
        return cleared.copy(rememberedMediaUri = mediaUri)
    }

    /** A queue transition changes media identity just like an explicit open; never reuse old tracks. */
    private fun onPlaybackTargetChanged(target: MediaTarget) {
        openedTarget = target
        mediaGeneration += 1L
        subtitleLoadTokens.clear()
        externalTracks.values.forEach { it.clear() }
        embeddedCues = emptyList()
        lastTrackListSignature = null
        lastAppliedEmbeddedKeys = null
        pendingEmbeddedDefaultAssignment = false
        wordStateKey = emptyList()
        wordStateJob?.cancel()
        pendingAdvance?.cancel()
        plan = null
        handledBlockId = null
        popupJob?.cancel()
        _ui.value = _ui.value.copy(
            settings = _ui.value.settings.copy(
                subtitles = clearSubtitleSources(_ui.value.settings.subtitles, target.uri),
            ),
            popup = null,
            wordStates = emptyMap(),
        )
        controller.clearTrackOverride(PlayerTrackType.TEXT)
        viewModelScope.launch {
            settingsRepository.update { settings ->
                settings.copy(subtitles = clearSubtitleSources(settings.subtitles, target.uri))
            }
            if (_ui.value.settings.subtitles.autoLoadExternal) loadSidecars(target.uri)
        }
    }

    /**
     * Recent videos form the queue used by the previous/next transport buttons. Unreadable SAF
     * entries are omitted; the item explicitly opened by the user is always retained.
     */
    private suspend fun recentPlaylist(current: MediaTarget): List<MediaTarget> {
        val recent = runCatching { recentVideos.observeRecent(limit = MAX_PLAYLIST_SIZE).first() }
            .getOrDefault(emptyList())
        val now = System.currentTimeMillis()
        val ordered = recent
            .filter { it.uri == current.uri || (it.isPlayable && canReadRecentUri(it.uri)) }
            .map { row ->
                val target = if (row.uri == current.uri) current else MediaTarget(row.uri, row.title)
                row.lastOpenedAt to target
            }
            .toMutableList()
        if (ordered.none { it.second.uri == current.uri }) ordered += now to current
        val chronological = ordered.sortedWith(compareBy({ it.first }, { it.second.uri }))
        if (chronological.size <= MAX_PLAYLIST_SIZE) return chronological.map { it.second }
        val currentIndex = chronological.indexOfFirst { it.second.uri == current.uri }
        val firstIndex = (currentIndex - (MAX_PLAYLIST_SIZE - 1)).coerceAtLeast(0)
        return chronological.drop(firstIndex).take(MAX_PLAYLIST_SIZE).map { it.second }
    }

    private fun canReadRecentUri(value: String): Boolean = runCatching {
        val uri = Uri.parse(value)
        when (uri.scheme) {
            "content" -> application.contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isReadPermission
            }
            "http", "https" -> true
            else -> false
        }
    }.getOrDefault(false)

    data class PickedSubtitle(val uri: String, val name: String, val role: TrackRole)

    private suspend fun loadSidecars(videoUri: String) {
        val generation = mediaGeneration
        if (videoUri != openedTarget?.uri) return
        val tree = settingsRepository.current().subtitles.sidecarTreeUri
        val files = runCatching { fileSource.sidecars(videoUri, tree) }.getOrDefault(emptyList())
        if (generation != mediaGeneration || files.isEmpty()) return
        val nativeTag = _ui.value.settings.languages.nativeLanguage
        files.forEach { file ->
            if (generation != mediaGeneration) return
            val guess = SubtitleRoleGuess.guess(file.name, nativeTag)
            loadFile(guess.role, file.key, file.name)
        }
    }

    /** Parses an additional subtitle source into a layer without replacing its existing sources. */
    fun loadFile(role: TrackRole, uri: String, name: String, normalizer: NormalizerConfig? = null) {
        val loadKey = role to uri
        val generation = mediaGeneration
        val token = ++nextSubtitleLoadToken
        subtitleLoadTokens[loadKey] = token
        viewModelScope.launch {
            val config = normalizer ?: _ui.value.settings.subtitles.normalizer
            val result = subtitles.load(SubtitleFile(key = uri, name = name), role, config)
            val loaded = result.getOrNull()
            if (loaded != null && generation == mediaGeneration && subtitleLoadTokens[loadKey] == token) {
                settingsRepository.update { settings ->
                    if (generation != mediaGeneration || subtitleLoadTokens[loadKey] != token) {
                        settings
                    } else {
                        val layer = settings.subtitleLayer(role)
                        settings.copy(
                            subtitles = settings.subtitles.updated(
                                role,
                                layer.copy(
                                    externalFileKeys = (layer.externalFileKeys + uri).distinct(),
                                    disabledExternalFileKeys = layer.disabledExternalFileKeys - uri,
                                ),
                            ),
                        )
                    }
                }
                if (generation == mediaGeneration && subtitleLoadTokens[loadKey] == token) {
                    externalTracks.getValue(role)[uri] = loaded
                    publish()
                }
            } else if (generation == mediaGeneration && subtitleLoadTokens[loadKey] == token) {
                message(result.errorOrNull()?.message)
            }
        }
    }

    /** SUB-6: reload every attached subtitle with the same normalizer, preserving multi-track layers. */
    fun applyNormalizer(config: NormalizerConfig) {
        viewModelScope.launch {
            settingsRepository.update { settings ->
                settings.copy(subtitles = settings.subtitles.copy(normalizer = config))
            }
            val saved = settingsRepository.current()
            TrackRole.entries.forEach { role ->
                saved.subtitleLayer(role).externalFileKeys.forEach { key ->
                    loadFile(role, key, externalTracks.getValue(role)[key]?.track?.name ?: key.substringAfterLast('/'), config)
                }
            }
            message(application.getString(R.string.subtitle_tools_applied))
        }
    }

    fun removeFile(role: TrackRole, uri: String) {
        subtitleLoadTokens.remove(role to uri)
        externalTracks.getValue(role).remove(uri)
        viewModelScope.launch {
            settingsRepository.update { settings ->
                val layer = settings.subtitleLayer(role)
                settings.copy(
                    subtitles = settings.subtitles.updated(
                        role,
                        layer.copy(
                            externalFileKeys = layer.externalFileKeys - uri,
                            disabledExternalFileKeys = layer.disabledExternalFileKeys - uri,
                        ),
                    ),
                )
            }
            publish()
        }
    }

    fun setExternalFileEnabled(role: TrackRole, uri: String, enabled: Boolean) = updateLayer(role) { layer ->
        val disabled = if (enabled) layer.disabledExternalFileKeys - uri else (layer.disabledExternalFileKeys + uri).distinct()
        layer.copy(disabledExternalFileKeys = disabled)
    }

    // ------------------------------------------------------------------ settings and layers

    private fun onSettings(settings: AppSettings) {
        refreshAiKey(settings)
        if (TrackRole.entries.any { role ->
                val layer = settings.subtitleLayer(role)
                layer.embeddedTrackKeys.isNotEmpty() || layer.embeddedTrackIndexes.isNotEmpty()
            }
        ) pendingEmbeddedDefaultAssignment = false
        val current = _ui.value
        _ui.value = current.copy(
            settings = settings,
            learningMode = settings.learning.mode,
            shadowing = current.shadowing.copy(
                autoRepeat = settings.shadowing.autoRepeatEnabled || settings.shadowing.repeatOnEveryBlock,
                stopAtEnd = settings.shadowing.stopAtEndOfBlock,
                repeatCount = settings.shadowing.repeatCount,
            ),
            list = current.list.copy(noSpoiler = settings.subtitles.noSpoilerMode),
        )
        syncEmbeddedTextTracks(settings)
        publish()
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settingsRepository.update(transform) }
    }

    fun updateLayer(role: TrackRole, transform: (SubtitleLayerSettings) -> SubtitleLayerSettings) {
        updateSettings { settings ->
            settings.copy(subtitles = settings.subtitles.updated(role, transform(settings.subtitleLayer(role))))
        }
    }

    fun toggleLayerVisible(role: TrackRole) = updateLayer(role) { it.copy(visible = !it.visible) }

    fun setLayerDelay(role: TrackRole, deltaMs: Long) =
        updateLayer(role) { it.copy(delayMs = (it.delayMs + deltaMs).coerceIn(-MAX_DELAY_MS, MAX_DELAY_MS)) }

    fun setLayerScale(role: TrackRole, percent: Int) =
        updateLayer(role) { it.copy(scalePercent = percent.coerceIn(MIN_SCALE_PERCENT, MAX_SCALE_PERCENT)) }

    fun setLayerTransparency(role: TrackRole, percent: Int) =
        updateLayer(role) { it.copy(transparencyPercent = percent.coerceIn(0, 100)) }

    fun toggleListFor(role: TrackRole) = updateLayer(role) { it.copy(showInList = !it.showInList) }

    /** SUB-3: layout mode turns subtitle plates into drag handles and gives gestures to them alone. */
    fun setLayoutMode(on: Boolean) {
        autoHideJob?.cancel()
        _ui.value = _ui.value.copy(
            layoutMode = on,
            controlsVisible = true,
            sheet = if (on) Sheet.NONE else _ui.value.sheet,
            popup = if (on) null else _ui.value.popup,
            menuOpen = false,
        )
        if (!on) refreshAutoHide()
    }

    /** Drag of one plate in layout mode: the move is kept as a draft and persisted at the end. */
    fun onLayerDrag(role: TrackRole, dxDp: Float, dyDp: Float) {
        val current = _ui.value.layerDrafts[role] ?: _ui.value.settings.subtitleLayer(role).placement
        val moved = current.movedBy(dxDp, dyDp)
        _ui.value = _ui.value.copy(layerDrafts = _ui.value.layerDrafts + (role to moved))
        publish()
    }

    fun onLayerDragEnd(role: TrackRole) {
        val draft = _ui.value.layerDrafts[role] ?: return
        _ui.value = _ui.value.copy(layerDrafts = _ui.value.layerDrafts - role)
        updateLayer(role) { it.copy(placement = draft) }
    }

    /** A soft subtitle can be independently checked in either layer. */
    fun assignEmbedded(ref: TrackRef, role: TrackRole, enabled: Boolean) {
        updateLayer(role) { layer ->
            val legacyKeys = if (layer.embeddedTrackKeys.isEmpty()) {
                playback.value.textTracks.filter { it.ref.trackIndex in layer.embeddedTrackIndexes }.map { it.ref.stableKey }
            } else {
                layer.embeddedTrackKeys
            }
            val keys = if (enabled) (legacyKeys + ref.stableKey).distinct() else legacyKeys - ref.stableKey
            layer.copy(embeddedTrackKeys = keys, embeddedTrackIndexes = emptyList())
        }
    }

    fun isEmbeddedAssigned(ref: TrackRef, role: TrackRole): Boolean {
        val layer = _ui.value.settings.subtitleLayer(role)
        return if (layer.embeddedTrackKeys.isNotEmpty()) {
            ref.stableKey in layer.embeddedTrackKeys
        } else {
            ref.trackIndex in layer.embeddedTrackIndexes
        }
    }

    private fun syncEmbeddedTextTracks(settings: AppSettings = _ui.value.settings) {
        val selectedKeys = TrackRole.entries.flatMap { role ->
            val layer = settings.subtitleLayer(role)
            if (layer.embeddedTrackKeys.isNotEmpty()) {
                layer.embeddedTrackKeys
            } else {
                controller.state.value.textTracks.filter { it.ref.trackIndex in layer.embeddedTrackIndexes }
                    .map { it.ref.stableKey }
            }
        }.toSet()
        val textTracks = controller.state.value.textTracks
        if (textTracks.isEmpty() || (pendingEmbeddedDefaultAssignment && selectedKeys.isEmpty())) return
        val signature = selectedKeys.sorted().joinToString("|")
        if (signature == lastAppliedEmbeddedKeys) return
        lastAppliedEmbeddedKeys = signature
        val refs = textTracks.filter { it.ref.stableKey in selectedKeys }.map { it.ref }
        controller.setSelectedTextTracks(refs)
    }

    private fun adoptDefaultEmbeddedTrack(state: PlaybackState): Boolean {
        val signature = state.target?.uri + ":" + state.textTracks.joinToString("|") { it.ref.stableKey }
        if (signature == lastTrackListSignature || state.textTracks.isEmpty()) return false
        lastTrackListSignature = signature
        val settings = _ui.value.settings
        val hasAssignment = TrackRole.entries.any { role ->
            val layer = settings.subtitleLayer(role)
            layer.embeddedTrackKeys.isNotEmpty() || layer.embeddedTrackIndexes.isNotEmpty()
        }
        val selected = state.textTracks.filter { it.isSelected }
        if (!hasAssignment && selected.isNotEmpty()) {
            pendingEmbeddedDefaultAssignment = true
            val roles = selected.groupBy { track ->
                SubtitleRoleGuess.guess(track.displayLabel, settings.languages.nativeLanguage).role
            }
            updateSettings { original ->
                roles.entries.fold(original) { acc, (role, tracks) ->
                    val layer = acc.subtitleLayer(role)
                    acc.copy(subtitles = acc.subtitles.updated(role, layer.copy(
                        embeddedTrackKeys = (layer.embeddedTrackKeys + tracks.map { it.ref.stableKey }).distinct(),
                    )))
                }
            }
            return true
        }
        syncEmbeddedTextTracks(settings)
        return false
    }

    // ------------------------------------------------------------------ position and chrome

    private fun onPosition(state: PlaybackState) {
        state.target?.let { target ->
            if (target.uri != openedTarget?.uri) onPlaybackTargetChanged(target)
        }
        val defaultAssignmentPending = adoptDefaultEmbeddedTrack(state)
        if (!defaultAssignmentPending) syncEmbeddedTextTracks(_ui.value.settings)
        publish(state)
        runShadowPlan(state, _ui.value.settings)
        if (state.isPlaying != wasPlaying) {
            wasPlaying = state.isPlaying
            refreshAutoHide()
        }
        refreshWordStates(state, _ui.value.settings)
    }

    /** Auto-hide only runs while nothing else needs the chrome; a paused video keeps it when configured. */
    private fun canAutoHide(): Boolean {
        val ui = _ui.value
        val state = playback.value
        if (!state.isPlaying && ui.settings.player.showControlsWhilePaused) return false
        if (state.isEnded) return false
        return !ui.layoutMode &&
            !ui.scrubbing &&
            !ui.menuOpen &&
            ui.sheet == Sheet.NONE &&
            ui.ai.state != AiState.BUSY &&
            ui.ai.state != AiState.ANSWER
    }

    /** Restarts the timer. Called on state changes only, never on the 120 ms position tick. */
    private fun refreshAutoHide() {
        autoHideJob?.cancel()
        if (!_ui.value.controlsVisible || !canAutoHide()) return
        val ms = _ui.value.settings.player.controlsAutoHideMs.coerceAtLeast(MIN_AUTO_HIDE_MS)
        autoHideJob = viewModelScope.launch {
            delay(ms)
            if (canAutoHide()) _ui.value = _ui.value.copy(controlsVisible = false)
        }
    }

    fun showControls() {
        if (!_ui.value.controlsVisible) _ui.value = _ui.value.copy(controlsVisible = true)
        refreshAutoHide()
    }

    fun toggleControls() {
        if (_ui.value.controlsVisible) {
            autoHideJob?.cancel()
            _ui.value = _ui.value.copy(controlsVisible = false)
        } else {
            showControls()
        }
    }

    /**
     * A single tap on the video (PLY-2). It closes a popup first, shows the unlock button while
     * locked, and otherwise toggles the controls.
     */
    fun onSurfaceTap() {
        val ui = _ui.value
        when {
            ui.popup != null -> dismissPopup()
            ui.layoutMode -> Unit
            ui.locked -> showLockButton()
            else -> toggleControls()
        }
    }

    /** The double tap zone decides the action: an explicit gesture mapping wins over the double tap setting. */
    fun onSurfaceDoubleTap(side: TapSide) {
        if (!gesturesAllowed()) return
        val settings = _ui.value.settings
        val slot = when (side) {
            TapSide.LEFT -> GestureSlot.DOUBLE_TAP_LEFT
            TapSide.CENTER -> GestureSlot.DOUBLE_TAP_CENTER
            TapSide.RIGHT -> GestureSlot.DOUBLE_TAP_RIGHT
        }
        val explicit = settings.gestures.mapping.containsKey(slot.key)
        val action = if (explicit) settings.gestures.actionFor(slot) else defaultDoubleTap(side, settings.player.doubleTapAction)
        when (action) {
            GestureAction.SEEK_FORWARD -> seekWithHud(settings.player.doubleTapSeekMs)
            GestureAction.SEEK_BACKWARD -> seekWithHud(-settings.player.doubleTapSeekMs)
            else -> perform(action)
        }
    }

    private fun defaultDoubleTap(side: TapSide, action: DoubleTapAction): GestureAction = when (action) {
        DoubleTapAction.PAUSE -> GestureAction.TOGGLE_PLAY
        DoubleTapAction.NONE -> GestureAction.NONE
        DoubleTapAction.SEEK -> when (side) {
            TapSide.LEFT -> GestureAction.SEEK_BACKWARD
            TapSide.CENTER -> GestureAction.TOGGLE_PLAY
            TapSide.RIGHT -> GestureAction.SEEK_FORWARD
        }
    }

    /** Runs a discrete action: the same code path serves gestures, the centre cluster and quick actions. */
    private fun perform(action: GestureAction) {
        val player = _ui.value.settings.player
        when (action) {
            GestureAction.NONE, GestureAction.BRIGHTNESS, GestureAction.VOLUME,
            GestureAction.SEEK, GestureAction.PLAYBACK_SPEED -> Unit
            GestureAction.TOGGLE_PLAY -> controller.togglePlayPause()
            GestureAction.SEEK_FORWARD -> seekWithHud(player.seekStepMs)
            GestureAction.SEEK_BACKWARD -> seekWithHud(-player.seekStepMs)
            GestureAction.TOGGLE_CONTROLS -> toggleControls()
            GestureAction.ASPECT_RATIO -> cycleAspect()
            GestureAction.REPEAT_BLOCK -> toggleRepeatBlock(autoRepeat = false)
            GestureAction.TOGGLE_LEARNING_SUBTITLE -> toggleLayerVisible(TrackRole.LEARNING)
            GestureAction.TOGGLE_TRANSLATION_SUBTITLE -> toggleLayerVisible(TrackRole.TRANSLATION)
            GestureAction.LOCK -> toggleLock()
            GestureAction.NEXT_BLOCK -> stepBlock(1)
            GestureAction.PREVIOUS_BLOCK -> stepBlock(-1)
            GestureAction.TOGGLE_MUTE -> setMuted(!playback.value.isMuted)
        }
    }

    private fun seekWithHud(deltaMs: Long) {
        if (!gesturesAllowed() || playback.value.isLive) return
        controller.seekBy(deltaMs)
        val forward = deltaMs >= 0L
        val add = (abs(deltaMs) / MS_PER_SECOND).toInt()
        val previous = _ui.value.hud as? GestureHud.DoubleTap
        val seconds = if (previous != null && previous.forward == forward) previous.seconds + add else add
        setHud(GestureHud.DoubleTap(forward, seconds))
        lingerHud(DOUBLE_TAP_HUD_MS)
    }

    // ------------------------------------------------------------------ continuous gestures

    /** The action a side of the screen performs on a vertical drag, from the user's mapping. */
    fun sideAction(side: TapSide): GestureAction =
        _ui.value.settings.gestures.actionFor(if (side == TapSide.LEFT) GestureSlot.LEFT_VERTICAL else GestureSlot.RIGHT_VERTICAL)

    /** Brightness or volume at [level] (0..1). The gesture layer already applied inversion and clamping rules. */
    fun onSideDrag(side: TapSide, level: Float) {
        if (!gesturesAllowed()) return
        val value = level.coerceIn(0f, 1f)
        when (sideAction(side)) {
            GestureAction.BRIGHTNESS -> {
                _intent.tryEmit(PlayerIntent.Brightness(value))
                setHud(GestureHud.Brightness(value))
            }
            GestureAction.VOLUME -> {
                _intent.tryEmit(PlayerIntent.Volume(value))
                setHud(GestureHud.Volume(value))
            }
            else -> Unit
        }
    }

    fun onSideDragEnd() = lingerHud()

    fun onHorizontalDragStart() {
        val settings = _ui.value.settings
        seekActive = gesturesAllowed() &&
            settings.gestures.horizontalEnabled &&
            settings.gestures.actionFor(GestureSlot.HORIZONTAL) == GestureAction.SEEK &&
            !playback.value.isLive
        if (!seekActive) return
        seekOriginMs = playback.value.positionMs
        seekTargetMs = seekOriginMs
    }

    /** [deltaPx] is the finger travel since the drag began; the whole width is [swipeSeekSecondsPerScreen] seconds. */
    fun onHorizontalDragUpdate(deltaPx: Float, widthPx: Float) {
        if (!seekActive || widthPx <= 0f) return
        val duration = playback.value.durationMs
        val deltaMs = (deltaPx / widthPx * _ui.value.settings.player.swipeSeekSecondsPerScreen * MS_PER_SECOND).toLong()
        val raw = (seekOriginMs + deltaMs).coerceAtLeast(0L)
        seekTargetMs = if (duration > 0L) raw.coerceAtMost(duration) else raw
        setHud(GestureHud.Seek(seekTargetMs, seekTargetMs - seekOriginMs, duration))
    }

    fun onHorizontalDragEnd() {
        if (!seekActive) return
        seekActive = false
        controller.seekTo(seekTargetMs)
        lingerHud()
    }

    /** Two-finger drag up raises the speed and down lowers it, snapped to the configured step. */
    fun onTwoFingerStart() {
        val settings = _ui.value.settings
        twoFingerActive = gesturesAllowed() &&
            settings.player.twoFingerSpeedShortcut &&
            settings.gestures.actionFor(GestureSlot.TWO_FINGER_VERTICAL) == GestureAction.PLAYBACK_SPEED
        twoFingerStartSpeed = playback.value.speedPercent
    }

    fun onTwoFingerSpeed(dyPx: Float, heightPx: Float) {
        if (!twoFingerActive || heightPx <= 0f) return
        val player = _ui.value.settings.player
        val range = (player.speedMaxPercent - player.speedMinPercent).coerceAtLeast(1)
        val raw = twoFingerStartSpeed + (-dyPx / heightPx) * range * TWO_FINGER_FULL_HEIGHT_FACTOR
        val step = player.speedStepPercent.coerceAtLeast(1)
        val snapped = ((raw / step).roundToInt() * step).coerceIn(player.speedMinPercent, player.speedMaxPercent)
        controller.setSpeedPercent(snapped)
        setHud(GestureHud.Speed(snapped))
    }

    fun onTwoFingerEnd() {
        if (!twoFingerActive) return
        twoFingerActive = false
        lingerHud()
    }

    /** Pinch runs whatever the user mapped it to; the default is the aspect ratio. */
    fun onPinch() {
        if (!gesturesAllowed()) return
        perform(_ui.value.settings.gestures.actionFor(GestureSlot.PINCH))
    }

    /** Hold the surface to fast-forward at the configured long-press speed, and let go to return. */
    fun onHoldSpeed(active: Boolean) {
        if (active) {
            if (holdPreviousSpeed != null || !gesturesAllowed()) return
            val player = _ui.value.settings.player
            val previous = playback.value.speedPercent
            holdPreviousSpeed = previous
            val target = player.longPressSpeedPercent.coerceIn(player.speedMinPercent, player.speedMaxPercent)
            controller.setSpeedPercent(target)
            _ui.value = _ui.value.copy(holdSpeed = true)
            setHud(GestureHud.Speed(target))
        } else {
            val previous = holdPreviousSpeed ?: return
            holdPreviousSpeed = null
            controller.setSpeedPercent(previous)
            _ui.value = _ui.value.copy(holdSpeed = false)
            lingerHud()
        }
    }

    fun onScrubStart() {
        autoHideJob?.cancel()
        scrubWasPlaying = playback.value.isPlaying
        if (scrubWasPlaying) controller.pause()
        _ui.value = _ui.value.copy(scrubbing = true, controlsVisible = true)
    }

    /** Seeking on each drag update lets the decoder and subtitle layers preview before release. */
    fun onScrubPreview(targetMs: Long) {
        if (_ui.value.scrubbing) controller.seekTo(targetMs.coerceAtLeast(0L))
    }

    fun onScrubEnd(targetMs: Long) {
        controller.seekTo(targetMs.coerceAtLeast(0L))
        _ui.value = _ui.value.copy(scrubbing = false)
        if (scrubWasPlaying) controller.play()
        scrubWasPlaying = false
        refreshAutoHide()
    }

    private fun setHud(hud: GestureHud?) {
        hudJob?.cancel()
        _ui.value = _ui.value.copy(hud = hud)
    }

    private fun lingerHud(ms: Long = HUD_LINGER_MS) {
        hudJob?.cancel()
        hudJob = viewModelScope.launch {
            delay(ms)
            _ui.value = _ui.value.copy(hud = null)
        }
    }

    /** Gestures are off in layout mode, and off while locked when the user keeps the lock strict. */
    private fun gesturesAllowed(): Boolean {
        val ui = _ui.value
        if (ui.layoutMode) return false
        return !(ui.locked && ui.settings.player.lockGesturesWhenLocked)
    }

    // ------------------------------------------------------------------ lock and rotation

    /** PLY-1 lock: a locked screen shows only the unlock button, and only after a tap on the video. */
    fun toggleLock() {
        lockJob?.cancel()
        if (_ui.value.locked) {
            _ui.value = _ui.value.copy(locked = false, lockButtonVisible = false, controlsVisible = true)
            refreshAutoHide()
        } else {
            autoHideJob?.cancel()
            _ui.value = _ui.value.copy(locked = true, controlsVisible = false)
            showLockButton()
        }
    }

    private fun showLockButton() {
        lockJob?.cancel()
        _ui.value = _ui.value.copy(lockButtonVisible = true)
        lockJob = viewModelScope.launch {
            delay(LOCK_BUTTON_MS)
            _ui.value = _ui.value.copy(lockButtonVisible = false)
        }
    }

    /** PLY-5: pins the orientation to the video's own shape, or returns to automatic rotation. */
    fun toggleRotationLock() {
        val player = _ui.value.settings.player
        val next = if (player.orientationLock == OrientationLock.AUTO || player.orientationLock == OrientationLock.SENSOR) {
            val state = playback.value
            val portrait = if (state.videoRotationDegrees % 180 != 0) {
                state.videoWidth > state.videoHeight
            } else {
                state.hasVideo && state.videoHeight > state.videoWidth
            }
            if (portrait) OrientationLock.PORTRAIT else OrientationLock.LANDSCAPE
        } else {
            OrientationLock.AUTO
        }
        val textRes = if (next == OrientationLock.AUTO) R.string.player_msg_rotation_unlocked else R.string.player_msg_rotation_locked
        message(application.getString(textRes))
        updateSettings { settings -> settings.copy(player = settings.player.copy(orientationLock = next)) }
    }

    fun setMenuOpen(open: Boolean) {
        if (_ui.value.menuOpen == open) return
        _ui.value = _ui.value.copy(menuOpen = open)
        if (open) autoHideJob?.cancel() else refreshAutoHide()
    }

    // ------------------------------------------------------------------ playback and shadowing (SHD-1..3)

    private class ShadowPlan(
        val block: SubtitleBlock,
        val segments: List<com.sublearn.core.subtitles.RepeatSegment>,
        val repeatTotal: Int,
        var completed: Int = 0,
        var index: Int = 0,
        var paused: Boolean = false,
    )

    private fun shadowConfig(settings: AppSettings) = ShadowingPauseConfig(
        baseMs = settings.shadowing.pauseBaseMs,
        durationMultiplier = settings.shadowing.pauseDurationMultiplier,
        pauseMaxMs = settings.shadowing.pauseMaxMs,
        preRollMs = settings.shadowing.preRollMs,
        minBlockDurationMs = settings.shadowing.minBlockDurationMs,
    )

    /**
     * Runs the repeat plan and the end-of-block stop.
     *
     * Position arrives every 120 ms, so the end of a block is detected with a lead of
     * [BLOCK_END_LEAD_MS] (longer than one tick) rather than a tolerance a tick can jump over. A
     * block is handled once: after a stop or a finished plan, playing on past its end never
     * re-triggers it.
     */
    private fun runShadowPlan(state: PlaybackState, settings: AppSettings) {
        val config = shadowConfig(settings)
        val active = plan
        if (active != null) {
            val pos = state.positionMs
            val movedAway = pos < active.block.startMs - MOVED_AWAY_MS || pos > active.block.endMs + MOVED_AWAY_MS
            if (movedAway) {
                pendingAdvance?.cancel()
                return finishPlan()
            }
            if (active.paused || !state.isPlaying) return
            val segment = active.segments.getOrNull(active.index) ?: return finishPlan()
            if (ShadowingMath.reachedBlockEnd(active.block, pos, BLOCK_END_LEAD_MS) || pos >= segment.endMs) {
                active.paused = true
                controller.pause()
                pendingAdvance?.cancel()
                pendingAdvance = viewModelScope.launch {
                    delay(segment.pauseAfterMs.coerceAtLeast(MIN_SEGMENT_PAUSE_MS))
                    advance(active, config)
                }
                publish()
            }
            return
        }
        val doc = primaryDocument() ?: return
        val block = doc.blockAt(state.positionMs)
        if (block?.id != handledBlockId) handledBlockId = null
        if (block == null || handledBlockId == block.id || !state.isPlaying) return
        val wantsAuto = settings.shadowing.autoRepeatEnabled || settings.shadowing.repeatOnEveryBlock
        val wantsStop = settings.shadowing.stopAtEndOfBlock
        if (!wantsAuto && !wantsStop) return
        if (block.durationMs < config.minBlockDurationMs) return
        if (!ShadowingMath.reachedBlockEnd(block, state.positionMs, BLOCK_END_LEAD_MS)) return
        handledBlockId = block.id
        if (wantsAuto) {
            startPlan(block, config, settings.shadowing.repeatCount.coerceAtLeast(1))
        } else {
            controller.pause()
        }
    }

    private fun startPlan(block: SubtitleBlock, config: ShadowingPauseConfig, repeatCount: Int) {
        val segments = ShadowingMath.plan(block, repeatCount, config)
        if (segments.isEmpty()) return
        handledBlockId = block.id
        plan = ShadowPlan(block, segments, repeatCount)
        controller.seekTo(ShadowingMath.repeatStart(block.startMs, config))
        controller.play()
        publish()
    }

    private fun advance(active: ShadowPlan, config: ShadowingPauseConfig) {
        if (plan !== active) return
        active.paused = false
        if (active.index + 1 < active.segments.size) {
            active.index += 1
            controller.seekTo(active.segments[active.index].startMs)
            controller.play()
        } else {
            active.completed += 1
            if (active.completed < active.repeatTotal) {
                active.index = 0
                controller.seekTo(ShadowingMath.repeatStart(active.block.startMs, config))
                controller.play()
            } else {
                finishPlan()
                return
            }
        }
        publish()
    }

    private fun finishPlan() {
        pendingAdvance?.cancel()
        plan = null
        publish()
    }

    private fun primaryDocument(): SubtitleDocument? = documentFor(TrackRole.LEARNING) ?: documentFor(TrackRole.TRANSLATION)

    /**
     * SHD-1: a tap repeats the block under the playhead once, and a hold runs the configured number
     * of repeats. A tap while a plan runs stops it.
     */
    fun toggleRepeatBlock(autoRepeat: Boolean = false) {
        if (plan != null) return finishPlan()
        val state = playback.value
        val doc = primaryDocument() ?: return message(application.getString(R.string.player_msg_need_subtitle))
        val block = doc.blockAt(state.positionMs) ?: return message(application.getString(R.string.player_msg_no_block))
        val settings = _ui.value.settings
        val count = if (autoRepeat) settings.shadowing.repeatCount.coerceAtLeast(1) else 1
        startPlan(block, shadowConfig(settings), count)
    }

    fun toggleStopAtEnd() = updateSettings { settings ->
        settings.copy(shadowing = settings.shadowing.copy(stopAtEndOfBlock = !settings.shadowing.stopAtEndOfBlock))
    }

    fun toggleAutoRepeat() = updateSettings { settings ->
        settings.copy(shadowing = settings.shadowing.copy(autoRepeatEnabled = !settings.shadowing.autoRepeatEnabled))
    }

    fun setRepeatCount(count: Int) = updateSettings { settings ->
        settings.copy(shadowing = settings.shadowing.copy(repeatCount = count.coerceIn(1, MAX_REPEAT_COUNT)))
    }

    // ------------------------------------------------------------------ seeking by subtitle

    fun stepBlock(delta: Int) {
        val preferredRole = _ui.value.list.role
        val role = preferredRole.takeIf { documentFor(it) != null }
            ?: TrackRole.entries.firstOrNull { documentFor(it) != null }
            ?: return message(application.getString(R.string.player_msg_need_subtitle))
        val doc = documentFor(role) ?: return
        val position = playback.value.positionMs
        val layer = _ui.value.settings.subtitleLayer(role)
        val shifted = position + layer.delayMs
        val target = if (delta > 0) doc.nextBlock(shifted) else doc.previousBlock(shifted)
        if (target == null) {
            message(application.getString(if (delta > 0) R.string.player_msg_last_subtitle else R.string.player_msg_first_subtitle))
            return
        }
        controller.seekTo((target.startMs - layer.delayMs).coerceAtLeast(0L))
        if (_ui.value.list.open) refreshList()
    }

    fun seekToBlock(block: SubtitleBlock) {
        controller.seekTo(block.startMs.coerceAtLeast(0L))
        showControls()
        controller.play()
    }

    // ------------------------------------------------------------------ popups (LRN-1)

    /**
     * A tap on the learning layer: one tap on a word translates that word (and any enclosing MWE),
     * two taps the line and three the block. Taps on the translation layer are read-only, so they
     * never open a card.
     */
    fun onSubtitleTap(role: TrackRole, taps: Int, word: String?, lineKey: String? = null, charOffset: Int = -1) {
        if (role != TrackRole.LEARNING) return
        val layer = _ui.value.layer(role)
        val matchedLine = if (lineKey != null) {
            layer.lines.firstOrNull { it.key == lineKey }
        } else {
            layer.lines.firstOrNull()
        }
        val block = matchedLine?.block ?: layer.block ?: return message(application.getString(R.string.player_msg_no_block))
        val lineText = matchedLine?.text ?: lineAt(block, playback.value.positionMs + layer.delayMs)
        val effectiveKey = lineKey ?: matchedLine?.key ?: "current"
        val spansForLine = matchedLine?.spans?.takeIf { it.isNotEmpty() } ?: _ui.value.lineSpans[effectiveKey].orEmpty()
        val matchedPhrase = findTappedPhraseSpan(word, charOffset, lineText, spansForLine)
        when {
            taps >= 3 -> showLinePopup(block.text, block, whole = true)
            taps == 2 -> showLinePopup(lineText, block, whole = false)
            !word.isNullOrBlank() -> showWordPopup(word, block, matchedPhrase, lineText)
            else -> showLinePopup(lineText, block, whole = false)
        }
        if (_ui.value.settings.learning.mode == LearningMode.OFF) {
            updateSettings { it.copy(learning = it.learning.copy(mode = LearningMode.ENTERTAINMENT)) }
        }
    }

    private fun findTappedPhraseSpan(
        word: String?,
        charOffset: Int,
        lineText: String,
        spans: List<com.sublearn.core.nlp.PhraseSpan>,
    ): com.sublearn.core.nlp.PhraseSpan? {
        if (spans.isEmpty()) return null
        if (charOffset >= 0) {
            spans.firstOrNull { charOffset in it.startChar until it.endChar }?.let { return it }
        }
        if (!word.isNullOrBlank()) {
            val wLower = word.lowercase()
            spans.firstOrNull { span ->
                val s = span.startChar.coerceIn(0, lineText.length)
                val e = span.endChar.coerceIn(s, lineText.length)
                val slice = lineText.substring(s, e).lowercase()
                slice.contains(wLower) || span.headword.lowercase().contains(wLower)
            }?.let { return it }
        }
        return null
    }

    /** The cue on screen right now, falling back to the whole block when the cues do not line up. */
    private fun lineAt(block: SubtitleBlock, positionMs: Long): String =
        block.cues.firstOrNull { positionMs in it.startMs..it.endMs }?.text ?: block.text

    private fun showWordPopup(
        word: String,
        block: SubtitleBlock,
        matchedPhrase: com.sublearn.core.nlp.PhraseSpan? = null,
        lineText: String = block.text,
    ) {
        val candidates = lexiconAccessProvider()?.lemmatiser(word) ?: fallbackLemmatiser.candidates(word)
        val bestLemma = candidates.firstOrNull { it != word.lowercase() } ?: word.lowercase()
        val initialPhraseSection = matchedPhrase?.let { span ->
            val sliceEnd = span.endChar.coerceIn(0, lineText.length)
            val sliceStart = span.startChar.coerceIn(0, sliceEnd)
            val surface = lineText.substring(sliceStart, sliceEnd).ifBlank { span.headword }
            PopupPhraseSection(
                headword = span.headword,
                lemmaKey = span.lemmaKey,
                surfaceText = surface,
                kind = span.kind,
                marked = span.lemmaKey.lowercase() in _ui.value.savedPhraseKeys ||
                    span.headword.lowercase() in _ui.value.savedPhraseKeys,
                // §7: at most two losing overlaps travel with the winning span, so the card can
                // show "in truth" next to "in the truth of it" without becoming a list.
                alternatives = span.alternatives.take(WordCardTokens.MAX_PHRASE_ROWS).map { alt ->
                    PopupPhraseAlternative(
                        headword = alt.headword,
                        kind = alt.kind,
                        level = lexiconAccessProvider()?.lookupWord(alt.lemmaKey.lowercase())?.cefr,
                    )
                },
            )
        }

        _ui.value = _ui.value.copy(
            popup = PopupUi(
                kind = PopupKind.WORD,
                sourceText = word,
                word = word,
                lemma = bestLemma,
                busy = true,
                timestampMs = block.startMs,
                phraseSection = initialPhraseSection,
            ),
        )
        pauseIfLineIsAboutToChange()
        popupJob?.cancel()
        popupJob = viewModelScope.launch {
            val markedExact = runCatching { myWords.find(word) }.getOrNull()
            val markedByLemma = if (markedExact == null && bestLemma != word.lowercase()) {
                runCatching { myWords.find(bestLemma) }.getOrNull()
            } else {
                null
            }
            val marked = markedExact ?: markedByLemma
            val level = levelOf(word) ?: levelOf(bestLemma)

            val resolvedPhraseSection = initialPhraseSection?.let { section ->
                val phraseMarked = runCatching {
                    myWords.find(section.headword) ?: myWords.find(section.lemmaKey)
                }.getOrNull() != null
                val orchPhrase = orchestrator?.translateImmediate(section.headword)
                val lexEntry = lexiconAccessProvider()?.lookupWord(section.lemmaKey.lowercase())
                val lexSenses = lexEntry?.let { lexiconAccessProvider()?.senses(it.id).orEmpty() }.orEmpty()
                // The profile may rank the meanings for this line; without a model this is the
                // lexicon's own order (`D-76`).
                val rankedSenses = rankSensesForLine(section.lemmaKey, lexSenses, block.text)
                val lexExamples = rankedSenses.firstOrNull()?.let {
                    lexiconAccessProvider()?.examples(it.senseId).orEmpty()
                }.orEmpty()
                val phraseGloss = orchPhrase?.primaryTranslation
                    ?: rankedSenses.map { it.glossFa }.distinct().take(4).joinToString("، ").takeIf { it.isNotBlank() }
                    ?: runCatching { translation.translate(section.headword).getOrNull() }.getOrNull()
                section.copy(
                    translation = phraseGloss,
                    examples = orchPhrase?.examples?.ifEmpty { lexExamples } ?: lexExamples,
                    level = orchPhrase?.cefr ?: lexEntry?.cefr,
                    lexiconEntryId = orchPhrase?.lexiconEntryId ?: lexEntry?.id,
                    marked = phraseMarked,
                    sourcesUsed = orchPhrase?.sourcesUsed?.map { it.name }.orEmpty(),
                )
            }

            if (orchestrator != null) {
                orchestrator.translateFlow(word, block.text).collect { orch ->
                    val fallbackRes = if (orch.primaryTranslation.isNullOrBlank()) {
                        translation.translateWord(word, block.text)
                    } else {
                        null
                    }
                    val finalGloss = orch.primaryTranslation ?: fallbackRes?.glossText
                    val finalContext = orch.contextTranslation ?: fallbackRes?.contextTranslation
                    val finalError = if (finalGloss == null) (orch.error?.message ?: fallbackRes?.error?.message) else null
                    val needsDownload = (orch.error?.kind == com.sublearn.core.common.SubLearnError.Kind.ModelMissing) ||
                        (fallbackRes?.needsModelDownload == true)
                    _ui.value = _ui.value.copy(
                        popup = _ui.value.popup?.copy(
                            busy = false,
                            lemma = orch.lemma ?: bestLemma,
                            phonetic = orch.phonetic,
                            translation = finalGloss,
                            posGlosses = orch.posGlosses,
                            examples = orch.examples,
                            sourcesUsed = orch.sourcesUsed.map { it.name },
                            lexiconEntryId = orch.lexiconEntryId,
                            contextTranslation = finalContext,
                            isPendingOnline = orch.isPendingOnline,
                            error = finalError,
                            needsModelDownload = needsDownload,
                            marked = marked != null,
                            level = orch.cefr ?: level?.name,
                            phraseSection = resolvedPhraseSection,
                        ),
                        modelMissing = needsDownload,
                    )
                    finalGloss?.let { gloss ->
                        val ui = _ui.value
                        _ui.value = ui.copy(glosses = ui.glosses + (word.lowercase() to gloss))
                    }
                }
            } else {
                val result: WordTranslation = translation.translateWord(word, block.text)
                _ui.value = _ui.value.copy(
                    popup = _ui.value.popup?.copy(
                        busy = false,
                        lemma = bestLemma,
                        translation = result.glossText,
                        contextTranslation = result.contextTranslation,
                        error = result.error?.message,
                        needsModelDownload = result.needsModelDownload,
                        marked = marked != null,
                        level = level?.name,
                        phraseSection = resolvedPhraseSection,
                    ),
                    modelMissing = result.needsModelDownload,
                )
                result.glossText?.let { gloss ->
                    val ui = _ui.value
                    _ui.value = ui.copy(glosses = ui.glosses + (word.lowercase() to gloss))
                }
            }
        }
    }

    private fun showLinePopup(text: String, block: SubtitleBlock, whole: Boolean) {
        val kind = if (whole) PopupKind.BLOCK else PopupKind.LINE
        _ui.value = _ui.value.copy(popup = PopupUi(kind, text, busy = true, timestampMs = block.startMs))
        pauseIfLineIsAboutToChange()
        popupJob?.cancel()
        // A line is translated as its own cue, a block as the whole text; both keep the block's timing.
        val source = if (whole) block else block.copy(text = text, cues = block.cues.filter { it.text == text })
        popupJob = viewModelScope.launch {
            translation.translateBlock(source).fold(
                onSuccess = { translated ->
                    _ui.value = _ui.value.copy(popup = _ui.value.popup?.copy(busy = false, translation = translated))
                },
                onFailure = { error ->
                    _ui.value = _ui.value.copy(
                        popup = _ui.value.popup?.copy(
                            busy = false,
                            error = error.message,
                            needsModelDownload = error.kind == com.sublearn.core.common.SubLearnError.Kind.ModelMissing,
                        ),
                    )
                },
            )
        }
    }

    /** Pausing only when the line would vanish anyway keeps reading possible without stealing control. */
    private fun pauseIfLineIsAboutToChange() {
        val state = playback.value
        if (!state.isPlaying) return
        val doc = primaryDocument() ?: return
        val block = doc.blockAt(state.positionMs) ?: return
        if (block.endMs - state.positionMs < PAUSE_LEAD_MS) {
            _ui.value = _ui.value.copy(popup = _ui.value.popup?.copy(wasPlayingBeforePause = true))
            controller.pause()
        }
    }

    fun dismissPopup() {
        val resume = _ui.value.popup?.wasPlayingBeforePause == true
        popupJob?.cancel()
        _ui.value = _ui.value.copy(popup = null)
        if (resume) controller.play()
        refreshAutoHide()
    }

    fun toggleMarkInPopup() {
        val popup = _ui.value.popup ?: return
        val block = _ui.value.layer(TrackRole.LEARNING).block ?: return
        viewModelScope.launch {
            val existing = popup.word?.let {
                runCatching { myWords.find(it) ?: popup.lemma?.let { lem -> myWords.find(lem) } }.getOrNull()
            }
            if (existing != null) {
                myWords.remove(existing.id)
                _ui.value = _ui.value.copy(popup = popup.copy(marked = false))
                learningCardsKey = null
                wordStateKey = emptyList()
                refreshWordStates(playback.value, _ui.value.settings)
            } else {
                val text = popup.word ?: popup.sourceText
                val id = myWords.mark(
                    MarkedWord(
                        id = 0L,
                        word = text,
                        isPhrase = popup.word == null || text.contains(' '),
                        translation = popup.translation,
                        contextText = block.text,
                        contextTranslation = popup.contextTranslation,
                        sourceTitle = playback.value.target?.title,
                        sourceUri = playback.value.target?.uri,
                        sourceStartMs = block.startMs,
                        status = WordStatus.LEARNING,
                        level = popup.level,
                        note = null,
                        markedAt = System.currentTimeMillis(),
                        reviewCount = 0,
                        lemma = popup.lemma,
                        lexiconEntryId = popup.lexiconEntryId,
                        sourceCueEndMs = block.endMs,
                        sourceKind = popup.sourcesUsed.firstOrNull(),
                    ),
                )
                _ui.value = _ui.value.copy(popup = popup.copy(marked = id != 0L))
                learningCardsKey = null
                wordStateKey = emptyList()
                refreshWordStates(playback.value, _ui.value.settings)
            }
        }
    }

    fun togglePhraseMarkInPopup() {
        val popup = _ui.value.popup ?: return
        val phrase = popup.phraseSection ?: return
        val block = _ui.value.layer(TrackRole.LEARNING).block ?: return
        viewModelScope.launch {
            val existing = runCatching {
                myWords.find(phrase.headword) ?: myWords.find(phrase.lemmaKey)
            }.getOrNull()
            if (existing != null) {
                myWords.remove(existing.id)
                val nextSaved = _ui.value.savedPhraseKeys - phrase.headword.lowercase() - phrase.lemmaKey.lowercase()
                _ui.value = _ui.value.copy(
                    popup = popup.copy(phraseSection = phrase.copy(marked = false)),
                    savedPhraseKeys = nextSaved,
                )
            } else {
                val id = myWords.mark(
                    MarkedWord(
                        id = 0L,
                        word = phrase.headword,
                        isPhrase = true,
                        translation = phrase.translation,
                        contextText = block.text,
                        contextTranslation = popup.contextTranslation,
                        sourceTitle = playback.value.target?.title,
                        sourceUri = playback.value.target?.uri,
                        sourceStartMs = block.startMs,
                        status = WordStatus.LEARNING,
                        level = phrase.level,
                        note = null,
                        markedAt = System.currentTimeMillis(),
                        reviewCount = 0,
                        phraseType = phrase.kind.name,
                        lemma = phrase.lemmaKey,
                        lexiconEntryId = phrase.lexiconEntryId,
                        surfaceParts = phrase.surfaceText,
                        sourceCueEndMs = block.endMs,
                        sourceKind = phrase.sourcesUsed.firstOrNull(),
                    ),
                )
                val nextSaved = _ui.value.savedPhraseKeys + phrase.headword.lowercase() + phrase.lemmaKey.lowercase()
                _ui.value = _ui.value.copy(
                    popup = popup.copy(phraseSection = phrase.copy(marked = id != 0L)),
                    savedPhraseKeys = nextSaved,
                )
            }
        }
    }

    /** Downloads the missing ML Kit model; ML Kit reports no byte counts, so the status is coarse. */
    fun downloadModel() {
        viewModelScope.launch {
            message(application.getString(R.string.translate_downloading))
            translation.downloadModel { progress ->
                if (progress.status == TranslationProgress.Status.RUNNING && progress.fraction > 0f) {
                    message(application.getString(R.string.translate_downloading) + " " + (progress.fraction * PERCENT).toInt() + "%")
                }
            }.fold(
                onSuccess = {
                    message(application.getString(R.string.settings_model_ready))
                    _ui.value = _ui.value.copy(modelMissing = false, popup = _ui.value.popup?.copy(needsModelDownload = false))
                },
                onFailure = { error -> message(error.message) },
            )
        }
    }

    /**
     * Passive LRN-2 hints follow the resolved learning layer, including embedded cues. Identity
     * includes source, cue timing and settings: a seek, track change or mode change cancels stale work.
     * This path never pauses playback, opens a popup, downloads a model or queries an online engine.
     */
    private fun refreshLearningCards() {
        val ui = _ui.value
        val state = ui.playback
        val settings = ui.settings
        val layer = ui.layer(TrackRole.LEARNING)
        val lines = layer.lines
        val allowed = settings.learning.mode == LearningMode.LEARNING && settings.learning.maxPopupCards > 0 &&
            state.target != null && state.isPlaying && layer.visible && !ui.scrubbing && lines.isNotEmpty()
        if (!allowed) {
            learningCardsJob?.cancel()
            learningCardsKey = null
            if (ui.learningCards.isNotEmpty()) _ui.value = ui.copy(learningCards = emptyList())
            return
        }
        val jumped = abs(state.positionMs - learningCardsPositionMs) > LEARNING_SEEK_RESET_MS
        learningCardsPositionMs = state.positionMs
        val identity = listOf(state.target!!.uri) + lines.map { "${it.key}:${it.block?.startMs}:${it.text}" }
        val key = identity to settings
        if (!jumped && learningCardsKey == key) return
        learningCardsKey = key
        learningCardsJob?.cancel()
        _ui.value = ui.copy(learningCards = emptyList())
        learningCardsJob = viewModelScope.launch {
            try {
                val candidates = withContext(Dispatchers.IO) { learningCandidates(lines, settings) }
                for (batch in selectLearningCandidates(candidates, settings.learning).chunked(VISIBLE_LEARNING_CARDS)) {
                    val cards = withContext(Dispatchers.IO) {
                        batch.mapNotNull { candidate ->
                            val translated = withTimeoutOrNull(LEARNING_LOOKUP_TIMEOUT_MS) {
                                orchestrator?.translateImmediate(
                                    query = candidate.query,
                                    sourceLanguage = settings.languages.learningLanguage,
                                    targetLanguage = settings.languages.nativeLanguage,
                                )?.primaryTranslation
                                    ?: translation.translate(candidate.query).getOrNull()
                            }?.trim()?.takeIf { it.isNotEmpty() && !it.equals(candidate.query, true) }
                            translated?.let {
                                LearningCardUi(candidate.lemma, candidate.query, it, candidate.level?.name)
                            }
                        }
                    }
                    if (learningCardsKey != key) return@launch
                    _ui.value = _ui.value.copy(learningCards = cards)
                    delay(settings.learning.popupLifetimeMs.coerceIn(1_000L, 20_000L))
                    if (learningCardsKey == key) _ui.value = _ui.value.copy(learningCards = emptyList())
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Optional hints failing must not interrupt the film or replace a deliberate tap lookup.
                if (learningCardsKey == key) _ui.value = _ui.value.copy(learningCards = emptyList())
            }
        }
    }

    private suspend fun learningCandidates(lines: List<SubtitleLineUi>, settings: AppSettings): List<LearningCandidate> {
        val lex = lexiconAccessProvider()
        if (lex is com.sublearn.core.lexicon.PackLexiconAccess) lex.ensureInitialized()
        val smart = settings.smartLearning
        val config = com.sublearn.core.nlp.NlpConfig(
            phrasalVerbsEnabled = smart.phrasalVerbsEnabled,
            idiomsEnabled = smart.idiomsEnabled,
            collocationsEnabled = smart.collocationsEnabled,
            crossBlockBridgeEnabled = false, // Simultaneous tracks must never become one invented sentence.
            confidenceThreshold = smart.confidenceThreshold,
        )
        val queries = ArrayList<Pair<String, Boolean>>()
        for (line in lines) {
            if (lex != null && settings.learning.showPhraseTranslation) {
                val cue = com.sublearn.core.nlp.CueSlice(0L, 0L, 0L, 0L, line.text)
                val spans = cueStreamAnalyzer.analyze(listOf(cue), lex, config)[0L].orEmpty()
                queries += spans.filter { it.isAboveTypeThreshold(config) && it.isAboveThreshold(config.confidenceThreshold) }
                    .map { it.headword to true }
            }
            queries += com.sublearn.core.subtitles.Tokenizer.spans(line.text)
                .filter { it.isWord && it.text.any(Char::isLetter) }.map { it.text.lowercase() to false }
        }
        val provider = wordLevels.provider(settings.level, settings.learning.manualLevel)
        // Bound dictionary work for unusually long or malformed subtitle lines as well as card count.
        return queries.distinct().take(MAX_LEARNING_LOOKUPS).map { (query, phrase) ->
            val lemmas = if (phrase) setOf(query) else lex?.lemmatiser(query) ?: fallbackLemmatiser.candidates(query)
            val keys = (listOf(query) + lemmas).distinct()
            val entry = keys.firstNotNullOfOrNull { lex?.lookupWord(it) }
            val marked = myWords.findByWords(keys).values
            val known = marked.any { it.status == WordStatus.KNOWN || settings.learning.markedWordsAreKnown }
            val entryLevel = CefrLevel.entries.firstOrNull { it != CefrLevel.UNKNOWN && it.name == entry?.cefr }
            val level = entryLevel ?: provider.level(query)?.takeUnless { it == CefrLevel.UNKNOWN }
            LearningCandidate(query, entry?.key ?: lemmas.firstOrNull() ?: query, level, known, phrase)
        }
    }

    // ------------------------------------------------------------------ word styling (SUB-5, LRN-3, D-45, D-50)

    /**
     * Styles the words and multi-word expressions of the block on screen (SUB-5, D-45, D-48, D-50).
     */
    private fun refreshWordStates(state: PlaybackState, settings: AppSettings) {
        if (settings.learning.mode == LearningMode.OFF || state.target == null) return
        val doc = documentFor(TrackRole.LEARNING) ?: return
        val posMs = state.positionMs + settings.subtitleLayer(TrackRole.LEARNING).delayMs
        val block = doc.blockAt(posMs) ?: return
        val words = block.tokens.filter { it.isWord }.map { it.text.lowercase() }.distinct()
        if (words.isEmpty() || words == wordStateKey) return
        wordStateKey = words
        wordStateJob?.cancel()
        wordStateJob = viewModelScope.launch {
            val (states, spansMap, savedPhrases) = withContext(Dispatchers.Default) {
                val st = computeWordStates(words, settings)
                val (sp, spSaved) = computeLinePhraseSpans(doc, block, settings)
                Triple(st, sp, spSaved)
            }
            if (wordStateKey == words) {
                _ui.value = _ui.value.copy(
                    wordStates = states,
                    lineSpans = spansMap,
                    savedPhraseKeys = savedPhrases,
                )
            }
        }
    }

    private suspend fun computeWordStates(words: List<String>, settings: AppSettings): Map<String, WordVisualState> {
        val lex = lexiconAccessProvider()
        val expandedLookup = LinkedHashSet<String>()
        val wordToLemmas = HashMap<String, Set<String>>()
        for (w in words) {
            expandedLookup += w
            val cands = lex?.lemmatiser(w) ?: fallbackLemmatiser.candidates(w)
            wordToLemmas[w] = cands
            expandedLookup.addAll(cands)
        }
        val marked = runCatching { myWords.findByWords(expandedLookup.toList()) }.getOrDefault(emptyMap())
        val provider = runCatching { wordLevels.provider(settings.level, settings.learning.manualLevel) }.getOrNull()
        return words.associateWith { word ->
            val lemmas = wordToLemmas[word].orEmpty()
            val isMarked = marked.containsKey(word) || lemmas.any { marked.containsKey(it) }
            val level = provider?.let { runCatching { it.level(word) }.getOrNull() }
            when {
                isMarked -> WordVisualState.MARKED
                level != null && level.ordinal >= settings.learning.manualLevel.ordinal &&
                    settings.learning.showAboveLevelOnly -> WordVisualState.ABOVE_LEVEL
                level != null && level.ordinal >= settings.level.assumedLevelOfUnknownWords.ordinal -> WordVisualState.ABOVE_LEVEL
                else -> WordVisualState.NONE
            }
        }
    }

    /**
     * Orders a word's meanings for the line it was tapped in.
     *
     * The ranker comes from the accuracy profile (`D-76`): priors and CEFR for the model-free
     * profiles, a sentence encoder for PRECISE and above, and the prompt model on top of that for
     * SMART. Anything that goes wrong - no ranker, a model that will not open, an inference that
     * fails - leaves the lexicon's own order in place, because a tapped word must always show its
     * meanings.
     */
    private suspend fun rankSensesForLine(
        lemmaKey: String,
        senses: List<com.sublearn.core.nlp.SenseCandidate>,
        line: String,
    ): List<com.sublearn.core.nlp.SenseCandidate> {
        if (senses.size < 2) return senses
        val ranker = runCatching { senseRankerProvider() }.getOrNull() ?: return senses
        val context = line.split(CONTEXT_SEPARATOR)
            .filter { it.isNotBlank() }
            .take(CONTEXT_WINDOW_TOKENS)
        return runCatching { ranker.rank(lemmaKey, senses, context) }.getOrDefault(senses)
    }

    private suspend fun computeLinePhraseSpans(
        doc: SubtitleDocument,
        currentBlock: SubtitleBlock,
        settings: AppSettings,
    ): Pair<Map<String, List<com.sublearn.core.nlp.PhraseSpan>>, Set<String>> {
        val lex = lexiconAccessProvider() ?: return emptyMap<String, List<com.sublearn.core.nlp.PhraseSpan>>() to emptySet()
        if (lex is com.sublearn.core.lexicon.PackLexiconAccess) {
            lex.ensureInitialized()
        }
        val smart = settings.smartLearning
        val nlpConfig = com.sublearn.core.nlp.NlpConfig(
            phrasalVerbsEnabled = smart.phrasalVerbsEnabled,
            idiomsEnabled = smart.idiomsEnabled,
            collocationsEnabled = smart.collocationsEnabled,
            crossBlockBridgeEnabled = smart.crossBlockBridgeEnabled,
            confidenceThreshold = smart.confidenceThreshold,
            showPossibleMatches = smart.showPossibleMatches,
        )
        val idx = doc.blocks.indexOfFirst { it.id == currentBlock.id }
        val windowBlocks = if (idx >= 0) {
            val from = (idx - 1).coerceAtLeast(0)
            val to = (idx + 1).coerceAtMost(doc.blocks.lastIndex)
            doc.blocks.subList(from, to + 1)
        } else {
            listOf(currentBlock)
        }
        val slices = windowBlocks.map { b ->
            com.sublearn.core.nlp.CueSlice(
                cueId = b.id,
                blockId = b.id,
                startMs = b.startMs,
                endMs = b.endMs,
                text = b.text,
            )
        }
        val tagger = if (smart.accuracyProfile != com.sublearn.core.settings.AccuracyProfileToken.BASIC) {
            fallbackPosTagger
        } else {
            null
        }
        val analyzedByBlock = runCatching {
            cueStreamAnalyzer.analyze(slices, lex, nlpConfig, tagger)
        }.getOrDefault(emptyMap())

        val currentSpans = analyzedByBlock[currentBlock.id].orEmpty()
            .filter { it.isAboveThreshold(nlpConfig.confidenceThreshold) }
        val spanDao = phraseSpanCacheDao
        if (spanDao != null && currentSpans.isNotEmpty() && lex is com.sublearn.core.lexicon.PackLexiconAccess) {
            val sourceKey = openedTarget?.uri ?: "inline"
            val packId = lex.activePackId()
            val packVer = lex.activePackVersion()
            val entities = currentSpans.mapIndexed { sIdx, sp ->
                com.sublearn.core.data.PhraseSpanCacheEntity(
                    sourceKey = sourceKey,
                    packId = packId,
                    packVersion = packVer,
                    analyzerVersion = 2,
                    spanId = "${currentBlock.id}:$sIdx:${sp.startChar}-${sp.endChar}",
                    blockId = currentBlock.id,
                    startChar = sp.startChar,
                    endChar = sp.endChar,
                    tokensCsv = sp.tokens.joinToString(","),
                    kind = sp.kind.name,
                    headword = sp.headword,
                    lemmaKey = sp.lemmaKey,
                    confidence = sp.confidence,
                    separable = sp.separable,
                )
            }
            runCatching { spanDao.upsertAll(entities) }
        }
        val candidateKeys = currentSpans.flatMap { listOf(it.headword.lowercase(), it.lemmaKey.lowercase()) }.distinct()
        val savedMap = if (candidateKeys.isNotEmpty()) {
            runCatching { myWords.findByWords(candidateKeys) }.getOrDefault(emptyMap())
        } else {
            emptyMap()
        }
        val lineKeys = (_ui.value.layer(TrackRole.LEARNING).lines.map { it.key } + "current").distinct()
        val spansByLine = lineKeys.associateWith { currentSpans }
        return spansByLine to savedMap.keys
    }

    private suspend fun levelOf(word: String): com.sublearn.core.settings.CefrLevel? {
        val settings = _ui.value.settings
        return runCatching { wordLevels.provider(settings.level, settings.learning.manualLevel).level(word) }.getOrNull()
    }

    // ------------------------------------------------------------------ list panel (PLY-6)

    fun toggleList() = setListOpen(!_ui.value.list.open)

    private fun setListOpen(open: Boolean) {
        _ui.value = _ui.value.copy(list = _ui.value.list.copy(open = open))
        if (open) refreshList()
    }

    fun setListRole(role: TrackRole) {
        _ui.value = _ui.value.copy(list = _ui.value.list.copy(role = role))
        refreshList()
    }

    fun setListQuery(query: String) {
        _ui.value = _ui.value.copy(list = _ui.value.list.copy(query = query))
    }

    /** PLY-6: long press on the list toggle hides the lines that have not been reached yet. */
    fun toggleNoSpoiler() = updateSettings { settings ->
        settings.copy(subtitles = settings.subtitles.copy(noSpoilerMode = !settings.subtitles.noSpoilerMode))
    }

    private fun refreshList() {
        val role = _ui.value.list.role
        val doc = documentFor(role)
        if (doc == null) {
            _ui.value = _ui.value.copy(list = _ui.value.list.copy(rows = emptyList(), available = false, currentIndex = -1))
            return
        }
        _ui.value = _ui.value.copy(
            list = _ui.value.list.copy(
                available = true,
                rows = doc.blocks.mapIndexed { index, block -> SubtitleListRow(block.id, index, block.startMs, block.text) },
            ),
        )
        publish()
    }

    fun seekToRow(row: SubtitleListRow) {
        val doc = documentFor(_ui.value.list.role) ?: return
        doc.blockById(row.blockId)?.let { seekToBlock(it) }
    }

    // ------------------------------------------------------------------ tracks, speed, aspect, decoder

    fun setRepeat(repeat: PlaylistRepeat) = controller.setRepeat(repeat)

    fun selectTrack(ref: TrackRef) = controller.selectTrack(ref)

    fun clearTrackOverride(type: PlayerTrackType) = controller.clearTrackOverride(type)

    fun setSpeedPercent(percent: Int) = controller.setSpeedPercent(percent)

    fun setAspect(mode: AspectMode) {
        val player = _ui.value.settings.player
        controller.setAspect(mode, player.customAspectWidth, player.customAspectHeight)
        updateSettings { settings -> settings.copy(player = settings.player.copy(aspect = mode)) }
    }

    fun setCustomAspect(width: Int, height: Int) {
        updateSettings { settings ->
            settings.copy(
                player = settings.player.copy(
                    customAspectWidth = width,
                    customAspectHeight = height,
                    aspect = AspectMode.CUSTOM_RATIO,
                ),
            )
        }
        controller.setAspect(AspectMode.CUSTOM_RATIO, width, height)
    }

    private fun cycleAspect() {
        val order = listOf(AspectMode.FIT, AspectMode.FILL, AspectMode.ZOOM, AspectMode.STRETCH)
        val current = _ui.value.settings.player.aspect
        val next = order[(order.indexOf(current).coerceAtLeast(0) + 1) % order.size]
        setAspect(next)
        message(application.getString(R.string.player_msg_aspect, application.getString(next.labelRes())))
    }

    fun setDecoder(mode: DecoderMode) {
        updateSettings { settings -> settings.copy(player = settings.player.copy(decoder = mode)) }
        viewModelScope.launch {
            runCatching { controller.setDecoderMode(mode) }
                .onFailure { message(application.getString(R.string.player_msg_decoder_failed)) }
        }
    }

    /** Shares the video's link, because the file itself may not be readable by the receiving app. */
    fun shareCurrent() {
        val target = playback.value.target ?: return message(application.getString(R.string.player_msg_no_media))
        _intent.tryEmit(PlayerIntent.Share(target.uri, target.title))
    }

    fun enterPip() {
        val state = playback.value
        val width = state.videoWidth.takeIf { it > 0 } ?: DEFAULT_ASPECT_WIDTH
        val height = state.videoHeight.takeIf { it > 0 } ?: DEFAULT_ASPECT_HEIGHT
        _intent.tryEmit(PlayerIntent.EnterPip(width, height))
    }

    fun setMuted(value: Boolean) {
        controller.setMuted(value)
        _ui.value = _ui.value.copy(muted = value)
    }

    fun setExternalAudioTrack(uri: String?, displayName: String? = null) {
        controller.setExternalAudioTrack(uri, displayName)
    }

    fun setVolumePercent(percent: Int) {
        controller.setVolume(percent.coerceIn(0, 100))
    }

    fun toggleRemainingTime() {
        updateSettings { settings ->
            settings.copy(player = settings.player.copy(showRemainingTime = !settings.player.showRemainingTime))
        }
    }

    fun togglePlay() = controller.togglePlayPause()

    fun playNextVideo() = controller.playNext()

    fun playPreviousVideo() = controller.playPrevious()

    fun seekBy(deltaMs: Long) = controller.seekBy(deltaMs)

    /** Floating quick actions (ACT-2): the position is a fraction of the video area, saved when the drag ends. */
    fun moveQuickAction(id: QuickActionId, xFraction: Float, yFraction: Float) {
        updateQuickAction(id) { spec -> spec.copy(xFraction = xFraction.coerceIn(0f, 1f), yFraction = yFraction.coerceIn(0f, 1f)) }
    }

    fun updateQuickAction(id: QuickActionId, transform: (QuickActionSpec) -> QuickActionSpec) {
        updateSettings { settings ->
            val spec = settings.quickActions.spec(id)
            settings.copy(quickActions = settings.quickActions.updated(id, transform(spec)))
        }
    }

    fun moveQuickAction(id: QuickActionId, dock: DockMode) {
        updateSettings { settings ->
            val order = (settings.quickActions.visibleIn(dock).filter { it.first != id }.maxOfOrNull { it.second.order } ?: -1) + 1
            settings.copy(quickActions = settings.quickActions.updated(id, settings.quickActions.spec(id).copy(dock = dock, order = order)))
        }
    }

    fun reorderQuickAction(id: QuickActionId, dock: DockMode, delta: Int) {
        if (delta == 0) return
        updateSettings { settings ->
            val row = settings.quickActions.visibleIn(dock).map { it.first }.toMutableList()
            val from = row.indexOf(id)
            if (from < 0) return@updateSettings settings
            val to = (from + delta).coerceIn(0, row.lastIndex)
            if (from == to) return@updateSettings settings
            val moving = row.removeAt(from)
            row.add(to, moving)
            val updated = row.mapIndexed { index, action ->
                action to settings.quickActions.spec(action).copy(order = index)
            }.toMap()
            settings.copy(quickActions = settings.quickActions.copy(
                specs = settings.quickActions.specs + updated.mapKeys { it.key.key }.mapValues { it.value },
            ))
        }
    }

    fun reorderQuickAction(id: QuickActionId, delta: Int) = reorderQuickAction(id, DockMode.BAR, delta)

    fun toggleQuickActionBarExpanded() {
        updateSettings { settings ->
            settings.copy(quickActions = settings.quickActions.copy(barExpanded = !settings.quickActions.barExpanded))
        }
    }

    fun setSheet(sheet: Sheet) {
        _ui.value = _ui.value.copy(sheet = sheet, controlsVisible = true)
        refreshAutoHide()
    }

    // ------------------------------------------------------------------ quick actions

    /** A quick action tap. Each id maps to exactly one behaviour, so the list stays exhaustive. */
    fun onQuickTap(id: QuickActionId) {
        when (id) {
            QuickActionId.TOGGLE_LEARNING -> toggleLayerVisible(TrackRole.LEARNING)
            QuickActionId.TOGGLE_TRANSLATION -> toggleLayerVisible(TrackRole.TRANSLATION)
            QuickActionId.REPEAT_BLOCK -> toggleRepeatBlock(autoRepeat = false)
            QuickActionId.STOP_AT_BLOCK_END -> toggleStopAtEnd()
            QuickActionId.LAYOUT_MODE -> setLayoutMode(!_ui.value.layoutMode)
            QuickActionId.SUBTITLE_LIST -> toggleList()
            QuickActionId.AI_EXPLAIN -> askAi(null)
            QuickActionId.MY_WORDS -> _intent.tryEmit(PlayerIntent.OpenWords)
            QuickActionId.PLAYLIST -> setSheet(Sheet.PLAYLIST)
            QuickActionId.ASPECT_RATIO -> setSheet(Sheet.ASPECT)
            QuickActionId.DECODER -> setSheet(Sheet.DECODER)
            QuickActionId.AUDIO_TRACK -> setSheet(Sheet.AUDIO)
            QuickActionId.SUBTITLE_TRACKS -> setSheet(Sheet.SUBTITLES)
            QuickActionId.SUBTITLE_TOOLS -> setSheet(Sheet.TOOLS)
            QuickActionId.SPEED -> setSheet(Sheet.SPEED)
            QuickActionId.PICTURE_IN_PICTURE -> enterPip()
            QuickActionId.SHARE -> shareCurrent()
            QuickActionId.SETTINGS -> _intent.tryEmit(PlayerIntent.OpenSettings)
            QuickActionId.TOGGLE_MUTE -> setMuted(!playback.value.isMuted)
        }
    }

    /**
     * Holding a toggle inverts it until release (SHD-1, "invert until release"): a layer hides while
     * held, or a stop-at-end flips for one block. Holding the repeat action runs auto-repeat.
     */
    fun onQuickHoldStart(id: QuickActionId) {
        if (!_ui.value.settings.shadowing.holdInvertsTemporarily) return
        when (id) {
            QuickActionId.TOGGLE_LEARNING, QuickActionId.TOGGLE_TRANSLATION, QuickActionId.STOP_AT_BLOCK_END -> {
                if (peekHeld != null) return
                peekHeld = id
                onQuickTap(id)
            }
            QuickActionId.REPEAT_BLOCK -> toggleRepeatBlock(autoRepeat = true)
            else -> Unit
        }
    }

    fun onQuickHoldEnd(id: QuickActionId) {
        if (peekHeld != id) return
        peekHeld = null
        onQuickTap(id)
    }

    // ------------------------------------------------------------------ AI (AI-1..AI-4)

    fun askAi(selectedText: String?) {
        val settings = _ui.value.settings
        if (!aiConfigured(settings)) {
            message(application.getString(R.string.player_msg_no_ai_key))
            return
        }
        val doc = documentFor(TrackRole.LEARNING) ?: return message(application.getString(R.string.player_msg_need_subtitle))
        val block = doc.blockAt(playback.value.positionMs) ?: return message(application.getString(R.string.player_msg_no_block))
        val index = doc.indexOf(block.id)
        aiJob?.cancel()
        val wasPlaying = playback.value.isPlaying
        if (settings.ai.pausePlayback && wasPlaying) controller.pause()
        _ui.value = _ui.value.copy(
            ai = _ui.value.ai.copy(
                state = AiState.BUSY,
                question = selectedText ?: block.text,
                answer = null,
                error = null,
                pausedForAnswer = settings.ai.pausePlayback && wasPlaying,
                wasPlaying = wasPlaying,
            ),
        )
        refreshAutoHide()
        aiJob = viewModelScope.launch {
            val key = settings.ai.keyRef?.let { runCatching { secrets.get(it) }.getOrNull() }
            val config = AiAskConfig(
                promptTemplate = settings.ai.promptTemplate,
                contextBlocks = doc.blocksBefore(index, settings.ai.contextBlocks),
                title = playback.value.target?.title,
                learningLanguage = SubtitleLanguage.displayName(settings.languages.learningLanguage),
                nativeLanguage = SubtitleLanguage.displayName(settings.languages.nativeLanguage),
                level = settings.learning.manualLevel.name,
                mode = settings.learning.mode.name.lowercase(),
                temperaturePercent = settings.ai.temperaturePercent,
                maxOutputTokens = settings.ai.maxOutputTokens,
                model = settings.ai.model,
            )
            val request = ai.buildRequest(config, block, selectedText)
            val providerId = when (settings.ai.provider) {
                AiProviderToken.GEMINI -> "gemini"
                AiProviderToken.OPENAI -> "openai"
                AiProviderToken.ANTHROPIC -> "anthropic"
                AiProviderToken.CUSTOM -> "custom"
            }
            ai.ask(providerId, request, key, settings.ai.customBaseUrl.ifBlank { null }).fold(
                onSuccess = { answer ->
                    _ui.value = _ui.value.copy(ai = _ui.value.ai.copy(state = AiState.ANSWER, answer = answer))
                },
                onFailure = { error ->
                    _ui.value = _ui.value.copy(ai = _ui.value.ai.copy(state = AiState.ERROR, error = error.message))
                    resumeAfterAi()
                },
            )
        }
    }

    fun cancelAi() {
        aiJob?.cancel()
        _ui.value = _ui.value.copy(ai = _ui.value.ai.copy(state = AiState.IDLE, answer = null, error = null))
        resumeAfterAi()
        refreshAutoHide()
    }

    /** AI-2: the answer sheet owns the pause, so playback returns to exactly what it was. */
    fun resumeAfterAi() {
        val ai1 = _ui.value.ai
        if (ai1.pausedForAnswer && ai1.wasPlaying) controller.play()
        _ui.value = _ui.value.copy(ai = ai1.copy(pausedForAnswer = false))
    }

    fun sectionsOf(answer: AiAnswer?) = AiAnswerParser.parse(answer?.text ?: "")

    /** The keystore is read once per key reference, never on the playback tick. */
    private fun refreshAiKey(settings: AppSettings) {
        val ref = settings.ai.keyRef
        if (ref == checkedKeyRef) return
        checkedKeyRef = ref
        viewModelScope.launch {
            aiKeyPresent = ref != null && runCatching { secrets.get(ref) }.getOrNull()?.isNotBlank() == true
            publish()
        }
    }

    // A custom endpoint is only usable once its base URL is set in Settings -> AI.
    private fun aiConfigured(settings: AppSettings): Boolean =
        aiKeyPresent && (settings.ai.provider != AiProviderToken.CUSTOM || settings.ai.customBaseUrl.isNotBlank())

    // ------------------------------------------------------------------ publish and helpers

    /** Recomputes live lines, media tracks and the derived UI from one playback snapshot. */
    private fun publish(state: PlaybackState = controller.state.value) {
        val current = _ui.value
        val settings = current.settings
        val position = state.positionMs
        val layers = TrackRole.entries.associateWith { role ->
            val layerSettings = settings.subtitleLayer(role)
            val storedKeys = layerSettings.externalFileKeys
            val activeTracks = storedKeys
                .filterNot { it in layerSettings.disabledExternalFileKeys }
                .mapNotNull { key -> externalTracks.getValue(role)[key]?.let { key to it } }
            val externalLines = activeTracks.mapNotNull { (key, loaded) ->
                loaded.document.blockAt(position + layerSettings.delayMs)?.let { block ->
                    SubtitleLineUi(key = key, text = block.text, block = block)
                }
            }
            val assignedSelectedRefs = state.textTracks
                .filter { it.isSelected && isEmbeddedTrackAssigned(settings, role, it.ref) }
                .map { it.ref }
                .toSet()
            val embeddedSelected = assignedSelectedRefs.isNotEmpty()
            val embeddedLines = if (embeddedSelected) {
                embeddedCues.mapIndexedNotNull { index, cue ->
                    if (cue.trackRef == null || cue.trackRef in assignedSelectedRefs) {
                        SubtitleLineUi(key = "embedded:$role:$index", text = cue.text)
                    } else {
                        null
                    }
                }
            } else {
                emptyList()
            }
            val lines = externalLines + embeddedLines
            val source = when {
                activeTracks.isNotEmpty() -> LayerSource.FILE
                embeddedSelected -> LayerSource.PLAYER_CUES
                else -> LayerSource.NONE
            }
            val names = storedKeys.associateWith { key ->
                externalTracks.getValue(role)[key]?.track?.name ?: key.substringAfterLast('/').ifBlank { key }
            }
            val block = externalLines.firstOrNull()?.block
            val text = lines.takeIf { it.isNotEmpty() }?.joinToString("\n") { it.text }
            current.layer(role).copy(
                block = block,
                text = text,
                source = source,
                fileName = names.values.joinToString(" · ").ifBlank { null },
                charsetName = activeTracks.joinToString(" · ") { it.second.charsetName }.ifBlank { null },
                warnings = activeTracks.flatMap { it.second.warnings }.distinct(),
                visible = layerSettings.visible,
                scalePercent = layerSettings.scalePercent,
                transparencyPercent = layerSettings.transparencyPercent,
                placement = current.layerDrafts[role] ?: layerSettings.placement,
                delayMs = layerSettings.delayMs,
                embeddedTrackIndexes = layerSettings.embeddedTrackIndexes,
                externalFileKeys = storedKeys,
                disabledExternalFileKeys = layerSettings.disabledExternalFileKeys,
                externalFileNames = names,
                lines = lines,
            )
        }
        val listRole = current.list.role
        val listDoc = documentFor(listRole)
        val currentBlock = layers.getValue(listRole).block
        _ui.value = current.copy(
            playback = state,
            layers = layers,
            list = current.list.copy(
                available = listDoc != null,
                currentIndex = currentBlock?.let { listDoc?.indexOf(it.id) } ?: -1,
            ),
            shadowing = current.shadowing.copy(
                active = plan != null,
                repeatsLeft = plan?.let { it.repeatTotal - it.completed } ?: 0,
                pausedForRepeat = plan?.paused == true,
                segmentLabel = plan?.let { application.getString(R.string.player_repeat_progress, it.completed + 1, it.repeatTotal) },
            ),
            ai = current.ai.copy(configured = aiConfigured(settings)),
            muted = state.isMuted,
        )
        refreshLearningCards()
    }

    private fun isEmbeddedTrackAssigned(settings: AppSettings, role: TrackRole, ref: TrackRef): Boolean {
        val layer = settings.subtitleLayer(role)
        return if (layer.embeddedTrackKeys.isNotEmpty()) {
            ref.stableKey in layer.embeddedTrackKeys
        } else {
            ref.trackIndex in layer.embeddedTrackIndexes
        }
    }

    private fun activeExternalDocuments(role: TrackRole): List<Pair<String, LoadedTrack>> {
        val layer = _ui.value.settings.subtitleLayer(role)
        return layer.externalFileKeys
            .filterNot { it in layer.disabledExternalFileKeys }
            .mapNotNull { key -> externalTracks.getValue(role)[key]?.let { key to it } }
    }

    private fun documentFor(role: TrackRole): SubtitleDocument? = activeExternalDocuments(role).firstOrNull()?.second?.document

    /** A message that clears itself, so a stale hint never sits over the video. */
    fun message(text: String?) {
        messageJob?.cancel()
        _ui.value = _ui.value.copy(message = text)
        if (text != null) {
            messageJob = viewModelScope.launch {
                delay(MESSAGE_MS)
                _ui.value = _ui.value.copy(message = null)
            }
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /** Called when the screen leaves the foreground: saves the position, keeps the player alive. */
    fun onLeave() {
        finishPlan()
        viewModelScope.launch {
            controller.flushPosition()
            playback.value.target?.let { target ->
                recentVideos.touch(target.uri, target.title, playback.value.durationMs, playback.value.positionMs)
            }
        }
    }

    /** Rebuilds the chrome after the Activity returns from PiP or a configuration change. */
    fun onReturn() {
        publish()
        showControls()
    }

    override fun onCleared() {
        pendingAdvance?.cancel()
        aiJob?.cancel()
        popupJob?.cancel()
        learningCardsJob?.cancel()
        super.onCleared()
    }

    /** Which area of the video a tap or drag started in. */
    enum class TapSide { LEFT, CENTER, RIGHT }

    companion object {
        /** How many words of the line a sense ranker is shown. The models take 256 tokens; this
         *  keeps the call short, and a subtitle line is shorter than this anyway. */
        private const val CONTEXT_WINDOW_TOKENS = 24

        /** Everything that is not a letter or a digit separates context words. */
        private val CONTEXT_SEPARATOR = Regex("[^\\p{L}\\p{N}']+")

        private const val RESUME_THRESHOLD_MS = 8_000L
        private const val PAUSE_LEAD_MS = 4_000L
        private const val MIN_AUTO_HIDE_MS = 1_000L
        private const val LOCK_BUTTON_MS = 2_500L
        private const val HUD_LINGER_MS = 350L
        private const val DOUBLE_TAP_HUD_MS = 700L
        private const val MESSAGE_MS = 2_500L
        private const val MS_PER_SECOND = 1_000L
        private const val NORMAL_SPEED = 100
        private const val PERCENT = 100f
        private const val MAX_DELAY_MS = 5_000L
        private const val MIN_SCALE_PERCENT = 60
        private const val MAX_SCALE_PERCENT = 220
        private const val MAX_REPEAT_COUNT = 12
        private const val MIN_SEGMENT_PAUSE_MS = 120L
        /** Ticks arrive every 120 ms; the lead must be longer so no tick can jump over a block end. */
        private const val BLOCK_END_LEAD_MS = 200L
        /** A seek further than this outside the handled block ends the shadowing run. */
        private const val MOVED_AWAY_MS = 1_500L
        /** A full-height two-finger drag covers this many times the speed range. */
        private const val TWO_FINGER_FULL_HEIGHT_FACTOR = 2f
        private const val INTENT_BUFFER = 16
        private const val MAX_PLAYLIST_SIZE = 40
        private const val DEFAULT_ASPECT_WIDTH = 16
        private const val DEFAULT_ASPECT_HEIGHT = 9
    }
}

/** Requests the Activity handles: window effects that Compose cannot own. */
sealed interface PlayerIntent {
    data class Brightness(val value: Float) : PlayerIntent
    data class Volume(val value: Float) : PlayerIntent
    data class EnterPip(val aspectWidth: Int, val aspectHeight: Int) : PlayerIntent
    data class Share(val uri: String, val title: String) : PlayerIntent
    data object OpenWords : PlayerIntent
    data object OpenSettings : PlayerIntent
}

/** Moves a plate by a finger delta in dp, keeping the anchor the user chose and clamping to the video. */
internal fun SubtitlePlacement.movedBy(dxDp: Float, dyDp: Float): SubtitlePlacement {
    val nextX = offsetXDp + if (horizontal == com.sublearn.core.settings.SubtitleHorizontalAnchor.END) -dxDp else dxDp
    val nextY = offsetYDp + if (vertical == com.sublearn.core.settings.SubtitleVerticalAnchor.BOTTOM) -dyDp else dyDp
    return copy(
        offsetXDp = nextX.coerceIn(-MAX_OFFSET_DP, MAX_OFFSET_DP),
        offsetYDp = nextY.coerceIn(-MAX_OFFSET_DP, MAX_OFFSET_DP),
    )
}

private const val MAX_OFFSET_DP = 900f

private const val LEARNING_SEEK_RESET_MS = 1_500L
private const val LEARNING_LOOKUP_TIMEOUT_MS = 900L
private const val MAX_LEARNING_LOOKUPS = 64
