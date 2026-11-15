package com.novum.tv.core.player

import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import com.novum.tv.model.PlaybackPlan

/**
 * 播放内核的公共能力。
 *
 * 这一层只依赖 Media3 与领域模型，不依赖 Compose：
 * 以后换播放页、加投屏或做后台播放，媒体组装与轨道策略都能直接复用。
 */

/**
 * 组装媒体条目：播放地址 + 外挂字幕。
 *
 * 外挂字幕必须在这里挂上，否则播放器不会产生字幕轨，按下键也无从切换。
 *
 * @param plan 播放计划。
 * @param subtitlesEnabled 是否默认选中第一条字幕（跟随设置里的「默认开启字幕」）。
 */
fun buildMediaItem(plan: PlaybackPlan, subtitlesEnabled: Boolean): MediaItem {
    val builder = MediaItem.Builder()
        .setUri(plan.url)
        .setMediaId(plan.url)
    if (plan.subtitles.isNotEmpty()) {
        builder.setSubtitleConfigurations(
            plan.subtitles.mapIndexed { index, subtitle ->
                MediaItem.SubtitleConfiguration.Builder(subtitle.url.toUri())
                    .setMimeType(subtitle.mimeType)
                    .setLanguage(subtitle.language)
                    .setLabel(subtitle.label)
                    .setId(subtitle.id)
                    .setSelectionFlags(
                        if (subtitlesEnabled && index == 0) C.SELECTION_FLAG_DEFAULT else 0,
                    )
                    .build()
            },
        )
    }
    return builder.build()
}

/** 当前音轨标签。 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun Player.audioLabel(): String = trackLabel(C.TRACK_TYPE_AUDIO, "音轨")

/** 当前字幕标签；未启用字幕时返回「关闭」。 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun Player.subtitleLabel(): String {
    val groups = currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
    if (groups.isEmpty()) return ""
    val selected = groups.firstOrNull { group ->
        (0 until group.length).any { group.isTrackSelected(it) }
    } ?: return "关闭"
    val format = selected.mediaTrackGroup.getFormat(0)
    return format.label ?: format.language?.takeIf { it != "und" } ?: "字幕"
}

/** 轨标签通用实现。 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun Player.trackLabel(type: Int, fallback: String): String {
    val groups = currentTracks.groups.filter { it.type == type }
    if (groups.isEmpty()) return ""
    val index = groups.indexOfFirst { group ->
        (0 until group.length).any { group.isTrackSelected(it) }
    }
    if (index < 0) return fallback
    val format = groups[index].mediaTrackGroup.getFormat(0)
    return format.label ?: format.language?.takeIf { it != "und" } ?: "$fallback ${index + 1}"
}

/** 循环切换音轨。 */
fun Player.cycleAudioTrack() {
    val groups = currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
    if (groups.size < 2) return
    val current = groups.indexOfFirst { group ->
        (0 until group.length).any { group.isTrackSelected(it) }
    }
    val next = if (current < 0) 0 else (current + 1) % groups.size
    trackSelectionParameters = trackSelectionParameters.buildUpon()
        .setOverrideForType(TrackSelectionOverride(groups[next].mediaTrackGroup, 0))
        .build()
}

/**
 * 循环切换字幕：关闭 → 第 1 条 → 第 2 条 … → 关闭。
 *
 * 关闭状态用 `setTrackTypeDisabled` 表达，而不是清空 override，
 * 否则选择器会按默认规则自动选回第一条字幕。
 */
fun Player.cycleSubtitleTrack() {
    val groups = currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
    if (groups.isEmpty()) return
    val current = groups.indexOfFirst { group ->
        (0 until group.length).any { group.isTrackSelected(it) }
    }
    val params = trackSelectionParameters.buildUpon()
    if (current < 0) {
        params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(groups[0].mediaTrackGroup, 0))
    } else if (current == groups.size - 1) {
        params.clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
    } else {
        params.setOverrideForType(TrackSelectionOverride(groups[current + 1].mediaTrackGroup, 0))
    }
    trackSelectionParameters = params.build()
}

/** 一次快进/快退的步长。 */
private const val SEEK_STEP_MS = 10_000L

/** 长按加速后的步进。 */
private const val SEEK_STEP_FAST_MS = 30_000L

/** 按住多少次重复后开始加速（遥控器重复间隔约 50ms）。 */
private const val SEEK_FAST_REPEAT_THRESHOLD = 3

/**
 * 根据按键重复次数决定 seek 步进：单击 10 秒，按住超过 3 次后加速到 30 秒。
 *
 * @param repeatCount 系统按键重复次数，首次按下为 0。
 */
fun seekStepMs(repeatCount: Int): Long =
    if (repeatCount > SEEK_FAST_REPEAT_THRESHOLD) SEEK_STEP_FAST_MS else SEEK_STEP_MS
