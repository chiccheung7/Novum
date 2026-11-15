package com.novum.tv.core.dispatcher

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * 协程调度器的可注入抽象。
 *
 * 存在的意义：单元测试可以替换为 `StandardTestDispatcher`，让所有协程在测试线程上确定性执行，
 * 避免 `Dispatchers.IO`/`Main` 带来的时序不确定与 `runBlocking` 污染。
 */
interface DispatcherProvider {

    /** CPU 密集型任务（解析、排序、评分计算）。 */
    val default: CoroutineDispatcher

    /** 磁盘与网络 IO。 */
    val io: CoroutineDispatcher

    /** 主线程（Compose 状态写入）。 */
    val main: CoroutineDispatcher
}

/**
 * 生产环境实现：直接使用 Kotlin 协程标准调度器。
 *
 * 说明：TV 设备普遍是低算力盒子，禁止在 [default] 上做长耗时计算；
 * 超过 16ms 的纯计算应切到 [io] 或自行拆分。
 */
class DefaultDispatcherProvider : DispatcherProvider {
    override val default: CoroutineDispatcher = Dispatchers.Default
    override val io: CoroutineDispatcher = Dispatchers.IO
    override val main: CoroutineDispatcher = Dispatchers.Main.immediate
}
