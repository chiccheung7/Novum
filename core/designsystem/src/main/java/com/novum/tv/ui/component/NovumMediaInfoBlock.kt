package com.novum.tv.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.novum.tv.ui.theme.NovumPalette
import com.novum.tv.ui.theme.NovumShapes
import com.novum.tv.ui.theme.NovumType
import kotlin.math.roundToInt

/**
 * 媒体信息块：首页 Hero 与详情页共用，包含标题、元信息、题材标签、格式标志和简介。
 * 按钮由页面管理。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NovumMediaInfoBlock(
    title: String?,
    metaParts: List<String>,
    genreTags: List<String>,
    formatBadges: List<String>,
    overview: String?,
    modifier: Modifier = Modifier,
    logoUrl: String? = null,
    titleMaxLines: Int = 1,
    overviewLines: Int = MEDIA_INFO_OVERVIEW_LINES,
) {
    var logoFailed by remember(logoUrl) { mutableStateOf(false) }
    val showLogo = !logoUrl.isNullOrBlank() && !logoFailed
    Column(modifier = modifier) {
        if (showLogo) {
            NovumImage(
                url = logoUrl,
                contentDescription = title,
                contentScale = ContentScale.Fit,
                requestSize = logoRequestSize(LocalDensity.current),
                // 标题图不可用时仍要能识别作品；下一条目/新URL会重新尝试加载。
                onLoadState = { success -> logoFailed = !success },
                modifier = Modifier
                    .height(MEDIA_INFO_LOGO_HEIGHT)
                    .aspectRatio(MEDIA_INFO_LOGO_ASPECT),
            )
        } else if (title != null) {
            Text(
                text = title,
                style = NovumType.Display,
                color = NovumPalette.Paper,
                maxLines = titleMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (metaParts.isNotEmpty()) {
            if (showLogo || title != null) {
                Spacer(
                    modifier = Modifier.height(
                        if (showLogo) MEDIA_INFO_LOGO_TO_META else MEDIA_INFO_TITLE_TO_META,
                    ),
                )
            }
            Text(
                text = metaParts.joinToString(" · "),
                style = NovumType.Body,
                color = NovumPalette.PaperMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 题材标签和格式标志合并一行，两类胶囊保持水平中线对齐。
        if (genreTags.isNotEmpty() || formatBadges.isNotEmpty()) {
            Spacer(modifier = Modifier.height(MEDIA_INFO_META_TO_TAGS))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(MEDIA_INFO_CHIP_GAP),
                verticalArrangement = Arrangement.spacedBy(MEDIA_INFO_META_TO_TAGS),
            ) {
                genreTags.forEach { NovumTagChip(text = it, outlined = true) }
                formatBadges.forEach { NovumTagChip(text = it) }
            }
        }
        if (overview != null) {
            Spacer(modifier = Modifier.height(MEDIA_INFO_TAGS_TO_OVERVIEW))
            Text(
                text = overview,
                style = NovumType.Synopsis,
                // 标题保持纸白；简介以较小的常规字重和次级灰表达正文层级。
                color = NovumPalette.PaperFaint,
                maxLines = overviewLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 信息块小胶囊：细描边 + Label 小字。
 *
 * 实底档使用 [NovumPalette.Glass]；空心档保持透明底与细描边。两种样式等高，
 * 水平中线一致，文字垂直居中。
 */
@Composable
fun NovumTagChip(text: String, outlined: Boolean = false) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .heightIn(min = 30.dp)
            .clip(NovumShapes.Pill)
            .background(
                if (outlined) {
                    Color.Transparent
                } else {
                    NovumPalette.Glass
                },
            )
            .border(
                width = 1.dp,
                color = NovumPalette.Paper.copy(alpha = 0.35f),
                shape = NovumShapes.Pill,
            )
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            style = NovumType.Label,
            color = NovumPalette.Paper,
            maxLines = 1,
        )
    }
}

/** 信息块标题 → 元信息行。 */
val MEDIA_INFO_TITLE_TO_META = 16.dp

/** 信息块 ClearLogo 高度；横版标题图位于信息块顶部并替代文字标题。 */
val MEDIA_INFO_LOGO_TO_META = 20.dp

/**
 * 信息块 ClearLogo 高度为 104dp；横版标题图在信息块顶部替代大文字标题。
 */
val MEDIA_INFO_LOGO_HEIGHT = 104.dp

/** ClearLogo 容器比例 3.2:1：横版标题图不会被拉成方块。 */
const val MEDIA_INFO_LOGO_ASPECT = 3.2f

/** ClearLogo 解码超采样倍数：按显示尺寸 2× 解码，GPU 缩放后文字边缘不发虚。 */
private const val MEDIA_INFO_LOGO_SUPERSAMPLE = 2f

/** ClearLogo 解码像素尺寸（显示盒 104dp × 3.2 × 2×超采样）。 */
private fun logoRequestSize(density: Density): IntSize = with(density) {
    IntSize(
        width = (MEDIA_INFO_LOGO_HEIGHT * MEDIA_INFO_LOGO_ASPECT * MEDIA_INFO_LOGO_SUPERSAMPLE).roundToPx(),
        height = (MEDIA_INFO_LOGO_HEIGHT * MEDIA_INFO_LOGO_SUPERSAMPLE).roundToPx(),
    )
}

/** 元信息行 → 标签/标志行。 */
val MEDIA_INFO_META_TO_TAGS = 12.dp

/** 标签/标志行 → 简介。 */
val MEDIA_INFO_TAGS_TO_OVERVIEW = 16.dp

/** 胶囊间距（首页 CTA 间距同口径）。 */
val MEDIA_INFO_CHIP_GAP = 20.dp

/** 简介行数：首页与详情页共用 5 行。 */
const val MEDIA_INFO_OVERVIEW_LINES = 5
