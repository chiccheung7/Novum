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
    fun masksQuotedTokenValues() {
        assertFalse(LogRedaction.redact("api_key=\"abc123DEF456\"").contains("abc123DEF456"))
        assertFalse(LogRedaction.redact("token='secret-value'").contains("secret-value"))
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
    fun masksTokenInJsonBody() {
        // 登录响应的 AccessToken 常被整段打进日志：键是带引号的 JSON 键、分隔符是冒号，
        // 与查询串的 key=value 不是同一形态，必须单独覆盖
        val raw = """{"User":{"Name":"alice"},"AccessToken":"0f1e2d3c4b5a","ServerId":"srv-1"}"""
        val safe = LogRedaction.redact(raw)

        assertFalse(safe.contains("0f1e2d3c4b5a"))
        assertTrue(safe.contains("\"AccessToken\":***"))
        // 非敏感字段保持原样，日志仍可读
        assertTrue(safe.contains("alice"))
        assertTrue(safe.contains("srv-1"))
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

    @Test
    fun masksDanmuPathToken() {
        // UHDNOW 弹幕端点的 token 在 URL 路径里：/api/v1/danmu/{token}/api/v2/…
        val raw = "GET https://smart.uhdnow.com/api/v1/danmu/0f1e2d3c-SECRET/api/v2/match failed"
        val safe = LogRedaction.redact(raw)

        assertFalse(safe.contains("0f1e2d3c-SECRET"))
        assertTrue(safe.contains("/api/v1/danmu/***/api/v2/match"))
        // 诊断信息保留
        assertTrue(safe.contains("failed"))
    }

    @Test
    fun keepsUrlsWithoutDanmuPathUntouched() {
        val raw = "GET https://smart.uhdnow.com/emby/Users/1/Items 200"
        assertEquals(raw, LogRedaction.redact(raw))
    }
}
