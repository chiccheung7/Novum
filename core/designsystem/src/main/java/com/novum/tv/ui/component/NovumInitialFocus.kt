package com.novum.tv.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * 页面初始焦点抓取。
 *
 * 问题：NavHost 切页（顶栏 OK 切页 / 详情返回）会销毁旧目的地的组合，
 * 新页面若无抓焦逻辑，窗口焦点会回落到层次最上方的可聚焦项——顶栏页签，
 * 表现为「按了页签焦点还停在页签上」。
 *
 * 用法：页面内容首项挂 [FocusRequester]，本副作用在组合后反复尝试抓焦
 * （每帧一查 + 100ms 兜底，最多约 2 秒）：目标可能晚于副作用完成组合
 * （异步加载的首卡、focusRequester 延迟挂接），未挂接或未接受焦点时继续重试。
 *
 * 默认 `requester` 实例跨重组稳定，因此每次进入组合只抓一次；
 * 需要「数据到位后再抓」的场景传显式 key（如 PosterGrid 的 items 非空）。
 *
 * @param requester 目标焦点索取器（应已挂到内容首项）。
 * @param key 重跑键；默认每次进入本组合只执行一轮。
 */
@Composable
fun NovumInitialFocus(requester: FocusRequester, key: Any? = requester) {
    val inputModeManager = LocalInputModeManager.current
    LaunchedEffect(key) {
        // A Dialog is a separate Android window. On TV/Android 15 the first composition can
        // happen before that window has received focus, so a single post-frame request is
        // legitimately rejected. Keep trying for a short, bounded hand-off window; the loop
        // exits immediately when the requester accepts focus.
        repeat(INITIAL_FOCUS_ATTEMPTS) {
            if (!isActive) return@LaunchedEffect
            // Dialog owns another input manager and Android may restore its initial touch
            // mode after the first composition. Request keyboard mode immediately before
            // each attempt so the focus request is not discarded by that transition.
            inputModeManager.requestInputMode(InputMode.Keyboard)
            withFrameNanos { }
            if (runCatching { requester.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            delay(INITIAL_FOCUS_RETRY_DELAY_MS)
        }
    }
}

/** Maximum time spent handing focus from a newly-created page/dialog to its first item. */
private const val INITIAL_FOCUS_ATTEMPTS = 20
private const val INITIAL_FOCUS_RETRY_DELAY_MS = 100L
