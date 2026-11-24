package com.novum.tv.data.repository

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 弹幕协议解析测试。
 *
 * 覆盖 dandanplay v2 的 match/search/bangumi/comment 四个响应结构，
 * 以及播放计划从 MediaSource 提取匹配文件名的规则。
 */
class DanmuRepositoryParseTest {

    // ---- parseComments：dandanplay comment 格式 ----

    @Test
    fun parsesCommentPField() {
        val response = buildJsonObject {
            putJsonArray("comments") {
                add(
                    buildJsonObject {
                        put("p", "12.5,1,16711680,0,25")
                        put("m", "前方高能")
                    },
                )
                add(
                    buildJsonObject {
                        put("p", "30,4,65280")
                        put("m", "底部弹幕")
                    },
                )
            }
        }
        val comments = parseComments(response)

        assertEquals(2, comments.size)
        assertEquals(12_500L, comments[0].timeMs)
        assertEquals(1, comments[0].mode)
        assertEquals(0xFFFF0000L, comments[0].color)
        assertEquals("前方高能", comments[0].text)
        assertEquals(30_000L, comments[1].timeMs)
        assertEquals(4, comments[1].mode)
        assertEquals(0xFF00FF00L, comments[1].color)
    }

    @Test
    fun sortsCommentsByTime() {
        val response = buildJsonObject {
            putJsonArray("comments") {
                add(buildJsonObject { put("p", "20,1,16777215"); put("m", "later") })
                add(buildJsonObject { put("p", "5,1,16777215"); put("m", "earlier") })
            }
        }
        val comments = parseComments(response)
        assertEquals(listOf(5_000L, 20_000L), comments.map { it.timeMs })
    }

    @Test
    fun dropsInvalidRows() {
        val response = buildJsonObject {
            putJsonArray("comments") {
                // 负时间 → 丢
                add(buildJsonObject { put("p", "-1,1,16777215"); put("m", "neg") })
                // 协议外模式（2/3 不在 1/4/5）→ 丢
                add(buildJsonObject { put("p", "5,2,16777215"); put("m", "bad-mode") })
                // 缺文本 → 丢
                add(buildJsonObject { put("p", "5,1,16777215") })
                // 纯空白文本 → 丢
                add(buildJsonObject { put("p", "5,1,16777215"); put("m", "   ") })
                // 非法 p 段 → 丢
                add(buildJsonObject { put("p", "abc,1,16777215"); put("m", "bad-time") })
                // 合法行保留
                add(buildJsonObject { put("p", "5,1,16777215"); put("m", "keep") })
            }
        }
        val comments = parseComments(response)
        assertEquals(listOf("keep"), comments.map { it.text })
    }

    @Test
    fun sanitizesControlCharsAndTruncates() {
        val response = buildJsonObject {
            putJsonArray("comments") {
                add(
                    buildJsonObject {
                        put("p", "5,1,16777215")
                        put("m", "line1\nline2\u0000\u0007  " + "长".repeat(200))
                    },
                )
            }
        }
        val text = parseComments(response).single().text
        assertFalse(text.contains("\n"))
        assertFalse(text.contains("\u0000"))
        assertEquals(MAX_TEXT_LENGTH, text.length)
        assertTrue(text.startsWith("line1line2"))
    }

    @Test
    fun rejectsOversizedCommentLists() {
        val response = buildJsonObject {
            putJsonArray("comments") {
                repeat(MAX_COMMENTS + 1) {
                    add(buildJsonObject { put("p", "5,1,16777215"); put("m", "x") })
                }
            }
        }
        var thrown: Exception? = null
        try {
            parseComments(response)
        } catch (error: IllegalArgumentException) {
            thrown = error
        }
        assertTrue(thrown != null)
    }

    @Test
    fun missingCommentsArrayThrows() {
        var thrown: Exception? = null
        try {
            parseComments(buildJsonObject { })
        } catch (error: IllegalStateException) {
            thrown = error
        }
        assertTrue(thrown != null)
    }

    // ---- parseMatch ----

    @Test
    fun parsesMatchedEpisode() {
        val response = buildJsonObject {
            put("isMatched", true)
            putJsonArray("matches") {
                add(
                    buildJsonObject {
                        put("episodeId", "98765")
                        put("episodeTitle", "第1话 开始")
                        put("animeTitle", "某动画")
                    },
                )
            }
        }
        val match = parseMatch(response)
        assertEquals("98765", match?.episodeId)
        assertEquals("第1话 开始", match?.title)
        assertEquals("某动画", match?.animeTitle)
    }

    @Test
    fun numericEpisodeIdCoercesToString() {
        val response = buildJsonObject {
            put("isMatched", true)
            putJsonArray("matches") {
                add(buildJsonObject { put("episodeId", 42) })
            }
        }
        assertEquals("42", parseMatch(response)?.episodeId)
    }

    @Test
    fun unmatchedReturnsNull() {
        assertNull(
            parseMatch(
                buildJsonObject {
                    put("isMatched", false)
                    putJsonArray("matches") { }
                },
            ),
        )
    }

    // ---- parseAnimes / parseEpisodes ----

    @Test
    fun parsesAnimeList() {
        val response = buildJsonObject {
            putJsonArray("animes") {
                add(buildJsonObject { put("bangumiId", "300000"); put("animeTitle", "某动画") })
                // 缺 id → 丢
                add(buildJsonObject { put("animeTitle", "无 id") })
            }
        }
        val animes = parseAnimes(response)
        assertEquals(1, animes.size)
        assertEquals("300000", animes[0].bangumiId)
        assertEquals("某动画", animes[0].title)
    }

    @Test
    fun parsesEpisodeList() {
        val response = buildJsonObject {
            putJsonObject("bangumi") {
                putJsonArray("episodes") {
                    add(buildJsonObject { put("episodeId", "1"); put("episodeTitle", "第1话") })
                    add(buildJsonObject { put("episodeId", "2"); put("episodeTitle", "第2话") })
                }
            }
        }
        val episodes = parseEpisodes(response)
        assertEquals(listOf("1", "2"), episodes.map { it.episodeId })
        assertEquals("第1话", episodes[0].title)
    }

    @Test
    fun missingBangumiReturnsEmpty() {
        assertTrue(parseEpisodes(buildJsonObject { }).isEmpty())
    }

    // ---- danmuFileName（MediaSourceDto 扩展）----

    @Test
    fun extractsFileNameFromPath() {
        val source = com.novum.tv.data.remote.emby.MediaSourceDto(
            Id = "1",
            Path = "/media/Movies/Foo (2024)/Foo.2024.2160p.WEB-DL.mkv",
        )
        assertEquals("Foo.2024.2160p.WEB-DL.mkv", source.danmuFileName())
    }

    @Test
    fun fallsBackToNameWhenPathMissing() {
        val source = com.novum.tv.data.remote.emby.MediaSourceDto(Id = "1", Name = "4K HDR")
        assertEquals("4K HDR", source.danmuFileName())
    }

    @Test
    fun stripsQueryFromPath() {
        val source = com.novum.tv.data.remote.emby.MediaSourceDto(Id = "1", Path = "/play/video/abc.m3u8?token=secret")
        assertEquals("abc.m3u8", source.danmuFileName())
    }

    @Test
    fun blankFieldsReturnNull() {
        assertNull(com.novum.tv.data.remote.emby.MediaSourceDto(Id = "1").danmuFileName())
    }
}
