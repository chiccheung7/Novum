package com.novum.tv.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.delay

/**
 * 页面初始焦点抓取。
 *
 * 问题：NavHost 切页（顶栏 OK 切页 / 详情返回）会销毁旧目的地的组合，
 * 新页面若无抓焦逻辑，窗口焦点会回落到层次最上方的可聚焦项——顶栏页签，
 * 表现为「按了页签焦点还停在页签上」。
 *
 * 用法：页面内容首项挂 [FocusRequester]，本副作用在组合后反复尝试抓焦
 * （每帧一查 + 100ms 兜底，最多 5 次）：目标可能晚于副作用完成组合
 * （异步加载的首卡、focusRequester 延迟挂接），runCatching 吞掉未挂接帧。
 *
 * 默认 `requester` 实例跨重组稳定，因此每次进入组合只抓一次；
 * 需要「数据到位后再抓」的场景传显式 key（如 PosterGrid 的 items 非空）。
 *
 * @param requester 目标焦点索取器（应已挂到内容首项）。
 * @param key 重跑键；默认每次进入本组合只执行一轮。
 */
@Composable
fun NovumInitialFocus(requester: FocusRequester, key: Any? = requester) {
    LaunchedEffect(key) {
        repeat(5) {
            withFrameNanos { }
            if (runCatching { requester.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(100)
        }
    }
}
