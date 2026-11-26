package com.novum.tv.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.request.Disposable
import coil3.request.ImageRequest
import com.novum.tv.model.MediaItem
import com.novum.tv.ui.theme.NovumDimens
import com.novum.tv.ui.theme.NovumFocusTokens
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 媒体库下钻、收藏与搜索共用的 2:3 海报网格，按可用宽度自适应列数，接近末尾时预加载。
 *
 * 首列静止时与 [NovumDimens.SafeHorizontal] 对齐；卡宽由内容区宽度和列间距反算。
 * [NovumFocusTokens.bleed] 按实际卡尺寸计算出血，行间距至少为出血大小。
 * 列间距由 [COLUMN_GAP] 控制，聚焦缩放所需的水平余量包含在安全边距内。
 *
 * @param items 条目列表。
 * @param header 页头槽，非空时作为跨满整行的 item 0，随内容滚动。
 * @param onOpenDetail 点击条目时打开详情。
 * @param onLoadMore 滚动接近末尾时触发。
 * @param focusOnEnter 数据就绪后是否将焦点交给首卡；有其他初始焦点的宿主应传 false。
 * @param modifier 外部修饰符。
 */
@Composable
fun PosterGrid(
    items: List<MediaItem>,
    onOpenDetail: (String) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
    focusOnEnter: Boolean = true,
) {
    val gridState = rememberLazyGridState()
    val firstFocus = remember { FocusRequester() }

    // 首焦落首卡：NovumInitialFocus 等帧重试——网格 item 在 measure 期才组合，
    // 数据刚到位的那一帧 requestFocus 会因节点未挂载而静默失败
    if (focusOnEnter) {
        NovumInitialFocus(firstFocus, key = items.firstOrNull()?.id)
    }

    // 页头槽占 item 0：预取/加载更多的「最后可见下标」按内容 item 数
    // 平移（header 非空时 layout 下标 = 内容下标 + 1），快照流统一用内容下标
    val headerOffset = if (header != null) 1 else 0

    LaunchedEffect(gridState, items.size) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { lastVisible ->
                if (lastVisible >= items.size + headerOffset - PRELOAD_DISTANCE) onLoadMore()
            }
    }

    // 向可见区后方预取图片，将网络与解码移出滚动关键路径。
    // 预取使用与显示一致的 cardRequestSize，确保 Coil 内存缓存键匹配。
    // 已预取 URL 随数据集记账，避免重复入队和重复解码。
    val context = LocalContext.current
    val density = LocalDensity.current
    // 预取尺寸必须与显示解码键一致；列数和显示端一样按可用宽度计算。
    val windowWidthPx = LocalWindowInfo.current.containerSize.width
    val safePx = with(density) { SafeMargin.toPx() }
    val gapPx = with(density) { COLUMN_GAP.toPx() }
    val minCardPx = with(density) { MIN_POSTER_WIDTH.toPx() }
    val prefetchSize = remember(density, windowWidthPx, safePx, gapPx, minCardPx) {
        val contentPx = windowWidthPx - safePx * 2
        val columns = posterColumnCount(contentPx, minCardPx, gapPx)
        val cardPx = ((contentPx - gapPx * (columns - 1)) / columns)
            .roundToInt().coerceAtLeast(1)
        IntSize(cardPx, (cardPx / POSTER_ASPECT).roundToInt())
    }
    val prefetched = remember(items, prefetchSize) { mutableSetOf<String>() }
    LaunchedEffect(items, prefetchSize, headerOffset) {
        val loader = SingletonImageLoader.get(context)
        // 网格离开组合时立即 dispose 预取请求，避免孤儿请求阻塞后续页面的解码队列。
        val inflight = mutableListOf<Disposable>()
        try {
            snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
                .distinctUntilChanged()
                .collect { lastRaw ->
                    val last = lastRaw - headerOffset
                    if (last < 0) return@collect
                    ((last + 1)..(last + PREFETCH_AHEAD)).forEach { index ->
                        items.getOrNull(index)?.imageUrl?.takeIf { it.isNotBlank() && prefetched.add(it) }
                            ?.let { url ->
                                runCatching {
                                    loader.enqueue(
                                        ImageRequest.Builder(context)
                                            .data(url)
                                            .size(prefetchSize.width, prefetchSize.height)
                                            .build(),
                                    ).also(inflight::add)
                                }
                            }
                    }
                }
        } finally {
            inflight.forEach { runCatching { it.dispose() } }
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // 内容区宽（68dp 对齐线内侧）决定列数；卡宽与首页共用约180dp目标。
        val contentWidth = maxWidth - SafeMargin * 2
        val columns = posterColumnCount(contentWidth.value)
        // 卡宽按内容区宽减去列间距后均分，保证网格左右边缘与页头对齐。
        val cardWidth = mediaCardWidth(contentWidth)
        // 卡高 = 2:3 海报高 + 文字区（标题/元数据）；出血按实际卡尺寸反推
        val cardHeight = cardWidth / POSTER_ASPECT + NovumDimens.CardTextBlockMaxHeight(1)
        // 出血按实际卡片的最大边计算。
        val bleed = NovumFocusTokens.bleed(
            if (cardWidth > cardHeight) cardWidth else cardHeight,
        )
        val rowSpacing = if (ROW_GAP > bleed) ROW_GAP else bleed

        val directionKeys = remember {
            setOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)
        }

        // bring-into-view 的目标是图区 Card，图下文字不在目标矩形内。
        // 底边 margin 计入文字块、图文间距与出血；顶边只需出血余量。
        // 纵横两轴的焦点补滚由内建 bring-into-view 完成。
        NovumGridFocusScrollScope(
            topMargin = bleed,
            bottomMargin = NovumDimens.CardImageToTextGap + NovumDimens.CardTextBlockMaxHeight(1) + bleed +
                // 落地回摆死区余量（冻结点允许多沉一个死区，文字不被裁）
                COLUMN_BOTTOM_SETTLE_DEAD_ZONE,
        ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = gridState,
                contentPadding = PaddingValues(
                    // 首列卡静止位 = 68dp 对齐线（与首页行同垂线）；聚焦放大滑进
                    // 对齐线内侧的 padding 区，不会被网格视口裁剪
                    start = SafeMargin,
                    end = SafeMargin,
                    // top 必须同样预留：第一行卡片向上放大，缺这段会被网格视口裁掉顶边
                    top = bleed,
                    bottom = NovumDimens.SafeVertical,
                ),
                horizontalArrangement = Arrangement.spacedBy(COLUMN_GAP),
                verticalArrangement = Arrangement.spacedBy(rowSpacing),
                modifier = Modifier
                    .fillMaxWidth()
                    // 所有方向键均交给焦点系统；LazyGrid 的焦点搜索负责移动条目，
                    // bring-into-view 负责滚动。只消费会导致视口移动、当前条目焦点不动。
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key in directionKeys) {
                            if (event.nativeKeyEvent.repeatCount > 0) {
                                NovumFastNav.poke()
                            } else {
                                NovumFastNav.onNavKeyDown()
                            }
                        }
                        false
                    }

            ) {
                // 页头 item：跨满整行、随内容滚走；非空才占位
                if (header != null) {
                    item(
                        key = "poster-grid-header",
                        span = { GridItemSpan(maxLineSpan) },
                        contentType = "poster-grid-header",
                    ) {
                        Box(modifier = Modifier.padding(bottom = NovumDimens.RowGap)) {
                            header()
                        }
                    }
                }
                itemsIndexed(
                    items = items,
                    key = { _, item -> item.id },
                    // 同构网格：显式 contentType 让 LazyGrid 跨页复用槽位
                    contentType = { _, _ -> "poster-grid-item" },
                ) { index, item ->
                    NovumCard(
                        title = item.title,
                        subtitle = item.subtitle,
                        // 评分角标：网格浏览时提供决策信息（标杆全有）
                        badge = item.rating?.takeIf { it > 0f }
                            ?.let { String.format(java.util.Locale.US, "%.1f", it) },
                        width = cardWidth,
                        aspectRatio = POSTER_ASPECT,
                        imageUrl = item.imageUrl,
                        onClick = { onOpenDetail(item.id) },
                        modifier = Modifier
                            .then(
                                if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                            ),
                    )
                }
            }
        }
    }
}

/** 内容对齐线（首页同款）：静止首列卡与页面标题同垂线。 */
private val SafeMargin = NovumDimens.SafeHorizontal

/** 2:3 海报的目标宽度。 */
private val MIN_POSTER_WIDTH = NovumDimens.PosterCardWidth

/** 按内容宽和海报目标宽度计算列数，接受相同单位（dp 或 px）。 */
internal fun posterColumnCount(
    contentWidth: Float,
    minCardWidth: Float = NovumDimens.PosterCardWidth.value,
    gap: Float = COLUMN_GAP.value,
): Int {
    return mediaGridColumns(contentWidth, minCardWidth, gap)
}

/** 列间距（guide 布局模板间距峰值 20dp；放大溢出 ≈5dp 仍包得住）。 */
private val COLUMN_GAP = 20.dp

/** 行间距（规范 32u ≈ 21dp，取整 20dp；小于出血时让位给出血）。 */
private val ROW_GAP = 20.dp

/** 距离末尾多少项时开始预加载。 */
private const val PRELOAD_DISTANCE = 12

/** 方向性预取张数：可见区间向后多取 6 张（与 NovumRow 同口径）。 */
private const val PREFETCH_AHEAD = 6

