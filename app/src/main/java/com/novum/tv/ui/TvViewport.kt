package com.novum.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density
import kotlin.math.min

/**
 * Android TV 官方设计基准画布：960 × 540 dp（MDPI，1px = 1dp）。
 *
 * 官方布局规范（design/ui/tv/guides/styles/layouts）要求所有 TV 布局按
 * 960×540 设计，左右 48dp、上下 27dp 过扫安全区，这样同一套 dp 在 720p /
 * 1080p / 4K 上按比例缩放。本项目的尺寸令牌（[com.novum.tv.ui.theme.NovumDimens]）
 * 正是这套基准：SafeHorizontal 68dp = 48 安全线 + 20 焦点预算。
 */
internal const val TV_DESIGN_WIDTH_DP = 960f

/** 官方设计基准画布高度（dp）。 */
internal const val TV_DESIGN_HEIGHT_DP = 540f

/** 归一化后的密度最少缩到基准的 1/4（防极端 dpi 报告算出 0 画布）。 */
private const val MIN_VIEWPORT_SCALE = 0.25f

/** 归一化后的密度最多放大 4 倍（同上，防异常大屏）。 */
private const val MAX_VIEWPORT_SCALE = 4f

/**
 * 把设备实际窗口归一到 960×540 设计基准后应使用的 Density。
 *
 * 电视上报的 density 差异极大：同样是 1920×1080，320dpi 的机型画布是
 * 960×540dp（与设计稿 1:1），而 480/560dpi 的机型只有 640×360 / 494×277dp。
 * 同一套绝对 dp 常量落到后者上，320dp 的卡片就占掉半屏、一屏只剩一行，
 * 480dp 的播放抽屉几乎铺满全屏——这正是"换台电视就变形"的根因。
 *
 * 归一化把画布固定回设计基准：`newDensity = 系统密度 × 缩放比`，
 * 之后所有 dp/sp 令牌的语义都变成"相对 960×540"，在任何电视上比例一致。
 *
 * 缩放比取**宽高两个方向的较小值**（而不是只按宽度）：
 * - 16:9 机型两方向相等，恰好得到 960×540；
 * - 21:9 等超宽屏 → 高度先到 540，宽度大于 960（横向多显示内容，不挤压纵向布局）；
 * - 4:3 等窄屏 → 宽度先到 960，高度大于 540。
 * 即保证两个方向都**不小于**设计基准，布局永远不会比设计稿更局促。
 *
 * `fontScale` 固定为 1：TV 是固定画布布局，系统字体放大会把行高/按钮撑破
 * （10 英尺观看距离下也无法靠"放大字体"补救），字号档位由应用内设置提供。
 *
 * @param windowWidthDp 实际窗口宽度（dp，系统密度口径）。
 * @param windowHeightDp 实际窗口高度（dp，系统密度口径）。
 * @param systemDensity 系统密度。
 * @return 归一化后的密度；窗口尺寸非法时原样返回。
 */
internal fun normalizeTvViewportDensity(
    windowWidthDp: Float,
    windowHeightDp: Float,
    systemDensity: Density,
): Density {
    if (windowWidthDp <= 0f || windowHeightDp <= 0f) return systemDensity
    // 密度本身非法（预览/测量异常）时不参与缩放，避免算出 0 或负的画布
    if (systemDensity.density <= 0f) return systemDensity
    val scale = min(
        windowWidthDp / TV_DESIGN_WIDTH_DP,
        windowHeightDp / TV_DESIGN_HEIGHT_DP,
    ).coerceIn(MIN_VIEWPORT_SCALE, MAX_VIEWPORT_SCALE)
    return Density(density = systemDensity.density * scale, fontScale = 1f)
}

/**
 * 视口归一化容器：把子树内的 [LocalDensity] 换成 960×540 基准密度。
 *
 * 挂在应用最外层（[NovumApp]），页面、浮层、对话框共享同一套坐标，
 * 各页面无需再自己判断分辨率。分辨率/显示模式切换会改变窗口尺寸或密度，
 * [LocalWindowInfo] 与 [LocalDensity] 的更新会自动触发重算。
 *
 * @param content 应用内容。
 */
@Composable
internal fun TvViewport(content: @Composable () -> Unit) {
    // 取真实窗口像素而不是 Configuration.screenWidthDp：
    // 后者在多窗口/内嵌场景不等于当前 Composable 的实际可用区域，
    // 且 lint 也会提示改用 WindowInfo（窗口尺寸变化会以状态形式通知重组）。
    val windowSize = LocalWindowInfo.current.containerSize
    val systemDensity = LocalDensity.current
    val normalized = remember(windowSize.width, windowSize.height, systemDensity.density) {
        val density = systemDensity.density
        normalizeTvViewportDensity(
            windowWidthDp = if (density > 0f) windowSize.width / density else 0f,
            windowHeightDp = if (density > 0f) windowSize.height / density else 0f,
            systemDensity = systemDensity,
        )
    }
    CompositionLocalProvider(LocalDensity provides normalized, content = content)
}
