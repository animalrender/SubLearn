package com.sublearn.feature.player

import android.app.Application
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
import com.sublearn.core.designsystem.R
import com.sublearn.core.lexicon.WordLevelSource
import com.sublearn.core.player.MediaTarget
import com.sublearn.core.player.PlaybackState
import com.sublearn.core.player.PlayerController
import com.sublearn.core.player.TrackRef
import com.sublearn.core.player.PlayerTrackType
import com.sublearn.core.security.SecretStore
import com.sublearn.core.settings.AppSettings
import com.sublearn.core.settings.AiProviderToken
import com.sublearn.core.settings.DecoderMode
import com.sublearn.core.settings.AspectMode
import com.sublearn.core.settings.GestureAction
import com.sublearn.core.settings.LearningMode
import com.sublearn.core.settings.SettingsRepository
import com.sublearn.core.settings.SubtitleLayerSettings
import com.sublearn.core.settings.SubtitlePlacement
import com.sublearn.core.settings.QuickActionId
import com.sublearn.core.subtitles.ShadowingMath
import com.sublearn.core.subtitles.ShadowingPauseConfig
import com.sublearn.core.subtitles.SubtitleBlock
import com.sublearn.core.subtitles.SubtitleDocument
import com.sublearn.core.subtitles.SubtitleFile
import com.sublearn.core.subtitles.TrackRole
import com.sublearn.core.subtitles.SubtitleLanguage
import com.sublearn.core.translate.TranslationProgress
import com.sublearn.core.translate.TranslationService
import com.sublearn.core.translate.WordTranslation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The brain of the player screen.
 *
 * It owns the subtitle documents for both layers, the shadowing state machine and the popup/answer
 * state, and it is the only place that writes subtitle settings. The [PlayerController] stays
 * media-only, which is what keeps the whole thing testable against `FakePlayerController`.
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
) : ViewModel() {
    private val _ui = MutableStateFlow(PlayerUi())
    val ui: StateFlow<PlayerUi> = _ui.asStateFlow()

    private val _intent = MutableSharedFlow<PlayerIntent>(extraBufferCapacity = 8)
    /** One-shot requests the Activity must carry out (PiP, orientation, brightness). */
    val intents: SharedFlow<PlayerIntent> = _intent

    private var learningDoc: SubtitleDocument? = null
    private var translationDoc: SubtitleDocument? = null
    private var plan: ShadowPlan? = null
    private var shadowJob: Job? = null
    private var aiJob: Job? = null
    private var popupJob: Job? = null
    private var autoHideJob: Job? = null
    private var wordStateJob: Job? = null
    private var openedTarget: MediaTarget? = null
    private var muted = false
    private var embeddedCueText: String? = null
    private var aiKeyPresent = false
    private var checkedKeyRef: String? = null

    val playback: StateFlow<PlaybackState> get() = controller.state

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings -> onSettings(settings) }
        }
        viewModelScope.launch {
            controller.state.collect { state -> onPosition(state) }
        }
        viewModelScope.launch {
            controller.embeddedCues.collect { cues ->
                embeddedCueText = cues.firstOrNull()?.text
                publish()
            }
        }
        viewModelScope.launch {
            controller.events.collect { event ->
                when (event) {
                    is com.sublearn.core.player.PlayerEvent.Failed -> message(event.message)
                    is com.sublearn.core.player.PlayerEvent.Ended -> {
                        plan = null
                        openedTarget?.let { recentVideos.touch(it.uri, it.title, playback.value.durationMs, it.startPositionMs) }
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
            val stillLoaded = openedTarget?.uri == uri && controller.state.value.target?.uri == uri
            if (stillLoaded) {
                // Coming back to the video that is already playing: keep playback and the loaded layers.
                subtitleUris.forEach { picked -> loadFile(picked.role, picked.uri, picked.name) }
                // A file picked in Settings while this video stayed loaded fills an empty layer.
                TrackRole.entries.forEach { role ->
                    val key = _ui.value.settings.subtitleLayer(role).externalFileKeys.lastOrNull()
                    val doc = if (role == TrackRole.LEARNING) learningDoc else translationDoc
                    if (key != null && doc == null && subtitleUris.none { it.role == role }) {
                        loadFile(role, key, key.substringAfterLast('/'))
                    }
                }
                _ui.value = _ui.value.copy(opening = false, playback = controller.state.value)
                publish()
                return@launch
            }
            _ui.value = _ui.value.copy(opening = true)
            val settings = _ui.value.settings
            // Layers and their remembered file keys belong to one video at a time.
            TrackRole.entries.forEach { role ->
                applyDocument(role, null, null, emptyList(), null)
                persistLayerKeys(role) { emptyList() }
            }
            val saved = if (settings.player.rememberPosition && settings.player.resumeOnOpen) {
                runCatching { recentVideos.find(uri) }.getOrNull()
            } else {
                null
            }
            val position = saved?.lastPositionMs?.takeIf { it > RESUME_THRESHOLD_MS && it < (saved.durationMs - 3_000L) } ?: 0L
            val target = MediaTarget(uri = uri, title = title, startPositionMs = position)
            openedTarget = target
            controller.open(target)
            _ui.value = _ui.value.copy(opening = false, playback = controller.state.value)
            if (position > 0L) message(null)
            subtitleUris.forEach { picked -> loadFile(picked.role, picked.uri, picked.name) }
            if (subtitleUris.isEmpty() && settings.subtitles.autoLoadExternal) {
                loadSidecars(uri, title)
            }
            if (saved != null && position > 0L) {
                message("resumed at " + clock(position))
            }
        }
    }

    data class PickedSubtitle(val uri: String, val name: String, val role: TrackRole)

    private suspend fun loadSidecars(videoUri: String, videoName: String) {
        val tree = settingsRepository.current().subtitles.sidecarTreeUri
        val files = runCatching { fileSource.sidecars(videoUri, tree) }.getOrDefault(emptyList())
        if (files.isEmpty()) return
        val nativeTag = _ui.value.settings.languages.nativeLanguage
        files.forEach { file ->
            val guess = SubtitleRoleGuess.guess(file.name, nativeTag)
            loadFile(guess.role, file.key, file.name)
        }
    }

    /** Parses a subtitle file and binds it to a layer (SUB-1). */
    fun loadFile(role: TrackRole, uri: String, name: String) {
        viewModelScope.launch {
            val config = _ui.value.settings.subtitles.normalizer
            val result = subtitles.load(SubtitleFile(key = uri, name = name), role, config)
            val loaded = result.getOrNull()
            if (loaded != null) {
                applyDocument(role, loaded.document, loaded.charsetName, loaded.warnings, name)
                persistLayerKeys(role) { keys -> (keys + uri).distinct() }
            } else {
                _ui.value = _ui.value.copy(message = result.errorOrNull()?.message)
            }
            publish()
        }
    }

    fun removeFile(role: TrackRole, uri: String) {
        viewModelScope.launch {
            applyDocument(role, null, null, emptyList(), null)
            persistLayerKeys(role) { keys -> keys - uri }
        }
    }

    private fun applyDocument(
        role: TrackRole,
        document: SubtitleDocument?,
        charset: String?,
        warnings: List<String>,
        fileName: String?,
    ) {
        when (role) {
            TrackRole.LEARNING -> learningDoc = document
            TrackRole.TRANSLATION -> translationDoc = document
        }
        _ui.value = _ui.value.copy(
            layers = _ui.value.layers + (role to _ui.value.layer(role).copy(
                fileName = fileName,
                charsetName = charset,
                warnings = warnings,
                source = if (document == null) LayerSource.NONE else LayerSource.FILE,
            )),
        )
        publish()
    }

    private suspend fun persistLayerKeys(role: TrackRole, transform: (List<String>) -> List<String>) {
        settingsRepository.update { settings ->
            val layer = settings.subtitleLayer(role)
            settings.copy(subtitles = settings.subtitles.updated(role, layer.copy(externalFileKeys = transform(layer.externalFileKeys))))
        }
    }

    // ------------------------------------------------------------------ settings

    private fun onSettings(settings: AppSettings) {
        refreshAiKey(settings)
        val current = _ui.value
        _ui.value = current.copy(
            settings = settings,
            learningMode = settings.learning.mode,
            shadowing = current.shadowing.copy(
                autoRepeat = settings.shadowing.autoRepeatEnabled || settings.shadowing.repeatOnEveryBlock,
                stopAtEnd = settings.shadowing.stopAtEndOfBlock,
                repeatCount = settings.shadowing.repeatCount,
            ),
            layers = TrackRole.entries.associateWith { role ->
                val layer = settings.subtitleLayer(role)
                current.layer(role).let { ui ->
                    ui.copy(
                        visible = layer.visible,
                        scalePercent = layer.scalePercent,
                        transparencyPercent = layer.transparencyPercent,
                        placement = layer.placement,
                        delayMs = layer.delayMs,
                    )
                }
            },
            list = current.list.copy(
                noSpoiler = settings.subtitles.noSpoilerMode,
                open = current.list.open && settings.subtitles.listPanelEnabled,
            ),
        )
        if (current.playback.target != null) {
            if (current.playback.speedPercent == 100 && settings.player.defaultSpeedPercent != 100) {
                controller.setSpeedPercent(settings.player.defaultSpeedPercent)
            }
        }
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


    fun setLayoutMode(on: Boolean) {
        _ui.value = _ui.value.copy(layoutMode = on, controlsVisible = !on)
        if (on) {
            controller.pause()
            _intent.tryEmit(PlayerIntent.HideSystemBars(false))
        }
    }

    fun moveLayer(role: TrackRole, placement: SubtitlePlacement) = updateLayer(role) { it.copy(placement = placement) }

    // ------------------------------------------------------------------ position, layers

    private fun onPosition(state: PlaybackState) {
        val settings = _ui.value.settings
        publish(state)
        runShadowPlan(state, settings)
        if (state.isPlaying) scheduleControlsHide(settings.player.controlsAutoHideMs)
        refreshWordStates(state, settings)
    }

    private fun scheduleControlsHide(ms: Long) {
        if (!_ui.value.controlsVisible) return
        autoHideJob?.cancel()
        autoHideJob = viewModelScope.launch {
            delay(ms)
            if (playback.value.isPlaying && !_ui.value.layoutMode && _ui.value.popup == null && _ui.value.sheet == Sheet.NONE) {
                _ui.value = _ui.value.copy(controlsVisible = false)
            }
        }
    }

    fun showControls(keepVisibleMs: Long? = null) {
        _ui.value = _ui.value.copy(controlsVisible = true)
        autoHideJob?.cancel()
        if (keepVisibleMs != null) {
            autoHideJob = viewModelScope.launch {
                delay(keepVisibleMs)
                if (!_ui.value.layoutMode && _ui.value.sheet == Sheet.NONE) _ui.value = _ui.value.copy(controlsVisible = false)
            }
        }
    }

    fun toggleControls() {
        if (_ui.value.controlsVisible) _ui.value = _ui.value.copy(controlsVisible = false) else showControls(KEEP_VISIBLE_MS)
    }

    /** Recomputes both layers' current text and the shadowing anchors. */
    private fun publish(state: PlaybackState = controller.state.value) {
        val settings = _ui.value.settings
        val position = state.positionMs
        val docs = mapOf(TrackRole.LEARNING to learningDoc, TrackRole.TRANSLATION to translationDoc)
        val cueRole = cueTargetLayer(state)
        val layers = TrackRole.entries.associateWith { role ->
            val doc = docs.getValue(role)
            val layer = settings.subtitleLayer(role)
            val delayed = position + layer.delayMs
            val block = doc?.blockAt(delayed)
            val usesCues = doc == null && role == cueRole
            val text = when {
                block != null -> block.text
                usesCues -> embeddedCueText
                else -> null
            }
            val source = when {
                doc != null -> LayerSource.FILE
                usesCues -> LayerSource.PLAYER_CUES
                else -> LayerSource.NONE
            }
            _ui.value.layer(role).copy(
                block = block,
                text = text,
                source = source,
                visible = layer.visible && (text != null || doc != null),
            )
        }
        val listDoc = if (_ui.value.list.role == TrackRole.LEARNING) learningDoc else translationDoc
        val current = layers[TrackRole.LEARNING]?.block ?: layers[TrackRole.TRANSLATION]?.block
        _ui.value = _ui.value.copy(
            playback = state,
            layers = layers,
            list = _ui.value.list.copy(
                available = listDoc != null,
                currentIndex = current?.let { listDoc?.indexOf(it.id) } ?: -1,
            ),
            shadowing = _ui.value.shadowing.copy(
                active = plan != null,
                repeatsLeft = plan?.let { it.repeatTotal - it.completed } ?: 0,
                pausedForRepeat = plan?.paused == true,
                segmentLabel = plan?.let { "repeat ${it.completed + 1}/${it.repeatTotal}" },
            ),
            ai = _ui.value.ai.copy(configured = aiConfigured(settings)),
        )
    }

    /** The layer that receives the player's own cue text when it has no file (see KNOWN_ISSUES). */
    private fun cueTargetLayer(state: PlaybackState): TrackRole? {
        val settings = _ui.value.settings
        val selectedIndex = state.textTracks.firstOrNull { it.isSelected }?.ref?.trackIndex
        if (selectedIndex != null) {
            TrackRole.entries.firstOrNull { role ->
                settings.subtitleLayer(role).embeddedTrackIndexes.contains(selectedIndex) && documentFor(role) == null
            }?.let { return it }
        }
        val fileless = TrackRole.entries.filter { documentFor(it) == null && settings.subtitleLayer(it).visible }
        return fileless.singleOrNull()
    }

    private fun documentFor(role: TrackRole): SubtitleDocument? = when (role) {
        TrackRole.LEARNING -> learningDoc
        TrackRole.TRANSLATION -> translationDoc
    }

    // ------------------------------------------------------------------ shadowing (SHD-1..3)

    private class ShadowPlan(
        val block: SubtitleBlock,
        val segments: List<com.sublearn.core.subtitles.RepeatSegment>,
        val repeatTotal: Int,
        var completed: Int = 0,
        var index: Int = 0,
        var paused: Boolean = false,
        val stopAtEnd: Boolean,
        val blockEndMs: Long,
    )

    private fun shadowConfig(settings: AppSettings) = ShadowingPauseConfig(
        baseMs = settings.shadowing.pauseBaseMs,
        durationMultiplier = settings.shadowing.pauseDurationMultiplier,
        pauseMaxMs = settings.shadowing.pauseMaxMs,
        preRollMs = settings.shadowing.preRollMs,
        minBlockDurationMs = settings.shadowing.minBlockDurationMs,
    )

    private fun runShadowPlan(state: PlaybackState, settings: AppSettings) {
        val config = shadowConfig(settings)
        val active = plan
        if (active != null) {
            val segment = active.segments.getOrNull(active.index) ?: return finishPlan()
            if (ShadowingMath.reachedBlockEnd(active.block, state.positionMs) || state.positionMs >= segment.endMs) {
                active.paused = true
                controller.pause()
                viewModelScope.launch {
                    delay(segment.pauseAfterMs.coerceAtLeast(120L))
                    advance(active, config)
                }
            }
            return
        }
        val doc = primaryDocument() ?: return
        val block = doc.blockAt(state.positionMs) ?: return
        val wantsAuto = settings.shadowing.autoRepeatEnabled || settings.shadowing.repeatOnEveryBlock
        val wantsStop = settings.shadowing.stopAtEndOfBlock
        if (!wantsAuto && !wantsStop) return
        if (!state.isPlaying) return
        if (ShadowingMath.reachedBlockEnd(block, state.positionMs) && block.durationMs >= config.minBlockDurationMs) {
            if (wantsStop && !wantsAuto) {
                controller.pause()
                return
            }
            startPlan(block, config, settings.shadowing.repeatCount.coerceAtLeast(1), wantsStop)
        }
    }

    private fun startPlan(block: SubtitleBlock, config: ShadowingPauseConfig, repeatCount: Int, stopAtEnd: Boolean) {
        val segments = ShadowingMath.plan(block, repeatCount, config)
        if (segments.isEmpty()) return
        plan = ShadowPlan(block, segments, repeatCount, stopAtEnd = stopAtEnd, blockEndMs = block.endMs)
        controller.seekTo(ShadowingMath.repeatStart(block.startMs, config))
        controller.play()
        publish()
    }

    private fun advance(active: ShadowPlan, config: ShadowingPauseConfig) {
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
            }
        }
        publish()
    }

    private fun finishPlan() {
        plan = null
        publish()
    }

    private fun primaryDocument(): SubtitleDocument? =
        learningDoc ?: translationDoc

    /** The manual repeat toggle: repeats the block the playhead is inside right now. */
    fun toggleRepeatBlock(inverted: Boolean = false) {
        val state = playback.value
        val doc = primaryDocument() ?: return message(NO_SUBTITLE_FILE)
        val block = doc.blockAt(state.positionMs) ?: return message(NO_BLOCK_HERE)
        val settings = _ui.value.settings
        val active = plan != null
        if (active && !inverted) {
            finishPlan()
            return
        }
        val config = shadowConfig(settings)
        val count = if (inverted) 1 else settings.shadowing.repeatCount.coerceAtLeast(1)
        startPlan(block, config, count, settings.shadowing.stopAtEndOfBlock)
    }

    fun toggleStopAtEnd() = updateSettings { settings ->
        settings.copy(shadowing = settings.shadowing.copy(stopAtEndOfBlock = !settings.shadowing.stopAtEndOfBlock))
    }

    fun toggleAutoRepeat() = updateSettings { settings ->
        settings.copy(shadowing = settings.shadowing.copy(autoRepeatEnabled = !settings.shadowing.autoRepeatEnabled))
    }

    fun setRepeatCount(count: Int) = updateSettings { settings ->
        settings.copy(shadowing = settings.shadowing.copy(repeatCount = count.coerceIn(1, 12)))
    }

    // ------------------------------------------------------------------ seeking by subtitle

    fun stepBlock(delta: Int) {
        val doc = primaryDocument() ?: return message(NO_SUBTITLE_FILE)
        val position = playback.value.positionMs
        val layer = _ui.value.settings.subtitleLayer(_ui.value.list.role)
        val shifted = position + layer.delayMs
        val target = if (delta > 0) doc.nextBlock(shifted) else doc.previousBlock(shifted)
        if (target == null) {
            message(if (delta > 0) "end of the subtitles" else "already at the first line")
            return
        }
        controller.seekTo((target.startMs - layer.delayMs).coerceAtLeast(0L))
        if (_ui.value.list.open) refreshList()
    }

    fun seekToBlock(block: SubtitleBlock) {
        controller.seekTo(block.startMs.coerceAtLeast(0L))
        showControls(KEEP_VISIBLE_MS)
        controller.play()
    }

    // ------------------------------------------------------------------ popups (LRN-1)

    /** A tap on a word, a line or a block: the granularity comes from the tap count. */
    fun onLayerTap(role: TrackRole, kind: PopupKind, word: String?, block: SubtitleBlock?) {
        val target = block ?: return message(NO_BLOCK_HERE)
        when (kind) {
            PopupKind.WORD -> if (!word.isNullOrBlank()) showWordPopup(word, target) else showLinePopup(target)
            PopupKind.LINE -> showLinePopup(target)
            PopupKind.BLOCK -> showBlockPopup(target)
        }
        if (_ui.value.settings.learning.mode == LearningMode.OFF) {
            updateSettings { it.copy(learning = it.learning.copy(mode = LearningMode.ENTERTAINMENT)) }
        }
    }

    private fun showWordPopup(word: String, block: SubtitleBlock) {
        _ui.value = _ui.value.copy(popup = PopupUi(PopupKind.WORD, word, word = word, busy = true, timestampMs = block.startMs))
        pauseIfLineIsAboutToChange()
        popupJob?.cancel()
        popupJob = viewModelScope.launch {
            val result: WordTranslation = translation.translateWord(word, block.text)
            val marked = runCatching { myWords.find(word) }.getOrNull()
            val level = levelOf(word)
            _ui.value = _ui.value.copy(
                popup = _ui.value.popup?.copy(
                    busy = false,
                    translation = result.glossText,
                    contextTranslation = result.contextTranslation,
                    error = result.error?.message,
                    needsModelDownload = result.needsModelDownload,
                    marked = marked != null,
                    level = level?.name,
                ),
                modelMissing = result.needsModelDownload,
            )
            result.glossText?.let { gloss ->
                val ui = _ui.value
                _ui.value = ui.copy(glosses = ui.glosses + (word.lowercase() to gloss))
            }
        }
    }

    private fun showLinePopup(block: SubtitleBlock) {
        _ui.value = _ui.value.copy(popup = PopupUi(PopupKind.LINE, block.text, busy = true, timestampMs = block.startMs))
        pauseIfLineIsAboutToChange()
        popupJob?.cancel()
        popupJob = viewModelScope.launch {
            translation.translateBlock(block).fold(
                onSuccess = { text -> _ui.value = _ui.value.copy(popup = _ui.value.popup?.copy(busy = false, translation = text)) },
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

    private fun showBlockPopup(block: SubtitleBlock) = showLinePopup(block)

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
        _ui.value = _ui.value.copy(popup = null)
        popupJob?.cancel()
        if (resume) controller.play()
    }

    fun toggleMarkInPopup() {
        val popup = _ui.value.popup ?: return
        val block = _ui.value.layer(TrackRole.LEARNING).block ?: return
        viewModelScope.launch {
            val existing = popup.word?.let { runCatching { myWords.find(it) }.getOrNull() }
            if (existing != null) {
                myWords.remove(existing.id)
                _ui.value = _ui.value.copy(popup = popup.copy(marked = false))
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
                    ),
                )
                _ui.value = _ui.value.copy(popup = popup.copy(marked = id != 0L))
                refreshWordStates(playback.value, _ui.value.settings)
            }
        }
    }

    /** Downloads the missing ML Kit model; ML Kit reports no byte counts, so the status is coarse. */
    fun downloadModel() {
        viewModelScope.launch {
            message(application.getString(R.string.translate_downloading))
            translation.downloadModel { progress ->
                if (progress.status == TranslationProgress.Status.RUNNING && progress.fraction > 0f) {
                    message(application.getString(R.string.translate_downloading) + " " + (progress.fraction * 100f).toInt() + "%")
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

    // ------------------------------------------------------------------ word styling (SUB-5, LRN-3)

    private fun refreshWordStates(state: PlaybackState, settings: AppSettings) {
        if (settings.learning.mode == LearningMode.OFF) return
        val block = learningDoc?.blockAt(state.positionMs + settings.subtitleLayer(TrackRole.LEARNING).delayMs) ?: return
        if (_ui.value.playback.target == null) return
        val words = block.tokens.filter { it.isWord }.map { it.text.lowercase() }.distinct()
        if (words.isEmpty()) return
        if (_ui.value.wordStates.keys.sorted() == words.sorted()) return
        wordStateJob?.cancel()
        wordStateJob = viewModelScope.launch(Dispatchers.Default) {
            val marked = runCatching { myWords.findByWords(words) }.getOrDefault(emptyMap())
            val provider = wordLevels.provider(settings.level, settings.learning.manualLevel)
            val states = words.mapNotNull { word ->
                val markedWord = marked[word]
                val level = runCatching { provider.level(word) }.getOrNull()
                val visual = when {
                    markedWord != null && settings.learning.markedWordsAreKnown -> WordVisualState.MARKED
                    markedWord != null -> WordVisualState.MARKED
                    level != null && level.ordinal >= settings.learning.manualLevel.ordinal &&
                        settings.learning.showAboveLevelOnly -> WordVisualState.ABOVE_LEVEL
                    level != null && level.ordinal >= settings.level.assumedLevelOfUnknownWords.ordinal -> WordVisualState.ABOVE_LEVEL
                    else -> WordVisualState.NONE
                }
                word to WordVisualStateBundle(visual, level?.name, markedWord?.translation)
            }.toMap()
            _ui.value = _ui.value.copy(wordStates = states.mapValues { it.value.visual })
        }
    }

    private data class WordVisualStateBundle(val visual: WordVisualState, val level: String?, val gloss: String?)

    private suspend fun levelOf(word: String): com.sublearn.core.settings.CefrLevel? {
        val settings = _ui.value.settings
        return runCatching { wordLevels.provider(settings.level, settings.learning.manualLevel).level(word) }.getOrNull()
    }

    // ------------------------------------------------------------------ list panel

    fun toggleList() {
        val open = !_ui.value.list.open
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

    fun toggleNoSpoiler() = updateSettings { settings ->
        settings.copy(subtitles = settings.subtitles.copy(noSpoilerMode = !settings.subtitles.noSpoilerMode))
    }

    private fun refreshList() {
        val role = _ui.value.list.role
        val doc = documentFor(role)
        if (doc == null) {
            _ui.value = _ui.value.copy(list = _ui.value.list.copy(rows = emptyList(), available = false))
            return
        }
        val current = doc.blockAt(playback.value.positionMs)?.id
        _ui.value = _ui.value.copy(
            list = _ui.value.list.copy(
                available = true,
                rows = doc.blocks.mapIndexed { index, block ->
                    SubtitleListRow(block.id, index, block.startMs, block.text, block.id == current)
                },
            ),
        )
    }

    fun seekToRow(row: SubtitleListRow) {
        val doc = documentFor(_ui.value.list.role) ?: return
        doc.blockById(row.blockId)?.let { seekToBlock(it) }
        refreshList()
    }

    // ------------------------------------------------------------------ tracks, speed, aspect, decoder

    fun setRepeat(repeat: com.sublearn.core.player.PlaylistRepeat) = controller.setRepeat(repeat)

    fun selectTrack(ref: TrackRef) {
        controller.selectTrack(ref)
        if (ref.type == PlayerTrackType.TEXT) {
            // Which layer an embedded track feeds is a per-video choice, remembered per layer.
            updateLayer(TrackRole.TRANSLATION) { layer ->
                layer.copy(embeddedTrackIndexes = (layer.embeddedTrackIndexes + ref.trackIndex).distinct())
            }
        }
    }


    fun clearTrackOverride(type: PlayerTrackType) = controller.clearTrackOverride(type)

    fun setSpeedPercent(percent: Int) = controller.setSpeedPercent(percent)

    fun nudgeSpeed(deltaPercent: Int) = controller.nudgeSpeed(
        deltaPercent,
        _ui.value.settings.player.speedMinPercent,
        _ui.value.settings.player.speedMaxPercent,
    )

    fun setAspect(mode: AspectMode) {
        controller.setAspect(mode, _ui.value.settings.player.customAspectWidth, _ui.value.settings.player.customAspectHeight)
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

    fun setDecoder(mode: DecoderMode) {
        updateSettings { settings -> settings.copy(player = settings.player.copy(decoder = mode)) }
        viewModelScope.launch {
            runCatching { controller.setDecoderMode(mode) }
                .onFailure { error -> message(error.message ?: "the decoder could not be changed") }
        }
    }

    val isMuted: Boolean get() = muted

    /** Share the current position as a link-free text payload: the file itself may not be shareable. */
    fun shareCurrent() {
        val state = playback.value
        val target = state.target ?: return message(NO_MEDIA_HERE)
        _intent.tryEmit(PlayerIntent.Share(target.uri, target.title))
    }

    fun enterPip() {
        val state = playback.value
        val width = state.videoWidth.takeIf { it > 0 } ?: 16
        val height = state.videoHeight.takeIf { it > 0 } ?: 9
        _intent.tryEmit(PlayerIntent.EnterPip(width, height))
    }

    fun setMuted(value: Boolean) {
        muted = value
        controller.setMuted(value)
    }

    fun togglePlay() = controller.togglePlayPause()

    fun seekBy(deltaMs: Long) = controller.seekBy(deltaMs)

    fun setBrightnessFollows(value: Float) {
        _intent.tryEmit(PlayerIntent.Brightness(value.coerceIn(0f, 1f)))
    }

    fun updateQuickAction(
        id: QuickActionId,
        transform: (com.sublearn.core.settings.QuickActionSpec) -> com.sublearn.core.settings.QuickActionSpec,
    ) {
        updateSettings { settings ->
            val spec = settings.quickActions.spec(id)
            settings.copy(quickActions = settings.quickActions.updated(id, transform(spec)))
        }
    }

    fun setLayerDelay(role: TrackRole, deltaMs: Long) =
        updateLayer(role) { it.copy(delayMs = (it.delayMs + deltaMs).coerceIn(-5_000L, 5_000L)) }

    fun setLayerScale(role: TrackRole, percent: Int) =
        updateLayer(role) { it.copy(scalePercent = percent.coerceIn(60, 220)) }

    fun setLayerTransparency(role: TrackRole, percent: Int) =
        updateLayer(role) { it.copy(transparencyPercent = percent.coerceIn(0, 100)) }

    fun toggleListFor(role: TrackRole) = updateLayer(role) { it.copy(showInList = !it.showInList) }

    fun setSheet(sheet: Sheet) {
        _ui.value = _ui.value.copy(sheet = sheet)
        if (sheet != Sheet.NONE) showControls(KEEP_VISIBLE_MS)
    }

    // ------------------------------------------------------------------ gestures and actions

    /** Every gesture and quick action funnels through here, so behaviour never depends on the input. */
    fun onAction(action: GestureAction) {
        when (action) {
            GestureAction.NONE -> Unit
            GestureAction.TOGGLE_PLAY -> controller.togglePlayPause()
            GestureAction.SEEK_FORWARD -> controller.seekBy(_ui.value.settings.player.seekStepMs)
            GestureAction.SEEK_BACKWARD -> controller.seekBy(-_ui.value.settings.player.seekStepMs)
            GestureAction.TOGGLE_CONTROLS -> toggleControls()
            GestureAction.ASPECT_RATIO -> cycleAspect()
            GestureAction.REPEAT_BLOCK -> toggleRepeatBlock()
            GestureAction.TOGGLE_LEARNING_SUBTITLE -> toggleLayerVisible(TrackRole.LEARNING)
            GestureAction.TOGGLE_TRANSLATION_SUBTITLE -> toggleLayerVisible(TrackRole.TRANSLATION)
            GestureAction.LOCK -> toggleLock()
            GestureAction.NEXT_BLOCK -> stepBlock(1)
            GestureAction.PREVIOUS_BLOCK -> stepBlock(-1)
            GestureAction.TOGGLE_MUTE -> {
                muted = !muted
                controller.setMuted(muted)
            }
            GestureAction.BRIGHTNESS, GestureAction.VOLUME, GestureAction.SEEK, GestureAction.PLAYBACK_SPEED ->
                Unit // handled by the gesture layer, which knows the drag distance
        }
    }

    fun onDrag(action: GestureAction, fraction: Float) {
        when (action) {
            GestureAction.BRIGHTNESS -> _intent.tryEmit(PlayerIntent.Brightness(fraction))
            GestureAction.VOLUME -> _intent.tryEmit(PlayerIntent.Volume(fraction))
            GestureAction.SEEK -> {
                val seconds = (fraction * _ui.value.settings.player.swipeSeekSecondsPerScreen).toLong()
                controller.seekTo((playback.value.positionMs + seconds * 1000L).coerceAtLeast(0L))
            }
            GestureAction.PLAYBACK_SPEED -> nudgeSpeed((fraction * _ui.value.settings.player.speedStepPercent * 4f).toInt())
            else -> Unit
        }
    }

    private fun cycleAspect() {
        val order = listOf(AspectMode.FIT, AspectMode.FILL, AspectMode.ZOOM, AspectMode.STRETCH)
        val current = playback.value.aspect
        val next = order[(order.indexOf(current).coerceAtLeast(0) + 1) % order.size]
        setAspect(next)
    }

    /** The two- and three-tap zones on the left and right of the video (PLY-2). */
    fun onDoubleTap(side: TapSide) {
        val settings = _ui.value.settings
        when (settings.player.doubleTapAction) {
            com.sublearn.core.settings.DoubleTapAction.PAUSE -> controller.togglePlayPause()
            com.sublearn.core.settings.DoubleTapAction.SEEK -> {
                val step = settings.player.doubleTapSeekMs
                seekBy(if (side == TapSide.RIGHT) step else -step)
            }
            com.sublearn.core.settings.DoubleTapAction.NONE -> showControls(KEEP_VISIBLE_MS)
        }
        showControls(KEEP_VISIBLE_MS)
    }

    enum class TapSide { LEFT, CENTER, RIGHT }

    fun toggleLock() {
        _ui.value = _ui.value.copy(locked = !_ui.value.locked, controlsVisible = !_ui.value.locked)
    }

    fun onQuickAction(id: QuickActionId, inverted: Boolean) {
        when (id) {
            QuickActionId.TOGGLE_LEARNING -> toggleLayerVisible(TrackRole.LEARNING)
            QuickActionId.TOGGLE_TRANSLATION -> toggleLayerVisible(TrackRole.TRANSLATION)
            QuickActionId.REPEAT_BLOCK -> toggleRepeatBlock(inverted)
            QuickActionId.STOP_AT_BLOCK_END -> updateSettings { settings ->
                settings.copy(shadowing = settings.shadowing.copy(stopAtEndOfBlock = inverted != settings.shadowing.stopAtEndOfBlock))
            }
            QuickActionId.LAYOUT_MODE -> setLayoutMode(!_ui.value.layoutMode)
            QuickActionId.SUBTITLE_LIST -> toggleList()
            QuickActionId.SUBTITLE_TRACKS -> setSheet(Sheet.TRACKS)
            QuickActionId.AUDIO_TRACK -> setSheet(Sheet.TRACKS)
            QuickActionId.AI_EXPLAIN -> askAi(null)
            QuickActionId.SPEED -> setSheet(Sheet.SPEED)
            QuickActionId.ASPECT_RATIO -> setSheet(Sheet.ASPECT)
            QuickActionId.DECODER -> setSheet(Sheet.DECODER)
            QuickActionId.SUBTITLE_TOOLS -> setSheet(Sheet.TOOLS)
            QuickActionId.PLAYLIST -> setSheet(Sheet.PLAYLIST)
            QuickActionId.MY_WORDS -> _intent.tryEmit(PlayerIntent.OpenWords)
            QuickActionId.SETTINGS -> _intent.tryEmit(PlayerIntent.OpenSettings)
            QuickActionId.PICTURE_IN_PICTURE -> enterPip()
            QuickActionId.SHARE -> shareCurrent()
            else -> message("${id.key} is not wired yet")
        }
    }

    // ------------------------------------------------------------------ AI (AI-1..AI-4)

    fun askAi(selectedText: String?) {
        val settings = _ui.value.settings
        if (!aiConfigured(settings)) {
            message(NO_AI_KEY)
            return
        }
        val doc = learningDoc ?: return message(NO_SUBTITLE_FILE)
        val block = doc.blockAt(playback.value.positionMs) ?: return message(NO_BLOCK_HERE)
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

    // ------------------------------------------------------------------ lifecycle

    /** Called when the screen leaves the foreground: saves the position, keeps the player alive. */
    fun onLeave() {
        plan = null
        shadowJob?.cancel()
        viewModelScope.launch {
            controller.flushPosition()
            openedTarget?.let { target ->
                recentVideos.touch(target.uri, target.title, playback.value.durationMs, playback.value.positionMs)
            }
        }
    }

    /** Rebuilds the player after the Activity returns from PiP or a configuration change. */
    fun onReturn() {
        publish()
        showControls(KEEP_VISIBLE_MS)
    }

    fun message(text: String?) {
        _ui.value = _ui.value.copy(message = text)
    }

    private fun clock(ms: Long): String {
        val total = ms / 1000L
        return "%d:%02d".format(total / 60L, total % 60L)
    }

    override fun onCleared() {
        shadowJob?.cancel()
        aiJob?.cancel()
        popupJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val RESUME_THRESHOLD_MS = 8_000L
        private const val PAUSE_LEAD_MS = 4_000L
        private const val KEEP_VISIBLE_MS = 6_000L
        private const val NO_SUBTITLE_FILE = "add a subtitle file to this layer first"
        private const val NO_BLOCK_HERE = "nothing is on screen at this moment"
        private const val NO_AI_KEY = "add an API key in Settings -> AI first"
        private const val NO_MEDIA_HERE = "open a video first"
    }
}

/** Requests the Activity handles: window effects that Compose cannot own. */
sealed interface PlayerIntent {
    data class Brightness(val value: Float) : PlayerIntent
    data class Volume(val value: Float) : PlayerIntent
    data class EnterPip(val aspectWidth: Int, val aspectHeight: Int) : PlayerIntent
    data class HideSystemBars(val hide: Boolean) : PlayerIntent
    data class Orientation(val lock: com.sublearn.core.settings.OrientationLock) : PlayerIntent
    data class Share(val uri: String, val title: String) : PlayerIntent
    data object OpenWords : PlayerIntent
    data object OpenSettings : PlayerIntent
}
