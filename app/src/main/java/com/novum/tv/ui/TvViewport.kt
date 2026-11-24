package com.novum.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density
import kotlin.math.min

/**
 * 设计基准画布：1280 × 720 dp。
 *
 * 这不是随手取的数：仓库里的尺寸令牌就是按这套画布标定的——
 * `PosterCardWidth` 177dp 对应「六列刚好铺满 1280 减安全区」；
 * Hero 信息区 420dp 在 720 高下占 58%（放到 540 高会占 78%，把首屏压死）；
 * 16:9 横卡 320dp 在 1280 画布下每行露出 3.3 张（960 画布只剩 2.4 张）。
 *
 * 早期版本按 Android TV 官方的 960×540 归一会把所有元素放大到 1.33 倍，
 * 在原生画布本来就 ≥1280dp 的电视上就表现为「所有东西都撑满屏幕」。
 */
internal const val TV_BASE_WIDTH_DP = 1280f

/** 设计基准画布高度（dp），见 [TV_BASE_WIDTH_DP]。 */
internal const val TV_BASE_HEIGHT_DP = 720f

/**
 * 画布上限：原生画布比这还大（极低 dpi 上报，如 4K @160dpi = 3840×2160dp）时
 * 按上限收敛，否则令牌会缩到看不清。1920×1080dp 是 1080p 常见的桌面口径。
 */
internal const val TV_MAX_WIDTH_DP = 1920f

/** 画布上限高度（dp），见 [TV_MAX_WIDTH_DP]。 */
internal const val TV_MAX_HEIGHT_DP = 1080f

/** 用户「界面大小」档位的合法区间（1.0 = 按设计画布原样）。 */
internal const val MIN_UI_SCALE = 0.7f

/**
 * 用户「界面大小」档位上限，见 [MIN_UI_SCALE]。
 *
 * 1.5 = 把 1920dp 画布压回 1280dp 设计画布，也就是"大画布电视恢复设计尺寸"的那一档。
 */
internal const val MAX_UI_SCALE = 1.6f

/**
 * 把设备实际窗口归一到 [TV_BASE_WIDTH_DP] 画布后应使用的 Density。
 *
 * 电视上报的 density 差异极大：同样是 1920×1080，320dpi 的机型画布是
 * 960×540dp，480/560dpi 的只有 640×360dp 左右，而部分机型会报到
 * 1920×1080dp——同一套绝对 dp 常量落到三者上，元素相对屏幕的大小会差 2–3 倍。
 *
 * 归一化规则（**只缩不放**，除非画布小到不可用）：
 * - 画布小于 [TV_BASE_WIDTH_DP]：放大画布（缩小 UI），避免卡片占半屏、一屏一行；
 * - 画布已在 [TV_BASE_WIDTH_DP]–[TV_MAX_WIDTH_DP] 之间：**保持原样**。
 *   这类设备本来就是令牌的标定环境，强行统一只会把「本来合适」的界面改大；
 * - 画布大于 [TV_MAX_WIDTH_DP]：收敛到上限，避免字小到看不清。
 *
 * 宽高分别按上述区间收敛，取两个方向折算比的**较小值**（保证两个方向都不比
 * 目标画布更局促）：16:9 两方向一致；21:9 由高度决定；4:3 由宽度决定。
 *
 * [uiScale] 是用户在设置里的「界面大小」：>1 表示界面更大（密度更大、画布更小）。
 *
 * `fontScale` 固定为 1：TV 是固定画布布局，系统字体放大会把行高/按钮撑破，
 * 字号档位由应用内设置（[uiScale]）提供。
 *
 * @param windowWidthDp 实际窗口宽度（dp，系统密度口径）。
 * @param windowHeightDp 实际窗口高度（dp，系统密度口径）。
 * @param systemDensity 系统密度。
 * @param uiScale 用户界面缩放（1 = 标准）。
 * @return 归一化后的密度；窗口尺寸非法时原样返回。
 */
internal fun normalizeTvViewportDensity(
    windowWidthDp: Float,
    windowHeightDp: Float,
    systemDensity: Density,
    uiScale: Float = 1f,
): Density {
    if (windowWidthDp <= 0f || windowHeightDp <= 0f) return systemDensity
    // 密度本身非法（预览/测量异常）时不参与缩放，避免算出 0 或负的画布
    if (systemDensity.density <= 0f) return systemDensity
    val scaleX = windowWidthDp / windowWidthDp.coerceIn(TV_BASE_WIDTH_DP, TV_MAX_WIDTH_DP)
    val scaleY = windowHeightDp / windowHeightDp.coerceIn(TV_BASE_HEIGHT_DP, TV_MAX_HEIGHT_DP)
    val userScale = uiScale.takeIf { it.isFinite() }?.coerceIn(MIN_UI_SCALE, MAX_UI_SCALE) ?: 1f
    return Density(density = systemDensity.density * min(scaleX, scaleY) * userScale, fontScale = 1f)
}

/**
 * 视口归一化容器：把子树内的 [LocalDensity] 换成 [TV_BASE_WIDTH_DP] 基准密度。
 *
 * 挂在应用最外层（[NovumApp]），页面、浮层、对话框共享同一套坐标，
 * 各页面无需再自己判断分辨率。分辨率/显示模式切换会改变窗口尺寸或密度，
 * [LocalWindowInfo] 与 [LocalDensity] 的更新会自动触发重算。
 *
 * @param uiScale 用户「界面大小」档位（1 = 标准）。
 * @param content 应用内容。
 */
@Composable
internal fun TvViewport(
    uiScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    // 取真实窗口像素而不是 Configuration.screenWidthDp：
    // 后者在多窗口/内嵌场景不等于当前 Composable 的实际可用区域，
    // 且 lint 也会提示改用 WindowInfo（窗口尺寸变化会以状态形式通知重组）。
    val windowSize = LocalWindowInfo.current.containerSize
    val systemDensity = LocalDensity.current
    val normalized = remember(windowSize.width, windowSize.height, systemDensity.density, uiScale) {
        val density = systemDensity.density
        normalizeTvViewportDensity(
            windowWidthDp = if (density > 0f) windowSize.width / density else 0f,
            windowHeightDp = if (density > 0f) windowSize.height / density else 0f,
            systemDensity = systemDensity,
            uiScale = uiScale,
        )
    }
    CompositionLocalProvider(LocalDensity provides normalized, content = content)
}
