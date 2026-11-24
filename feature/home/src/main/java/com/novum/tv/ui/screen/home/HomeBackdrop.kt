package com.novum.tv.ui.screen.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.novum.tv.model.HomeEntry
import com.novum.tv.ui.component.NovumImage
import com.novum.tv.ui.component.backdropRequestSize
import com.novum.tv.ui.component.PosterArtwork
import com.novum.tv.ui.theme.NovumPalette
import kotlin.math.roundToInt

/**
 * 首页全屏背景层：实现视差、变暗、溶解与 Ken Burns 动效。
 */

/**
 * 全屏背景层：背景铺满视口，延伸到顶栏背后。
 *
 * 背景随 Hero 条目交叉淡入；预取与显示使用相同的解码尺寸。
 * 遮罩维持内容可读性：顶部压暗、底部渐入页面底色，左侧压暗信息区。
 *
 * Ken Burns 动效作用于背景图，文字组保持静止；每次条目切换时重新开始推近。
 *
 * Crossfade 只包图片层；占位与遮罩保持在图片层之外。
 *
 * @param entry 当前背景条目；null 时显示灰渐变占位。
 * @param progress 背景联动进度；绘制层读取 State 时只触发重绘，不触发重组。
 * @param driftPx 视差滞后下沉上限（px，f=1 时）。
 */
@Composable
internal fun FullScreenBackdrop(
    entry: HomeEntry?,
    progress: State<Pair<Float, Float>>,
    driftPx: Float,
) {
    // Ken Burns：30 秒内线性推近 1.0→1.08。
    val kenBurns = remember { Animatable(1f) }
    LaunchedEffect(entry?.let { it.backdropUrl ?: it.imageUrl }) {
        kenBurns.snapTo(1f)
        kenBurns.animateTo(
            1f + KEN_BURNS_SCALE,
            animationSpec = tween(durationMillis = KEN_BURNS_MS, easing = LinearEasing),
        )
    }
    Box(modifier = Modifier.fillMaxSize()) {
        // 整组溶解层（g 驱动）：图片/占位/遮罩/变暗一体淡入页面底色；
        // 遮罩终点色 == Ink，溶解到底色无一丝接缝
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = 1f - progress.value.second
                },
        ) {
            // 占位：图未上屏时兜底（轮播切换时不重建，单份）
            PosterArtwork(seed = entry?.title ?: "home-empty", modifier = Modifier.fillMaxSize())
            Crossfade(
                targetState = entry?.let { it.backdropUrl ?: it.imageUrl },
                animationSpec = tween(durationMillis = HERO_BACKDROP_FADE_MS),
                label = "homeBackdropImage",
                modifier = Modifier.fillMaxSize(),
        ) { backdrop ->
            if (backdrop != null) {
                    NovumImage(
                        url = backdrop,
                        // 背景装饰图不占语义：标题/简介交给信息块与行卡片
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            // 背景上探 25%，视差下沉时不露底；静止基准位向上补偿一半余量。
                            .backdropOverscan()
                            .graphicsLayer {
                                val f = progress.value.first
                                // 背景随滚动视差下沉，不随滚动缩放。
                                translationY = -size.height * (BACKDROP_OVERSCAN - 1f) / 2f + f * driftPx
                                // Ken Burns 推近叠加在视差之上；上探余量可容纳 1.08 倍缩放。
                                scaleX = kenBurns.value
                                scaleY = kenBurns.value
                            },
                        // 与预取同一解码尺寸：缓存 key 一致，首屏 Hero 零等待
                        requestSize = heroBackdropPixelSize(
                            windowSize = LocalWindowInfo.current.containerSize,
                            density = LocalDensity.current,
                        ),
                    )
                }
            }
            // 遮罩①：纵向——顶部兜顶栏可读，底部渐入页面底色兜滚动行内容
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(BACKDROP_VERTICAL_MASK),
            )
            // 遮罩②：横向左压暗，兜左侧信息块（背景在内容之下，只压暗图）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(BACKDROP_HORIZONTAL_MASK),
            )
            // ② 变暗：黑幕随过渡进度压上（内容接管页面时背景光退场）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = BACKDROP_DIM_MAX * progress.value.first
                    }
                    .background(Color.Black),
            )
        }
    }
}

/**
 * 背景两联动滚动进度：`(f, g)`。
 *
 * - **f（过渡段）**：Hero 行位移 / 静止底位——视差、变暗的驱动值；
 * - **g（溶解段）**：背景整组的不透明度倒数。Hero 滚出视口的瞬间到
 *   [HERO_EXIT_DISSOLVE]（半黑），继续往下滚到下一个内容行时到 1（全黑）。
 *
 * g 用「已滚过的距离」而不是「Hero 是否还在视口里」来算：后者在 Hero 恰好
 * 离开视口时会跳变（旧实现直接返回常量），上翻与下翻停在同一行却是两种亮度。
 * Hero 不在 [LazyListState.layoutInfo] 里时，用首可见行的下标 + 行内偏移
 * 续上这段距离——列表结构固定为「0 顶栏 / 1 Hero / 2 起内容行」。
 *
 * 静止几何未捕获（NaN，冷启动首帧/深链恢复）时按完全离场处理（f=g=1）。
 * 绘制期调用，读 [LazyListState] 快照状态只触发重绘不重组。
 */
internal fun backdropProgress(
    listState: LazyListState,
    heroRestTopPx: Float,
    heroRestBottomPx: Float,
    backdropDeadZonePx: Float,
): Pair<Float, Float> {
    if (heroRestTopPx.isNaN() || heroRestBottomPx.isNaN()) return 1f to 1f
    val hero = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == HERO_ROW_KEY }
    if (hero == null) {
        val first = listState.layoutInfo.visibleItemsInfo.firstOrNull() ?: return 1f to 1f
        // 行内已滚过的比例：0 = 该行贴顶，1 = 该行刚好整行滚出
        val withinRow = if (first.size > 0) {
            (-first.offset.toFloat() / first.size).coerceIn(0f, 1f)
        } else {
            0f
        }
        val rowsPastFirstContent = (first.index - FIRST_CONTENT_ROW_INDEX) + withinRow
        val g = HERO_EXIT_DISSOLVE +
            (1f - HERO_EXIT_DISSOLVE) * rowsPastFirstContent.coerceIn(0f, 1f)
        return 1f to g
    }
    val heroTop = hero.offset.toFloat()
    // 软斜坡（替代硬钳制死区）：
    //   横向移焦点时 bring-into-view 会产生几像素纵向偏移，需要屏蔽；但硬钳制
    //   （<T 取 0、≥T 取实际值）在阈值处会**跳变**——25px 偏移瞬间让背景位移
    //   2.4px，纵向滚动起步时肉眼可见「咔」一下。
    //   软斜坡把阈值从曲线上**平移掉**：f = (scrolled − T) / (span − T)
    //   在 T 处 f=0 且连续，hero 完全离场时仍恰好 f=1（满视差）。
    //   代价：视差起步滞后 T（16dp），远小于一行的行程，不可感知。
    val scrolled = (heroRestTopPx - heroTop).coerceAtLeast(0f)
    val span = (heroRestBottomPx - backdropDeadZonePx).coerceAtLeast(1f)
    val f = ((scrolled - backdropDeadZonePx) / span).coerceIn(0f, 1f)
    // 溶解：Hero 滚过自身高度（正好完全离场）时到 HERO_EXIT_DISSOLVE，
    // 与下方「Hero 已离场」分支在边界处衔接，不跳变
    val heroHeight = (heroRestBottomPx - heroRestTopPx).coerceAtLeast(1f)
    val g = (scrolled / heroHeight).coerceIn(0f, 1f) * HERO_EXIT_DISSOLVE
    return f to g
}

/**
 * 背景图按 [BACKDROP_OVERSCAN] 倍视口高度测量，父级不裁剪，布局仍报告原视口高度。
 * 绘制时补偿一半扩展量，为视差下沉留出余量。
 *
 * 使用 min=max 的膨胀高度约束。内部只有
 * matchParentSize 子项（不参与撑开父级），minHeight=0 会让内容高度塌缩成
 * 0，导致背景图不可见。fillMaxHeight 的比例上限为 1.0，因此使用自定义 layout 扩展。
 */
private fun Modifier.backdropOverscan(): Modifier = layout { measurable, constraints ->
    val inflatedHeight = if (constraints.hasBoundedHeight) {
        (constraints.maxHeight * BACKDROP_OVERSCAN).roundToInt()
    } else {
        constraints.maxHeight
    }
    val placeable = measurable.measure(
        constraints.copy(
            minHeight = inflatedHeight,
            maxHeight = inflatedHeight,
        ),
    )
    layout(placeable.width, constraints.maxHeight) {
        placeable.placeRelative(0, 0)
    }
}

/**
 * 背景纵向遮罩：顶部保证顶栏可读，底部渐入页面底色。
 */
private val BACKDROP_VERTICAL_MASK = Brush.verticalGradient(
    0f to NovumPalette.Ink.copy(alpha = 0.42f),
    0.4f to NovumPalette.Ink.copy(alpha = 0.32f),
    1f to NovumPalette.Ink.copy(alpha = 0.9f),
)

/** 背景横向遮罩：左侧压暗以保证信息块可读。 */
private val BACKDROP_HORIZONTAL_MASK = Brush.horizontalGradient(
    0f to NovumPalette.Ink.copy(alpha = 0.5f),
    0.6f to Color.Transparent,
)

// 背景联动参数：视差、变暗与溶解。

/** Hero 行注册键，供布局注册和背景进度计算共用。 */
internal const val HERO_ROW_KEY = "hero"

/** 背景图上探比例：视差下沉不露底（静止基准位绘制期补偿一半）。 */
private const val BACKDROP_OVERSCAN = 1.25f

/** 背景视差滞后下沉上限（f=1 时；48dp < 上探余量 90dp，永不露底）。 */
internal val BACKDROP_DRIFT_DP = 48.dp

/** 背景联动完全展开时的最大遮罩 alpha。 */
private const val BACKDROP_DIM_MAX = 0.45f

/**
 * Hero 完全离场（滚过自身高度）时的溶解程度：0.5 = 半黑。
 *
 * 用户口径：「翻到继续观看行时应该半黑，到下面时应该全黑」。整组 alpha
 * 到 0 才算全黑，所以这里取一半，再往下滚一个内容行补到 1。
 */
internal const val HERO_EXIT_DISSOLVE = 0.5f

/**
 * 首个内容行在列表里的下标：0 = 顶栏、1 = Hero，之后才是内容行。
 *
 * Hero 滚出视口后用它把「首可见行」换算成「离场后又滚了几行」。
 */
private const val FIRST_CONTENT_ROW_INDEX = 2

/** 背景视差死区（dp）：软斜坡起点，屏蔽横向移焦点引起的纵向微抖。 */
internal val BACKDROP_DEAD_ZONE_DP = 16.dp

/** Hero 背景随条目切换的 crossfade 时长。 */
private const val HERO_BACKDROP_FADE_MS = 300

/** Ken Burns 推近幅度。 */
private const val KEN_BURNS_SCALE = 0.08f

/** Ken Burns 推近时长（30s 线性，相位随轮播重启）。 */
private const val KEN_BURNS_MS = 30_000

/**
 * Hero 背景图的解码像素尺寸：预取与显示消费点共用——
 * Coil 缓存 key 含解码尺寸，必须严格一致，否则预取等于白做。
 *
 * 解码尺寸为窗口宽度的 2/3、高度的 2/3 × 5/4，以适配背景扩展后的显示比例。
 * 预取和显示共用此函数，保证 Coil 缓存键一致。
 */
internal fun heroBackdropPixelSize(
    windowSize: IntSize,
    density: Density,
): IntSize = backdropRequestSize(windowSize, density)
