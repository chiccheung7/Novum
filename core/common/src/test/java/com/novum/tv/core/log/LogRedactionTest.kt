package com.novum.tv.core.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日志脱敏测试。
 *
 * 重点覆盖"token 藏在 URL 里"这条真实路径：Emby 的鉴权既能走请求头，也能塞进
 * `api_key` 查询参数，异常信息一打印就会把凭据写进 logcat。
 */
class LogRedactionTest {

    @Test
    fun masksApiKeyInUrl() {
        val raw = "GET http://smart.uhdnow.com/Items/1/Images/Primary?maxWidth=400&api_key=abc123DEF456 failed"
        val safe = LogRedaction.redact(raw)

        assertFalse(safe.contains("abc123DEF456"))
        assertTrue(safe.contains("api_key=***"))
        // 其余诊断信息必须保留，否则日志就失去意义
        assertTrue(safe.contains("maxWidth=400"))
        assertTrue(safe.contains("failed"))
    }

    @Test
    fun masksEmbyTokenHeader() {
        val raw = "headers: X-Emby-Token: 0f1e2d3c4b5a, Accept: application/json"
        val safe = LogRedaction.redact(raw)

        assertFalse(safe.contains("0f1e2d3c4b5a"))
        assertTrue(safe.contains("Accept: application/json"))
    }

    @Test
    fun masksPasswordAndGenericAuthorization() {
        assertFalse(LogRedaction.redact("password=hunter2").contains("hunter2"))
        assertFalse(
            LogRedaction.redact("Authorization: Bearer eyJhbGciOi.xxx").contains("eyJhbGciOi"),
        )
    }

    @Test
    fun masksUserInfoInUrl() {
        val safe = LogRedaction.redact("connect to http://alice:s3cr3t@demo.example.com:8096/emby")

        assertFalse(safe.contains("s3cr3t"))
        assertTrue(safe.contains("alice"))
        assertTrue(safe.contains("demo.example.com"))
    }

    @Test
    fun keepsHarmlessTextUntouched() {
        val raw = "线路排序: smart.uhdnow.com=32ms"
        assertEquals(raw, LogRedaction.redact(raw))
        assertEquals("", LogRedaction.redact(""))
    }

    @Test
    fun masksAllOccurrences() {
        val safe = LogRedaction.redact("a?api_key=k1 b?api_key=k2")

        assertFalse(safe.contains("k1"))
        assertFalse(safe.contains("k2"))
    }
}
