package com.novum.tv.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.novum.tv.core.designsystem.R

/**
 * 字体：拉丁与数字使用 Inter（OFL 许可，约 800KB），中文回落系统黑体。
 *
 * 字重使用 Regular、Medium、SemiBold；SemiBold 用于 Display、Title、Section，大字号下保持清晰。
 * 中小字号用 Regular/Medium，避免远距离观看时笔画糊成块。
 *
 * 大字号字距归零，小字号适当松排。
 *
 * 行高比例：Display 1.19 / Title 1.19 / Section 1.25 / Body 1.29 / Caption 1.33 / Label 1.25。
 *
 * 字号按 **1280×720dp 画布**标定（官方 960×540 基准 × 1.333 后再收一档 ≈ ×1.13，
 * 实机确认这一档最舒服）：Display 54 / Title 44 / Section 28 / BodyStrong 22 /
 * Body 20 / CardTitle 20 / ButtonLg 22 / Button 18 / Caption 16 / Label 14sp。
 *
 * 注意：字阶收档时**几何不动**——卡片宽度、按钮 60dp、行高 58dp 仍按 1280 画布
 * 的焦点目标取值，否则整屏会一起缩水（实机：85% 整体缩放时卡片偏小）。
 *
 * 改这一张表即可改变全站字阶——禁止在调用点写 `.sp` 字面量
 * （`DesignTokenContractTest` 会扫描源码拦截）。
 */
private val NovumFontFamily = FontFamily(
    Font(R.font.inter_regular, weight = FontWeight.Normal),
    Font(R.font.inter_medium, weight = FontWeight.Medium),
    Font(R.font.inter_semibold, weight = FontWeight.SemiBold),
)

object NovumType {

    /** Hero 主标题 54/64 SemiBold（官方 48sp@960 换算到 1280 画布后再收一档）。 */
    val Display: TextStyle = TextStyle(
        fontFamily = NovumFontFamily,
        fontSize = 54.sp,
        lineHeight = 64.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp,
    )

    /** 详情页/页级标题 44/53 SemiBold（行距比 1.2）。 */
    val Title: TextStyle = TextStyle(
        fontFamily = NovumFontFamily,
        fontSize = 44.sp,
        lineHeight = 53.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp,
    )

    /** 内容行/区块标题 28/35 SemiBold（行距比 1.25）。 */
    val Section: TextStyle = TextStyle(
        fontFamily = NovumFontFamily,
        fontSize = 28.sp,
        lineHeight = 35.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp,
    )

    /** 正文/剧情 20/26 Regular +0.2。 */
    val Body: TextStyle = TextStyle(
        fontFamily = NovumFontFamily,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.2.sp,
    )

    /** 简介正文：比主标题和卡名更轻，18/26保留电视阅读所需的行距。 */
    val Synopsis: TextStyle = TextStyle(
        fontFamily = NovumFontFamily,
        fontSize = 18.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.2.sp,
    )

    /** 卡片标题/设置项强调 22/29 Medium（10ft 糊块顾虑保留 500）。 */
    val BodyStrong: TextStyle = TextStyle(
        fontFamily = NovumFontFamily,
        fontSize = 22.sp,
        lineHeight = 29.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp,
    )

    /** 卡片标题：20/27 Medium（与正文同字号、行距更松）；次行使用 [Label]。 */
    val CardTitle: TextStyle = TextStyle(
        fontFamily = NovumFontFamily,
        fontSize = 20.sp,
        lineHeight = 27.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp,
    )

    /**
     * 紧凑按钮文案（弹窗/行内次级动作，18/23 Medium +0.5）。
     *
     * 中文按钮普遍只有 2–3 个字（播放/返回/设置），默认字距下两个字会挤在一起，
     * 加 0.5sp 字距后字面之间有呼吸——与 Apple 小号光学松排同口径。
     */
    val Button: TextStyle = TextStyle(
        fontFamily = NovumFontFamily,
        fontSize = 18.sp,
        lineHeight = 23.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.5.sp,
    )

    /** 大号 CTA 文案：Hero 双 CTA 等主入口使用 22/29，字距与 [Button] 一致。 */
    val ButtonLg: TextStyle = TextStyle(
        fontFamily = NovumFontFamily,
        fontSize = 22.sp,
        lineHeight = 29.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.5.sp,
    )

    /** 元数据/标签 16/21 Regular +0.3（小号松排）。 */
    val Caption: TextStyle = TextStyle(
        fontFamily = NovumFontFamily,
        fontSize = 16.sp,
        lineHeight = 21.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.3.sp,
    )

    /** 角标/徽章 14/18 Medium +0.4（不承担必要信息，但三米外仍可辨）。 */
    val Label: TextStyle = TextStyle(
        fontFamily = NovumFontFamily,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.4.sp,
    )

    /**
     * 播放页字幕档位：20 / 26 / 32 / 40sp。
     *
     * 档位值由本表提供；SubtitleLayer 按字号 × 1.35 计算行高。
     */
    val SubtitleSizes: List<TextUnit> = listOf(20.sp, 26.sp, 32.sp, 40.sp)

    /** 字幕档位取值：[sizeLevel] 1–4，越界钳到最近档（SubtitleLayer 唯一消费）。 */
    fun subtitleSize(sizeLevel: Int): TextUnit =
        SubtitleSizes[(sizeLevel - 1).coerceIn(0, SubtitleSizes.lastIndex)]
}
