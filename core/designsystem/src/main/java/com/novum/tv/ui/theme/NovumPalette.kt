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

    /** 浮层面板底（三级表面）#141418，比 [InkElevated] 略亮。 */
    val Surface: Color = Color(0xFF141418)

    /** 三级表面 #2A2A2A（菜单/浮层内聚焦底、选中行、进度槽）。 */
    val SurfaceHigh: Color = Color(0xFF2A2A2A)

    /**
     * 浮层面板底：准不透明，保证亮视频背景上的文字可读。
     */
    val PanelOverlay: Color = Color(0xF00A0A0A)

    /**
     * 主文字色 #F5F5F7：标题、卡名、正文与按钮文案。
     */
    val Paper: Color = Color(0xFFF5F5F7)

    /**
     * 按压态底 #D9D9D9：白底聚焦的按钮按下时略压暗，文字仍用 [Ink]。
     */
    val PaperPressed: Color = Color(0xFFD9D9D9)

    /** 次要文字 White@.81（10ft 观看下 72% 在白字边缘会发灰，提亮一档保证可读）。 */
    val PaperMuted: Color = Color(0xCFFFFFFF)

    /**
     * 三级文字 White@.66：Hero/详情简介等比 [PaperMuted] 再低一档、但暗底上仍可读的正文。
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

    /** 强调色 #FFFFFF：进度条已播段、输入光标、选中竖条等实底元素。 */
    val Accent: Color = Color(0xFFFFFFFF)

    /** 分隔线与非焦点描边 #3A3A3C。 */
    val Border: Color = Color(0xFF3A3A3C)

    /** 交互按钮未聚焦时的细白描边：Paper 55% 不透明度。 */
    val ControlOutline: Color = Color(0x8CF5F5F7)

    /** 交互控件常态底：烟玻璃 #141418@.62（按钮/芯片未聚焦时的底）。 */
    val Glass: Color = Color(0x9E141418)

    /** 叠加在视频画面上的控件底·主档 #141418@.55（播放器主按钮/开关）。 */
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

    /** 深玻璃面板底 #141418@.76：播放器抽屉、信息面板与卡图角标/进度文案。 */
    val GlassDeep: Color = Color(0xC2141418)

    /** 遮罩黑 Black@.80：弹层/退出确认框背后的页面压暗。 */
    val Scrim: Color = Color(0xCC000000)

    /** 危险/错误色 #FF6961。 */
    val Danger: Color = Color(0xFFFF6961)

    /**
     * 成功/已完成色 #34C759，与 [Danger] 构成语义色。
     */
    val Success: Color = Color(0xFF34C759)
}
