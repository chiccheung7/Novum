package com.novum.tv.data.line

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 线路解析测试。
 *
 * `/System/Ext/ServerDomains` 的返回有两级解析：
 * 1. 官方结构（docs.uhdnow.com/partner/emby-ext-domains）：`{ ok, data: [{ name, url }] }`；
 * 2. 兜底：不依赖字段名，只认 http(s) 字符串（结构随服务端版本变化）。
 *
 * 这里用多种差异很大的返回形态证明两条路径各自的契约。
 */
class LineProbeParseTest {

    private fun json(text: String) = Json.parseToJsonElement(text)

    @Test
    fun parsesOfficialStructureWithNameAndUrl() {
        // 官方结构：ok + data[].name/url，name 进 ServerLine 供展示
        val payload = json(
            """
            {
              "data": [
                { "name": "香港线路", "url": "https://hk.example.com" },
                { "name": "美国线路", "url": "https://us.example.com:8888/" }
              ],
              "ok": true
            }
            """.trimIndent(),
        )

        val lines = linesFromJson(payload, "http://current:8096")

        assertEquals(
            listOf(
                ServerLine("https://hk.example.com", 0L, "香港线路"),
                ServerLine("https://us.example.com:8888", 0L, "美国线路"),
                ServerLine("http://current:8096", 0L),
            ),
            lines,
        )
    }

    @Test
    fun officialStructureWithoutOkFallsBackToRecursive() {
        // 缺 ok 字段不算官方结构 → 走递归兜底（无 name）
        val payload = json(
            """{"data": [{"name": "x", "url": "https://line.example.com"}]}""",
        )

        val lines = linesFromJson(payload, "http://current:8096")

        assertEquals(
            listOf(
                ServerLine("https://line.example.com", 0L),
                ServerLine("http://current:8096", 0L),
            ),
            lines,
        )
        assertNull(lines.first().name)
    }

    @Test
    fun officialStructureWithoutDataFallsBackToRecursive() {
        // ok:true 但 data 不是数组（如新版本改字段）→ 递归兜底仍能抓到 URL
        val payload = json("""{"ok":true,"domains":["https://a.example.com"]}""")

        val lines = linesFromJson(payload, "http://current:8096")

        assertEquals(
            listOf(
                ServerLine("https://a.example.com", 0L),
                ServerLine("http://current:8096", 0L),
            ),
            lines,
        )
    }

    @Test
    fun extractsUrlsFromNestedStructure() {
        val payload = json(
            """
            {
              "Domains": ["https://line1.example.com", "https://line2.example.com"],
              "Meta": { "Backup": { "url": "http://line3.example.com:8096/" } }
            }
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                ServerLine("https://line1.example.com", 0L),
                ServerLine("https://line2.example.com", 0L),
                ServerLine("http://line3.example.com:8096", 0L),
                ServerLine("http://current:8096", 0L),
            ),
            linesFromJson(payload, "http://current:8096"),
        )
    }

    @Test
    fun ignoresUnknownStructureButKeepsServer() {
        // 结构完全认不出（甚至不是线路列表）时，至少要保住当前服务器
        val payload = json("""{"ok":true,"count":3,"note":"no urls here"}""")

        assertEquals(
            listOf(ServerLine("http://current:8096", 0L)),
            linesFromJson(payload, "http://current:8096"),
        )
    }

    @Test
    fun deduplicatesAndTrimsTrailingSlash() {
        // 官方结构去重：重复 URL（含尾斜杠变体）只留一条
        val payload = json(
            """
            {
              "data": [
                { "name": "A", "url": "https://same:8096/" },
                { "name": "B", "url": "https://same:8096" }
              ],
              "ok": true
            }
            """.trimIndent(),
        )

        assertEquals(
            listOf(ServerLine("https://same:8096", 0L, "A")),
            linesFromJson(payload, "https://same:8096/"),
        )
    }

    @Test
    fun ignoresNonHttpStringsAndNonStringPrimitives() {
        val payload = json(
            """
            {"name":"line-a","port":8096,"enabled":true,"path":"/emby","ws":"ws://line.example.com"}
            """.trimIndent(),
        )

        assertEquals(
            listOf(ServerLine("http://current:8096", 0L)),
            linesFromJson(payload, "http://current:8096"),
        )
    }

    @Test
    fun serverIsAlwaysLastSoFallbackSurvives() {
        val payload = json("""["https://fast.example.com"]""")
        val lines = linesFromJson(payload, "http://current:8096")

        assertEquals("http://current:8096", lines.last().baseUrl)
    }
}
