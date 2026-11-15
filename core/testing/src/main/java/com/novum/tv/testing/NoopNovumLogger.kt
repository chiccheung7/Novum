package com.novum.tv.testing

import com.novum.tv.core.log.NovumLogger

/**
 * 测试用空日志，供不需要断言日志内容的构造点使用。
 */
object NoopNovumLogger : NovumLogger {

    override val isEnabled: Boolean = false

    override fun d(tag: String, message: String) = Unit

    override fun w(tag: String, message: String, throwable: Throwable?) = Unit

    override fun e(tag: String, message: String, throwable: Throwable?) = Unit
}
