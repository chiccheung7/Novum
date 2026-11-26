package com.novum.tv.ui.screen.player

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.media3.common.Player
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayerLifecycleTest {
    @Test
    fun playingOrBufferingPlayerPausesOnStopAndResumesOnStart() {
        val owner = TestOwner(Lifecycle.State.STARTED)
        val player = FakePlayer(playWhenReady = true)
        val removeObserver = observePlayerLifecycle(player.instance, owner.lifecycle)

        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        assertFalse(player.playWhenReady)
        assertEquals(1, player.pauseCalls)

        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        assertTrue(player.playWhenReady)
        assertEquals(1, player.playCalls)
        removeObserver()
    }

    @Test
    fun manuallyPausedPlayerStaysPausedAfterReturning() {
        val owner = TestOwner(Lifecycle.State.STARTED)
        val player = FakePlayer(playWhenReady = false)
        val removeObserver = observePlayerLifecycle(player.instance, owner.lifecycle)

        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)

        assertFalse(player.playWhenReady)
        assertEquals(0, player.playCalls)
        removeObserver()
    }

    @Test
    fun playerCreatedBeforeStartIsPausedUntilPageBecomesVisible() {
        val owner = TestOwner(Lifecycle.State.CREATED)
        val player = FakePlayer(playWhenReady = true)
        val removeObserver = observePlayerLifecycle(player.instance, owner.lifecycle)

        assertFalse(player.playWhenReady)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        assertTrue(player.playWhenReady)
        removeObserver()
    }

    @Test
    fun automaticFallbackCannotRestartPlaybackWhilePageIsStopped() {
        val owner = TestOwner(Lifecycle.State.STARTED)
        val player = FakePlayer(playWhenReady = true)
        val removeObserver = observePlayerLifecycle(player.instance, owner.lifecycle)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)

        // 模拟起播超时后 switchToFallback() 的 prepare + play。
        player.instance.play()
        assertFalse(player.playWhenReady)

        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        assertTrue(player.playWhenReady)
        removeObserver()
    }

    @Test
    fun removingObserverPreventsCallsToReleasedPlayer() {
        val owner = TestOwner(Lifecycle.State.STARTED)
        val player = FakePlayer(playWhenReady = true)
        val removeObserver = observePlayerLifecycle(player.instance, owner.lifecycle)

        removeObserver()
        assertTrue(player.listeners.isEmpty())
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)

        assertTrue(player.playWhenReady)
        assertEquals(0, player.pauseCalls)
        assertEquals(0, player.playCalls)
    }

    private class TestOwner(initialState: Lifecycle.State) : LifecycleOwner {
        val registry = LifecycleRegistry(this).apply { currentState = initialState }
        override val lifecycle: Lifecycle get() = registry
    }

    /** 只实现生命周期桥需要的 Player 契约，不启动解码器或连接媒体地址。 */
    private class FakePlayer(var playWhenReady: Boolean) {
        var pauseCalls = 0
        var playCalls = 0
        val listeners = mutableListOf<Player.Listener>()
        val instance: Player = Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java),
        ) { _, method, arguments ->
            when (method.name) {
                "getPlayWhenReady" -> playWhenReady
                "pause" -> {
                    pauseCalls++
                    playWhenReady = false
                    notifyPlayWhenReady()
                    null
                }

                "play" -> {
                    playCalls++
                    playWhenReady = true
                    notifyPlayWhenReady()
                    null
                }

                "addListener" -> { listeners += arguments!![0] as Player.Listener; null }
                "removeListener" -> { listeners -= arguments!![0] as Player.Listener; null }
                else -> error("Unexpected Player call: ${method.name}")
            }
        } as Player

        private fun notifyPlayWhenReady() {
            listeners.toList().forEach {
                it.onPlayWhenReadyChanged(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            }
        }
    }
}
