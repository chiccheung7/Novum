package com.novum.tv.ui.component

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import com.novum.tv.testing.RepoPaths

/**
 * 焦点安全契约测试：防止 Lazy 行/网格缺少出血边距和共享焦点效果。
 *
 * 容器须声明 contentPadding；LazyRow 文件须接入 [NovumFocusScrollScope]；
 * 共享焦点效果须使用 graphicsLayer 与 zIndex，不允许在外部另写缩放动画。
 */
class FocusContractTest {

/** 仓库根目录。 */
    private val projectRoot: File = RepoPaths.root

    private val sourceRoots: List<File> = RepoPaths.mainSourceDirs()

    @Test
    fun cardContainersMustDeclareContentPadding() {
        val offenders = mutableListOf<String>()
        kotlinSources().forEach { file ->
            val text = file.readText()
            listOf("LazyRow(", "LazyVerticalGrid(").forEach { call ->
                var cursor = 0
                while (true) {
                    val start = text.indexOf(call, cursor)
                    if (start < 0) break
                    cursor = start + 1
                    val args = balancedArguments(text, start + call.length - 1) ?: continue
                    if (!args.contains("contentPadding")) {
                        val line = text.substring(0, start).count { it == '\n' } + 1
                        offenders += "${file.relativeTo(projectRoot)}:$line ($call)"
                    }
                }
            }
        }
        assertTrue(
            "卡片容器必须声明 contentPadding（聚焦放大需要边缘出血，PITFALLS F16）：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun lazyRowsMustProvideFocusScrollMargin() {
        val offenders = mutableListOf<String>()
        kotlinSources().forEach { file ->
            val text = file.readText()
            if (!text.contains("LazyRow(")) return@forEach
            // NovumRow 自身负责包滚动作用域；其余文件必须显式接入
            if (file.name == "NovumRow.kt") return@forEach
            val hasScope = text.contains("NovumFocusScrollScope") ||
                text.contains("NovumNoScrollDeadZone")
            if (!hasScope) offenders += file.relativeTo(projectRoot).toString()
        }
        assertTrue(
            "含 LazyRow 的页面必须接入 NovumFocusScrollScope（聚焦卡与视口边缘保持出血距离，PITFALLS F16）：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun focusEffectMustScaleInDrawPhaseAndRaiseZIndex() {
        val focusEffect = projectRoot.resolve(
            "core/designsystem/src/main/java/com/novum/tv/ui/component/FocusEffect.kt",
        )
        assertTrue("找不到共享焦点效果 FocusEffect.kt", focusEffect.exists())
        val text = focusEffect.readText()
        assertTrue(
            "共享焦点效果必须用 graphicsLayer 做缩放（布局期缩放会触发重测量→抖动）",
            text.contains("graphicsLayer"),
        )
        assertTrue(
            "共享焦点效果必须给聚焦项抬 zIndex（否则放大的边缘会被后面的卡片盖住）",
            text.contains("zIndex("),
        )
    }

    @Test
    fun noHandRolledFocusScalingOutsideSharedEffect() {
        val offenders = mutableListOf<String>()
        kotlinSources().forEach { file ->
            val text = file.readText()
            // 共享效果自身就是缩放动画的唯一实现
            if (file.name == "FocusEffect.kt") return@forEach
            var cursor = 0
            while (true) {
                val start = text.indexOf("animateFloatAsState(", cursor)
                if (start < 0) break
                cursor = start + 1
                val args = balancedArguments(text, start + "animateFloatAsState".length) ?: continue
                if (args.contains("scale", ignoreCase = true)) {
                    val line = text.substring(0, start).count { it == '\n' } + 1
                    offenders += "${file.relativeTo(projectRoot)}:$line"
                }
            }
            if (text.contains("Modifier.scale(")) {
                offenders += file.relativeTo(projectRoot).toString() + " (Modifier.scale)"
            }
        }
        assertTrue(
            "焦点缩放必须走 tvFocusEffect；不要自写布局期缩放（PITFALLS F16）：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun topBarMustNotUseFocusEffects() {
        val topBar = projectRoot.resolve(
            "core/designsystem/src/main/java/com/novum/tv/ui/component/NovumTopBar.kt",
        )
        assertTrue("找不到顶栏组件 NovumTopBar.kt（阶段 11 复辟：rail 已退役）", topBar.exists())
        val text = topBar.readText()
        // 用户菜单不再占用顶栏浮层焦点；当前结构无浮层，无需额外困焦。
        assertTrue(
            "顶栏不得自绘浮层（菜单已退役；引入浮层须走 Dialog 困焦）",
            !text.contains("Dialog("),
        )
        // 顶栏贴屏幕上缘，共享缩放效果（带阴影溢出）必出屏：
        // 图标钮只许 tvFocusScale（56dp 图标在 75dp 行高内有余量）
        assertTrue(
            "顶栏不得挂 tvFocusEffect（贴边放大出屏）",
            !text.contains("tvFocusEffect"),
        )
        // Back 分层：返回键将焦点交给 Hero 播放 CTA。
        assertTrue(
            "顶栏不得残留 Back 交焦链（rail/页签已退役）",
            !text.contains("focusRequester") && !text.contains("onFocusChanged"),
        )
    }

    @Test
    fun cardFocusMaterialContract() {
        // 卡片聚焦材质使用官方 Card 缩放；焦点态边框透明，选中框通过 Accent 描边独立呈现。
        // Glow 显式设为 None，防止默认光晕改变界面。
        val focusEffect = projectRoot.resolve(
            "core/designsystem/src/main/java/com/novum/tv/ui/component/FocusEffect.kt",
        ).readText()
        val card = projectRoot.resolve(
            "core/designsystem/src/main/java/com/novum/tv/ui/component/NovumCard.kt",
        ).readText()
        assertTrue(
            "卡聚焦缩放必须走官方 CardDefaults.scale（自绘缩放与内建动画叠加、" +
                "表现为边缘滞后与快扫掉帧，PITFALLS 实机 2026-11-07）",
            card.contains("focusedScale = NovumFocusTokens.CardScale"),
        )
        assertTrue(
            "卡的缩放/阴影/描边一律经官方参数承载，禁止自绘修饰器（tvFocusLift 已退役）",
            !card.contains("tvFocusLift") && !card.contains("tvFocusEffect"),
        )
        assertTrue(
            "卡光晕必须显式三态 Glow.None（2026-11-07 拍板只保留官方缩放）",
            card.contains("Glow.None"),
        )
        assertTrue(
            "卡焦点态描边必须走官方 CardDefaults.border 参数",
            card.contains("CardDefaults.border"),
        )
        assertTrue(
            "常态描边保持 0dp 透明占位（只在焦点/按压时出白框）",
            card.contains("BorderStroke(0.dp, Color.Transparent)"),
        )
        assertTrue(
            "卡焦点必须是 2dp 白描边（2026-11-24 拍板：焦点=白描边 + 放大，" +
                "只靠 1.10 缩放在暗色海报上不够明确）",
            card.contains(
                "focusedBorder = Border(BorderStroke(FOCUS_FRAME_WIDTH, NovumPalette.Paper))",
            ),
        )
        assertTrue(
            "选中态必须改成左侧竖条（白描边只留给焦点，否则没聚焦的已选卡比聚焦卡更显眼）：" +
                "选中标记经 selected = glowing 传入图区",
            card.contains("selected = glowing") && card.contains("if (selected)"),
        )
        assertTrue(
            "选中态不得回潮整卡描边（会与焦点白框抢语义）",
            !card.contains("selectedFrame") && !card.contains("SELECTED_FRAME_WIDTH"),
        )
        assertTrue(
            "选中发光已退役（2026-11-11 第四轮拍板）：不得回潮 selectedGlow/SELECTED_TEXT_GLOW",
            !card.contains("selectedGlow") && !card.contains("SELECTED_TEXT_GLOW"),
        )
        assertTrue(
            "自绘聚焦阴影已退役（2026-11-07）：FocusEffect 不得再含 shadowElevation",
            !focusEffect.contains("shadowElevation"),
        )
        assertTrue(
            "自绘聚焦高光已退役：FocusEffect 不得再含 glow 分支",
            !focusEffect.contains("glow"),
        )
    }

    /** 收集参与规范检查的 Kotlin 源文件。 */
    private fun kotlinSources(): List<File> = sourceRoots
        .filter { it.exists() }
        .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    /** 从 `(` 位置取出配对的参数文本（忽略字符串里的括号，足够应付本项目的调用形态）。 */
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
