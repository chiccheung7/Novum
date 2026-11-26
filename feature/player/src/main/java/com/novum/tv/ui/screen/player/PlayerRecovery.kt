package com.novum.tv.ui.screen.player

import androidx.media3.common.Player
import com.novum.tv.model.PlayMethod
import com.novum.tv.model.PlaybackPlan
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update

/** 每次失败只沿恢复链向前走，已经失败的转码不能再次请求同一种转码。 */
internal enum class PlaybackRecoveryAction {
    UseFallback,
    RequestTranscode,
    ShowFailure,
}

internal fun resolvePlaybackRecovery(
    plan: PlaybackPlan,
    fallbackUsed: Boolean,
    transcodeRequested: Boolean,
): PlaybackRecoveryAction {
    if (plan.fallbackUrl != null && !fallbackUsed) return PlaybackRecoveryAction.UseFallback
    val currentMethod = if (fallbackUsed) plan.fallbackMethod ?: PlayMethod.Transcode else plan.method
    return if (currentMethod != PlayMethod.Transcode && !transcodeRequested) {
        PlaybackRecoveryAction.RequestTranscode
    } else {
        PlaybackRecoveryAction.ShowFailure
    }
}

/**
 * 起播超时只覆盖第一次 READY 之前；seek/重缓冲不重新开始起播计时。
 * 暂停期间取消计时，恢复播放意图后重新给足准备窗口，每个媒体计划最多回调一次。
 */
internal suspend fun observePlaybackStartupTimeout(
    player: Player,
    timeoutMs: Long,
    onTimeout: () -> Unit,
) {
    val startup = MutableStateFlow(
        PlaybackStartup(
            hasBeenReady = player.playbackState == Player.STATE_READY,
            playWhenReady = player.playWhenReady,
            ended = player.playbackState == Player.STATE_ENDED,
        ),
    )
    val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            startup.update {
                it.copy(
                    hasBeenReady = it.hasBeenReady || playbackState == Player.STATE_READY,
                    ended = playbackState == Player.STATE_ENDED,
                )
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            startup.update { it.copy(playWhenReady = playWhenReady) }
        }
    }
    player.addListener(listener)
    var timedOut = false
    try {
        startup.collectLatest {
            if (timedOut || it.hasBeenReady || !it.playWhenReady || it.ended) return@collectLatest
            delay(timeoutMs)
            timedOut = true
            onTimeout()
        }
    } finally {
        player.removeListener(listener)
    }
}

private data class PlaybackStartup(
    val hasBeenReady: Boolean,
    val playWhenReady: Boolean,
    val ended: Boolean,
)
