package com.novum.tv.perf

import android.view.Window

/**
 * JankStats 帧采样钩子：release 变体为 no-op。
 *
 * JankStats 仅在 debugImplementation 中，main 源集不能引用 androidx.metrics。
 * debug/release 各提供一份同名顶层函数，main 不重复声明，避免冲突重载。
 */
fun attachJankStats(window: Window) {
    // release 零开销：不挂任何监听
}
