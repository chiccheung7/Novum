package com.novum.tv.ui.component

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import com.novum.tv.testing.RepoPaths

/**
 * 设计令牌契约测试：检查令牌定义位置与调用点的一致性。
 *
 * 字号字面量集中在 [NovumType]；白系半透明底使用指定色板令牌。
 * Robolectric 下可扫描源码验证，不重复覆盖焦点容器契约。
 */
class DesignTokenContractTest {

/** 仓库根目录。 */
    private val projectRoot: File = RepoPaths.root

    private val sourceRoots: List<File> = RepoPaths.mainSourceDirs()

    /** 白系透明色豁免：登记语境明确的局部常量。新增豁免需注明原因。 */
    private val whiteAlphaExemptFiles = setOf(
        // 进度条轨道 / 缓冲 / 滑块环三档（播放器专属视觉微调）
        "PlayerProgressBar.kt",
        // 缓冲圆弧透明度
        "PlayerOverlays.kt",
        // 空心胶囊细描边
        "NovumMediaInfoBlock.kt",
    )

    private fun kotlinSources(): List<File> = sourceRoots
        .filter { it.exists() }
        .flatMap { root -> root.walkTopDown().filter { it.extension == "kt" }.toList() }

    /** 代码行（行号 + 文本）：KDoc / 行注释里写 `24sp`、`Paper.copy(` 不算违规。 */
    private fun codeLines(file: File): List<Pair<Int, String>> =
        file.readLines().mapIndexedNotNull { index, line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) {
                null
            } else {
                (index + 1) to line
            }
        }

    @Test
    fun fontSizeLiteralsMustLiveInNovumType() {
        val offenders = mutableListOf<String>()
        val numericSp = Regex("""\d+(?:\.\d+)?\.sp\b""")
        kotlinSources().forEach { file ->
            if (file.name == "NovumType.kt") return@forEach
            codeLines(file).forEach { (line, text) ->
                if (numericSp.containsMatchIn(text)) {
                    offenders += "${file.relativeTo(projectRoot)}:$line"
                }
            }
        }
        assertTrue(
            "字号字面量只许住在 NovumType.kt（唯一字阶来源；用 NovumType.* / NovumType.subtitleSize()）：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun whiteFamilyAlphaMustUsePaletteTokens() {
        val offenders = mutableListOf<String>()
        val scatteredWhiteAlpha =
            Regex("""NovumPalette\s*\.\s*(?:Paper|PaperMuted|PaperPressed|PaperBody|Glass\w*)\s*\.\s*copy\s*\(""")
        kotlinSources().forEach { file ->
            if (file.name in whiteAlphaExemptFiles) return@forEach
            codeLines(file).forEach { (line, text) ->
                if (scatteredWhiteAlpha.containsMatchIn(text)) {
                    offenders += "${file.relativeTo(projectRoot)}:$line"
                }
            }
        }
        assertTrue(
            "白系半透明底只许用档位令牌（Glass/GlassVideo/GlassVideoSoft/GlassDisabled/PanelOverlay）：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }
}
