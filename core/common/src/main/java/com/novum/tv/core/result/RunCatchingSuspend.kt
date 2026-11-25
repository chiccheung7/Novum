package com.novum.tv.core.result

import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching] 的可挂起安全版：CancellationException 原样重抛，其余异常包成失败结果。
 *
 * 为什么不用 [runCatching]：它把协程取消也当成普通失败吞掉——调用方拿到「失败结果」
 * 后继续执行，轻则多跑一段无用逻辑，重则把取消路径记成业务错误（如给已取消的
 * 会话写偏好、给已取消的页面弹失败提示）。所有包裹 **suspend 调用** 的容错都用本函数；
 * 纯本地计算（JSON 解码、文件读写等不经过挂起点）仍可用 [runCatching]。
 */
inline fun <T> runCatchingSuspend(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (throwable: Throwable) {
    Result.failure(throwable)
}
