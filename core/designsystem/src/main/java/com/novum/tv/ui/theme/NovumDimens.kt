package com.novum.tv.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 尺寸与动效令牌。
 *
 * 背景铺满视口；标题、静止首尾卡与关键按钮共用 [SafeHorizontal] 对齐线。
 * 横卡与海报卡分别使用 [CardWidth]、[PosterCardWidth] 目标宽度。
 * 横向滚动行通过 contentPadding 留出安全边距，焦点滚动同时计入缩放溢出。
 *
 * **所有数值都是1280×720dp设计画布口径**。卡宽为目标值，布局统一按内容宽
 * 计算列数和实际宽度：该画布下六列海报约174dp；横卡固定304dp，横向行露出后续卡，横卡网格保留行尾余量。
 * 应用根节点 `TvViewport` 会把过小的画布收敛到该基准，并叠加用户在设置里的
 * 「界面大小」档位——这里不需要、也不应该再按某台机器的"实际画布"换算或写像素值。
 */
object NovumDimens {

    /**
     * 内容对齐线 68dp（48dp 安全线 + 20dp 焦点预算）：
     * 行标题、**所有页面**的静止首尾卡、关键按钮距屏幕左右边 68dp。
     * **只约束内容**——背景图全出血不用它。
     */
    val SafeHorizontal = 68.dp

    /** 垂直最小保护 27dp：列表顶距/底距、详情信息区上下（偏紧，余量由各行自身内边距补足）。 */
    val SafeVertical = 27.dp

    /** 顶部导航最小高度 64dp（顶栏随页滚动）。 */
    val TopBarHeight = 64.dp

    /** 卡片间距（小档，12dp）。 */
    val Gutter = 12.dp

    /** 内容行之间的间距（48dp）。 */
    val RowGap = 48.dp

    /** 行标题 → 该行首卡顶 12dp。 */
    val TitleToRow = 12.dp

    /**
     * 纵向堆叠内容块（标题/正文/操作区）之间的间距（16dp）。
     */
    val TextBlockGap = 16.dp

    /**
     * 浮层/面板内容内边距（左右 24dp，上下 16dp）。
     */
    val OverlayPaddingHorizontal = 24.dp

    /** 浮层/面板内容内边距·上下（配 [OverlayPaddingHorizontal]）。 */
    val OverlayPaddingVertical = 16.dp

    /** 区块之间的大间距 40dp。 */
    val SectionGap = 40.dp

    /**
     * 16:9 标准卡宽度：首页继续观看行、分类页库卡与选集行共用。
     * 2:3 海报卡使用 [PosterCardWidth]。
     */
    val CardWidth = 304.dp

    /**
     * 2:3 海报卡目标宽度；与网格共用计算，1280dp 画布下六列实际约174dp。
     */
    val PosterCardWidth = 180.dp

    /**
     * 标准卡图区 → 下方文字区的间距（官方标准卡规格图标注 8dp）。
     */
    val CardImageToTextGap = 8.dp

    /** 聚焦缩放 1.10（官方最大档；唯一来源见 [NovumFocusTokens.CardScale]）。 */
    const val FocusScale = 1.10f

    /** 行内卡片间距 20dp。 */
    val CardGap = 20.dp

    /**
     * 焦点放大/阴影需要的额外留白，避免被 Lazy 容器裁剪。
     *
     * 取值依据：1.10× 时单边溢出 = 最大边 × 0.05。16:9 卡（320dp）溢出 8dp、
     * 2:3 卡（高 266dp）溢出 6.6dp，10dp 覆盖。更宽/更高的卡不要手改这个
     * 常量：用 [com.novum.tv.ui.theme.NovumFocusTokens.bleed] 按实际尺寸计算
     * （网格在布局期由 `maxWidth` 反推卡片宽度后自行计算）。
     */
    val FocusBleed = 10.dp

    /**
     * 卡片文字区的最大高度预算（焦点出血/滚动边距估算用），按副题行数取档。
     *
     * 必须与 [com.novum.tv.ui.component.NovumCard] 的文字区保持一致：
     * 间距 8dp + CardTitle 20sp（27 行高）+ Label 14sp（18 行高）× 行数。
     * 1 行元数据取54dp；两行简介使用Caption的21dp行高，取80dp留余量。
     * 全站副题 [subtitleLines] 上限 2（subtitleMaxLines）。
     */
    fun CardTextBlockMaxHeight(subtitleLines: Int): Dp = when (subtitleLines) {
        1 -> 54.dp
        else -> 80.dp
    }

    /** 焦点动画时长 150ms（焦点缩放）。 */
    const val FocusAnimationMs = 150

    /**
     * 全站文本按钮统一高度（60dp）。
     *
     * 官方 48dp 焦点目标 @960 画布，换算到 1280 画布 = 64dp；60dp 保证
     * 22sp 按钮文案有充足上下内边距，同时不把整页撑高。
     */
    val ButtonHeight = 60.dp

    /**
     * 登录等较宽的表单操作按钮宽度；首页与详情使用 ActionButtonWidth。
     */
    val ButtonWidth = 200.dp

    /** 首页和详情操作按钮共用的紧凑宽度，四字文案与24dp两侧内边距均可容纳。 */
    val ActionButtonWidth = 144.dp
}
