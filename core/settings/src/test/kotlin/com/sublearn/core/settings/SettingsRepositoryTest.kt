package com.sublearn.core.settings

import com.sublearn.core.subtitles.TrackRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRepositoryTest {
    @Test
    fun `defaults are loadable and versioned`() {
        assertEquals(AppSettings.SCHEMA_VERSION, AppSettings.DEFAULT.schemaVersion)
        assertTrue(AppSettings.DEFAULT.subtitles.layers.size == 2)
        assertEquals(3_000L, AppSettings.DEFAULT.player.controlsAutoHideMs)
    }

    @Test
    fun `round trips through json`() {
        val original = AppSettings.DEFAULT.copy(
            player = AppSettings.DEFAULT.player.copy(decoder = DecoderMode.SOFTWARE, doubleTapAction = DoubleTapAction.SEEK),
            learning = AppSettings.DEFAULT.learning.copy(mode = LearningMode.LEARNING, maxPopupCards = 6),
        )
        val decoded = SettingsJson.decode(SettingsJson.encode(original)).getOrThrow()
        assertEquals(original, decoded)
    }

    @Test
    fun `unknown keys are ignored instead of failing`() {
        val text = SettingsJson.encode(AppSettings.DEFAULT).replace("\"schemaVersion\"", "\"futureOption\": 12, \"schemaVersion\"")
        assertNotNull(SettingsJson.decode(text).getOrNull())
    }

    @Test
    fun `a newer document is rejected with a message`() {
        val text = SettingsJson.encode(AppSettings.DEFAULT.copy(schemaVersion = 99))
        val result = SettingsJson.decode(text)
        assertNotNull(result.errorOrNull())
        assertTrue(result.errorOrNull()!!.message.contains("newer"))
    }

    @Test
    fun `migration stamps the current version`() {
        val legacy = """{"schemaVersion": 1, "player": {"seekStepMs": 15000}}"""
        val decoded = SettingsJson.decode(legacy).getOrThrow()
        assertEquals(AppSettings.SCHEMA_VERSION, decoded.schemaVersion)
        assertEquals(15_000L, decoded.player.seekStepMs)
        // Untouched sections keep their defaults.
        assertEquals(LearningMode.ENTERTAINMENT, decoded.learning.mode)
    }

    @Test
    fun `update persists and the flow sees it`() = runTest {
        val storage = InMemorySettingsStorage()
        val repo = DefaultSettingsRepository(storage)
        repo.update { it.copy(shadowing = it.shadowing.copy(repeatCount = 4)) }
        assertEquals(4, repo.settings.value.shadowing.repeatCount)
        val reloaded = DefaultSettingsRepository(storage)
        assertEquals(4, reloaded.current().shadowing.repeatCount)
    }

    @Test
    fun `concurrent updates are serialised and both land`() = runTest {
        val repo = DefaultSettingsRepository(InMemorySettingsStorage())
        coroutineScope {
            repeat(20) {
                launch(Dispatchers.Unconfined) {
                    repo.update { it.copy(shadowing = it.shadowing.copy(repeatCount = it.shadowing.repeatCount + 1)) }
                }
            }
        }
        assertEquals(AppSettings.DEFAULT.shadowing.repeatCount + 20, repo.current().shadowing.repeatCount)
    }

    @Test
    fun `export then import restores the document`() = runTest {
        val repo = DefaultSettingsRepository(InMemorySettingsStorage())
        repo.update { it.copy(ai = it.ai.copy(model = "gemini-test", contextBlocks = 3)) }
        val exported = repo.exportJson()
        repo.reset()
        assertEquals(AppSettings.DEFAULT.ai.contextBlocks, repo.current().ai.contextBlocks)
        val report = repo.importJson(exported).getOrThrow()
        assertTrue(report.appliedSections.contains(Sections.AI))
        assertTrue(report.droppedUnknownSections.isEmpty())
        assertEquals(3, repo.current().ai.contextBlocks)
        assertEquals("gemini-test", repo.current().ai.model)
    }

    @Test
    fun `merge import keeps sections that are absent`() = runTest {
        val repo = DefaultSettingsRepository(InMemorySettingsStorage())
        repo.update { it.copy(shadowing = it.shadowing.copy(repeatCount = 7)) }
        val partial = "{\"schemaVersion\": 3, \"player\": {\"seekStepMs\": 5000}, \"mystery\": true}"
        val report = repo.importJson(partial, ImportMode.MERGE).getOrThrow()
        assertEquals(7, repo.current().shadowing.repeatCount)
        assertEquals(5_000L, repo.current().player.seekStepMs)
        assertEquals(listOf(Sections.PLAYER), report.appliedSections)
        assertEquals(listOf("mystery"), report.droppedUnknownSections)
    }

    @Test
    fun `font overrides never bleed between surfaces`() {
        val registry = FontRegistry(
            mapOf(fontKey(FontSurface.SUBTITLE_LEARNING, SubtitleLayerRole.NATIVE) to FontSpec(sizeSp = 30f)),
        )
        assertEquals(30f, registry.styleFor(FontSurface.SUBTITLE_LEARNING, SubtitleLayerRole.NATIVE).sizeSp)
        assertEquals(
            FontSpec.defaultFor(FontSurface.SUBTITLE_LEARNING, SubtitleLayerRole.LEARNING).sizeSp,
            registry.styleFor(FontSurface.SUBTITLE_LEARNING, SubtitleLayerRole.LEARNING).sizeSp,
        )
        assertEquals(
            FontSpec.defaultFor(FontSurface.WORD_CARD, SubtitleLayerRole.NATIVE).sizeSp,
            registry.styleFor(FontSurface.WORD_CARD, SubtitleLayerRole.NATIVE).sizeSp,
        )
    }

    @Test
    fun `gesture remapping falls back to the default and clears when equal to it`() {
        val gestures = GestureSettings()
        assertEquals(GestureAction.BRIGHTNESS, gestures.actionFor(GestureSlot.LEFT_VERTICAL))
        val remapped = gestures.withAction(GestureSlot.LEFT_VERTICAL, GestureAction.VOLUME)
        assertEquals(GestureAction.VOLUME, remapped.actionFor(GestureSlot.LEFT_VERTICAL))
        val backToDefault = remapped.withAction(GestureSlot.LEFT_VERTICAL, GestureAction.BRIGHTNESS)
        assertFalse(backToDefault.mapping.containsKey(GestureSlot.LEFT_VERTICAL.key))
    }

    @Test
    fun `layer settings are per role and independent`() {
        val subtitles = SubtitleSettings().updated(TrackRole.TRANSLATION, SubtitleLayerSettings(visible = false, delayMs = 900L))
        assertEquals(900L, subtitles.layer(TrackRole.TRANSLATION).delayMs)
        assertTrue(subtitles.layer(TrackRole.LEARNING).visible)
        assertFalse(subtitles.layer(TrackRole.TRANSLATION).visible)
    }

    @Test
    fun `quick action layout respects dock mode and order`() {
        val settings = QuickActionSettings()
        val hidden = settings.updated(QuickActionId.AI_EXPLAIN, QuickActionSpec(dock = DockMode.HIDDEN))
        assertTrue(hidden.visibleIn(DockMode.HIDDEN).isEmpty())
        assertTrue(hidden.visibleIn(DockMode.BAR).none { it.first == QuickActionId.AI_EXPLAIN })
        assertTrue(hidden.visibleIn(DockMode.FLOATING).none { it.first == QuickActionId.AI_EXPLAIN })
        assertTrue(settings.specs.isNotEmpty())
        assertEquals(DockMode.BAR, settings.spec(QuickActionId.TOGGLE_LEARNING).dock)
    }

    @Test
    fun `prompt template keeps its placeholders`() {
        val prompt = AiSettings.DEFAULT_PROMPT
        listOf("nativeLanguage", "level", "title", "timestamp", "context", "selected").forEach {
            assertTrue("missing placeholder ${'$'}{$it}", prompt.contains("\${$it}"))
        }
    }
}
