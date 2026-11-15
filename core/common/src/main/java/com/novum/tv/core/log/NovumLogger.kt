package com.novum.tv.core.log

import android.util.Log

/**
 * 统一日志门面。
 *
 * 之所以不直接调用 [Log]：单元测试（JVM/Robolectric）里可以注入内存实现来断言关键路径，
 * 发布包也能通过 [isEnabled] 一次性关掉调试日志，避免电视端 logcat 被高频播放日志刷屏。
 */
interface NovumLogger {

    /** 调试日志，仅 [isEnabled] 为 true 时输出。 */
    fun d(tag: String, message: String)

    /** 警告日志，始终输出。 */
    fun w(tag: String, message: String, throwable: Throwable? = null)

    /** 错误日志，始终输出。 */
    fun e(tag: String, message: String, throwable: Throwable? = null)

    /** 是否输出调试日志（Release 关闭）。 */
    val isEnabled: Boolean
}

/**
 * 基于 Android [Log] 的实现。
 *
 * 所有文本在写出前统一过 [LogRedaction]：Emby 的 token 可能出现在 URL 的 `api_key`
 * 查询参数里，异常信息一打印就会把凭据写进 logcat。在这里做而不是让调用点各自处理，
 * 是因为"靠人记"的脱敏一定会漏。
 *
 * @param debugEnabled 是否输出调试级别日志；Release 构建传 false。
 */
class AndroidNovumLogger(debugEnabled: Boolean) : NovumLogger {

    override val isEnabled: Boolean = debugEnabled

    override fun d(tag: String, message: String) {
        if (isEnabled) Log.d(tag, LogRedaction.redact(message))
    }

    override fun w(tag: String, message: String, throwable: Throwable?) {
        val safe = LogRedaction.redact(message)
        if (throwable == null) Log.w(tag, safe) else Log.w(tag, safe, throwable)
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        val safe = LogRedaction.redact(message)
        if (throwable == null) Log.e(tag, safe) else Log.e(tag, safe, throwable)
    }
}
