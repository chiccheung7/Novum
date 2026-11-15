package com.novum.tv.ui.component

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import com.novum.tv.testing.RepoPaths

/**
 * 按钮契约测试：固定宽度按钮必须显式居中，按钮文案使用 [NovumType.ButtonLg]，
 * 按钮内不写死文字颜色；屏内返回动作由遥控器返回键与导航栈处理。
 */
class ButtonContractTest {

    /** 仓库根目录。 */
    private val projectRoot: File = RepoPaths.root

    private val sourceRoots: List<File> = RepoPaths.mainSourceDirs()

    @Test
    fun fixedWidthButtonsMustCenterContent() {
        val offenders = mutableListOf<String>()

        kotlinSources().forEach { file ->
            val text = file.readText()
            var cursor = 0
            while (true) {
                val start = text.indexOf("NovumButton(", cursor)
                if (start < 0) break
                cursor = start + 1
                val open = start + "NovumButton".length
                val args = balancedArguments(text, open) ?: continue
                val fixedWidth = Regex("""\.(width|size)\(""").containsMatchIn(args)
                if (fixedWidth && !args.contains("centerContent = true")) {
                    val line = text.substring(0, start).count { it == '\n' } + 1
                    offenders += "${file.relativeTo(projectRoot)}:$line"
                }
            }
        }

        assertTrue(
            "以下定宽 NovumButton 未开启 centerContent，文案会贴左：\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /** 交互组件不自绘细描边，焦点外观由共享材质组件统一负责。 */
    @Test
    fun nonFixedWidthButtonsMustNotCenterContent() {
        val offenders = mutableListOf<String>()

        kotlinSources().forEach { file ->
            val text = file.readText()
            var cursor = 0
            while (true) {
                val start = text.indexOf("NovumButton(", cursor)
                if (start < 0) break
                cursor = start + 1
                val open = start + "NovumButton".length
                val args = balancedArguments(text, open) ?: continue
                val fixedWidth = Regex("""\.(width|size)\(""").containsMatchIn(args)
                if (!fixedWidth && args.contains("centerContent = true")) {
                    val line = text.substring(0, start).count { it == '\n' } + 1
                    offenders += "${file.relativeTo(projectRoot)}:$line"
                }
            }
        }

        assertTrue(
            "以下不定宽 NovumButton 不应传 centerContent（内容 fillMaxWidth 会撑满整行，" +
                "把兄弟节点挤到零宽——PITFALLS M10）：\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun buttonLabelsUseButtonLgType() {
        val offenders = mutableListOf<String>()

        kotlinSources().forEach { file ->
            val text = file.readText()
            var cursor = 0
            while (true) {
                val start = text.indexOf("NovumButton(", cursor)
                if (start < 0) break
                cursor = start + 1
                val block = callBlock(text, start) ?: continue
                // BodyStrong 与 16sp Button 均不符合全站按钮字号规范。
                if (block.contains("NovumType.BodyStrong") ||
                    block.contains("NovumType.Button,") ||
                    block.contains("NovumType.Button)")
                ) {
                    val line = text.substring(0, start).count { it == '\n' } + 1
                    offenders += "${file.relativeTo(projectRoot)}:$line"
                }
            }
        }

        assertTrue(
            "按钮文案请用 NovumType.ButtonLg（全站统一 20sp/48dp，不要 BodyStrong/Button）：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun buttonLabelsMustNotHardcodeColor() {
        val offenders = mutableListOf<String>()

        kotlinSources().forEach { file ->
            val text = file.readText()
            var cursor = 0
            while (true) {
                val start = text.indexOf("NovumButton(", cursor)
                if (start < 0) break
                cursor = start + 1
                val block = callBlock(text, start) ?: continue
                if (block.contains("color = ")) {
                    val line = text.substring(0, start).count { it == '\n' } + 1
                    offenders += "${file.relativeTo(projectRoot)}:$line"
                }
            }
        }

        assertTrue(
            "按钮内不要写死文字颜色（聚焦白底后会看不见），交给 NovumButton 的内容色：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /** 交互组件不自绘细描边，焦点外观由共享材质组件统一负责。 */
    @Test
    fun interactiveComponentsMustNotDrawBorders() {
        val guarded = listOf("NovumButton.kt", "NovumIconButton.kt", "NovumChip.kt")
        val offenders = mutableListOf<String>()
        kotlinSources().forEach { file ->
            if (file.name in guarded) {
                val text = file.readText()
                if (text.contains(".border(") || text.contains("BorderStroke(")) {
                    offenders += file.relativeTo(projectRoot).toString()
                }
            }
        }
        assertTrue(
            "交互组件不得自绘描边（小白线已全站退役，玻璃黑底+聚焦白底是唯一语义）：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /** 收集参与规范检查的 Kotlin 源文件。 */
    private fun kotlinSources(): List<File> = sourceRoots
        .filter { it.exists() }
        .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    /**
     * 取出一次完整调用的文本：参数括号 + 紧随其后的尾随 lambda。
     *
     * 只取参数不够——按钮文案写在尾随 lambda 里，样式检查必须连 lambda 一起看。
     */
    private fun callBlock(text: String, start: Int): String? {
        val open = start + "NovumButton".length
        val argsEnd = matchingIndex(text, open, '(', ')') ?: return null
        var cursor = argsEnd + 1
        while (cursor < text.length && text[cursor].isWhitespace()) cursor++
        val lambdaEnd = if (text.getOrNull(cursor) == '{') {
            matchingIndex(text, cursor, '{', '}') ?: argsEnd
        } else {
            argsEnd
        }
        return text.substring(open, lambdaEnd + 1)
    }

    /** 找到与 [openIndex] 处开括号配对的闭括号下标。 */
    private fun matchingIndex(text: String, openIndex: Int, open: Char, close: Char): Int? {
        var depth = 0
        var inString = false
        for (i in openIndex until text.length) {
            val c = text[i]
            when {
                c == '"' -> inString = !inString
                inString -> Unit
                c == open -> depth++
                c == close -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }

    /**
     * 从 `(` 位置取出配对的参数文本（忽略字符串里的括号，足够应付本项目的调用形态）。
     */
    private fun balancedArguments(text: String, openIndex: Int): String? {
        if (text.getOrNull(openIndex) != '(') return null
        var depth = 0
        var inString = false
        for (i in openIndex until text.length) {
            val c = text[i]
            when {
                c == '"' -> inString = !inString
                inString -> Unit
                c == '(' -> depth++
                c == ')' -> {
                    depth--
                    if (depth == 0) return text.substring(openIndex + 1, i)
                }
            }
        }
        return null
    }
}
