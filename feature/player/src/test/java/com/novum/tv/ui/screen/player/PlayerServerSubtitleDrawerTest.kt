package com.novum.tv.ui.screen.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.novum.tv.model.PlayMethod
import com.novum.tv.model.PlaybackPlan
import com.novum.tv.model.TrackOption as ServerTrack
import com.novum.tv.ui.theme.NovumTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h720dp-xhdpi")
class PlayerServerSubtitleDrawerTest {
    @get:Rule val composeRule = createComposeRule()

    private val currentPlan = mutableStateOf(burnedPlan())
    private val switching = mutableStateOf(false)
    private val error = mutableStateOf<String?>(null)
    private val serverSelections = mutableListOf<Int>()
    private val localSelections = mutableListOf<TrackOption>()

    @Test
    fun selectingBurnedSubtitleDispatchesTheServerIndexAndWaitsForTheConfirmedPlan() {
        showDrawer()
        composeRule.onNodeWithText("简体中文 ASS").assertIsNotEnabled()
        click("繁體中文 ASS")
        composeRule.runOnIdle { assertEquals(listOf(37), serverSelections) }
        // A requested track is not shown as selected before a replacement plan succeeds.
        composeRule.onNodeWithText("简体中文 ASS").assertIsNotEnabled()
        composeRule.onNodeWithText("繁體中文 ASS").assertIsEnabled()
        composeRule.runOnIdle { currentPlan.value = burnedPlan(37) }
        composeRule.onNodeWithText("繁體中文 ASS").assertIsNotEnabled()
        composeRule.onNodeWithText("简体中文 ASS").assertIsEnabled()
        composeRule.runOnIdle { assertEquals(emptyList<TrackOption>(), localSelections) }
    }

    @Test
    fun closingBurnedSubtitlesUsesTheServerAndKeepsTracksAvailableForReopening() {
        showDrawer()
        click("字幕开关")
        composeRule.runOnIdle {
            assertEquals(listOf(-1), serverSelections)
            currentPlan.value = burnedPlan().copy(
                burnedInSubtitleLabel = null,
                burnedInSubtitleIndex = null,
                serverSubtitleStreamIndex = -1,
            )
        }
        composeRule.onNodeWithText("服务器烧录字幕").assertDoesNotExist()
        composeRule.onNodeWithText("简体中文 ASS").assertIsEnabled()
        composeRule.onNodeWithText("繁體中文 ASS").assertIsEnabled()
        click("繁體中文 ASS")
        composeRule.runOnIdle {
            assertEquals(listOf(-1, 37), serverSelections)
            assertEquals(emptyList<TrackOption>(), localSelections)
        }
        composeRule.onNodeWithText("字幕延迟").assertDoesNotExist()
        composeRule.onNodeWithText("字幕字号").assertDoesNotExist()
    }

    @Test
    fun switchingDisablesBothTracksAndTheToggleWithoutLosingTheActualSelection() {
        showDrawer()
        composeRule.runOnIdle { switching.value = true }
        composeRule.onNodeWithText("正在切换字幕…").assertExists()
        composeRule.onNodeWithText("字幕开关").assertIsNotEnabled()
        composeRule.onNodeWithText("简体中文 ASS").assertIsNotEnabled()
        composeRule.onNodeWithText("繁體中文 ASS").assertIsNotEnabled()
        // Even a queued semantics action cannot dispatch another server request.
        click("繁體中文 ASS")
        composeRule.runOnIdle { assertEquals(emptyList<Int>(), serverSelections) }
        composeRule.runOnIdle { switching.value = false }
        composeRule.onNodeWithText("简体中文 ASS").assertIsNotEnabled()
        composeRule.onNodeWithText("繁體中文 ASS").assertIsEnabled()
    }

    @Test
    fun aFailedSwitchExplainsTheFailureAndAllowsRetryWhileRetainingTheOldTrack() {
        showDrawer()
        click("繁體中文 ASS")
        composeRule.runOnIdle {
            switching.value = true
            error.value = null
        }
        composeRule.runOnIdle {
            switching.value = false
            error.value = "网络连接失败，请检查网络后重试"
        }
        composeRule.onNodeWithText("网络连接失败，请检查网络后重试").assertExists()
        composeRule.onNodeWithText("简体中文 ASS").assertIsNotEnabled()
        click("繁體中文 ASS")
        composeRule.runOnIdle { assertEquals(listOf(37, 37), serverSelections) }
    }

    @Test
    fun localSoftSubtitlesKeepUsingThePlayerDespiteHavingServerTrackMetadata() {
        currentPlan.value = burnedPlan().copy(
            method = PlayMethod.DirectPlay,
            burnedInSubtitleLabel = null,
            burnedInSubtitleIndex = null,
            serverSubtitleSelection = false,
            serverSubtitleStreamIndex = null,
        )
        showDrawer()
        composeRule.onNodeWithText("服务器烧录字幕").assertDoesNotExist()
        composeRule.onNodeWithText("繁體中文 ASS").assertDoesNotExist()
        click("Local traditional")
        composeRule.runOnIdle {
            assertEquals(listOf("1:1"), localSelections.map { it.id })
            assertEquals(emptyList<Int>(), serverSelections)
        }
        composeRule.onNodeWithText("字幕延迟").assertExists()
    }

    @Test
    fun anOlderBurnedPlanWithoutTheServerModeFlagStillExposesServerSwitching() {
        currentPlan.value = burnedPlan().copy(serverSubtitleSelection = false, serverSubtitleStreamIndex = null)
        showDrawer()
        composeRule.onNodeWithText("简体中文 ASS").assertIsNotEnabled()
        click("繁體中文 ASS")
        composeRule.runOnIdle { assertEquals(listOf(37), serverSelections) }
    }

    @Test
    fun missingMediaSourceIdExplainsWhyServerSubtitleSwitchingIsUnavailable() {
        currentPlan.value = burnedPlan().copy(mediaSourceId = null)
        showDrawer()
        composeRule.onNodeWithText("此片源不支持字幕操作").assertExists()
        composeRule.onNodeWithText("字幕开关").assertIsNotEnabled()
        composeRule.onNodeWithText("繁體中文 ASS").assertIsNotEnabled()
        click("繁體中文 ASS")
        composeRule.runOnIdle { assertEquals(emptyList<Int>(), serverSelections) }
    }

    private fun showDrawer() {
        composeRule.setContent {
            NovumTheme {
                Box(Modifier.fillMaxSize()) {
                    PlayerSettingsDrawer(
                        visible = true,
                        initialTab = DrawerTab.Subtitle,
                        modifier = Modifier.align(Alignment.CenterEnd),
                        onTabsFocusChanged = {},
                        audioTracks = emptyList(),
                        textTracks = listOf(
                            TrackOption(TEXT_OFF_ID, "关闭字幕", false),
                            TrackOption("1:0", "Local simplified", true),
                            TrackOption("1:1", "Local traditional", false),
                        ),
                        passthrough = false,
                        plan = currentPlan.value,
                        subtitle = "",
                        audioLabel = "",
                        subtitleLabel = "",
                        chapters = emptyList(),
                        onSelectAudio = {},
                        onSelectText = { localSelections += it },
                        onTogglePassthrough = {},
                        onSelectChapter = {},
                        onSelectServerSubtitle = { serverSelections += it },
                        subtitleSwitching = switching.value,
                        subtitleSwitchError = error.value,
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun click(label: String) {
        composeRule.onNodeWithText(label).performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
    }

    private fun burnedPlan(index: Int = 12) = PlaybackPlan(
        url = "https://example.test/transcode.m3u8",
        method = PlayMethod.Transcode,
        audioTracks = emptyList(),
        subtitleTracks = listOf(
            ServerTrack(12, "简体中文 ASS", "zh-Hans"),
            ServerTrack(37, "繁體中文 ASS", "zh-Hant"),
        ),
        subtitles = emptyList(),
        durationMs = 90_000L,
        startPositionMs = 12_345L,
        mediaSourceId = "source-1",
        burnedInSubtitleLabel = if (index == 12) "简体中文 ASS" else "繁體中文 ASS",
        burnedInSubtitleIndex = index,
        serverSubtitleSelection = true,
        serverSubtitleStreamIndex = index,
    )
}
