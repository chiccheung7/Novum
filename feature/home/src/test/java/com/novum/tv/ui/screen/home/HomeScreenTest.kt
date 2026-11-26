package com.novum.tv.ui.screen.home

import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.performSemanticsAction
import com.novum.tv.model.HOME_ROW_RESUME
import com.novum.tv.model.HomeEntry
import com.novum.tv.model.HomeFeed
import com.novum.tv.model.HomeLatestRow
import com.novum.tv.model.HomeRow
import com.novum.tv.ui.component.NovumNavTab
import com.novum.tv.ui.theme.NovumTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 首页 Compose UI 测试（Robolectric，无需真机/模拟器）。
 *
 * 直接渲染 [HomeContent] 注入假数据，绕开 ViewModel 与 Hilt。覆盖：顶栏
 * 图标导航接线、顶栏 DOWN 下移焦点行为、Hero 信息块（双 CTA：播放 CTA
 * 电影起播/剧集进详情、详情 CTA）、继续观看行、「查看全部」接线。
 *
 * 顶栏是列表 item 0，随页面内容滚动。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1920dp-h1080dp-xhdpi")
class HomeScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** 任意区块为空的首页数据：只剩顶栏可见。 */
    private val emptyFeed = HomeFeed(
        hero = emptyList(),
        resume = null,
        nextUp = null,
        latest = emptyList(),
    )

    /** 顶栏 + 内容的标准测试挂载（首页真实结构：顶栏是列表 item 0）。 */
    private fun mountWithTopBar(feed: HomeFeed, onNavigateTab: (NovumNavTab) -> Unit = {}) {
        composeRule.setContent {
            NovumTheme {
                HomeContent(
                    feed = feed,
                    onOpenDetail = {},
                    onPlay = {},
                    onOpenLibrary = { _, _ -> },
                    onNavigateTab = onNavigateTab,
                )
            }
        }
    }

    /** 只挂内容的测试挂载（Hero 用例聚焦沉浸列语义，不掺顶栏文案）。 */
    private fun mountContent(
        feed: HomeFeed,
        onOpenDetail: (String) -> Unit = {},
        onPlay: (String) -> Unit = {},
        onOpenLibrary: (String, String) -> Unit = { _, _ -> },
        heroRotateIdleMs: Long = 6_000L,
    ) {
        composeRule.setContent {
            NovumTheme {
                HomeContent(
                    feed = feed,
                    onOpenDetail = onOpenDetail,
                    onPlay = onPlay,
                    onOpenLibrary = onOpenLibrary,
                    heroRotateIdleMs = heroRotateIdleMs,
                )
            }
        }
    }

    @Test
    fun showsBrandAndTopIcons() {
        mountWithTopBar(emptyFeed)

        // 左上角位置由 Hero ClearLogo 使用。
        // 首页右上保留导航入口；各一级内页不再重复显示这组图标。
        composeRule.onNodeWithText("Novum").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("首页").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("收藏").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("搜索").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("设置").assertIsDisplayed()
    }

    @Test
    fun navigateTabWhenIconClicked() {
        val received = mutableListOf<NovumNavTab>()
        mountWithTopBar(emptyFeed, onNavigateTab = { received.add(it) })

        // 语义点击派发：验证「图标 → 回调」接线正确（cd 挂在图标钮容器上）
        composeRule.onNodeWithContentDescription("搜索").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithContentDescription("收藏").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()

        assertEquals(
            "图标点击应按序触发导航回调",
            listOf(NovumNavTab.Search, NovumNavTab.Favorites),
            received,
        )
    }

    @Test
    fun navRowDownKeyMovesFocusIntoContent() {
        mountWithTopBar(
            emptyFeed.copy(
                hero = listOf(HomeEntry(id = "m1", title = "顺流逆流", type = "Movie")),
            ),
        )

        // 焦点先落到顶栏「收藏」图标（流式列表 item 0 内的首个可聚焦项）
        composeRule.onNodeWithContentDescription("收藏").performSemanticsAction(SemanticsActions.RequestFocus)
        composeRule.onNodeWithContentDescription("收藏").assertIsFocused()

        // 顶栏按 DOWN 必须能把焦点移入下方内容区。
        // 浮层时代几何焦点搜索失控（「不能下移」）；流式布局后这是同一条列表的
        // 普通焦点搜索，Hero 卡或双 CTA 任一获焦即算通过
        composeRule.onNodeWithContentDescription("收藏").performKeyPress(
            ComposeKeyEvent(
                NativeKeyEvent(0L, 0L, NativeKeyEvent.ACTION_DOWN, NativeKeyEvent.KEYCODE_DPAD_DOWN, 0),
            ),
        )
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("收藏").assertIsNotFocused()
        val contentTexts = setOf("播放", "详情", "顺流逆流")
        val focusedTexts = composeRule.onAllNodes(isFocused())
            .fetchSemanticsNodes()
            .flatMap { node -> node.config.getOrElse(SemanticsProperties.Text) { emptyList() } }
            .map { it.text }
        assertTrue("顶栏 DOWN 后焦点应落在内容区（Hero 卡或 CTA）", focusedTexts.any { it in contentTexts })
    }

    @Test
    fun heroZoneRendersInfoBlockAndCards() {
        mountContent(
            emptyFeed.copy(
                hero = listOf(HomeEntry(id = "h1", title = "顺流逆流", type = "Movie", backdropUrl = null)),
            ),
        )

        // 信息块：无 ClearLogo 时回退文本标题；双 CTA 同时在场
        // 页面不显示额外眉题。
        composeRule.onNodeWithText("播放").assertIsDisplayed()
        composeRule.onNodeWithText("详情").assertIsDisplayed()
        // 片名只在信息块中显示一次。
        // （原 logo 位由放大标题补上）
        composeRule.onAllNodesWithText("顺流逆流").assertCountEquals(1)
    }

    @Test
    fun heroPlayCtaStartsPlayerForMovie() {
        var playedId: String? = null
        mountContent(
            emptyFeed.copy(
                hero = listOf(HomeEntry(id = "m1", title = "顺流逆流", type = "Movie")),
            ),
            onPlay = { playedId = it },
        )

        // Filled 播放 CTA：电影直接起播（无进度 → 从头播）
        composeRule.onNodeWithText("播放").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()

        assertEquals("电影 Hero 的播放 CTA 应直接起播", "m1", playedId)
    }

    @Test
    fun heroPlayCtaOpensDetailForSeries() {
        var detailId: String? = null
        mountContent(
            emptyFeed.copy(
                hero = listOf(
                    HomeEntry(id = "ep-9", title = "庆余年", type = "Series", seriesId = "series-1"),
                ),
            ),
            onOpenDetail = { detailId = it },
        )

        // 剧集没有「从哪播」的知识：播放 CTA 落详情页选集，由详情页起播
        composeRule.onNodeWithText("播放").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()

        assertEquals("剧集 Hero 的播放 CTA 应进详情页选源", "series-1", detailId)
    }

    @Test
    fun heroDetailCtaOpensDetail() {
        var detailId: String? = null
        mountContent(
            emptyFeed.copy(
                hero = listOf(HomeEntry(id = "m1", title = "顺流逆流", type = "Movie")),
            ),
            onOpenDetail = { detailId = it },
        )

        composeRule.onNodeWithText("详情").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()

        assertEquals("Hero 详情 CTA 应进详情页", "m1", detailId)
    }

    @Test
    fun heroAutoplayRunsAfterIdleWhenFocusIsOutsideCtas() {
        composeRule.mainClock.autoAdvance = false
        mountContent(
            emptyFeed.copy(
                hero = listOf(
                    HomeEntry(id = "h1", title = "海报一", type = "Movie"),
                    HomeEntry(id = "h2", title = "海报二", type = "Movie"),
                ),
            ),
            heroRotateIdleMs = 1_000L,
        )
        // 先完成入场动画；顶栏获焦不会把 Hero 滚出视口。
        composeRule.mainClock.advanceTimeBy(600L)
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("收藏")
            .performSemanticsAction(SemanticsActions.RequestFocus)
        composeRule.onNodeWithContentDescription("收藏").assertIsFocused()
        composeRule.onNodeWithText("播放").assertIsNotFocused()
        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("海报二").assertIsDisplayed()

        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("海报一").assertIsDisplayed()
    }

    @Test
    fun heroAutoplayRestartsIdleCountdownAfterRemoteInput() {
        composeRule.mainClock.autoAdvance = false
        mountContent(
            emptyFeed.copy(
                hero = listOf(
                    HomeEntry(id = "h1", title = "海报一", type = "Movie"),
                    HomeEntry(id = "h2", title = "海报二", type = "Movie"),
                ),
            ),
            heroRotateIdleMs = 1_000L,
        )
        composeRule.mainClock.advanceTimeBy(600L)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("播放").performKeyPress(
            ComposeKeyEvent(
                NativeKeyEvent(0L, 0L, NativeKeyEvent.ACTION_DOWN, NativeKeyEvent.KEYCODE_DPAD_RIGHT, 0),
            ),
        )
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(600L)
        composeRule.waitForIdle()
        // 首次计时已满一秒，但按键之后尚未满一秒，不能提前换图。
        composeRule.onNodeWithText("海报一").assertIsDisplayed()

        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("海报二").assertIsDisplayed()
    }

    @Test
    fun heroAutoplayPausesOffPageAndRestartsCountdownOnReturn() {
        composeRule.mainClock.autoAdvance = false
        val pageVisible = mutableStateOf(true)
        val feed = emptyFeed.copy(
            hero = listOf(
                HomeEntry(id = "h1", title = "海报一", type = "Movie"),
                HomeEntry(id = "h2", title = "海报二", type = "Movie"),
            ),
        )
        composeRule.setContent {
            NovumTheme {
                HomeContent(
                    feed = feed,
                    onOpenDetail = {},
                    onPlay = {},
                    onOpenLibrary = { _, _ -> },
                    heroRotateIdleMs = 1_000L,
                    isPageVisible = pageVisible.value,
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(600L)
        composeRule.waitForIdle()
        composeRule.runOnIdle { pageVisible.value = false }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(3_000L)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("海报一").assertIsDisplayed()

        composeRule.runOnIdle { pageVisible.value = true }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(600L)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("海报一").assertIsDisplayed()
        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("海报二").assertIsDisplayed()
    }

    @Test
    fun rendersResumeAndLatestRows() {
        mountContent(
            emptyFeed.copy(
                resume = HomeRow(HOME_ROW_RESUME, listOf(HomeEntry(id = "r1", title = "顺流逆流"))),
                latest = listOf(
                    HomeLatestRow(
                        libraryId = "lib-1",
                        libraryName = "华语电影",
                        items = listOf(HomeEntry(id = "l1", title = "爱情来了")),
                    ),
                ),
            ),
        )

        composeRule.onNodeWithText("继续观看").assertIsDisplayed()
        composeRule.onNodeWithText("顺流逆流").assertIsDisplayed()
        composeRule.onNodeWithText("华语电影").assertIsDisplayed()
        // 分库行第二行 = 「标题 · 发行年份」，标题文本会在卡片标题与副标题
        // 各出现一次——取首个断言即可
        composeRule.onAllNodesWithText("爱情来了").onFirst().assertIsDisplayed()
        composeRule.onNodeWithText("查看全部").assertIsDisplayed()
    }

    @Test
    fun marksNextUpItemsInTheCombinedResumeRow() {
        mountContent(
            emptyFeed.copy(
                resume = HomeRow(
                    HOME_ROW_RESUME,
                    listOf(HomeEntry(id = "next-1", title = "庆余年", isNextUp = true)),
                ),
            ),
        )

        composeRule.onNodeWithText("下一集").assertIsDisplayed()
    }

    @Test
    fun opensDetailForResumeEntryWhenClicked() {
        var detailId: String? = null
        mountContent(
            emptyFeed.copy(
                resume = HomeRow(
                    HOME_ROW_RESUME,
                    listOf(HomeEntry(id = "ep-1", title = "红高粱", seriesId = "series-1")),
                ),
            ),
            onOpenDetail = { detailId = it },
        )

        // 官方标准卡：点击语义锚在图区 Card 节点（contentDescription = 片名）
        composeRule.onNodeWithContentDescription("红高粱").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()

        assertEquals("继续观看的分集应跳所属剧集详情", "series-1", detailId)
    }

    @Test
    fun opensDetailForLatestEntryWhenClicked() {
        var detailId: String? = null
        mountContent(
            emptyFeed.copy(
                latest = listOf(
                    HomeLatestRow(
                        libraryId = "lib-1",
                        libraryName = "华语电影",
                        items = listOf(HomeEntry(id = "l1", title = "爱情来了")),
                    ),
                ),
            ),
            onOpenDetail = { detailId = it },
        )

        // 官方标准卡：点击语义锚在图区 Card 节点（contentDescription = 片名）
        composeRule.onNodeWithContentDescription("爱情来了")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()

        assertEquals("分类库行应打开详情页", "l1", detailId)
    }

    @Test
    fun latestViewAllOpensItsLibraryAndDpadLinksActionWithFirstCard() {
        val opened = mutableListOf<Pair<String, String>>()
        mountContent(
            emptyFeed.copy(
                latest = listOf(
                    HomeLatestRow(
                        libraryId = "lib-1",
                        libraryName = "华语电影",
                        items = listOf(HomeEntry(id = "l1", title = "爱情来了")),
                    ),
                ),
            ),
            onOpenLibrary = { id, name -> opened += id to name },
        )

        val viewAll = composeRule.onNodeWithText("查看全部")
        viewAll.assertIsDisplayed()
        viewAll.performSemanticsAction(SemanticsActions.RequestFocus)
        viewAll.assertIsFocused()
        viewAll.performKeyPress(
            ComposeKeyEvent(
                NativeKeyEvent(0L, 0L, NativeKeyEvent.ACTION_DOWN, NativeKeyEvent.KEYCODE_DPAD_DOWN, 0),
            ),
        )
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("爱情来了").assertIsFocused()
        composeRule.onNodeWithContentDescription("爱情来了").performKeyPress(
            ComposeKeyEvent(
                NativeKeyEvent(0L, 0L, NativeKeyEvent.ACTION_DOWN, NativeKeyEvent.KEYCODE_DPAD_UP, 0),
            ),
        )
        composeRule.waitForIdle()
        viewAll.assertIsFocused()

        viewAll.performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        assertEquals(listOf("lib-1" to "华语电影"), opened)
    }
}
