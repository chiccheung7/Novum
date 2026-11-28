package com.novum.tv.ui.screen.player

import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import com.novum.tv.core.error.NovumError
import com.novum.tv.core.player.buildMediaItem
import com.novum.tv.model.PlayMethod
import com.novum.tv.model.PlaybackPlan

/** 每次失败只沿恢复链向前走，已经失败的转码不能再次请求同一种转码。 */
internal enum class PlaybackRecoveryAction {
    RetryPlayback,
    UseFallback,
    RequestTranscode,
    ShowFailure,
}
internal fun resolvePlaybackRecovery(
    plan: PlaybackPlan,
    fallbackUsed: Boolean,
    transcodeRequested: Boolean,
    networkError: NovumError? = null,
    networkRetryUsed: Boolean = false,
): PlaybackRecoveryAction {
    // A gateway outage says nothing about decoder support. Refresh the same
    // playback once, then stop rather than falling into a transcode loop.
    if (networkError != null) {
        val retryable = networkError is NovumError.Network ||
            networkError is NovumError.Server && networkError.code in setOf(500, 502, 503, 504)
        return if (retryable && !networkRetryUsed) PlaybackRecoveryAction.RetryPlayback
            else PlaybackRecoveryAction.ShowFailure
    }
    if (plan.fallbackUrl != null && !fallbackUsed) return PlaybackRecoveryAction.UseFallback
    val currentMethod = if (fallbackUsed) plan.fallbackMethod ?: PlayMethod.Transcode else plan.method
    return if (currentMethod != PlayMethod.Transcode && !transcodeRequested) {
        PlaybackRecoveryAction.RequestTranscode
    } else {
        PlaybackRecoveryAction.ShowFailure
    }
}

/** Keep actual HTTP statuses separate from local decoder/unsupported-stream failures. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun playbackNetworkError(error: PlaybackException): NovumError? {
    val causes = generateSequence<Throwable>(error) { it.cause }.take(16).toList()
    causes.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.let {
        return if (it.responseCode == 401 || it.responseCode == 403) NovumError.Unauthorized()
            else NovumError.Server(it.responseCode)
    }
    return if (error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
        causes.any { it is HttpDataSource.HttpDataSourceException }
    ) NovumError.Network(error) else null
}

/** Replacing an item must not turn a user-paused playback back on. */
internal fun Player.preparePlayback(plan: PlaybackPlan, subtitlesEnabled: Boolean) {
    val intendedPlayback = playWhenReady
    setMediaItem(buildMediaItem(plan, subtitlesEnabled), localPlaybackPosition(plan, plan.startPositionMs))
    prepare()
    playWhenReady = intendedPlayback
}

/** An error screen must not leave an old buffered stream running behind it. */
internal fun Player.stopFailedPlayback() {
    stop()
    clearMediaItems()
}

/** Keep an absolute timeline before clearing/releasing the media item and its stream offset. */
internal data class PlaybackCheckpoint(
    val plan: PlaybackPlan,
    val positionMs: Long,
    val durationMs: Long,
) {
    fun stopPosition(finished: Boolean): Long = when {
        finished && durationMs > 0 -> durationMs
        durationMs > 0 && positionMs >= durationMs - durationMs / 20 -> durationMs
        else -> positionMs
    }
}

/** Screen-owned: survives Stage removal, and navigation/disposal can finish a session only once. */
internal class PlaybackCheckpoints {
    var latest: PlaybackCheckpoint? = null
        private set
    private var stopped = false
    private var activePlayer: Player? = null

    fun attach(player: Player) {
        activePlayer = player
    }

    /** Capture and unregister before release; disposal order must never require released getters. */
    fun detach(player: Player) {
        capture(player)
        if (activePlayer === player) activePlayer = null
    }

    fun captureActive(): PlaybackCheckpoint? = activePlayer?.let(::capture) ?: latest

    fun save(checkpoint: PlaybackCheckpoint) {
        latest = checkpoint
    }

    fun capture(player: Player): PlaybackCheckpoint? {
        player.activePlaybackPlan()?.let { plan ->
            save(PlaybackCheckpoint(plan, player.mediaPositionMs(), player.mediaDurationMs()))
        }
        return latest
    }

    fun reportStopped(finished: Boolean = false, report: (Long) -> Unit) {
        val checkpoint = latest ?: return
        if (stopped) return
        stopped = true
        report(checkpoint.stopPosition(finished))
    }
}
