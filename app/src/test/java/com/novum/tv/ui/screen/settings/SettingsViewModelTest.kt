package com.novum.tv.ui.screen.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.novum.tv.core.state.UiState
import com.novum.tv.data.settings.AppSettings
import com.novum.tv.testing.MainDispatcherRule
import com.novum.tv.testing.RecordingToastSink
import com.novum.tv.testing.NoopNovumLogger
import com.novum.tv.testing.FakeSettingsRepository
import com.novum.tv.testing.NoopAuthRepository
import com.novum.tv.testing.NoopSessionStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 设置页 ViewModel 单元测试。
 *
 * 覆盖：默认值暴露、布尔开关写回、枚举与档位循环边界。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1920dp-h1080dp-xhdpi")
class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun exposesStoredSettings() = runTest {
        val repository = FakeSettingsRepository()
        val (viewModel, _) = viewModel(repository)

        viewModel.uiState.test {
            val success = awaitSuccess()
            assertTrue(success.data.preferDirect)
            assertTrue(success.data.audioPassthrough)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun togglesDirectPlay() = runTest {
        val repository = FakeSettingsRepository()
        val (viewModel, toast) = viewModel(repository)

        viewModel.uiState.test {
            awaitSuccess()
            viewModel.setPreferDirect(false)
            assertEquals(listOf("优先直连播放：已关闭"), toast.texts)
            val updated = awaitSuccess()
            assertFalse(updated.data.preferDirect)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun cyclesSubtitleSizeAndWrapsAround() = runTest {
        val repository = FakeSettingsRepository(AppSettings(subtitleSize = 4))
        val (viewModel, toast) = viewModel(repository)

        viewModel.uiState.test {
            awaitSuccess()
            viewModel.cycleSubtitleSize()
            assertEquals(listOf("字幕字号：小"), toast.texts)
            val wrapped = awaitSuccess()
            assertEquals(1, wrapped.data.subtitleSize)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun togglesSkipIntroAndCyclesSeconds() = runTest {
        val repository = FakeSettingsRepository(AppSettings(skipIntro = false, skipIntroSeconds = 90))
        val (viewModel, toast) = viewModel(repository)

        viewModel.uiState.test {
            awaitSuccess()
            viewModel.setSkipIntro(true)
            assertEquals(listOf("自动跳过片头：已开启"), toast.texts)
            toast.reset()
            assertTrue(awaitSuccess().data.skipIntro)
            // 90 → 120，再循环回到 30
            viewModel.cycleSkipIntroSeconds()
            assertEquals(120, awaitSuccess().data.skipIntroSeconds)
            viewModel.cycleSkipIntroSeconds()
            assertEquals(30, awaitSuccess().data.skipIntroSeconds)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * 回归：`0.7f + 0.1f = 0.7999999`，旧实现 `(x * 10).toInt()` 截断回 0.7，
     * 设置页看起来「点了没反应」——档位必须落在十分位整数上。
     */
    @Test
    fun cyclesDanmuOpacityByExactSteps() = runTest {
        val repository = FakeSettingsRepository(AppSettings(danmuOpacity = 0.7f))
        val (viewModel, toast) = viewModel(repository)

        viewModel.uiState.test {
            awaitSuccess()
            viewModel.cycleDanmuOpacity()
            assertEquals(listOf("透明度：80%"), toast.texts)
            assertEquals(0.8f, awaitSuccess().data.danmuOpacity, 0.0001f)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** 透明度到顶回绕到 10%（10 档循环不会卡在某一档）。 */
    @Test
    fun danmuOpacityWrapsToLowestStep() = runTest {
        val repository = FakeSettingsRepository(AppSettings(danmuOpacity = 1f))
        val (viewModel, _) = viewModel(repository)

        viewModel.uiState.test {
            awaitSuccess()
            viewModel.cycleDanmuOpacity()
            assertEquals(0.1f, awaitSuccess().data.danmuOpacity, 0.0001f)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** 「界面大小」按档位表推进并回绕（85 → 100 → 115 → 130 → 150 → 85）。 */
    @Test
    fun cyclesUiScaleThroughSteps() = runTest {
        val repository = FakeSettingsRepository(AppSettings(uiScale = 1.5f))
        val (viewModel, toast) = viewModel(repository)

        viewModel.uiState.test {
            awaitSuccess()
            viewModel.cycleUiScale()
            assertEquals(listOf("界面大小：85%"), toast.texts)
            assertEquals(0.85f, awaitSuccess().data.uiScale, 0.0001f)
            cancelAndIgnoreRemainingEvents()
        }
    }

}

/** 用最小依赖构造 ViewModel：账号与会话在设置页只用于退出登录与展示。 */
private fun viewModel(
    repository: FakeSettingsRepository,
    toast: RecordingToastSink = RecordingToastSink(),
): Pair<SettingsViewModel, RecordingToastSink> {
    val vm = SettingsViewModel(
        settingsRepository = repository,
        authRepository = NoopAuthRepository,
        sessionStore = NoopSessionStore,
        toast = toast,
        logger = NoopNovumLogger,
        appContext = ApplicationProvider.getApplicationContext<Context>(),
    )
    return vm to toast
}

/**
 * 取下一个「成功」状态；首个 Loading 由 `stateIn` 的初始值产生，测试里直接跳过。
 */
private suspend fun app.cash.turbine.ReceiveTurbine<UiState<AppSettings>>.awaitSuccess(): UiState.Success<AppSettings> {
    while (true) {
        val item = awaitItem()
        if (item is UiState.Success) return item
    }
}
