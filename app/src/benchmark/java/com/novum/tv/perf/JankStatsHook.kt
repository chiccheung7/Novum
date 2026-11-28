package com.novum.tv.perf

import android.view.Window

/** Benchmark 与 Release 一样不启用逐帧日志，避免采样干扰测量。 */
@Suppress("UNUSED_PARAMETER")
fun attachJankStats(window: Window) = Unit
