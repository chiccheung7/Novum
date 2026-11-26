package com.novum.tv.ui.component

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.novum.tv.ui.theme.NovumTheme
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h720dp-xhdpi")
class NovumMediaInfoBlockTest {
    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun blankLogoKeepsTheTextTitleVisible() {
        mountInfoBlock(" ")

        composeRule.onNodeWithText("测试作品").assertIsDisplayed()
    }

    @Test
    fun failedLogoFallsBackToTheTextTitle() {
        val missingImage = temporaryFolder.newFile("missing-logo.png")
        check(missingImage.delete())
        mountInfoBlock(missingImage.toURI().toString())
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("测试作品").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("测试作品").assertIsDisplayed()
    }

    private fun mountInfoBlock(logoUrl: String) {
        composeRule.setContent {
            NovumTheme {
                NovumMediaInfoBlock(
                    title = "测试作品",
                    logoUrl = logoUrl,
                    metaParts = emptyList(),
                    genreTags = emptyList(),
                    formatBadges = emptyList(),
                    overview = null,
                )
            }
        }
    }
}
