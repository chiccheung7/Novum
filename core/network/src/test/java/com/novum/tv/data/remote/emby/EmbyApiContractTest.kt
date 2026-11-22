package com.novum.tv.data.remote.emby

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import com.novum.tv.testing.RepoPaths

/**
 * EmbyApi 接口契约测试。
 *
 * 约束 Retrofit 方法保持抽象，且 EPISODE_FIELDS 包含界面消费的简介、时长、进度、
 * 缩略图和版本字段。
 */
class EmbyApiContractTest {

/** 仓库根目录。 */
    private val projectRoot: File = RepoPaths.root

    private val apiFile: File =
        projectRoot.resolve("core/network/src/main/java/com/novum/tv/data/remote/emby/EmbyApi.kt")

    @Test
    fun retrofitMethodsMustNotCarryDefaultBodies() {
        assertTrue("找不到 EmbyApi.kt：${apiFile.path}", apiFile.exists())
        // 去掉块注释后再扫：KDoc 里的 `{uid}` 占位、@param 说明都不是方法体
        val text = apiFile.readText()
            .replace(Regex("""/\*\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")

        val offenders = mutableListOf<String>()
        val funStart = Regex("""\bfun\s+([A-Za-z_]\w*)\s*\(""")
        funStart.findAll(text).forEach { match ->
            val openParen = text.indexOf('(', match.range.first)
            val closeParen = findClosingParen(text, openParen) ?: return@forEach
            // 声明尾：闭括号所在行的余下部分（含 `: 返回类型`）；
            // 方法体只可能是同行 `= … / { …` 或紧随的下一非空行
            val tail = text.substring(closeParen + 1)
            val sameLine = tail.substringBefore('\n')
            val nextMeaningfulLine = tail.substringAfter('\n', "")
                .lineSequence()
                .firstOrNull { it.isNotBlank() }
                ?.trim()
                .orEmpty()
            val hasBody = sameLine.contains('=') || sameLine.contains('{') ||
                nextMeaningfulLine.startsWith("=") || nextMeaningfulLine.startsWith("{")
            if (hasBody) {
                offenders += "${match.groupValues[1]}()"
            }
        }
        assertTrue(
            "Retrofit 接口方法不得带 Kotlin 默认实现——Retrofit 不会为带体方法生成" +
                "代理实现，请求从不发出（§4.96）：\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun nextUpMustPassUserIdAsQueryParameter() {
        val text = apiFile.readText()
        val start = text.indexOf("suspend fun nextUp(")
        assertTrue("找不到 nextUp 定义", start >= 0)
        val declaration = text.substring(start, (text.indexOf("):", start) + 2).coerceAtLeast(start + 40))
        assertTrue(
            "Shows/NextUp 没有 {uid} 路径占位，userId 必须是 Query 参数：" + declaration,
            declaration.contains("@Query(\"UserId\")"),
        )
        assertTrue(
            "nextUp 不得用 @Path(\"uid\")——运行时会抛 URL 异常并被静默吞掉：" + declaration,
            !declaration.contains("@Path(\"uid\")"),
        )
    }

    @Test
    fun episodeFieldsMustCarryUiConsumedFields() {
        // 消费点：Overview→选集卡简介、RunTimeTicks→片长/剩余时长、UserData→进度与已看、
        // ImageTags→分集缩略图、MediaSources→单集多版本入口
        listOf("Overview", "RunTimeTicks", "UserData", "ImageTags", "MediaSources").forEach { field ->
            assertTrue(
                "EPISODE_FIELDS 缺少 UI 消费字段 $field（漏字段的症状是界面上对应区块静默变空）：" +
                    EmbyApi.EPISODE_FIELDS,
                EmbyApi.EPISODE_FIELDS.contains(field),
            )
        }
    }

    private fun findClosingParen(text: String, openIndex: Int): Int? {
        var depth = 0
        for (index in openIndex until text.length) {
            when (text[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return null
    }
}
