package com.novum.tv.ui.screen.player

import com.novum.tv.model.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 自动连播切换点测试（口径：默认开 / 10 秒 / 季末回详情）。
 *
 * 切换点决定「剪掉多少」：片尾章起点 + 10 秒（剪大段片尾），无片尾章兜底
 * 结尾前 5 秒（几乎不剪）——钉死口径防后续调整把内容剪多了。
 */
class PlayerAutoNextTest {

    private fun chapter(name: String?, startMs: Long) = Chapter(
        name = name,
        startMs = startMs,
    )

    @Test
    fun `片尾章切换点为章起点加十秒`() {
        // 末章「片尾」从 18 分钟起，总长 20 分钟 → 切换点 = 18:10 + 10s
        val chapters = listOf(
            chapter("正片", 0),
            chapter("片尾", 1_080_000),
        )
        assertEquals(1_090_000L, computeAutoNextSwitchAt(chapters, 1_200_000L))
    }

    @Test
    fun `片尾章贴近结尾时收在结尾处`() {
        // 片尾章起点 + 10s 超过总长 → 收在 duration（自然结束先到也不越界）
        val chapters = listOf(
            chapter("正片", 0),
            chapter("credits", 1_199_995),
        )
        assertEquals(1_200_000L, computeAutoNextSwitchAt(chapters, 1_200_000L))
    }

    @Test
    fun `无片尾章兜底为结尾前五秒`() {
        // 末章名不含片尾 → 兜底切换点 = duration - 5s（倒计时结尾前 15s 起跑）
        val chapters = listOf(
            chapter("正片", 0),
            chapter("第二段", 600_000),
        )
        assertEquals(1_195_000L, computeAutoNextSwitchAt(chapters, 1_200_000L))
    }

    @Test
    fun `无章节同样兜底`() {
        assertEquals(1_195_000L, computeAutoNextSwitchAt(emptyList(), 1_200_000L))
    }

    @Test
    fun `时长未知不启用`() {
        val chapters = listOf(chapter("片尾", 0))
        assertNull(computeAutoNextSwitchAt(chapters, 0L))
    }
}
