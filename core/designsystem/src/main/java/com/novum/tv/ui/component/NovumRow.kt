package com.novum.tv.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import coil3.SingletonImageLoader
import coil3.request.Disposable
import coil3.request.ImageRequest
import com.novum.tv.ui.theme.NovumDimens
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * TV 横向内容行：区块标题 + 高性能 LazyRow。
 *
 * 性能与焦点约定：
 * - **必须**通过 [key] 提供稳定且唯一的 key，避免列表刷新时卡片重建、焦点丢失；
 * - 只组合可视区域内的条目，滚动时复用 ViewHolder 级别的槽位；
 * - 焦点离开可视区域时由 LazyList 内建的 bring-into-view 自动滚动，不再手写滚动逻辑；
 *   （手写滚动会与内建滚动叠加，表现为画面抖动）；
 * - 内容左右留出安全边距，首尾卡片不会贴屏幕边缘；
 * - 行必须包在 [NovumFocusScrollScope] 里，为聚焦卡缩放留出裁剪余量。
 *
 * 焦点移动由 LazyList 内建 bring-into-view 处理，避免额外滚动动画相互干扰。
 *
 * @param elements 数据源；为空时仅渲染标题（调用方应改为空态）。
 * @param key 稳定 key 生成函数，通常是条目 id。
 * @param modifier 外部修饰符。
 * @param title 区块标题；为 null 时不渲染标题。
 * @param trailing 标题右侧的次要信息（如总数）。
 * @param trailingContent 标题右侧可聚焦槽位（如「查看全部 ›」按钮），优先于 [trailing]。
 *   注意：槽位在 [NovumFocusScrollScope] 之外，聚焦它不会触发行内滚动。
 * @param contentPadding 列表内容内边距；默认 null = 水平取 [edgePadding]、垂直 FocusBleed。
 *   LazyRow 会裁剪超出自身边界的内容，焦点放大需要这段余量，否则卡片边缘会被切掉。
 *   **水平必须对称**，使首尾卡的视觉位置与标题对齐。
 * @param itemSpacing 卡片间距。
 * @param focusScrollMargin 聚焦卡与行视口边缘的最小距离，默认与出血一致。
 * @param edgePadding 行标题与内容共用的水平边缘；用于对齐卡片和行标题。
 * @param prefetchUrl 方向性预取的图址提取器；
 *   null = 不预取。可见区间向后预取 [PREFETCH_AHEAD] 张，图在焦点到达前就绪
 *   （磁盘 + 内存缓存），网络与解码离开焦点关键路径。
 * @param prefetchSize 预取解码尺寸（像素）；传 [cardRequestSize] 算出的标准卡
 *   显示尺寸——与显示同键，预取直接命中内存缓存（尺寸配对）。null =
 *   按原图解码（仅暖磁盘，内存缓存打不中）。
 * @param itemContent 单个条目的内容；`index` 可用于把首焦点定位到第一张卡。
 */
@Composable
fun <T> NovumRow(
    elements: List<T>,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    title: String? = null,
    trailing: String? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    contentPadding: PaddingValues? = null,
    itemSpacing: Dp = NovumDimens.CardGap,
    focusScrollMargin: Dp = NovumDimens.FocusBleed,
    edgePadding: Dp = NovumDimens.FocusBleed,
    prefetchUrl: ((T) -> String?)? = null,
    prefetchSize: IntSize? = null,
    itemContent: @Composable LazyItemScope.(index: Int, element: T) -> Unit,
) {
    val listState = rememberLazyListState()
    var focusedIndex by remember { mutableIntStateOf(-1) }
    val focusMarginPx = with(LocalDensity.current) { focusScrollMargin.toPx() }
    val spacingPx = with(LocalDensity.current) { itemSpacing.toPx() }
    val itemFocus = remember { mutableMapOf<Any, FocusRequester>() }
    val navigationScope = rememberCoroutineScope()
    // Keep the in-flight repeat-scroll job in Compose state. A plain local var
    // is recreated on every recomposition and drops this guard mid-animation.
    var navigationJob by remember { mutableStateOf<Job?>(null) }
    // 内容出血：水平取 edgePadding，垂直恒取 FocusBleed。
    val effectiveContentPadding = contentPadding ?: PaddingValues(
        start = edgePadding,
        end = edgePadding,
        top = NovumDimens.FocusBleed,
        bottom = NovumDimens.FocusBleed,
    )

    // 方向性预取：监听最后可见下标，向滚动方向预取 [PREFETCH_AHEAD] 张。
    // URL 随数据集记账以避免重复入队；请求尺寸与显示尺寸一致，确保命中内存缓存。
    val prefetched = remember(elements) { mutableSetOf<String>() }
    if (prefetchUrl != null) {
        val context = LocalContext.current
        val enqueueSize = prefetchSize
        LaunchedEffect(elements, prefetchUrl, enqueueSize) {
            val loader = SingletonImageLoader.get(context)
            // 行离开组合或页面切换时取消未完成的预取请求。
            val inflight = mutableListOf<Disposable>()
            try {
                snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
                    .distinctUntilChanged()
                    .collect { last ->
                        if (last < 0) return@collect
                        ((last + 1)..(last + PREFETCH_AHEAD)).forEach { index ->
                            elements.getOrNull(index)?.let { element ->
                                prefetchUrl(element)?.takeIf { it.isNotBlank() && prefetched.add(it) }
                                    ?.let { url ->
                                        runCatching {
                                            loader.enqueue(
                                                ImageRequest.Builder(context)
                                                    .data(url)
                                                    .apply {
                                                        if (enqueueSize != null) {
                                                            size(enqueueSize.width, enqueueSize.height)
                                                        }
                                                    }
                                                    .build(),
                                            ).also(inflight::add)
                                        }
                                    }
                            }
                        }
                    }
            } finally {
                inflight.forEach { runCatching { it.dispose() } }
            }
        }
    }

    // repeat 同时移动焦点（否则视口与焦点脱节）。
    // 长按连移只 moveFocus，位移交给 bring-into-view 完成。
    val focusManager = LocalFocusManager.current
    val syncStep: (FocusDirection) -> Unit = { dir ->
        focusManager.moveFocus(dir)
    }
    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        if (title != null) {
            NovumSectionHeader(
                title = title,
                trailing = trailing,
                trailingContent = trailingContent,
                horizontalPadding = edgePadding,
            )
        } else if (trailingContent != null) {
            // 无标题但有动作的行：动作仍要对齐到行的右端
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        end = edgePadding,
                        bottom = NovumDimens.TitleToRow,
                    ),
                horizontalArrangement = Arrangement.End,
            ) {
                trailingContent()
            }
        }
        // 行内焦点边距根治：把行的静止对齐线（contentPadding，首页 68dp）
        // 交给滚动规格当边距下限——聚焦首/尾卡时 bring-into-view 的滚动目标正好是
        // 静止线，移回行首/行尾必然与标题对齐，不再越过安全边距顶到屏幕边缘。
        // 中间卡维持「出血+溢出」小边距：缩放边缘不被裁即可，不额外多滚。
        // 单按、长按连移和跨行移动都由滚动规格统一对齐。
        NovumFocusScrollScope(
            focusMargin = focusScrollMargin,
            contentPadding = effectiveContentPadding,
        ) {
            LazyRow(
                state = listState,
                contentPadding = effectiveContentPadding,
                horizontalArrangement = Arrangement.spacedBy(itemSpacing),
                modifier = Modifier
                    .fillMaxWidth()
                    // 横向键拦截只做两件事：速率置闸与 repeat 消费+同步移焦
                    // （防系统再走一次 bring-into-view 一帧双滚）。
                    // 单按不拦截，位移与对齐交给内建 bring-into-view。
                    .onPreviewKeyEvent { event ->
                        val isHorizontalNavDown = event.type == KeyEventType.KeyDown &&
                            (event.key == Key.DirectionRight || event.key == Key.DirectionLeft)
                        val isRepeatNav = isHorizontalNavDown &&
                            event.nativeKeyEvent.repeatCount > 0
                        if (isHorizontalNavDown && !isRepeatNav) {
                            // 某些遥控器长按不提供 repeatCount，因此也按键流间隔检测。
                            NovumFastNav.onNavKeyDown()
                        }
                        if (isRepeatNav) {
                            NovumFastNav.poke()
                            val layout = listState.layoutInfo
                            val focused = layout.visibleItemsInfo.firstOrNull { it.index == focusedIndex }
                            if (navigationJob?.isActive == true) return@onPreviewKeyEvent true
                            val forward = event.key == Key.DirectionRight
                            val nextIndex = focusedIndex + if (forward) 1 else -1
                            if (focused != null && nextIndex in elements.indices) {
                                val next = layout.visibleItemsInfo.firstOrNull { it.index == nextIndex }
                                val nextSize = next?.size ?: focused.size
                                val nextOffset = next?.offset?.toFloat() ?: if (forward) {
                                    focused.offset + focused.size + spacingPx
                                } else {
                                    focused.offset - nextSize - spacingPx
                                }
                                val start = layout.viewportStartOffset + maxOf(layout.beforeContentPadding.toFloat(), focusMarginPx)
                                val end = layout.viewportEndOffset - maxOf(layout.afterContentPadding.toFloat(), focusMarginPx)
                                val delta = when {
                                    nextOffset < start -> nextOffset - start
                                    nextOffset + nextSize > end -> nextOffset + nextSize - end
                                    else -> 0f
                                }
                                if (delta != 0f) {
                                    val requester = itemFocus.getOrPut(key(elements[nextIndex])) { FocusRequester() }
                                    navigationJob = navigationScope.launch {
                                        listState.scrollBy(delta)
                                        repeat(3) {
                                            withFrameNanos { }
                                            if (runCatching { requester.requestFocus() }.getOrDefault(false)) return@launch
                                        }
                                    }
                                    return@onPreviewKeyEvent true
                                }
                            }
                            // repeat 同时移动焦点：消费事件——不消费时系统
                            // 还会再走一次 bring-into-view，一帧内两次滚动叠加成
                            // 抽帧观感。位移全交给 bring-into-view（含
                            // 首尾静止线对齐，见上方滚动规格注释）。
                            syncStep(
                                if (event.key == Key.DirectionRight) FocusDirection.Right else FocusDirection.Left,
                            )
                            return@onPreviewKeyEvent true
                        }
                        false
                    },
                    // 行两侧不加边缘渐隐；卡片滚出时直接贴屏幕边缘裁切。
            ) {
                itemsIndexed(
                    items = elements,
                    key = { _, element -> key(element) },
                    // 同一行内条目结构一致，显式声明 contentType 让 LazyRow 跨行复用槽位
                    contentType = { _, _ -> "novum-row-item" },
                ) { index, element ->
                    // 不再手动 bringIntoView：Compose 1.7+ 的 LazyList 在焦点移动时会自动滚动，
                    // 再叠加一层会让「聚焦 → 滚动 → 再滚动」互相拉扯，表现为画面抖动。
                    val requester = remember(key(element)) {
                        itemFocus.getOrPut(key(element)) { FocusRequester() }
                    }
                    Box(Modifier.focusRequester(requester).onFocusChanged { if (it.hasFocus) focusedIndex = index }) {
                        itemContent(index, element)
                    }
                }
            }
        }
    }
}

/** 方向性预取张数：可见区间向后多取 6 张（Netflix 快扫口径）。 */
private const val PREFETCH_AHEAD = 6
