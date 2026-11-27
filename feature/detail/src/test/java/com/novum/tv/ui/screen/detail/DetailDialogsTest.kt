package com.novum.tv.ui.screen.detail

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.novum.tv.model.Season
import com.novum.tv.ui.theme.NovumTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h720dp-xhdpi")
class DetailDialogsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun seasonBeyondFirstScreenIsVisibleAndFocusedOnOpen() {
        composeRule.setContent {
            NovumTheme {
                SeasonMenuDialog(
                    seasons = (1..20).map {
                        Season(id = "s$it", name = "Season $it", index = it, episodeCount = 10, unplayedCount = 1)
                    },
                    selectedSeasonId = "s20",
                    onSelect = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.runOnUiThread {
            ShadowDialog.getLatestDialog().window!!.decorView.dispatchWindowFocusChanged(true)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("第 20 季").assertIsDisplayed().assertIsFocused()
    }
}
