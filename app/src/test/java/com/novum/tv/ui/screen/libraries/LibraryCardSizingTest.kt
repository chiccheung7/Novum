package com.novum.tv.ui.screen.libraries

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.novum.tv.model.MediaLibrary
import com.novum.tv.ui.component.LANDSCAPE_ASPECT
import com.novum.tv.ui.component.NovumSkeletonCardGrid
import com.novum.tv.ui.theme.NovumTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h720dp-xhdpi")
class LibraryCardSizingTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun directoryUsesThreeFixed304DpColumnsAndLoadingCardsMatch() {
        val loading = mutableStateOf(false)
        mount(1280.dp, loading)
        composeRule.onNodeWithContentDescription("测试库1").assertIsDisplayed().assertWidthIsEqualTo(304.dp)
        val first = composeRule.onNodeWithContentDescription("测试库1").fetchSemanticsNode().boundsInRoot
        val third = composeRule.onNodeWithContentDescription("测试库3").fetchSemanticsNode().boundsInRoot
        val fourth = composeRule.onNodeWithContentDescription("测试库4").fetchSemanticsNode().boundsInRoot
        assertEquals("前三张库卡同一行", first.top, third.top, 1f)
        assertTrue("第四张卡应换行而非把四列挤回271dp", fourth.top > first.top)

        composeRule.runOnIdle { loading.value = true }
        composeRule.onNodeWithTag("media-skeleton-card-0").assertIsDisplayed().assertWidthIsEqualTo(304.dp)
        val skeletonFirst = composeRule.onNodeWithTag("media-skeleton-card-0").fetchSemanticsNode().boundsInRoot
        val skeletonFourth = composeRule.onNodeWithTag("media-skeleton-card-3").fetchSemanticsNode().boundsInRoot
        assertEquals("加载与完成态首卡对齐", first.left, skeletonFirst.left, 1f)
        assertTrue("骨架按相同三列规格换行", skeletonFourth.top > skeletonFirst.top)
    }

    @Test
    fun narrowDirectoryAndItsSkeletonClampCardsToTheAvailableWidth() {
        val loading = mutableStateOf(false)
        mount(336.dp, loading)
        composeRule.onNodeWithContentDescription("测试库1").assertIsDisplayed().assertWidthIsEqualTo(200.dp)
        val first = composeRule.onNodeWithContentDescription("测试库1").fetchSemanticsNode().boundsInRoot
        val host = composeRule.onNodeWithTag("library-sizing-host").fetchSemanticsNode().boundsInRoot
        assertTrue(first.left >= host.left && first.right <= host.right)

        composeRule.runOnIdle { loading.value = true }
        composeRule.onNodeWithTag("media-skeleton-card-0").assertIsDisplayed().assertWidthIsEqualTo(200.dp)
        val skeleton = composeRule.onNodeWithTag("media-skeleton-card-0").fetchSemanticsNode().boundsInRoot
        assertEquals(first.left, skeleton.left, 1f)
        assertTrue(skeleton.right <= host.right)
    }

    private fun mount(width: Dp, loading: androidx.compose.runtime.MutableState<Boolean>) {
        val libraries = (1..6).map { MediaLibrary("library-$it", "测试库$it", "movies", 10, null) }
        composeRule.setContent {
            NovumTheme {
                Box(Modifier.requiredSize(width, 720.dp).testTag("library-sizing-host")) {
                    if (loading.value) {
                        NovumSkeletonCardGrid(aspectRatio = LANDSCAPE_ASPECT)
                    } else {
                        LibraryGrid(libraries, remember { FocusRequester() }, { _, _ -> }, Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}
