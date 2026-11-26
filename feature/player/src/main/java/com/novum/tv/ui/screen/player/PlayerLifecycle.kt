package com.novum.tv.ui.screen.player

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.Player

/**
 * 播放器只在页面可见时运行。按 Home 不会销毁组合，不能只靠 onDispose 释放。
 *
 * 用 playWhenReady 记住播放意图：缓冲中的播放也需要恢复，用户主动暂停的播放则不恢复。
 * 返回的清理函数随组合离场移除生命周期与播放器监听。
 */
internal fun observePlayerLifecycle(player: Player, lifecycle: Lifecycle): () -> Unit {
    var resumeOnStart = false

    fun pauseForBackground() {
        resumeOnStart = player.playWhenReady
        player.pause()
    }

    val playerListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // 播放器报错后的自动兜底会调用 play()，即使 Home 后才返回也不能后台出声。
            if (playWhenReady && !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                resumeOnStart = true
                player.pause()
            }
        }
    }
    val observer = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> {
                if (resumeOnStart) {
                    resumeOnStart = false
                    player.play()
                }
            }

            Lifecycle.Event.ON_STOP -> pauseForBackground()
            else -> Unit
        }
    }
    player.addListener(playerListener)
    // 起播请求可能在 Activity 停止后才完成；注册时就对齐当前状态。
    if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) pauseForBackground()
    lifecycle.addObserver(observer)
    return {
        lifecycle.removeObserver(observer)
        player.removeListener(playerListener)
    }
}
