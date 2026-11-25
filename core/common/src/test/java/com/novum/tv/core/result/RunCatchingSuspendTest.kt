package com.novum.tv.core.result

import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * runCatchingSuspend 契约：CancellationException 必须原样重抛——
 * 被取消的协程不允许拿着「失败结果」继续跑（旧 runCatching 的缺陷）。
 */
class RunCatchingSuspendTest {

    @Test
    fun returnsSuccessValue() {
        assertEquals("ok", runCatchingSuspend { "ok" }.getOrNull())
    }

    @Test
    fun wrapsRegularExceptionsAsFailure() {
        val result = runCatchingSuspend<String> { throw IOException("io") }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IOException)
    }

    @Test
    fun rethrowsCancellationInsteadOfCapturingIt() {
        assertThrows(CancellationException::class.java) {
            runCatchingSuspend<String> { throw CancellationException("cancelled") }
        }
    }
}
