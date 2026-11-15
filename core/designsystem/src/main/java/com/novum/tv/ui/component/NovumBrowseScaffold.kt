package com.novum.tv.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.novum.tv.ui.theme.NovumDimens
import com.novum.tv.ui.theme.NovumFocusTokens
import com.novum.tv.ui.theme.NovumPalette

/**
 * 浏览页骨架。
 *
 * 职责：首焦重试与重锚、行下标自动分配、内容出血以及纵向焦点滚动边距。
 * 页面特有的 Back 分层与退出确认由宿主页处理。
 *
 * @param modifier 外部修饰符。
 * @param listState 可注入列表状态；有行级滚动语义时由宿主持有。
 * @param topInset 列表顶部留白。
 * @param firstFocus 首焦目标；null 表示页面自管。
 * @param focusEpoch 重锚版本号；null 表示仅首次挂载时抓焦。
 * @param enter 是否播放入场动画。
 * @param backgroundColor 骨架底色；背景全出血页面可传透明色。
 * @param content 行注册域。
 */
@Composable
fun NovumBrowseScaffold(
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    topInset: Dp = 0.dp,
    firstFocus: FocusRequester? = null,
    focusEpoch: Any? = null,
    enter: Boolean = false,
    backgroundColor: Color = NovumPalette.Ink,
    content: NovumBrowseScope.() -> Unit,
) {
    // 机制①：首焦重试 + 重锚（统一走共享实现）
    if (firstFocus != null) {
        NovumInitialFocus(firstFocus, key = focusEpoch ?: firstFocus)
    }

    // 机制⑤：按住即快滚自建重复——本机遥控器长按不产生原生
    // repeat（TCL 系统层截走合成键流），KeyDown 后 300ms 未见 KeyUp 即由
    val holdScope = rememberCoroutineScope()
    val rowGapPx = with(LocalDensity.current) { NovumDimens.RowGap.toPx() }
    // 纵向即时位移：连续 tween 滚动会落后于焦点跳格，与 bring-into-view 的弹簧动画互相冲突。使用 scrollBy 瞬移，使视口与焦点同步移动。
    val instantRowScroll: (FocusDirection) -> Unit = { dir ->
        val pitch = (listState.layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: 0) + rowGapPx
        if (pitch > 0f) {
            holdScope.launch { listState.scrollBy(if (dir == FocusDirection.Down) pitch else -pitch) }
        }
    }
    val holdDirections = remember {
        mapOf(
            Key.DirectionUp to FocusDirection.Up,
            Key.DirectionDown to FocusDirection.Down,
        )
    }
    // repeat 同时移动焦点，保持焦点与视口同步。
    val focusManager = LocalFocusManager.current
    val syncStep: (FocusDirection) -> Unit = { dir ->
        // 只 moveFocus，位移交给 bring-into-view，避免同帧叠加滚动。
        focusManager.moveFocus(dir)
    }

    // 机制④的动效侧：入场动画在绘制期读值（graphicsLayer 回调内），
    // 逐帧只重绘不重组，也不触发 Lazy 重测量
    val enterAnim = remember { Animatable(if (enter) 0f else 1f) }
    if (enter) {
        LaunchedEffect(Unit) {
            enterAnim.animateTo(1f, animationSpec = tween(durationMillis = ENTER_ANIMATION_MS))
        }
    }

    Box(modifier = modifier.fillMaxSize().background(backgroundColor)) {
        // 纵向焦点滚动边距：底边覆盖标准卡图下的文字块；预算包含图文间距、文字高度、底部安全区、缩放外溢和落地余摆。
        NovumColumnFocusScrollScope(
            topMargin = NovumDimens.FocusBleed,
            bottomMargin = NovumDimens.CardImageToTextGap +
                NovumDimens.CardTextBlockMaxHeight(1) +
                NovumDimens.SafeVertical +
                POSTER_CARD_COLUMN_GROWTH +
                COLUMN_BOTTOM_SETTLE_DEAD_ZONE,
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    // repeat 键流消费事件，避免系统同帧再执行 bring-into-view；焦点由首次 KeyDown 建立，repeat 期间系统不再移动焦点。
                    .onPreviewKeyEvent { event ->
                        val isNavDown = event.type == KeyEventType.KeyDown &&
                            (event.key == Key.DirectionUp || event.key == Key.DirectionDown)
                        if (isNavDown && event.nativeKeyEvent.repeatCount > 0) {
                            // repeat 逐格即时位移（/4.63）。
                            // **消费事件**——原返回 false 让系统再走一次
                            // bring-into-view，一帧内两次滚动（我们整格 + 系统补差）
                            // 叠加成「抽帧/掉帧」观感；消费后视口位移只有我们这一套。
                            // 焦点移动由上一个非 repeat KeyDown 建立，repeat 期间
                            // 系统本就不再移动焦点（TCL 键流 repeatCount 语义）
                            syncStep(
                                if (event.key == Key.DirectionDown) FocusDirection.Down else FocusDirection.Up,
                            )
                            NovumFastNav.poke()
                            true
                        } else {
                            if (isNavDown) NovumFastNav.onNavKeyDown()
                            false
                        }
                    }
                    .graphicsLayer {
                        alpha = enterAnim.value
                        val scale = ENTER_SCALE_START + (1f - ENTER_SCALE_START) * enterAnim.value
                        scaleX = scale
                        scaleY = scale
                    },
                verticalArrangement = Arrangement.spacedBy(NovumDimens.RowGap),
                // 机制③：顶部留白由宿主按页面形态注入（首页 = 安全区；详情页 = 0）
                contentPadding = PaddingValues(
                    top = topInset,
                    // 底部安全区由区块间距与垂直安全区组成，确保末行文字滚动到底时仍留有安全边距。
                    bottom = NovumDimens.SectionGap + NovumDimens.SafeVertical,
                ),
            ) {
                // 机制②：注册域每次列表重建时新建，cursor 从 0 起确定性计数
                NovumBrowseScope(this).content()
            }
        }
    }
}

/**
 * 浏览页行注册域（[NovumBrowseScaffold] 的 content 接收者）。
 *
 * 只用 [row]/[rows] 注册内容——它们保证下标自动分配正确；绕过它们直接调
 * LazyListScope 会拿不到正确的绝对行下标。
 */
class NovumBrowseScope internal constructor(
    private val list: LazyListScope,
) {
    private var cursor = 0

    /**
     * 注册单行。
     *
     * @param key 复用键（与 LazyColumn item key 语义一致）。
     * @param contentType 复用类型标记。
     * @param content 行内容；`row` 参数 = 该行在列表中的绝对下标。
     */
    fun row(
        key: Any? = null,
        contentType: Any? = null,
        content: @Composable LazyItemScope.(row: Int) -> Unit,
    ) {
        val index = cursor++
        list.item(key = key, contentType = contentType) { content(index) }
    }

    /**
     * 注册同构行组（如逐库行）：组内下标与绝对行下标同时交给内容。
     *
     * @param count 行数。
     * @param key 组内下标 → 复用键。
     * @param contentType 组内下标 → 复用类型标记。
     * @param content 行内容；`row` = 绝对下标（对齐用），`index` = 组内下标（取数用）。
     */
    fun rows(
        count: Int,
        key: (index: Int) -> Any,
        contentType: (index: Int) -> Any? = { null },
        content: @Composable LazyItemScope.(row: Int, index: Int) -> Unit,
    ) {
        if (count <= 0) return
        val base = cursor
        cursor += count
        list.items(
            count = count,
            key = { index -> key(index) },
            contentType = { index -> contentType(index) },
        ) { index -> content(base + index, index) }
    }
}

/** 浏览页入场动画时长（轻微放大 + 淡入）。 */
private const val ENTER_ANIMATION_MS = 500

/** 入场起始缩放：1.02 只做纵深感，不做「弹出来」。 */
private const val ENTER_SCALE_START = 1.02f

/**
 * 2:3 分库卡整列的聚焦放大下扩量：按图、图文间距和文字块的总高度乘以单侧缩放溢出比例计算。
 * 纵向底边预算据此留白，确保放大后的卡片文字不被裁切。
 */
private val POSTER_CARD_COLUMN_GROWTH =
    (NovumDimens.PosterCardWidth / POSTER_ASPECT +
        NovumDimens.CardImageToTextGap +
        NovumDimens.CardTextBlockMaxHeight(1)) *
        (NovumFocusTokens.CardScale - 1f) / 2f
