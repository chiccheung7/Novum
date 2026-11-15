package com.novum.tv.ui.screen.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 缓冲网速格式化测试（单位改 MB/s）。
 *
 * 核心口径：[androidx.media3.exoplayer.upstream.DefaultBandwidthMeter] 的
 * `bitrateEstimate` 是**比特/秒**，网络传输习惯用**字节**——÷8 换算
 * 1 MB/s 对应 8 Mbps。边界逐条钉住，防止单位换算回潮。
 */
class PlayerFormatTest {

    @Test
    fun convertsBitsToMegabytes() {
        // 8 Mbps = 1 MB/s
        assertEquals("1.0 MB/s", formatSpeed(8_000_000L))
        // 27.4 Mbps ≈ 3.4 MB/s（宽带典型值）
        assertEquals("3.4 MB/s", formatSpeed(27_450_000L))
    }

    @Test
    fun fallsBackToKilobytesBelowOneMegabyte() {
        // 6.8 Mbps = 850 KB/s
        assertEquals("850 KB/s", formatSpeed(6_800_000L))
    }

    @Test
    fun zeroBandwidthFallsBackToKilobytesNotNegative() {
        // 起播首轮无估计值：调用方不显示文案，但函数本身对 0 不能产出异常值
        assertEquals("0 KB/s", formatSpeed(0L))
    }
}
