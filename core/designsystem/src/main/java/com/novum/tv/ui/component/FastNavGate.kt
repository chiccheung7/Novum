package com.novum.tv.ui.component

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue

/**
 * 快扫进行中信号：按导航 KeyDown 间隔判断是否快速移动焦点。
 * Coil 拦截器在快扫窗口内延后未命中内存缓存的图片请求；解码与网络请求不占用滚动关键路径。
 * 窗口由 State 驱动，过期时可触发订阅者重算。
 * 相邻按键间隔不超过 [FAST_KEY_INTERVAL_MS] 即判为快扫，窗口时长见 [CLEAR_DELAY_MS]。
 */
object NovumFastNav {

    /** 快扫窗口到期时刻（uptime ms）；0 = 从未快扫。快照状态，见类 KDoc。 */
    var activeUntilMs by mutableLongStateOf(0L)
        private set

    /** 上一次导航 KeyDown 的时刻（uptime ms）；0 = 尚无。 */
    private var lastNavKeyMs = 0L

    /** 快扫窗口：最后一次触发后多久内仍视为「快扫中」。 */
    private const val CLEAR_DELAY_MS = 450L

    /** 相邻导航键间隔 ≤ 此值（ms）判定为快扫键流。 */
    private const val FAST_KEY_INTERVAL_MS = 300L

    /**
     * 每次导航 KeyDown（非 repeat）调用一次：间隔够近即判快扫并延长窗口。
     *
     * @return true = 本次键入被判定为快扫键流的一部分。
     */
    fun onNavKeyDown(nowMs: Long = SystemClock.uptimeMillis()): Boolean {
        val interval = nowMs - lastNavKeyMs
        lastNavKeyMs = nowMs
        val isFastScan = interval in 1..FAST_KEY_INTERVAL_MS
        if (isFastScan) poke(nowMs)
        return isFastScan
    }

    /** repeat 键序内每次按键调用：延长快扫窗口。 */
    fun poke(nowMs: Long = SystemClock.uptimeMillis()) {
        activeUntilMs = maxOf(activeUntilMs, nowMs + CLEAR_DELAY_MS)
    }

    /** 当前是否处于快扫窗口。 */
    fun isActive(nowMs: Long = SystemClock.uptimeMillis()): Boolean = nowMs < activeUntilMs
}
