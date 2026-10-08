package com.sublearn.feature.player

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sublearn.core.designsystem.SubLearnTheme
import com.sublearn.core.settings.AppSettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A real-device smoke test of the two overlay pieces that depend on actual text measurement:
 * the translation card (word granularity, Latin source with a Persian gloss) and the subtitle list.
 *
 * The same widgets are exercised in JVM tests for logic; this exists because hit testing and
 * bidi shaping only behave correctly with a live font stack, which Robolectric cannot provide.
 */
@RunWith(AndroidJUnit4::class)
class PlayerPopupsSmokeTest {
    @get:Rule
    val rule = createComposeRule()

    private fun showPopup(popup: PopupUi) {
        rule.setContent {
            SubLearnTheme(settings = AppSettings.DEFAULT) {
                PopupLayer(
                    popup = popup,
                    settings = AppSettings.DEFAULT,
                    onDismiss = {},
                    onToggleMark = {},
                    onDownloadModel = {},
                    onAskAi = {},
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun wordCardShowsTheSourceAndTheGloss() {
        showPopup(
            PopupUi(
                kind = PopupKind.WORD,
                sourceText = "reluctant",
                word = "reluctant",
                translation = "بی‌میل",
                contextTranslation = "unwilling and hesitant",
                timestampMs = 4_200L,
            ),
        )
        rule.onNodeWithText("reluctant").assertIsDisplayed()
        rule.onNodeWithText("بی‌میل").assertIsDisplayed()
    }

    @Test
    fun busyCardShowsTheLineButNoGlossYet() {
        showPopup(PopupUi(kind = PopupKind.LINE, sourceText = "He was none the wiser.", busy = true))
        rule.onNodeWithText("He was none the wiser.").assertIsDisplayed()
        rule.onAllNodes(androidx.compose.ui.test.hasText("بی‌میل")).assertNone()
    }

    @Test
    fun listRowsRenderForSearchAndSeek() {
        val rows = List(25) { index -> SubtitleListRow(index.toLong(), index, index * 1_500L, "line $index", index == 3) }
        rule.setContent {
            SubLearnTheme(settings = AppSettings.DEFAULT) {
                SubtitleListPanel(
                    list = SubtitleListUi(open = true, rows = rows, available = true, currentIndex = 3),
                    reduceMotion = true,
                    onToggle = {},
                    onRoleChange = {},
                    onQuery = {},
                    onSeekToRow = {},
                    onToggleSpoiler = {},
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("line 7").assertIsDisplayed()
    }
}
