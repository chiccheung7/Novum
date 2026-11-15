package com.novum.tv.ui.screen.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import com.novum.tv.core.designsystem.R
import com.novum.tv.model.HomeEntry
import com.novum.tv.model.HomeLatestRow
import com.novum.tv.model.HomeRow
import com.novum.tv.ui.component.LANDSCAPE_ASPECT
import com.novum.tv.ui.component.POSTER_ASPECT
import com.novum.tv.ui.component.NovumCard
import com.novum.tv.ui.component.NovumRow
import com.novum.tv.ui.component.cardRequestSize
import com.novum.tv.ui.theme.NovumDimens

/**
 * 首页内容行族：
 * 继续观看行 / 分库行 / 条目卡。
 */

/**
 * 首页「继续观看」行：16:9 标准卡加观看进度条。
 *
 * @param row 区块数据。
 * @param showProgress 是否显示观看进度条。
 * @param showRating 是否显示评分角标。
 * @param firstFocus 非空时挂到第一张卡（首焦落点）。
 * @param onEntryClick 条目点击回调。
 */
@Composable
internal fun HomeSectionRow(
    row: HomeRow,
    showProgress: Boolean,
    showRating: Boolean,
    firstFocus: FocusRequester?,
    onEntryClick: (HomeEntry) -> Unit,
) {
    // 预取尺寸与 EntryCard 显示解码配对：同键直接落内存缓存
    val density = LocalDensity.current
    val prefetchSize = remember(density) {
        cardRequestSize(NovumDimens.CardWidth, LANDSCAPE_ASPECT, density)
    }
    NovumRow(
        elements = row.items,
        key = { it.id },
        title = rowTitle(),
        // 卡片可见边缘统一落在 [NovumDimens.SafeHorizontal] 对齐线上（出血留在 padding 内）
        edgePadding = NovumDimens.SafeHorizontal,
        // 与 EntryCard 选图同口径：预取的图 = 显示的图
        prefetchUrl = { it.imageUrl ?: it.posterUrl },
        prefetchSize = prefetchSize,
    ) { index, entry ->
        // 图下两行卡文——第一行作品标题、第二行剧集
        // SxxExx · 分集标题 / 电影 年份 · 总时长（数据层 subtitle 已按此拼好）
        EntryCard(
            entry = entry,
            showProgress = showProgress,
            // 评分角标：继续观看/分库行统一展示
            showRating = true,
            onClick = { onEntryClick(entry) },
            modifier = firstFocus?.takeIf { index == 0 }?.let { Modifier.focusRequester(it) }
                ?: Modifier,
        )
    }
}

/**
 * 分库行使用 2:3 海报卡；行内卡片由 [NovumRow] 排列。
 *
 * @param row 库数据。
 * @param onOpenDetail 打开详情页。
 * @param onOpenLibrary 打开该库网格（「查看全部」）。
 * @param firstFocus 非空时挂到第一张卡（首焦兜底：无 Hero 且无继续观看时）。
 */
@Composable
internal fun HomeLatestSection(
    row: HomeLatestRow,
    onOpenDetail: (String) -> Unit,
    onOpenLibrary: (libraryId: String, libraryName: String) -> Unit,
    firstFocus: FocusRequester?,
) {
    // 预取尺寸与 EntryCard 显示解码配对：同键直接落内存缓存。
    // 分库行使用 2:3 海报卡。
    val density = LocalDensity.current
    val prefetchSize = remember(density) {
        cardRequestSize(NovumDimens.PosterCardWidth, POSTER_ASPECT, density)
    }
    NovumRow(
        elements = row.items,
        key = { it.id },
        title = row.libraryName,
        edgePadding = NovumDimens.SafeHorizontal,
        prefetchUrl = { it.posterUrl ?: it.imageUrl },
        prefetchSize = prefetchSize,
    ) { index, entry ->
        EntryCard(
            entry = entry,
            showProgress = false,
            showRating = true,
            usePoster = true,
            // 分库行第二行 = 发行年份 · 首个标签（题材第一个）
            subtitleOverride = buildList {
                entry.year?.let { add(it.toString()) }
                entry.genres.firstOrNull()?.takeIf { it.isNotBlank() }?.let { add(it) }
            }.takeIf { it.isNotEmpty() }?.joinToString(" · "),
            onClick = { onOpenDetail(entry.seriesId ?: entry.id) },
            modifier = (firstFocus?.takeIf { index == 0 }?.let { Modifier.focusRequester(it) }
                ?: Modifier),
        )
    }
}

/**
 * 首页条目卡片：继续观看行使用 16:9 标准卡，分库行使用 2:3 海报卡。
 *
 * @param entry 条目。
 * @param showProgress 是否显示进度条。
 * @param showRating 是否显示评分角标。
 * @param usePoster true 使用 2:3 海报卡并优先取 posterUrl；false 使用 16:9 标准卡。
 * @param onClick 点击回调。
 * @param modifier 外部修饰符（首焦索取器/焦点上报挂这里）。
 * @param onCardFocused 卡片获得焦点时的可选回调。
 */
@Composable
private fun EntryCard(
    entry: HomeEntry,
    showProgress: Boolean,
    showRating: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    usePoster: Boolean = false,
    /** 副标题覆盖（分库行用「标题 · 发行年份」）；null = 用 entry.subtitle。 */
    subtitleOverride: String? = null,
    onCardFocused: ((HomeEntry) -> Unit)? = null,
) {
    // 副标题：显式覆盖（分库行「标题 · 发行年份」）优先；继续观看行不再把剩余
    // 时间拼进第二行——参考详情页选集卡，移到卡图进度条上方
    // （progressText，与进度条错层展示）
    val subtitle = subtitleOverride ?: entry.subtitle
    NovumCard(
        title = entry.title,
        subtitle = subtitle,
        badge = entry.rating?.let { String.format(java.util.Locale.US, "%.1f", it) }
            .takeIf { showRating },
        imageUrl = if (usePoster) entry.posterUrl ?: entry.imageUrl else entry.imageUrl ?: entry.posterUrl,
        // 继续观看行（16:9）卡图左上角挂所属剧集 clearlogo
        // 分库行海报卡不挂（只针对继续观看行）
        logoUrl = if (usePoster) null else entry.logoUrl,
        progress = entry.progress.takeIf { showProgress },
        // 剩余时长：继续观看行参考详情页选集卡，文案放卡图
        // 进度条上方；数据层只在有进度时才产 remainingMinutes，海报卡恒 null
        progressText = entry.remainingMinutes
            ?.takeIf { showProgress }
            ?.let { stringResource(R.string.home_remaining_minutes, it) },
        width = if (usePoster) NovumDimens.PosterCardWidth else NovumDimens.CardWidth,
        aspectRatio = if (usePoster) POSTER_ASPECT else LANDSCAPE_ASPECT,
        // 继续观看行（16:9）要两行文字；分库行第二行走 subtitleOverride
        showSubtitle = true,
        onClick = onClick,
        modifier = modifier.onFocusChanged { state ->
            if (state.isFocused) onCardFocused?.invoke(entry)
        },
    )
}

/** 区块标题文案：首页现存唯一区块是「继续观看」。 */
@Composable
private fun rowTitle(): String = stringResource(R.string.home_row_resume)
