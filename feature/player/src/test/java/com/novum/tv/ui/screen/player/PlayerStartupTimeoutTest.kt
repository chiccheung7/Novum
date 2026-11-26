package com.novum.tv.ui.screen.player

import androidx.media3.common.Player
import java.lang.reflect.Proxy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayerStartupTimeoutTest {
    @Test
    fun initialBufferingTimesOutOnceAfterTheFullWindow() = runTest {
        val player = FakePlayer()
        var timeouts = 0
        val watch = backgroundScope.launch {
            observePlaybackStartupTimeout(player.instance, TIMEOUT_MS) { timeouts++ }
        }
        runCurrent()

        advanceTimeBy(TIMEOUT_MS - 1)
        runCurrent()
        assertEquals(0, timeouts)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, timeouts)

        player.changePlayWhenReady(false)
        runCurrent()
        player.changePlayWhenReady(true)
        runCurrent()
        advanceTimeBy(TIMEOUT_MS)
        runCurrent()
        assertEquals("一个媒体计划只触发一次超时恢复", 1, timeouts)
        watch.cancelAndJoin()
    }

    @Test
    fun rebufferingNearTheDeadlineDoesNotFailAStartedStream() = runTest {
        val player = FakePlayer()
        var timeouts = 0
        val watch = backgroundScope.launch {
            observePlaybackStartupTimeout(player.instance, TIMEOUT_MS) { timeouts++ }
        }
        runCurrent()

        advanceTimeBy(1_000)
        player.changePlaybackState(Player.STATE_READY)
        runCurrent()
        advanceTimeBy(6_000)
        // 起播第 7 秒 seek 或短暂断流，旧代码在第 8 秒只看瞬时状态而错误降级。
        player.changePlaybackState(Player.STATE_BUFFERING)
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()

        assertEquals(0, timeouts)
        watch.cancelAndJoin()
    }

    @Test
    fun alreadyReadyPlayerDoesNotStartAStartupTimeout() = runTest {
        val player = FakePlayer(playbackState = Player.STATE_READY)
        var timeouts = 0
        val watch = backgroundScope.launch {
            observePlaybackStartupTimeout(player.instance, TIMEOUT_MS) { timeouts++ }
        }
        runCurrent()
        player.changePlaybackState(Player.STATE_BUFFERING)
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()

        assertEquals(0, timeouts)
        watch.cancelAndJoin()
    }

    @Test
    fun pausingInitialBufferingSuspendsTimeoutUntilPlaybackIsRequestedAgain() = runTest {
        val player = FakePlayer()
        var timeouts = 0
        val watch = backgroundScope.launch {
            observePlaybackStartupTimeout(player.instance, TIMEOUT_MS) { timeouts++ }
        }
        runCurrent()
        advanceTimeBy(3_000)
        player.changePlayWhenReady(false)
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals("暂停不能触发降级或恢复播放", 0, timeouts)

        player.changePlayWhenReady(true)
        runCurrent()
        advanceTimeBy(TIMEOUT_MS - 1)
        runCurrent()
        assertEquals(0, timeouts)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, timeouts)
        watch.cancelAndJoin()
    }

    @Test
    fun endingOrLeavingThePlayerCancelsPendingTimeout() = runTest {
        val player = FakePlayer()
        var timeouts = 0
        val watch = backgroundScope.launch {
            observePlaybackStartupTimeout(player.instance, TIMEOUT_MS) { timeouts++ }
        }
        runCurrent()
        player.changePlaybackState(Player.STATE_ENDED)
        runCurrent()
        advanceTimeBy(TIMEOUT_MS)
        runCurrent()
        assertEquals(0, timeouts)

        watch.cancelAndJoin()
        assertTrue(player.listeners.isEmpty())
        player.changePlaybackState(Player.STATE_BUFFERING)
        advanceTimeBy(TIMEOUT_MS)
        runCurrent()
        assertEquals(0, timeouts)
    }

    /** 只实现启动监视器使用的 Player 契约，不启动解码器或网络。 */
    private class FakePlayer(
        private var playbackState: Int = Player.STATE_BUFFERING,
        private var playWhenReady: Boolean = true,
    ) {
        val listeners = mutableListOf<Player.Listener>()
        val instance: Player = Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java),
        ) { _, method, arguments ->
            when (method.name) {
                "getPlaybackState" -> playbackState
                "getPlayWhenReady" -> playWhenReady
                "addListener" -> { listeners += arguments!![0] as Player.Listener; null }
                "removeListener" -> { listeners -= arguments!![0] as Player.Listener; null }
                else -> error("Unexpected Player call: ${method.name}")
            }
        } as Player

        fun changePlaybackState(state: Int) {
            playbackState = state
            listeners.toList().forEach { it.onPlaybackStateChanged(state) }
        }

        fun changePlayWhenReady(ready: Boolean) {
            playWhenReady = ready
            listeners.toList().forEach {
                it.onPlayWhenReadyChanged(ready, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            }
        }
    }

    private companion object {
        const val TIMEOUT_MS = 8_000L
    }
}
