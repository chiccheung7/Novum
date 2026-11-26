package com.novum.tv.ui.screen.player

import com.novum.tv.model.Chapter

/**
 * 播放页的纯格式化工具。
 *
 * 抽成单独文件是为了让控制条、进度条、信息面板共用同一套时间/倍速/码率口径，
 * 避免三个地方各写一份、改一处漏两处。
 */

/** 毫秒格式化为 `mm:ss` 或 `h:mm:ss`。 */
internal fun formatTime(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

/** 剩余时间：给进度条右侧用的 `-mm:ss`。 */
internal fun formatRemaining(positionMs: Long, durationMs: Long): String {
    val remaining = (durationMs - positionMs).coerceAtLeast(0L)
    return "-${formatTime(remaining)}"
}

/** 倍速展示：1 → `1.0×`。 */
internal fun speedLabel(speed: Float): String = "%.2f×".format(speed).replace(".00×", ".0×")

/** 码率展示：12.4 Mbps / 820 kbps。 */
internal fun formatBitrate(bps: Long): String =
    if (bps >= 1_000_000) "%.1f Mbps".format(bps / 1_000_000f) else "${bps / 1000} kbps"

/**
 * 网速展示（缓冲指示器下方）：3.4 MB/s / 850 KB/s。
 *
 * 与 [formatBitrate] 的口径区分（缓冲网速用 MB/s）：
 * **网络传输习惯用字节**（MB/s），**流媒体码率习惯用比特**（Mbps）——
 * 网络字节采样器输出比特/秒，这里 ÷8 转字节。
 * [PlayerSettingsDrawer] 画质 tab 的规格行继续走 [formatBitrate]。
 *
 * @param bps 实际接收字节采样换算的比特/秒。
 */
internal fun formatSpeed(bps: Long): String {
    val bytesPerSec = bps / 8f
    return if (bytesPerSec >= 1_000_000f) {
        "%.1f MB/s".format(bytesPerSec / 1_000_000f)
    } else {
        "%.0f KB/s".format(bytesPerSec / 1000f)
    }
}

/** 章节列表里当前位置对应的章节名。 */
internal fun List<Chapter>.currentChapterName(positionMs: Long): String? =
    lastOrNull { it.startMs <= positionMs }?.name
