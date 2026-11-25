package com.novum.tv.ui.component

import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 媒体库下钻、收藏与搜索共用的 2:3 海报网格，固定 6 列，接近末尾时预加载。
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
    // 预取尺寸必须与显示解码键一致：显示侧卡宽 = (容器宽 − 2×68dp
    // − 5×列距) / 6。容器宽取窗口宽（网格恒全宽），与 BoxWithConstraints 同源
    val windowWidthPx = LocalWindowInfo.current.containerSize.width
    val safePx = with(density) { SafeMargin.toPx() }
    val gapPx = with(density) { COLUMN_GAP.toPx() }
    val prefetchSize = remember(density, windowWidthPx, safePx, gapPx) {
        val contentPx = windowWidthPx - safePx * 2
        val cardPx = ((contentPx - gapPx * (POSTER_COLUMNS - 1)) / POSTER_COLUMNS)
            .coerceAtLeast(1f).toInt()
        IntSize(cardPx, (cardPx / POSTER_ASPECT).toInt())
    }
    val prefetched = remember(items) { mutableSetOf<String>() }
    LaunchedEffect(items) {
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
        // 内容区宽（68dp 对齐线内侧）→ 列数自适应：1280dp 落 6 列（2:3 177dp 卡）
        val contentWidth = maxWidth - SafeMargin * 2
        // 固定 6 列，与页头的安全对齐线同宽。
        val columns = POSTER_COLUMNS
        // 卡宽按内容区宽减去列间距后均分，保证网格左右边缘与页头对齐。
        val cardWidth = (contentWidth - COLUMN_GAP * (POSTER_COLUMNS - 1)) / POSTER_COLUMNS
        // 卡高 = 2:3 海报高 + 文字区（标题/元数据）；出血按实际卡尺寸反推
        val cardHeight = cardWidth / POSTER_ASPECT + NovumDimens.CardTextBlockMaxHeight(1)
        // 出血按实际卡片的最大边计算。
        val bleed = NovumFocusTokens.bleed(
            if (cardWidth > cardHeight) cardWidth else cardHeight,
        )
        val rowSpacing = if (ROW_GAP > bleed) ROW_GAP else bleed

        // 长按自建重复与 repeat 键流使用即时步进，避免与内建 bring-into-view 动画叠加。
        val gridScope = rememberCoroutineScope()
        val rowSpacingPx = with(LocalDensity.current) { rowSpacing.toPx() }
        // 纵向格距 = 卡高 + 行距，不使用可能被视口裁剪的可见项高度。
        val rowPitchPx = with(LocalDensity.current) { cardHeight.toPx() } + rowSpacingPx
        /**
         * 网格即时位移。
         *
         * LazyGridState.scrollBy 仅滚动纵向主轴：
         * - 上下键按整行位移。
         * - 左右键只移动焦点，不调用 scrollBy；bring-into-view 保证卡片可见。
         */
        val instantGridScroll: (FocusDirection) -> Unit = { dir ->
            if (dir == FocusDirection.Up || dir == FocusDirection.Down) {
                if (rowPitchPx > 0f) {
                    val forward = dir == FocusDirection.Down
                    gridScope.launch { gridState.scrollBy(if (forward) rowPitchPx else -rowPitchPx) }
                }
            }
        }
        val holdDirections = remember {
            mapOf(
                Key.DirectionUp to FocusDirection.Up,
                Key.DirectionDown to FocusDirection.Down,
                Key.DirectionLeft to FocusDirection.Left,
                Key.DirectionRight to FocusDirection.Right,
            )
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
                    // repeat 纵向键流即时移动整行并消费事件，避免系统再次补滚。
                    // 横向键流交给焦点系统；非 repeat 方向键仅做快扫速率检测。
                    .onPreviewKeyEvent { event ->
                        val isNavDown = event.type == KeyEventType.KeyDown && holdDirections.containsKey(event.key)
                        if (isNavDown && event.nativeKeyEvent.repeatCount > 0) {
                            val dir = holdDirections[event.key]!!
                            val vertical = dir == FocusDirection.Up || dir == FocusDirection.Down
                            if (vertical) {
                                // 纵向 repeat：整行即时位移
                                instantGridScroll(dir)
                                NovumFastNav.poke()
                                // 消费：避免系统再走一次 bring-into-view 造成双滚
                                true
                            } else {
                                // 横向 repeat：不位移、不消费——焦点在列间移动，
                                // bring-into-view 负责可见性
                                NovumFastNav.poke()
                                false
                            }
                        } else {
                            if (isNavDown) NovumFastNav.onNavKeyDown()
                            false
                        }
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

/** 海报网格固定列数，与页头同宽。 */
private const val POSTER_COLUMNS = 6

/** 列间距（guide 布局模板间距峰值 20dp；放大溢出 ≈5dp 仍包得住）。 */
private val COLUMN_GAP = 20.dp

/** 行间距（规范 32u ≈ 21dp，取整 20dp；小于出血时让位给出血）。 */
private val ROW_GAP = 20.dp

/** 距离末尾多少项时开始预加载。 */
private const val PRELOAD_DISTANCE = 12

/** 方向性预取张数：可见区间向后多取 6 张（与 NovumRow 同口径）。 */
private const val PREFETCH_AHEAD = 6

