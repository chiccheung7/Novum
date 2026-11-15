package com.novum.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/**
 * 全局 TV 深色配色方案。
 *
 * TV 场景固定使用深色主题：十英尺界面在暗环境下以深色底 + 高对比文字为主，
 * 避免大面积亮色造成眩光与烧屏。
 *
 * 说明：这里只映射 TV Material3 组件需要的核心角色色；业务组件优先直接引用
 * [NovumPalette]，避免同一颜色出现两套语义（例如 `primary` 与 `Amber` 混用）。
 */
private val NovumColorScheme = darkColorScheme(
    // TV 场景固定使用深色主题：暗环境以深色背景和高对比文字为主。
    primary = NovumPalette.Accent,
    onPrimary = NovumPalette.Ink,
    background = NovumPalette.Ink,
    onBackground = NovumPalette.Paper,
    surface = NovumPalette.InkElevated,
    onSurface = NovumPalette.Paper,
)

/**
 * Novum 主题入口：注入配色，并提供设计令牌（[NovumType] / [NovumDimens] / [NovumShapes]）。
 *
 * @param content 主题作用域内的界面内容。
 */
@Composable
fun NovumTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NovumColorScheme,
        content = content,
    )
}
