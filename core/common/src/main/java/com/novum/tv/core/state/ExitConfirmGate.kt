package com.novum.tv.core.state

/**
 * 首页「二次返回退出」的判定门。
 *
 * 电视上没有"关闭窗口"这种操作，一次误按返回就直接退出应用会让用户重走启动流程，
 * 所以采用主流做法：第一次按返回给出提示，[windowMs] 内再按一次才真正退出。
 *
 * 做成纯函数式的小对象，便于单元测试覆盖时间窗口边界。
 *
 * @param windowMs 两次返回之间的有效窗口（毫秒）。默认与 Toast 展示时长一致，
 *   避免出现"提示已经消失但窗口还开着"的错位。
 */
class ExitConfirmGate(private val windowMs: Long = DEFAULT_WINDOW_MS) {

    private var lastBackAtMs: Long? = null

    /**
     * 记录一次返回键，并判定是否应当退出。
     *
     * @param nowMs 单调时钟（`SystemClock.uptimeMillis()`），不要用墙钟——
     *   用户改系统时间会让墙钟跳变，导致窗口永远命中或永远不命中。
     * @return true 表示应当退出应用；false 表示只提示、不退出。
     */
    fun onBack(nowMs: Long): Boolean {
        val last = lastBackAtMs
        lastBackAtMs = nowMs
        if (last == null) return false
        val elapsed = nowMs - last
        // 时钟回拨（elapsed < 0）按"重新开始计时"处理，不退出
        return elapsed in 0..windowMs
    }

    /** 重置窗口：页面离开前台或用户做了别的操作后，旧的计时不应继续有效。 */
    fun reset() {
        lastBackAtMs = null
    }

    private companion object {
        /** 与 Toast 展示时长对齐（2.5s）。 */
        const val DEFAULT_WINDOW_MS = 2_500L
    }
}
