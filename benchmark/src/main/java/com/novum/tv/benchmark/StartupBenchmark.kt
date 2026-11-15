package com.novum.tv.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 帧时序基线（流畅度规范 11.2）与 profile 旅程预备。
 * `connectedReleaseAndroidTest` 跑帧时序，产出 P50/P90/P99 报告。
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val baselineRule = BaselineProfileRule()

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    /** Profile 生成旅程（baselineprofile 插件适配 AGP 9 后启用）。 */
    @Test
    fun generateBaselineProfile() = baselineRule.collect(
        packageName = PACKAGE,
        maxIterations = 5,
    ) {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        execShell(ui, "am start -n $PACKAGE/com.novum.tv.MainActivity")
        Thread.sleep(2_500)  // Hero 静止期
        repeat(6) { execShell(ui, "input keyevent KEYCODE_DPAD_DOWN"); Thread.sleep(120) }
        repeat(6) { execShell(ui, "input keyevent KEYCODE_DPAD_RIGHT"); Thread.sleep(120) }
        execShell(ui, "input keyevent KEYCODE_DPAD_CENTER")
        Thread.sleep(1_500)
        execShell(ui, "input keyevent KEYCODE_BACK")
        Thread.sleep(500)
    }

    /** 帧时序基线：Down×10 + Right×12 连发（协议口径）。 */
    @Test
    fun dpadScrollFrameTiming() = benchmarkRule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.WARM,
        iterations = 5,
        setupBlock = {
            navigateToHome()
        },
    ) {
        repeat(10) {
            device.pressDPadDown()
            Thread.sleep(120)
        }
        repeat(12) {
            device.pressDPadRight()
            Thread.sleep(80)
        }
        device.waitForIdle()
    }

    private fun execShell(device: android.app.UiAutomation, cmd: String) {
        device.executeShellCommand(cmd).close()
    }

    private fun MacrobenchmarkScope.navigateToHome() {
        pressHome()
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        execShell(ui, "am start -n $PACKAGE/com.novum.tv.MainActivity")
        device.waitForIdle()
        Thread.sleep(2_000)
    }

    companion object {
        const val PACKAGE = "com.novum.tv"
    }
}
