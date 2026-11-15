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
 * 行高比例：Display 1.21 / Section 1.25 / Body 1.28 / Caption 1.29 / Label 1.25。
 *
 * 字号：Display 48 / Title 40 / Section 24 / BodyStrong 20 / Body 18 / ButtonLg 20 / Button 16 / Caption 14 / Label 12sp。
 */
private val NovumFontFamily = FontFamily(
    Font(R.font.inter_regular, weight = FontWeight.Normal),
    Font(R.font.inter_medium, weight = FontWeight.Medium),
    Font(R.font.inter_semibold, weight = FontWeight.SemiBold),
)

object NovumType {

    /** Hero 主标题 48/58 SemiBold（tvOS LargeTitle 行距比 1.21）。 */
    val Display: TextStyle = TextStyle(
        fontFamily = NovumFontFamily,
        fontSize = 48.sp,
        lineHeight = 58.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp,
    )

    /** 详情页/页级标题 40/48 SemiBold（行距比 1.2）。 */
    val Title: TextStyle = TextStyle(
        fontFamily = NovumFontFamily,
        fontSize = 40.sp,
        lineHeight = 48.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp,
    )

    /** 内容行/区块标题 24/30 SemiBold（tvOS Title3 行距比 1.25）。 */
    val Section: TextStyle = TextStyle(
        fontFamily = NovumFontFamily,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp,
    )

    /** 正文/剧情 18/23 Regular +0.2（Apple Body 17/22 行距比 1.29 + 小号微松）。 */
    val Body: TextStyle = TextStyle(
        fontFamily = NovumFontFamily,
        fontSize = 18.sp,
        lineHeight = 23.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.2.sp,
    )

    /** 卡片标题/设置项强调 20/26 Medium（Apple Headline 位；10ft 糊块顾虑保留 500）。 */
    val BodyStrong: TextStyle = TextStyle(
        fontFamily = NovumFontFamily,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp,
    )

    /** 卡片标题：18sp Medium，用于首页继续观看卡和选集卡；次行使用 [Label]。设置项强调使用 [BodyStrong]。 */
    val CardTitle: TextStyle = TextStyle(
        fontFamily = NovumFontFamily,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp,
    )

    /**
     * 按钮文案（全站唯一，16/20 Medium +0.5）。
     *
     * 中文按钮普遍只有 2–3 个字（播放/返回/设置），默认字距下两个字会挤在一起，
     * 加 0.5sp 字距后字面之间有呼吸——与 Apple 小号光学松排同口径。
     */
    val Button: TextStyle = TextStyle(
        fontFamily = NovumFontFamily,
        fontSize = 16.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.5.sp,
    )

    /** 大号 CTA 文案：Hero 双 CTA 等主入口使用 20sp/26 行高，字距与 [Button] 一致。 */
    val ButtonLg: TextStyle = TextStyle(
        fontFamily = NovumFontFamily,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.5.sp,
    )

    /** 元数据/标签 14/18 Regular +0.3（Apple Footnote 行距比 1.29 + 小号松排）。 */
    val Caption: TextStyle = TextStyle(
        fontFamily = NovumFontFamily,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.3.sp,
    )

    /** 角标/徽章 12/15 Medium +0.4（Apple Caption2 松排；不承担必要信息）。 */
    val Label: TextStyle = TextStyle(
        fontFamily = NovumFontFamily,
        fontSize = 12.sp,
        lineHeight = 15.sp,
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
