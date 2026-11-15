package com.novum.tv.core.result

import com.novum.tv.core.error.NovumError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 结果封装与错误模型的契约测试。
 *
 * 这两个类型被所有 Repository 使用，行为一旦变化会影响全应用，因此单独锁死语义。
 */
class NovumResultTest {

    @Test
    fun mapsSuccessValue() {
        val result: NovumResult<Int> = successOf(21)

        val mapped = result.map { it * 2 }

        assertEquals(42, mapped.valueOrNull())
        assertNull(mapped.errorOrNull())
    }

    @Test
    fun keepsFailureWhenMapping() {
        val error = NovumError.Network()
        val result: NovumResult<Int> = failureOf(error)

        val mapped = result.map { it * 2 }

        assertEquals(error, mapped.errorOrNull())
        assertNull(mapped.valueOrNull())
    }

    @Test
    fun retryableErrorsAreMarked() {
        assertTrue(NovumError.Network().isRetryable)
        assertTrue(NovumError.Server(code = 500).isRetryable)
        assertFalse(NovumError.Unauthorized().isRetryable)
        assertFalse(NovumError.NotFound().isRetryable)
    }
}
