package com.novum.tv.ui.screen.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import androidx.tv.material3.Icon
import com.novum.tv.core.designsystem.R
import com.novum.tv.model.HomeEntry
import com.novum.tv.ui.component.NovumButton
import com.novum.tv.ui.component.novumActionButtonWidth
import com.novum.tv.ui.component.NovumMediaInfoBlock
import com.novum.tv.ui.icon.NovumIcons
import com.novum.tv.ui.theme.NovumDimens
import com.novum.tv.ui.theme.NovumType

/**
 * 首页 Hero 区：信息块与大号双 CTA。
 */

/** Hero 信息块宽度（字阶放大后容纳 64sp 标题与三行简介）。 */
private val HERO_INFO_WIDTH = 720.dp

/** Hero 双 CTA 间距。 */
private val HERO_CTA_GAP = 20.dp

/** Hero 双 CTA 高度（= 全站统一按钮高度 [NovumDimens.ButtonHeight]）。 */
private val HERO_CTA_HEIGHT = NovumDimens.ButtonHeight

/** Hero 区在短屏幕收紧高度，但保留 CTA 与元信息所需的最低空间。 */
private val HERO_INFO_ZONE_MIN_H = 360.dp
private val HERO_INFO_ZONE_MAX_H = 420.dp

/** 简介 → 双 CTA 间距（CTA 不再沉底，跟简介保持合适距离即可）。 */
private val HERO_OVERVIEW_TO_CTA = 24.dp

/**
 * Hero 区在页面级全屏背景 [FullScreenBackdrop] 上展示媒体信息和播放/详情双 CTA。
 * 信息块沿 [NovumDimens.SafeHorizontal] 对齐；播放 CTA 为首焦目标。
 * 内容由页面轮播驱动，6 秒无操作时切换条目，按键操作会重置计时。
 *
 * @param entry 当前展示作品（页面轮播驱动）。
 * @param onPlay 直接起播（电影条目）。
 * @param onDetail 打开详情页。
 * @param firstFocus 非空时挂到「立即播放」CTA（首焦落点）。
 * @param onPlayFocused 「立即播放」CTA 焦点变化回调（Back 分层状态）。
 */
@Composable
internal fun HeroSection(
    entry: HomeEntry?,
    onPlay: (String) -> Unit,
    onDetail: (String) -> Unit,
    firstFocus: FocusRequester?,
    onPlayFocused: ((Boolean) -> Unit)? = null,
) {
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val zoneHeight = adaptiveHeroInfoZoneHeight(windowHeight)
    val overviewLines = adaptiveHeroOverviewLines(zoneHeight)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(zoneHeight),
    ) {
        if (entry != null) {
            HeroInfoBlock(
                entry = entry,
                firstFocus = firstFocus,
                onPlayFocused = onPlayFocused,
                overviewLines = overviewLines,
                onDetail = { onDetail(entry.seriesId ?: entry.id) },
                onPlay = {
                    // 电影直接起播；剧集/分集进详情页选源（详情页再决定从哪播）
                    if (entry.type == "Movie") {
                        onPlay(entry.id)
                    } else {
                        onDetail(entry.seriesId ?: entry.id)
                    }
                },
            )
        }
    }
}

/**
 * Hero 信息块：首页与详情共用 [NovumMediaInfoBlock]，包含标题、元信息、标签、
 * 格式标志和简介；其下方为播放与详情双 CTA。
 *
 * @param entry 当前展示作品。
 * @param firstFocus 非空时挂到「立即播放」CTA（首焦落点）。
 * @param onPlayFocused 「立即播放」CTA 焦点变化回调（Back 分层状态）。
 * @param onDetail 「详情」CTA。
 * @param onPlay 「播放」CTA。
 */
@Composable
private fun HeroInfoBlock(
    entry: HomeEntry,
    firstFocus: FocusRequester?,
    onPlayFocused: ((Boolean) -> Unit)? = null,
    overviewLines: Int,
    onDetail: () -> Unit,
    onPlay: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(HERO_INFO_WIDTH)
            .fillMaxHeight()
            // 与全局内容对齐线一致。
            .padding(start = NovumDimens.SafeHorizontal),
    ) {
        // 信息块由 designsystem 组件渲染；有 ClearLogo 时替代文字标题。
        NovumMediaInfoBlock(
            title = entry.title,
            // ClearLogo 放在信息块顶部；无 logo 时显示文字标题。
            logoUrl = entry.logoUrl,
            metaParts = buildList {
                entry.year?.let { add(it.toString()) }
                when (entry.type) {
                    "Movie" -> add(stringResource(R.string.home_entry_movie))
                    "Series" -> add(stringResource(R.string.home_entry_series))
                }
                // 分级/分辨率插在类型与片长中间
                entry.officialRating?.takeIf { it.isNotBlank() }?.let { add(it) }
                entry.resolution?.let { add(it) }
                entry.runtimeMinutes?.let { add(formatRuntime(it)) }
                entry.rating?.takeIf { it > 0f }?.let {
                    add(String.format(java.util.Locale.US, "%.1f", it))
                }
            },
            genreTags = entry.genres.take(2),
            // 只显示动态范围；编码类标签不再上屏
            formatBadges = entry.videoFormats,
            overview = entry.overview,
            overviewLines = overviewLines,
        )
        // 简介与双 CTA 之间使用固定间距。
        Spacer(modifier = Modifier.height(HERO_OVERVIEW_TO_CTA))
        // 双 CTA 使用全站按钮规格；聚焦时白底黑字。
        Row(
            horizontalArrangement = Arrangement.spacedBy(HERO_CTA_GAP),
        ) {
            HeroPlayButton(
                onClick = onPlay,
                firstFocus = firstFocus,
                onPlayFocused = onPlayFocused,
            )
            NovumButton(
                onClick = onDetail,
                centerContent = true,
                modifier = Modifier
                    .width(novumActionButtonWidth())
                    .heightIn(min = HERO_CTA_HEIGHT),
            ) {
                Text(
                    text = stringResource(R.string.home_hero_detail),
                    style = NovumType.ButtonLg,
                    maxLines = 1,
                )
            }
        }
    }
}

internal fun adaptiveHeroInfoZoneHeight(windowHeight: Dp): Dp =
    (windowHeight * 0.58f).coerceIn(HERO_INFO_ZONE_MIN_H, HERO_INFO_ZONE_MAX_H)

internal fun adaptiveHeroOverviewLines(zoneHeight: Dp): Int = when {
    zoneHeight < 390.dp -> 3
    zoneHeight < 410.dp -> 4
    else -> 5
}

/**
 * 「立即播放」CTA：沿用统一最小高度，宽度由播放图标与文案决定。
 *
 * 电影直接起播；剧集进入详情页选择分集。
 */
@Composable
private fun HeroPlayButton(
    onClick: () -> Unit,
    firstFocus: FocusRequester?,
    onPlayFocused: ((Boolean) -> Unit)?,
) {
    NovumButton(
        onClick = onClick,
        modifier = Modifier
            .heightIn(min = HERO_CTA_HEIGHT)
            .then(firstFocus?.let { Modifier.focusRequester(it) } ?: Modifier)
            .then(
                if (onPlayFocused != null) {
                    Modifier.onFocusChanged { onPlayFocused(it.isFocused) }
                } else {
                    Modifier
                },
            ),
    ) {
        Icon(NovumIcons.Play, contentDescription = null, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.home_hero_play),
            style = NovumType.ButtonLg,
            maxLines = 1,
        )
    }
}

/** 片长格式化：小于 60 分钟显示分钟，否则显示小时和分钟。 */
@Composable
private fun formatRuntime(minutes: Int): String = when {
    minutes < 60 -> stringResource(R.string.media_runtime_minutes, minutes)
    minutes % 60 == 0 -> stringResource(R.string.media_runtime_hours, minutes / 60)
    else -> stringResource(R.string.media_runtime_hours_minutes, minutes / 60, minutes % 60)
}
