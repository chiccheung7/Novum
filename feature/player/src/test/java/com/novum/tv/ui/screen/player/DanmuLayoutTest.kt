package com.novum.tv.ui.screen.player

import com.novum.tv.model.DanmuComment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 弹幕布局引擎测试：密度采样确定性、车道分配、可见窗口、重放正确性。
 *
 * 布局是纯函数（无 Compose 依赖），单测直接驱动。
 */
class DanmuLayoutTest {

    private fun spec(
        laneCount: Int = 4,
        scrollDurationMs: Long = 8_000,
    ) = DanmuLayoutSpec(
        laneCount = laneCount,
        fontPx = 30f,
        laneHeightPx = 45f,
        topInsetPx = 8f,
        scrollDurationMs = scrollDurationMs,
        fixedDurationMs = 4_000,
        density = 1f,
    )

    private fun comment(timeMs: Long, mode: Int = 1, text: String = "弹幕") =
        DanmuComment(text = text, timeMs = timeMs, mode = mode, color = 0xFFFFFFFF)

    private fun measure(text: String) = text.length * 30f

    // ---- 密度采样 ----

    @Test
    fun densitySamplingIsDeterministic() {
        val sample = comment(1_000, text = "同一条")
        // 同一条弹幕多次判定结果一致；密度变化只影响去留阈值
        val first = danmuKeptByDensity(sample, 0.7f)
        repeat(10) {
            assertEquals(first, danmuKeptByDensity(sample, 0.7f))
        }
        assertTrue(danmuKeptByDensity(sample, 1f))
    }

    @Test
    fun densityOneKeepsAll() {
        val comments = (0 until 100).map { comment(it * 1_000L, text = "t$it") }
        assertTrue(comments.all { danmuKeptByDensity(it, 1f) })
    }

    // ---- 布局 ----

    @Test
    fun onlyCommentsInWindowAreVisible() {
        val comments = listOf(
            comment(0),
            comment(1_000),
            comment(20_000), // 未出生（timeMs > 渲染时间）→ 不可见
        )
        val items = danmuLayout(comments, positionMs = 2_000, spec = spec(), widthPx = 1_000f, measure = ::measure)
        // 0s 与 1s 两条都在滚动存活期内；20s 的还没到
        assertEquals(2, items.size)
    }

    @Test
    fun futureCommentsNotDrawn() {
        val comments = listOf(comment(5_000))
        val items = danmuLayout(comments, positionMs = 2_000, spec = spec(), widthPx = 1_000f, measure = ::measure)
        assertTrue(items.isEmpty())
    }

    @Test
    fun scrollItemMovesRightToLeft() {
        val comments = listOf(comment(0))
        val early = danmuLayout(comments, positionMs = 500, spec = spec(), widthPx = 1_000f, measure = ::measure)
        val late = danmuLayout(comments, positionMs = 5_000, spec = spec(), widthPx = 1_000f, measure = ::measure)
        // 500ms 时刚入场（右缘附近）；5000ms 时已移动到更左
        assertTrue(early.single().x > late.single().x)
        // 5000ms 时的 x ≈ width - (width+textW) * 5000/8000
        val expected = 1_000f - (1_000f + measure("弹幕")) * 5_000f / 8_000f
        assertEquals(expected, late.single().x, 0.5f)
    }

    @Test
    fun scrollItemGoneAfterLifetime() {
        val comments = listOf(comment(0))
        val items = danmuLayout(comments, positionMs = 8_100, spec = spec(), widthPx = 1_000f, measure = ::measure)
        assertTrue(items.isEmpty())
    }

    @Test
    fun simultaneousCommentsLandOnDifferentLanes() {
        // 同一时刻两条滚动弹幕必须分到不同车道（车道高 = 字高 1.5 倍）
        val comments = listOf(comment(0, text = "第一条"), comment(0, text = "第二条弹幕文字更长"))
        val items = danmuLayout(comments, positionMs = 100, spec = spec(laneCount = 4), widthPx = 1_000f, measure = ::measure)
        assertEquals(2, items.size)
        val lanes = items.map { it.y }.distinct()
        assertEquals(2, lanes.size)
    }

    /**
     * 回归：滚动弹幕要**铺开**到多条车道。
     *
     * 固定从 0 号车道起扫时，0 号车道一让出间隙，下一条就又被分到 0 号，
     * 实机上半屏只剩两三行在用（全屏也只占顶部一条带）。起扫位置按弹幕
     * 时间轮转后，10 条弹幕应落在 4 条以上的车道。
     */
    @Test
    fun scrollCommentsSpreadAcrossLanes() {
        val comments = (0 until 10).map { index ->
            comment(index * 1_000L, text = "第${'$'}{index}条")
        }
        val items = danmuLayout(
            comments,
            positionMs = 9_000,
            spec = spec(laneCount = 8),
            widthPx = 1_000f,
            measure = ::measure,
        )
        assertTrue(items.isNotEmpty())
        assertTrue(
            "滚动弹幕应分散到多条车道，实际只用了 " +
                items.map { it.y }.distinct().size + " 条",
            items.map { it.y }.distinct().size >= 4,
        )
    }

    @Test
    fun scrollLaneRespectsEnterGap() {
        // 第一条刚进入不久（尾部还没让出间隙），第二条同车道必须延后分配
        val first = comment(0, text = "a".repeat(20)) // 长文本，尾部长时间未进入
        val second = comment(500, text = "b")
        val items = danmuLayout(listOf(first, second), positionMs = 600, spec = spec(laneCount = 1), widthPx = 1_000f, measure = ::measure)
        // 单车道：第二条因碰撞被丢弃
        assertEquals(1, items.size)
        assertEquals(first.text, items.single().text)
    }

    @Test
    fun fixedCommentCenteredAndStacksFromBottom() {
        val bottom1 = comment(0, mode = 4, text = "底部一")
        val bottom2 = comment(0, mode = 4, text = "底部二")
        val items = danmuLayout(listOf(bottom1, bottom2), positionMs = 100, spec = spec(laneCount = 4), widthPx = 1_000f, measure = ::measure)
        assertEquals(2, items.size)
        // 居中：x + textW/2 ≈ width/2
        items.forEach { item ->
            assertEquals(500f, item.x + measure(item.text) / 2f, 0.5f)
        }
        // 底部车道从区域底部向上排：第二条 y 更小
        assertTrue(items[0].y > items[1].y)
    }

    @Test
    fun topCommentStacksFromTop() {
        val top1 = comment(0, mode = 5, text = "顶部一")
        val top2 = comment(0, mode = 5, text = "顶部二")
        val items = danmuLayout(listOf(top1, top2), positionMs = 100, spec = spec(laneCount = 4), widthPx = 1_000f, measure = ::measure)
        assertEquals(2, items.size)
        assertTrue(items[0].y < items[1].y)
    }

    @Test
    fun fixedCommentGoneAfterStay() {
        val items = danmuLayout(listOf(comment(0, mode = 4)), positionMs = 4_100, spec = spec(), widthPx = 1_000f, measure = ::measure)
        assertTrue(items.isEmpty())
    }

    @Test
    fun seekBackwardReplaysCorrectly() {
        // seek 回 1s：本来 8s 处的弹幕重新进入画面（重放自愈）
        val comments = listOf(comment(8_000))
        assertTrue(danmuLayout(comments, positionMs = 8_200, spec = spec(), widthPx = 1_000f, measure = ::measure).isNotEmpty())
        assertTrue(danmuLayout(comments, positionMs = 1_000, spec = spec(), widthPx = 1_000f, measure = ::measure).isEmpty())
    }

    @Test
    fun maxVisibleCapsDrawCost() {
        // 大量同车道弹幕：总数封顶，不至于一帧画几百条
        val comments = (0 until 200).map { comment(it * 10L, text = "n$it") }
        val items = danmuLayout(comments, positionMs = 2_000, spec = spec(laneCount = 2), widthPx = 1_000f, measure = ::measure)
        assertTrue(items.size <= DANMU_MAX_VISIBLE)
    }

    @Test
    fun unknownModeIgnored() {
        val items = danmuLayout(listOf(comment(0, mode = 9)), positionMs = 100, spec = spec(), widthPx = 1_000f, measure = ::measure)
        assertTrue(items.isEmpty())
    }

    /**
     * 回归：车道分配不能随播放位置漂移。
     *
     * 旧实现用「当前播放位置」判断车道是否空闲，并且重放窗口只从可见起点开始：
     * 播放位置一推进，窗口里最早的那条被裁掉，后面所有弹幕会被重新分到别的
     * 车道——画面上就是弹幕一行一行地上下跳动。
     */
    @Test
    fun laneAssignmentDoesNotDriftWithPlaybackPosition() {
        val comments = listOf(
            comment(11_500, text = "老弹幕"),
            comment(19_000, text = "新弹幕"),
        )
        val early = danmuLayout(
            comments,
            positionMs = 19_000,
            spec = spec(laneCount = 2),
            widthPx = 1_000f,
            measure = ::measure,
        )
        // 0.6 秒后「老弹幕」已过期（不再绘制），但它的车道占用必须仍被复现
        val later = danmuLayout(
            comments,
            positionMs = 19_600,
            spec = spec(laneCount = 2),
            widthPx = 1_000f,
            measure = ::measure,
        )
        assertEquals(2, early.size)
        assertEquals(1, later.size)
        assertEquals(early.last().y, later.single().y, 0.001f)
    }

    @Test
    fun layoutIsReplayable() {
        // 相同输入重复调用结果一致（逐帧重放的基础）
        val comments = listOf(comment(0), comment(500, mode = 4), comment(800, mode = 5))
        val one = danmuLayout(comments, positionMs = 1_000, spec = spec(), widthPx = 1_000f, measure = ::measure)
        val two = danmuLayout(comments, positionMs = 1_000, spec = spec(), widthPx = 1_000f, measure = ::measure)
        assertEquals(one, two)
        assertFalse(one.isEmpty())
    }
}
