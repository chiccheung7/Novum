package com.novum.tv.core.player

import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
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
        .setTag(plan)
    if (plan.subtitles.isNotEmpty() && plan.burnedInSubtitleLabel == null) {
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

/**
 * 判断当前音频输出路径的文案（信息面板展示「音画延迟是否生效」的依据）。
 *
 * 延迟处理器 [DelayAudioProcessor] 只挂在 PCM 解码链路上；bitstream 直通
 * 把压缩帧原样交给功放，不经过处理器——所以调延迟前先看这里。
 *
 * 判定口径：
 * - 直通关闭 → 强制解码为 PCM；
 * - 音轨本身是 PCM（LPCM/FLAC 等）→ 必然 PCM；
 * - 压缩音轨且设备不支持该编码直通 → Media3 回退解码为 PCM；
 * - 压缩音轨 + 直通开 + 设备支持 → bitstream 直通。
 *
 * @param codec 当前音轨的 MIME/codec 字符串（如 `audio/ac3`）；null 视为未知。
 * @param passthrough 用户是否开启音频直通。
 * @param deviceSupports 设备是否具备压缩音频直通能力。
 * @param pcmText PCM 路径文案。
 * @param bitstreamText 直通路径文案。
 * @param pcmFallbackText 设备不支持直通而回退解码的文案。
 */
fun audioOutputLabel(
    codec: String?,
    passthrough: Boolean,
    deviceSupports: Boolean,
    pcmText: String,
    bitstreamText: String,
    pcmFallbackText: String,
): String {
    val lower = codec.orEmpty().lowercase()
    val isCompressed = COMPRESSED_CODEC_HINTS.any { lower.contains(it) }
    return when {
        !passthrough -> pcmText
        !isCompressed -> pcmText
        !deviceSupports -> pcmFallbackText
        else -> bitstreamText
    }
}

/** 压缩音频编码的 MIME/codec 关键字（命中即视为 bitstream 候选）。 */
private val COMPRESSED_CODEC_HINTS = listOf(
    "ac3", "ec-3", "eac3", "truehd", "mlp", "dts", "dts-hd", "dtsx", "opus", "mpegh",
)

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
    val selectedIndex = (0 until selected.length).first { selected.isTrackSelected(it) }
    val format = selected.getTrackFormat(selectedIndex)
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
    val selected = groups[index]
    val selectedIndex = (0 until selected.length).first { selected.isTrackSelected(it) }
    val format = selected.getTrackFormat(selectedIndex)
    return format.label ?: format.language?.takeIf { it != "und" } ?: "$fallback ${index + 1}"
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
