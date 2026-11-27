package com.novum.tv.ui.screen.player

import androidx.media3.common.C
import androidx.media3.common.Player
import com.novum.tv.model.PlaybackPlan

/** Progressive server transcodes may expose a local timeline beginning partway through a film. */
internal fun absolutePlaybackPosition(plan: PlaybackPlan?, localPositionMs: Long): Long =
    (localPositionMs.coerceAtLeast(0L) + (plan?.streamStartPositionMs ?: 0L)).coerceAtLeast(0L)

internal fun localPlaybackPosition(plan: PlaybackPlan, absolutePositionMs: Long): Long =
    (absolutePositionMs - plan.streamStartPositionMs).coerceAtLeast(0L)

/** Pending requests remain the base for repeated keys while a server seek is still loading. */
internal fun playbackSeekTarget(currentPositionMs: Long, pendingTargetMs: Long?, deltaMs: Long, durationMs: Long): Long {
    val target = ((pendingTargetMs ?: currentPositionMs) + deltaMs).coerceAtLeast(0L)
    return if (durationMs > 0) target.coerceAtMost(durationMs) else target
}

/** Confirm the player's absolute timeline is close enough to the requested seek target. */
internal fun playbackSeekReached(
    currentPositionMs: Long,
    targetPositionMs: Long,
    toleranceMs: Long = 3_000L,
): Boolean = kotlin.math.abs(currentPositionMs - targetPositionMs) <= toleranceMs

internal fun Player.activePlaybackPlan(): PlaybackPlan? =
    currentMediaItem?.localConfiguration?.tag as? PlaybackPlan

internal fun Player.mediaPositionMs(): Long = absolutePlaybackPosition(activePlaybackPlan(), currentPosition)

internal fun Player.mediaBufferedPositionMs(): Long = absolutePlaybackPosition(activePlaybackPlan(), bufferedPosition)

internal fun Player.mediaDurationMs(): Long {
    val plan = activePlaybackPlan()
    return plan?.durationMs?.takeIf { it > 0 }
        ?: duration.takeIf { it != C.TIME_UNSET && it > 0 }?.let { absolutePlaybackPosition(plan, it) }
        ?: 0L
}

/** Rebuild streams that cannot seek locally, including positions before a transcode's origin. */
internal fun Player.seekToMediaPosition(positionMs: Long, rebuildStream: (Long) -> Unit) {
    val target = positionMs.coerceAtLeast(0L)
    val plan = activePlaybackPlan()
    if (plan != null && (target < plan.streamStartPositionMs || !isCurrentMediaItemSeekable)) {
        rebuildStream(target)
    } else {
        seekTo(if (plan == null) target else localPlaybackPosition(plan, target))
    }
}
