package com.novum.tv.ui.component

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NovumFastNav] 速率检测：
 * 某些遥控器长按到达应用时 repeatCount 为 0，快扫判定依赖键流间隔。
 * 相邻导航键 ≤300ms 判快扫，窗口 450ms。
 *
 * 对象级单例状态无法隔离，用单一时间轴覆盖全部场景。
 */
class FastNavGateTest {

    @Test
    fun rateDetectionClassifiesKeyStreams() {
        // t=0：首键，间隔无穷大（首键前无记录），不判快扫
        assertFalse(NovumFastNav.onNavKeyDown(nowMs = 1_000L))
        assertFalse(NovumFastNav.isActive(nowMs = 1_050L))

        // t=1240：第二键间隔 240ms ≤ 300ms → 快扫，窗口至 1240+450
        assertTrue(NovumFastNav.onNavKeyDown(nowMs = 1_240L))
        assertTrue(NovumFastNav.isActive(nowMs = 1_600L))
        // 窗口已过期（1691 > 1690）
        assertFalse(NovumFastNav.isActive(nowMs = 1_691L))

        // 慢速单击（800ms/键）：连续三键都不判快扫
        assertFalse(NovumFastNav.onNavKeyDown(nowMs = 2_500L))
        assertFalse(NovumFastNav.onNavKeyDown(nowMs = 3_300L))
        assertFalse(NovumFastNav.onNavKeyDown(nowMs = 4_100L))
        assertFalse(NovumFastNav.isActive(nowMs = 4_200L))

        // 长按键流（100ms/键）：首键间隔大不判快扫，第二键起持续置闸；
        // poke 顺延不清退（maxOf 语义）
        assertFalse(NovumFastNav.onNavKeyDown(nowMs = 10_000L))
        assertTrue(NovumFastNav.onNavKeyDown(nowMs = 10_100L))
        NovumFastNav.poke(nowMs = 10_200L)
        assertTrue(NovumFastNav.onNavKeyDown(nowMs = 10_300L))
        assertTrue(NovumFastNav.isActive(nowMs = 10_700L))
        assertFalse(NovumFastNav.isActive(nowMs = 10_751L))

        // 窗口过期后的第一键不判快扫（无重复置闸），第二键恢复
        assertFalse(NovumFastNav.onNavKeyDown(nowMs = 30_000L))
        assertTrue(NovumFastNav.onNavKeyDown(nowMs = 30_080L))
    }
}
