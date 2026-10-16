package com.sublearn.feature.player

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sublearn.core.designsystem.SubLearnTheme
import com.sublearn.core.player.PlaybackState
import com.sublearn.core.settings.AppSettings
import com.sublearn.core.settings.LearningMode
import com.sublearn.core.settings.SubtitleHorizontalAnchor
import com.sublearn.core.settings.SubtitleVerticalAnchor
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LearningCardsSmokeTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun cardsDisappearInEntertainmentAndWhileControlsAreVisible() {
        val base = AppSettings.DEFAULT
        val settings = base.copy(learning = base.learning.copy(
            mode = LearningMode.LEARNING,
            popupCorner = base.learning.popupCorner.copy(
                vertical = SubtitleVerticalAnchor.TOP,
                horizontal = SubtitleHorizontalAnchor.END,
                offsetXDp = 0f,
                offsetYDp = 0f,
            ),
        ))
        val state = mutableStateOf(PlayerUi(
            settings = settings,
            playback = PlaybackState(isPlaying = true),
            controlsVisible = false,
            learningCards = listOf(LearningCardUi("reluctant", "reluctant", "بی‌میل", "B2")),
        ))
        rule.setContent {
            SubLearnTheme(settings = state.value.settings) {
                LearningCardsOverlay(state.value, inPip = false)
            }
        }
        rule.onNodeWithText("reluctant").assertIsDisplayed()
        rule.onNodeWithText("بی‌میل").assertIsDisplayed()
        rule.runOnIdle { state.value = state.value.copy(controlsVisible = true) }
        rule.waitForIdle()
        rule.onNodeWithText("reluctant").assertDoesNotExist()
        rule.runOnIdle {
            state.value = state.value.copy(
                controlsVisible = false,
                settings = settings.copy(learning = settings.learning.copy(mode = LearningMode.ENTERTAINMENT)),
            )
        }
        rule.onNodeWithText("reluctant").assertDoesNotExist()
    }

    @Test
    fun noHintCoversSubtitlesWhenAllCornersAreOccupied() {
        val base = AppSettings.DEFAULT
        val settings = base.copy(learning = base.learning.copy(mode = LearningMode.LEARNING))
        val ui = PlayerUi(
            settings = settings,
            playback = PlaybackState(isPlaying = true),
            controlsVisible = false,
            learningCards = listOf(LearningCardUi("word", "reluctant", "بی‌میل", "B2")),
        )
        rule.setContent {
            SubLearnTheme(settings = settings) {
                LearningCardsOverlay(ui, inPip = false, subtitleBounds = listOf(Rect(-10_000f, -10_000f, 10_000f, 10_000f)))
            }
        }
        rule.onNodeWithText("reluctant").assertDoesNotExist()
    }

}
