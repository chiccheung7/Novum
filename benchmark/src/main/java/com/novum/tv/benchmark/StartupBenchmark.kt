package com.novum.tv.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 帧时序基线（流畅度规范 11.2）与 profile 旅程预备。
 * `connectedBenchmarkAndroidTest` 跑帧时序，产出 P50/P90/P99 报告。
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val baselineRule = BaselineProfileRule()

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    /** 无需登录即可验证冷启动测量链路；已登录时同样覆盖会话恢复。 */
    @Test
    fun coldStartupTiming() = benchmarkRule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.COLD,
        iterations = 5,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
    }

    /** Profile 生成旅程（baselineprofile 插件适配 AGP 9 后启用）。 */
    @Test
    fun generateBaselineProfile() = baselineRule.collect(
        packageName = PACKAGE,
        maxIterations = 5,
    ) {
        navigateToHome()
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
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
        startupMode = null,
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
        startActivityAndWait()
        assumeTrue(
            "需要先登录并加载含 Hero 的首页，不能把登录页按键计为首页滚动性能",
            device.wait(Until.hasObject(By.text("立即播放")), 20_000),
        )
        device.waitForIdle()
    }

    companion object {
        const val PACKAGE = "com.novum.tv"
    }
}
