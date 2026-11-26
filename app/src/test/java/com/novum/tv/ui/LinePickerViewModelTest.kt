package com.novum.tv.ui

import com.novum.tv.data.line.LineProbe
import com.novum.tv.data.remote.emby.EmbyApi
import com.novum.tv.data.remote.emby.EmbyApiProvider
import com.novum.tv.data.remote.emby.SessionHolder
import com.novum.tv.data.session.Session
import com.novum.tv.data.settings.AppSettings
import com.novum.tv.data.settings.pinnedLineUrlFor
import com.novum.tv.testing.FakeSettingsRepository
import com.novum.tv.testing.MainDispatcherRule
import com.novum.tv.testing.NoopNovumLogger
import com.novum.tv.ui.component.LineOption
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LinePickerViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    @Test
    fun displaysOnlyTheActiveAccountsPinnedLine() = runTest {
        val holder = SessionHolder().apply { update(session()) }
        val settings = pinnedSettings(holder)
        val viewModel = viewModel(FakeSettingsRepository(settings), holder)
        collectPinned(viewModel)
        runCurrent()

        assertEquals(PinnedLine(url = option.baseUrl, name = option.displayName), viewModel.pinned.value)
    }

    @Test
    fun anotherUserOnTheSameServerDoesNotDisplayThePreviousUsersPin() = runTest {
        val holder = SessionHolder().apply { update(session()) }
        val settings = pinnedSettings(holder)
        holder.update(session(userId = "user-b"))
        val viewModel = viewModel(FakeSettingsRepository(settings), holder)
        collectPinned(viewModel)
        runCurrent()

        assertEquals(PinnedLine(), viewModel.pinned.value)
    }

    @Test
    fun legacyPinWithoutOwnerAppearsAsAutomatic() = runTest {
        val holder = SessionHolder().apply { update(session()) }
        val settings = pinnedSettings(holder).copy(pinnedLineAccountKey = "")
        val viewModel = viewModel(FakeSettingsRepository(settings), holder)
        collectPinned(viewModel)
        runCurrent()

        assertEquals(PinnedLine(), viewModel.pinned.value)
    }

    @Test
    fun selectionPersistsUrlNameAndAccountTogether() = runTest {
        val holder = SessionHolder().apply { update(session()) }
        val previous = AppSettings(preferDirect = false, playbackSpeed = 1.5f)
        val settings = FakeSettingsRepository(previous)
        val viewModel = viewModel(settings, holder)

        viewModel.select(option)
        runCurrent()

        assertEquals(
            previous.copy(
                pinnedLineUrl = option.baseUrl,
                pinnedLineName = option.displayName,
                pinnedLineAccountKey = holder.playbackPrefsNamespace().orEmpty(),
            ),
            settings.settings.first(),
        )
    }

    @Test
    fun queuedSelectionRetainsTheAccountThatChoseTheLine() = runTest {
        val holder = SessionHolder().apply { update(session()) }
        val accountA = requireNotNull(holder.playbackPrefsNamespace())
        val settings = FakeSettingsRepository()
        val viewModel = viewModel(settings, holder)

        viewModel.select(option)
        // Main 队列还没执行 settings.update 时切换服务器/账号。
        holder.update(session(server = "https://server-b.example", userId = "user-b"))
        collectPinned(viewModel)
        runCurrent()

        val stored = settings.settings.first()
        assertEquals(accountA, stored.pinnedLineAccountKey)
        assertEquals(option.baseUrl, stored.pinnedLineUrlFor(accountA))
        assertNull(stored.pinnedLineUrlFor(holder.playbackPrefsNamespace()))
        assertEquals(PinnedLine(), viewModel.pinned.value)
    }

    @Test
    fun automaticSelectionClearsTheUrlNameAndOwnerTogether() = runTest {
        val holder = SessionHolder().apply { update(session()) }
        val previous = pinnedSettings(holder).copy(subtitleSize = 3)
        val settings = FakeSettingsRepository(previous)
        val viewModel = viewModel(settings, holder)

        viewModel.select(null)
        runCurrent()

        assertEquals(
            previous.copy(pinnedLineUrl = "", pinnedLineName = "", pinnedLineAccountKey = ""),
            settings.settings.first(),
        )
    }

    @Test
    fun loggedOutSelectionCannotCreateAnUnownedPin() = runTest {
        val settings = FakeSettingsRepository()
        val viewModel = viewModel(settings, SessionHolder())

        viewModel.select(option)
        runCurrent()

        assertEquals(AppSettings(), settings.settings.first())
    }

    private fun TestScope.collectPinned(viewModel: LinePickerViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.pinned.collect() }
    }

    private fun viewModel(settings: FakeSettingsRepository, holder: SessionHolder) = LinePickerViewModel(
        lineProbe = LineProbe(
            clients = object : EmbyApiProvider {
                override fun api(baseUrl: String): EmbyApi = error("本用例不得访问服务器")
            },
            sessionHolder = holder,
            logger = NoopNovumLogger,
        ),
        settings = settings,
        sessionHolder = holder,
    )

    private fun pinnedSettings(holder: SessionHolder) = AppSettings(
        pinnedLineUrl = option.baseUrl,
        pinnedLineName = option.displayName,
        pinnedLineAccountKey = requireNotNull(holder.playbackPrefsNamespace()),
    )

    private fun session(
        server: String = "https://server-a.example",
        userId: String = "user-a",
    ) = Session(server, "test-token", userId, "Test user", "", "device-1")

    private val option = LineOption(
        baseUrl = "https://line-a.example",
        displayName = "线路 A",
        shortName = "line-a.example",
        latencyMs = 10,
    )
}
