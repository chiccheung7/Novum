package com.novum.tv.ui.screen.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 显示模式决策测试。
 *
 * 决策决定 4K 片源能不能原生输出：挑错模式（虚高面积/刷新率偏离）轻则浪费
 * 面板带宽，重则把正在播的 4K 打回 1080p（偏好泄漏回落分支）——这里把保守
 * 策略与换集泄漏场景钉死。
 */
class DisplayModeSwitcherTest {

    private fun mode(id: Int, width: Int, height: Int, refreshRate: Float) =
        ModeCandidate(modeId = id, width = width, height = height, refreshRate = refreshRate)

    /** 4K 面板典型模式表：1080p 多帧率 + 2160p 多帧率。 */
    private val tvModes = listOf(
        mode(1, 1920, 1080, 60f),
        mode(2, 1920, 1080, 50f),
        mode(3, 3840, 2160, 60f),
        mode(4, 3840, 2160, 50f),
    )

    @Test
    fun `当前输出已容纳片源时不切换`() {
        // 1080p 输出 + 1080p 片源：零成本，不动窗口
        assertNull(matchDisplayMode(mode(1, 1920, 1080, 60f), 1920, 1080, 60f, tvModes))
        // 4K 输出 + 720p 片源：输出绰绰有余，也不动
        assertNull(matchDisplayMode(mode(3, 3840, 2160, 60f), 1280, 720, 60f, tvModes))
    }

    @Test
    fun `四K片源切换到能容纳的最小模式`() {
        val target = matchDisplayMode(mode(1, 1920, 1080, 60f), 3840, 2160, 60f, tvModes)
        assertEquals(3, target?.modeId)
    }

    @Test
    fun `同面积候选取刷新率最近者`() {
        // 25fps 片源在 2160p 双帧率候选里应落 50Hz 而不是 60Hz
        val target = matchDisplayMode(mode(1, 1920, 1080, 60f), 3840, 2160, 25f, tvModes)
        assertEquals(4, target?.modeId)
    }

    @Test
    fun `片源帧率未知时锚定当前刷新率`() {
        val target = matchDisplayMode(mode(1, 1920, 1080, 50f), 3840, 2160, sourceFps = null, candidates = tvModes)
        assertEquals(4, target?.modeId)
    }

    @Test
    fun `没有能容纳片源的模式时不切换`() {
        val tinyPanel = listOf(mode(1, 1920, 1080, 60f))
        assertNull(matchDisplayMode(mode(1, 1920, 1080, 60f), 3840, 2160, 60f, tinyPanel))
    }

    @Test
    fun `只有最新一代会话才允许还原`() {
        assertTrue(isLatestSession(sessionGeneration = 2, latestGeneration = 2))
        assertFalse(
            "旧页离场晚于新页起播：代际落后必须放弃还原，不能打回新页刚切好的模式",
            isLatestSession(sessionGeneration = 1, latestGeneration = 2),
        )
    }

    @Test
    fun `窗口挂着默认偏好时不回落`() {
        // 偏好 == 原始默认（从未切换过）：无泄漏可言
        assertFalse(
            shouldRestorePreference(
                currentPreference = 0,
                originalPreference = 0,
                defaultMode = mode(0, 1920, 1080, 60f),
                sourceWidth = 1920,
                sourceHeight = 1080,
            ),
        )
    }

    @Test
    fun `回落默认后不够容纳片源时保留泄漏偏好`() {
        // 上一集 4K 残留偏好 + 本集仍是 4K 片源：默认 1080p 装不下，必须保留
        assertFalse(
            shouldRestorePreference(
                currentPreference = 3,
                originalPreference = 0,
                defaultMode = mode(0, 1920, 1080, 60f),
                sourceWidth = 3840,
                sourceHeight = 2160,
            ),
        )
    }

    @Test
    fun `残留偏好泄漏到低分片源时回落`() {
        // 上一集 4K 残留偏好 + 本集 1080p 片源：默认 1080p 仍够 → 回落还原
        assertTrue(
            shouldRestorePreference(
                currentPreference = 3,
                originalPreference = 0,
                defaultMode = mode(0, 1920, 1080, 60f),
                sourceWidth = 1920,
                sourceHeight = 1080,
            ),
        )
    }
}
