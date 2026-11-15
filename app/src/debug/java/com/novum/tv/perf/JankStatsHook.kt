package com.novum.tv.perf

import android.util.Log
import android.view.Window
import androidx.metrics.performance.JankStats

/**
 * JankStats 帧耗时采样：debug 变体将超过 20ms 的帧记录到 logcat。
 * 调用方使用源集同名函数；release 变体提供不依赖 JankStats 的 no-op。
 */
fun attachJankStats(window: Window) {
    // 500ms 节流：监听回调在帧回调线程上，逐帧写 logcat 自己就会制造卡顿
    var lastLogAt = 0L
    JankStats.createAndTrack(
        window,
        JankStats.OnFrameListener { frameData ->
            val now = System.nanoTime()
            if (frameData.frameDurationUiNanos > JANK_LOG_THRESHOLD_NANOS &&
                now - lastLogAt > JANK_LOG_THROTTLE_NANOS
            ) {
                lastLogAt = now
                Log.i(
                    "JankStats",
                    "jank ${frameData.frameDurationUiNanos / 1_000_000}ms " +
                        "states=${frameData.states}",
                )
            }
        },
    )
}

/** 帧耗时告警阈值：60Hz 一帧 16.7ms，超 20ms 才记（减少长按连按时的噪音）。 */
private const val JANK_LOG_THRESHOLD_NANOS = 20_000_000L

/** 同类告警的最小间隔：500ms（debug 构建上采样自身要轻）。 */
private const val JANK_LOG_THROTTLE_NANOS = 500_000_000L
