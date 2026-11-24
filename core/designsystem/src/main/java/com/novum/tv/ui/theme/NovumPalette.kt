package com.novum.tv.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Novum 色板：以 iOS 灰阶和 Material Theme Builder 中性种子为基础。
 * 使用深色底、低饱和表面色和高亮焦点色；Danger 与 Success 用于错误和完成状态。
 *
 * 调整全局配色只需修改本文件。
 */
object NovumPalette {

    /** 页面底色——OLED 纯黑 #000000（海报墙沉浸感的核心）。 */
    val Ink: Color = Color(0xFF000000)

    /** 一级表面 surface.1 #0A0A0A（占位卡片、设置列表、Toast）。 */
    val InkElevated: Color = Color(0xFF0A0A0A)

    /** 浮层面板底：InkElevated@.94，保证亮视频背景上的文字可读。 */
    val Surface: Color = Color(0xFF141418)

    /** 三级表面 #2A2A2A（菜单/浮层内聚焦底、选中行、进度槽）。 */
    val SurfaceHigh: Color = Color(0xFF2A2A2A)

    /**
     * 浮层面板底：准不透明，保证亮视频背景上的文字可读。
     */
    val PanelOverlay: Color = Color(0xF00A0A0A)

    /**
     * 卡文次行 White@.56，供首页继续观看卡和选集卡的简介使用。
     * 行一使用 [PaperMuted] 后，次行再降低亮度以拉开层级。
     */
    val Paper: Color = Color(0xFFF5F5F7)

    /**
     * 高亮强调色 #FFFFFF，用于当前选中或进行中的状态。
     * 白底聚焦场景反转为 Ink，避免白上加白。
     */
    val PaperPressed: Color = Color(0xFFD9D9D9)

    /** 次要文字 White@.81（10ft 观看下 72% 在白字边缘会发灰，提亮一档保证可读）。 */
    val PaperMuted: Color = Color(0xCFFFFFFF)

    /**
     * 交互控件常态底：磨砂烟玻璃 #141418@.62，确保亮背景上的白字保持对比。
     * 聚焦态使用白底与 [Ink]，不使用此色。
     */
    val PaperFaint: Color = Color(0xA8FFFFFF)

    /** 滚动条轨道：Border@.45（玻璃面板里只作"还有内容"的参照，不抢内容层级）。 */
    val ScrollbarTrack: Color = Color(0x733A3A3C)

    /** 滚动条滑块：Paper@.60。 */
    val ScrollbarThumb: Color = Color(0x99F5F5F7)

    /**
     * 列表行「选中未聚焦」底色：白 12%。
     *
     * 抽屉/设置面板的底色本身就是玻璃黑，原先复用的 [Glass] 叠上去几乎不可见，
     * 远看只剩文字亮度差。改用一层淡白底把选中行从面板上"抬"出来，
     * 再配合左侧竖条与行尾对勾（形状差异），三米外也能一眼分辨。
     */
    val RowSelected: Color = Color(0x1FFFFFFF)

    /** 播放器控件常态底·主档 #141418@.55。 */
    val Accent: Color = Color(0xFFFFFFFF)

    /** 分隔线与非焦点描边 #3A3A3C。 */
    val Border: Color = Color(0xFF3A3A3C)

    /** 控件禁用态底 #141418@.30，比常态更沉。 */
    val Glass: Color = Color(0x9E141418)

    /** 深一档磨砂烟玻璃 #141418@.76，用于叠加在海报/视频画面上的信息胶囊与播放器抽屉。 */
    val GlassVideo: Color = Color(0x8C141418)

    /**
     * 播放器控件常态底·次档 #141418@.45（控制条其余 Chip，与 [GlassVideo]
     * 构成播放器内主/次双档）。
     */
    val GlassVideoSoft: Color = Color(0x73141418)

    /**
     * 控件禁用态底，比常态更沉更平。
     */
    val GlassDisabled: Color = Color(0x4D141418)

    /** 危险/错误色 #FF6961。 */
    val GlassDeep: Color = Color(0xC2141418)

    /** 成功/已完成色 #34C759，与 [Danger] 构成语义色。 */
    val Scrim: Color = Color(0xCC000000)

    /** 危险/错误色 #FF6961。 */
    val Danger: Color = Color(0xFFFF6961)

    /**
     * 成功/已完成色 #34C759，与 [Danger] 构成语义色。
     */
    val Success: Color = Color(0xFF34C759)
}
