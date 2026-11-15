package com.novum.tv.ui.component

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import com.novum.tv.testing.RepoPaths

/**
 * UI 文案契约测试：用户可见文案集中在 core/designsystem strings.xml。
 *
 * 豁免：@Preview 示例、服务端数据匹配词表、仓储日志，以及脱敏后进入 logcat 的诊断文案。
 */
class UiCopyContractTest {

    /** 仓库根目录。 */
    private val projectRoot: File = RepoPaths.root

    /** 参与扫描的 UI 源根：app 主源集 + 全部 feature 模块主源集。 */
    private val uiSourceRoots: List<File> = buildList {
        add(File(projectRoot, "app/src/main/java"))
        File(projectRoot, "feature").listFiles()?.forEach { module ->
            add(File(module, "src/main/java"))
        }
    }.filter { it.exists() }

    /** 判别词表白名单（文件名）：匹配服务端数据的逻辑词表，非展示文案。 */
    private val keywordListFiles = setOf("PlayerTracks.kt", "PlayerSkip.kt")

    @Test
    fun uiFilesMustNotHardcodeChineseCopy() {
        val offenders = mutableListOf<String>()

        uiSourceRoots
            .filter { it.exists() }
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .filter { it.name !in keywordListFiles }
            .forEach { file ->
                // 预览数据不上设备：剥掉 @Preview 函数体再扫
                val text = stripPreviewFunctions(file.readText())
                text.lineSequence().forEachIndexed { i, line ->
                    val trimmed = line.trimStart()
                    val isComment = trimmed.startsWith("//") || trimmed.startsWith("*") ||
                        trimmed.startsWith("/*")
                    // 日志是诊断输出（脱敏后进 logcat），不是用户可见文案——
                    // 与 dataRepositoriesMustNotHardcodeDisplayCopy 同口径豁免
                    val exempt = "logger." in line
                    if (!isComment && !exempt && CJK_IN_LITERAL.containsMatchIn(line)) {
                        offenders += "${file.relativeTo(projectRoot)}:${i + 1}"
                    }
                }
            }

        assertTrue(
            "用户可见文案请进 strings.xml（资源层），以下 UI 源码里出现硬编码中文：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /**
     * 数据层仓储的展示文案必须经 [com.novum.tv.data.text.MediaStrings]。三处豁免：`logger.` 日志诊断、`Regex(` 服务端数据
     * 判别（如集名「第 N 集」判重）、以及 `*ForLog` 后缀函数（仅产日志文案，如
     * DefaultPlaybackRepository 的 unsupportedReasonForLog）——都不面向用户。
     */
    @Test
    fun dataRepositoriesMustNotHardcodeDisplayCopy() {
        val repositoryDir = File(projectRoot, "core/data/src/main/java/com/novum/tv/data/repository")
        val offenders = mutableListOf<String>()

        repositoryDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name.startsWith("Default") }
            .forEach { file ->
                // ForLog 函数 = 仅产日志文案（后缀即豁免标记），剥掉再扫
                val text = stripForLogFunctions(file.readText())
                text.lineSequence().forEachIndexed { i, line ->
                    val trimmed = line.trimStart()
                    val isComment = trimmed.startsWith("//") || trimmed.startsWith("*") ||
                        trimmed.startsWith("/*")
                    val exempt = "logger." in line || "Regex(" in line
                    if (!isComment && !exempt && CJK_IN_LITERAL.containsMatchIn(line)) {
                        offenders += "${file.relativeTo(projectRoot)}:${i + 1}"
                    }
                }
            }

        assertTrue(
            "仓储展示文案请经 MediaStrings（资源层），日志/判别正则/ForLog 函数除外：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /** 删除所有 @Preview 注解所标函数的整个函数体（预览数据不上设备）。 */
    private fun stripPreviewFunctions(text: String): String {
        var result = text
        while (true) {
            val previewAt = result.indexOf("@Preview")
            if (previewAt < 0) break
            // 从注解起找函数体的开括号（跨过的内容含注解参数、签名），再配对闭括号
            val open = result.indexOf('{', previewAt)
            if (open < 0) break
            val close = matchingBrace(result, open) ?: break
            result = result.substring(0, previewAt) + result.substring(close + 1)
        }
        return result
    }

    /** 删除所有 `*ForLog` 后缀函数体（后缀即「仅日志文案」豁免标记）。 */
    private fun stripForLogFunctions(text: String): String {
        var result = text
        while (true) {
            val m = FOR_LOG_FUNCTION.find(result) ?: break
            val open = result.indexOf('{', m.range.last)
            if (open < 0) break
            val close = matchingBrace(result, open) ?: break
            result = result.substring(0, m.range.first) + result.substring(close + 1)
        }
        return result
    }

    /** 找到与 [openIndex] 处 `{` 配对的 `}` 下标（带字符串感知，足够应付本项目的调用形态）。 */
    private fun matchingBrace(text: String, openIndex: Int): Int? {
        var depth = 0
        var inString = false
        for (i in openIndex until text.length) {
            val c = text[i]
            when {
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }

    private companion object {
        /** 字符串字面量内含 CJK 字符（Unicode CJK 统一表意区即可覆盖本项目文案）。 */
        val CJK_IN_LITERAL = Regex(""""[^"]*[\u4e00-\u9fff][^"]*"""")

        /** `*ForLog` 后缀函数声明（含扩展函数接收者 `fun Receiver.nameForLog(`；后缀即「仅日志文案」豁免标记）。 */
        val FOR_LOG_FUNCTION = Regex("""fun\s+[\w.]*ForLog\s*\(""")
    }
}
