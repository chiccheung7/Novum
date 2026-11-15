package com.novum.tv.core.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首页二次返回判定测试。
 *
 * 边界最容易出错的三种情况：窗口内第二次、恰好等于窗口、时钟回拨。
 */
class ExitConfirmGateTest {

    @Test
    fun firstBackOnlyWarns() {
        val gate = ExitConfirmGate(windowMs = 2_500)

        assertFalse(gate.onBack(1_000))
    }

    @Test
    fun secondBackInsideWindowExits() {
        val gate = ExitConfirmGate(windowMs = 2_500)
        gate.onBack(1_000)

        assertTrue(gate.onBack(3_000))
    }

    @Test
    fun secondBackAfterWindowOnlyWarnsAgain() {
        val gate = ExitConfirmGate(windowMs = 2_500)
        gate.onBack(1_000)

        // 超窗：不退出，并重新开始计时
        assertFalse(gate.onBack(3_600))
        assertTrue(gate.onBack(5_000))
    }

    @Test
    fun windowBoundaryIsInclusive() {
        val gate = ExitConfirmGate(windowMs = 2_500)
        gate.onBack(0)

        assertTrue(gate.onBack(2_500))
    }

    @Test
    fun clockGoingBackwardsDoesNotExit() {
        val gate = ExitConfirmGate(windowMs = 2_500)
        gate.onBack(10_000)

        // 墙钟被用户改动时 elapsed 为负，不能当成"窗口内"
        assertFalse(gate.onBack(1_000))
        // 之后仍可正常二次确认
        assertTrue(gate.onBack(2_000))
    }

    @Test
    fun resetClearsWindow() {
        val gate = ExitConfirmGate(windowMs = 2_500)
        gate.onBack(1_000)
        gate.reset()

        assertFalse(gate.onBack(1_500))
    }
}
