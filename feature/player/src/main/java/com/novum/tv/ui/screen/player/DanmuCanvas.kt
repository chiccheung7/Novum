package com.novum.tv.ui.screen.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.ExoPlayer
import com.novum.tv.model.DanmuComment
import com.novum.tv.model.DanmuConfig
import com.novum.tv.model.DanmuArea
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * 弹幕渲染引擎：布局纯函数 + Canvas 绘制。
 *
 * 布局采用**逐帧无状态重放**：每帧从「当前时间 - 最大存活时长」的窗口起点
 * 二分定位，重放窗口内的弹幕做车道分配。好处：
 * - 暂停天然冻结（位置不前进，画面不动）；
 * - seek 前后无需任何状态修复（重放自愈）；
 * - 无增量状态机，碰撞规则单一出处。
 * 成本：窗口内条目（滚动 8s + 定型 4s，典型 ≤ 100 条）× 车道数（≤ 20）的
 * 简单比较，远低于逐条 Compose 节点的重组开销。
 */

/** 一帧里的一条待绘制弹幕。 */
internal data class DanmuItem(
    val text: String,
    val x: Float,
    val y: Float,
    val color: Long,
)

/** 布局参数（渲染尺寸换算后；与 [DanmuConfig] 的档位解耦，便于单测注入像素值）。 */
internal data class DanmuLayoutSpec(
    val laneCount: Int,
    val fontPx: Float,
    val laneHeightPx: Float,
    val topInsetPx: Float,
    val scrollDurationMs: Long,
    val fixedDurationMs: Long,
    val density: Float,
) {
    init {
        require(laneCount >= 0) { "laneCount must not be negative" }
        require(density in 0f..1f) { "density out of range: $density" }
    }
}

/** 滚动弹幕横穿画面的基准时长（速度档位在此基础上缩放）。 */
internal const val DANMU_SCROLL_DURATION_MS = 8_000L

/** 顶部/底部弹幕的停留时长。 */
internal const val DANMU_FIXED_DURATION_MS = 4_000L

/** 单帧最多绘制的弹幕条数（封顶绘制成本）。 */
internal const val DANMU_MAX_VISIBLE = 60

/** 新弹幕进入车道时与同车道上一条尾部的最小间隙（像素）。 */
internal const val DANMU_LANE_GAP_PX = 24f

/** 只在所选显示区域分配完整车道，顶部区域为画面高度的四分之一。 */
internal fun danmuLaneCount(heightPx: Float, area: DanmuArea, laneHeightPx: Float, topInsetPx: Float): Int =
    ((heightPx * area.fraction - topInsetPx) / laneHeightPx).toInt().coerceAtLeast(0)

/** Increase the sampled comment volume as the display area grows from the default half screen. */
internal fun danmuEffectiveDensity(density: Float, area: DanmuArea): Float =
    (density.coerceIn(0f, 1f) * sqrt(area.fraction / DanmuArea.Half.fraction)).coerceIn(0f, 1f)

/**
 * 密度采样：确定性丢弃——同一条弹幕在任何帧、任何密度下的去留一致
 * （密度变大只「捡回」弹幕，不会整体换一批）。
 */
internal fun danmuKeptByDensity(comment: DanmuComment, density: Float): Boolean {
    if (density >= 1f) return true
    val bucket = ((comment.timeMs / 100L).toInt() * 31 + comment.text.hashCode()) and 0x7FFFFFFF
    return bucket % 1000 < (density * 1000f).toInt()
}

/**
 * 计算一帧的弹幕布局。
 *
 * @param comments 按时间升序的弹幕表。
 * @param positionMs 渲染时间（播放位置已扣除偏移）。
 * @param spec 布局参数。
 * @param widthPx 画面宽度（像素）。
 * @param measure 文本宽度测量（与绘制同一 Paint，保证一致）。
 */
internal fun danmuLayout(
    comments: List<DanmuComment>,
    positionMs: Long,
    spec: DanmuLayoutSpec,
    widthPx: Float,
    measure: (String) -> Float,
): List<DanmuItem> {
    if (spec.laneCount == 0) return emptyList()
    val maxDuration = maxOf(spec.scrollDurationMs, spec.fixedDurationMs)
    // 重放窗口比「可见范围」再往前整整一个生命周期：车道状态要靠这段历史预热。
    // 只从「可见起点」开始重放的话，窗口每滑动一帧就少掉一批历史，同一条弹幕
    // 会被重新分到别的车道——画面上就是弹幕一行一行地上下跳。
    val replayFrom = positionMs - maxDuration * 2
    var low = 0
    var high = comments.size
    while (low < high) {
        val mid = (low + high) ushr 1
        if (comments[mid].timeMs < replayFrom) low = mid + 1 else high = mid
    }
    // 车道状态：重放窗口重建
    val scrollLaneFreeAt = LongArray(spec.laneCount) // 每车道上一条滚动弹幕尾部让出进入间隙的时间
    val scrollLaneExitAt = LongArray(spec.laneCount)
    val topLaneBusyUntil = LongArray(spec.laneCount)
    val bottomLaneBusyUntil = LongArray(spec.laneCount)
    val items = ArrayList<DanmuItem>(32)
    var index = low
    while (index < comments.size && items.size < DANMU_MAX_VISIBLE) {
        val comment = comments[index]
        if (comment.timeMs > positionMs) break
        index++
        if (!danmuKeptByDensity(comment, spec.density)) continue
        val elapsed = positionMs - comment.timeMs
        // 车道判定一律用**弹幕自己的时间**，不能用当前帧时间：后者会让同一条弹幕
        // 随着播放位置推进而"变得可用"，分到的车道逐帧变化（垂直跳动）。
        // 已过期的弹幕仍参与车道记账（只是不绘制、不测量），
        // 这样窗口起点附近的车道占用与上一帧完全一致。
        // 过期弹幕也要量宽度：它占用车道多久由文本宽度决定，估错会让后续弹幕
        // 分到不同车道（宽度缓存命中后成本很低）
        val expired = elapsed > maxDuration
        val textWidth = measure(comment.text)
        when (comment.mode) {
            1 -> {
                val lane = pickScrollLane(
                    scrollLaneFreeAt,
                    scrollLaneExitAt,
                    comment.timeMs,
                    widthPx,
                    textWidth,
                    spec.scrollDurationMs,
                )
                if (lane >= 0) {
                    // 尾部完全进入画面（右边缘退回 widthPx-gap）的时刻；
                    // 过期弹幕的占用早已结束，直接记成它自己的时间即可
                    val enterProgress = (textWidth + DANMU_LANE_GAP_PX) / (widthPx + textWidth)
                    scrollLaneFreeAt[lane] =
                        comment.timeMs + (spec.scrollDurationMs * enterProgress).toLong()
                    scrollLaneExitAt[lane] = comment.timeMs + spec.scrollDurationMs
                }
                if (lane < 0 || elapsed > spec.scrollDurationMs) continue
                val progress = elapsed.toFloat() / spec.scrollDurationMs
                val x = widthPx - (widthPx + textWidth) * progress
                items += DanmuItem(comment.text, x, spec.topInsetPx + lane * spec.laneHeightPx, comment.color)
            }

            5 -> { // 顶部：车道从区域顶部向下
                val lane = firstFreeLane(topLaneBusyUntil, comment.timeMs, spec.laneCount)
                if (lane >= 0) topLaneBusyUntil[lane] = comment.timeMs + spec.fixedDurationMs
                if (lane < 0 || elapsed > spec.fixedDurationMs) continue
                items += DanmuItem(comment.text, (widthPx - textWidth) / 2f, spec.topInsetPx + lane * spec.laneHeightPx, comment.color)
            }

            4 -> { // 底部：车道从区域底部向上
                val lane = firstFreeLane(bottomLaneBusyUntil, comment.timeMs, spec.laneCount)
                if (lane >= 0) bottomLaneBusyUntil[lane] = comment.timeMs + spec.fixedDurationMs
                if (lane < 0 || elapsed > spec.fixedDurationMs) continue
                val y = spec.topInsetPx + (spec.laneCount - lane - 1) * spec.laneHeightPx
                items += DanmuItem(comment.text, (widthPx - textWidth) / 2f, y, comment.color)
            }

            else -> Unit // 协议外的模式直接忽略（解析层已过滤，防御）
        }
    }
    return items
}

/**
 * 滚动车道选择：挑「空闲最久」的车道（分散使用，避免弹幕扎堆同一区域）。
 *
 * @param atMs 弹幕进入时间（**不是**当前播放位置），保证同一批弹幕的分道结果
 *   与查询时刻无关。
 */
private fun pickScrollLane(
    laneFreeAt: LongArray,
    laneExitAt: LongArray,
    atMs: Long,
    widthPx: Float,
    textWidth: Float,
    scrollDurationMs: Long,
): Int {
    // 扫描起点按**弹幕自身时间**轮转：
    // - 只用"是否空闲"判断（不看历史占用早晚），重放窗口滑动不会改变结论 → 不跳行；
    // - 但固定从 0 号车道开始扫会让所有弹幕挤在头一两行（0 号车道上一旦让出间隙，
    //   下一条就又落在 0 号）——实测半屏只剩两三行在用。
    // 轮转以 timeMs 取模，同一条弹幕的起点恒定，相邻弹幕则从不同车道起扫，
    // 垂直方向自然铺开。
    val laneCount = laneFreeAt.size
    if (laneCount == 0) return -1
    val start = laneStartIndex(atMs, laneCount)
    for (offset in 0 until laneCount) {
        val lane = (start + offset) % laneCount
        if (laneFreeAt[lane] > atMs) continue
        // 全部文本使用同一存活时长，长文本横移更快。仅看入场间隙会让后来的
        // 长文本追上短文本；还要保证前一条离场时，新文本的左缘仍在其右侧。
        val safeTravelMs = scrollDurationMs *
            (widthPx - DANMU_LANE_GAP_PX).coerceAtLeast(0f) /
            (widthPx + textWidth).coerceAtLeast(1f)
        if (laneExitAt[lane] - atMs <= safeTravelMs) return lane
    }
    return -1
}

/**
 * 车道起扫位置：按弹幕时间每 [LANE_ROTATE_STEP_MS] 转一格。
 *
 * 是"弹幕时间"而不是"当前播放位置"——后者会让同一条弹幕随播放推进换道。
 */
private fun laneStartIndex(atMs: Long, laneCount: Int): Int {
    val step = ((atMs / LANE_ROTATE_STEP_MS) % laneCount).toInt()
    return if (step < 0) step + laneCount else step
}

/**
 * 定型弹幕车道选择：挑停留期结束最久的车道（同样的分散原则）。
 *
 * @param atMs 弹幕进入时间，同 [pickScrollLane]。
 */
private fun firstFreeLane(busyUntil: LongArray, atMs: Long, laneCount: Int): Int {
    // 同上：只按"此刻是否空闲"判断，起扫位置同样按弹幕时间轮转
    if (laneCount <= 0) return -1
    val start = laneStartIndex(atMs, laneCount)
    for (offset in 0 until laneCount) {
        val lane = (start + offset) % laneCount
        if (busyUntil[lane] <= atMs) return lane
    }
    return -1
}

/** 基准字号（sp）：密度归一化画布下的弹幕字号基准，档位在其上缩放。 */
internal val DANMU_BASE_FONT_SP = 20

/**
 * 弹幕覆盖层：挂在播放器 Surface 之上、控制层之下。
 *
 * 平滑度处理：
 * - 时钟按显示刷新回调（[withFrameNanos]）推进，而不是 `delay(50)` 轮询——
 *   后者受主线程调度抖动影响，位移会一顿一顿；
 * - 时钟只在**绘制阶段**读取（`Canvas` 的 draw lambda），位置变化只触发重绘，
 *   不重组覆盖层；
 * - 文本宽度按字号缓存，逐帧只做车道分配；
 * - 白字黑边用「先描边后填充」两支 Paint，不用 `setShadowLayer`
 *   （文字阴影走软件路径，弹幕一多就掉帧）。
 *
 * @param comments 弹幕表（按时间升序）。
 * @param config 渲染配置。
 * @param offsetMs 弹幕时间偏移（正=延后显示）。
 * @param player 播放器（读取实时位置；暂停时位置停走，弹幕自然冻结）。
 */
@Composable
internal fun DanmuOverlay(
    comments: List<DanmuComment>,
    config: DanmuConfig,
    offsetMs: Long,
    player: ExoPlayer,
    modifier: Modifier = Modifier,
) {
    if (!config.enabled || comments.isEmpty()) return
    // 绘制时钟：只在 draw lambda 里读，写它只失效绘制、不触发重组
    val clockMs = remember(player) { mutableLongStateOf(player.mediaPositionMs()) }
    LaunchedEffect(player, config.enabled) {
        var lastTickMs = 0L
        while (isActive) {
            withFrameNanos { frameNs ->
                // 与显示时钟同步，最多 60fps；播放暂停时不请求多余重绘。
                val nowMs = frameNs / 1_000_000L
                if (nowMs - lastTickMs >= DANMU_TICK_MS) {
                    lastTickMs = nowMs
                    val position = player.mediaPositionMs()
                    // 暂停时位置不变——写相同值不会失效绘制，等于零成本
                    if (position != clockMs.longValue) clockMs.longValue = position
                }
            }
        }
    }
    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val fontPx = with(density) { DANMU_BASE_FONT_SP.dp.toPx() } * config.size.scale
        val laneHeightPx = fontPx * 1.5f
        val topInsetPx = with(density) { 8.dp.toPx() }
        val laneCount = danmuLaneCount(heightPx, config.area, laneHeightPx, topInsetPx)
        val scrollDurationMs = (DANMU_SCROLL_DURATION_MS * config.speed.durationScale).toLong()
        val spec = DanmuLayoutSpec(
            laneCount = laneCount,
            fontPx = fontPx,
            laneHeightPx = laneHeightPx,
            topInsetPx = topInsetPx,
            scrollDurationMs = scrollDurationMs,
            fixedDurationMs = DANMU_FIXED_DURATION_MS,
            density = danmuEffectiveDensity(config.density, config.area),
        )
        // 填充与描边各一支 Paint：先描边后填充，避免 shadow layer 的软件绘制路径
        val fillPaint = remember(fontPx) {
            android.graphics.Paint().apply {
                isAntiAlias = true
                isFakeBoldText = true
                textSize = fontPx
            }
        }
        val outlinePaint = remember(fontPx) {
            android.graphics.Paint().apply {
                isAntiAlias = true
                isFakeBoldText = true
                textSize = fontPx
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = (fontPx * 0.09f).coerceAtLeast(1.5f)
                strokeJoin = android.graphics.Paint.Join.ROUND
                color = android.graphics.Color.BLACK
            }
        }
        var timeline by remember(comments, spec, widthPx) { mutableStateOf<DanmuTimeline?>(null) }
        LaunchedEffect(comments, spec, widthPx) {
            // Lane assignment and text measurement used to replay the preceding 16 seconds on
            // every video frame. Compile once off the UI thread, then only update x positions.
            timeline = withContext(Dispatchers.Default) {
                val measurePaint = android.graphics.Paint(fillPaint)
                val widths = TextWidthCache(measurePaint)
                compileDanmuTimeline(comments, spec, widthPx, widths::width)
            }
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            // 渲染时间 = 播放位置 - 偏移（偏移正=弹幕延后出现）
            val renderPosition = clockMs.longValue - offsetMs
            val alpha = (config.opacity.coerceIn(0.1f, 1f) * 255).toInt()
            val canvas = drawContext.canvas.nativeCanvas
            timeline?.forEachVisible(renderPosition) { text, x, y, color ->
                val baseline = y + fontPx
                outlinePaint.alpha = alpha
                canvas.drawText(text, x, baseline, outlinePaint)
                fillPaint.color = color.toInt()
                fillPaint.alpha = alpha
                canvas.drawText(text, x, baseline, fillPaint)
            }
        }
    }
}

internal data class ScheduledDanmu(
    val comment: DanmuComment,
    val width: Float,
    val y: Float,
    val durationMs: Long,
)

/** Seek-safe immutable schedule: no lane arrays, item lists or text measurements per frame. */
internal class DanmuTimeline(
    private val entries: List<ScheduledDanmu>,
    private val widthPx: Float,
    private val maxDurationMs: Long,
) {
    fun forEachVisible(positionMs: Long, draw: (String, Float, Float, Long) -> Unit) {
        var low = 0
        var high = entries.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (entries[mid].comment.timeMs < positionMs - maxDurationMs) low = mid + 1 else high = mid
        }
        var visible = 0
        var index = low
        while (index < entries.size && visible < DANMU_MAX_VISIBLE) {
            val entry = entries[index++]
            val elapsed = positionMs - entry.comment.timeMs
            if (elapsed < 0L) break
            if (elapsed > entry.durationMs) continue
            val x = if (entry.comment.mode == 1) {
                widthPx - (widthPx + entry.width) * elapsed.toFloat() / entry.durationMs
            } else (widthPx - entry.width) / 2f
            draw(entry.comment.text, x, entry.y, entry.comment.color)
            visible++
        }
    }
}

internal fun compileDanmuTimeline(
    comments: List<DanmuComment>, spec: DanmuLayoutSpec, widthPx: Float, measure: (String) -> Float,
): DanmuTimeline {
    if (spec.laneCount == 0) return DanmuTimeline(emptyList(), widthPx, 0L)
    val free = LongArray(spec.laneCount)
    val exit = LongArray(spec.laneCount)
    val top = LongArray(spec.laneCount)
    val bottom = LongArray(spec.laneCount)
    val entries = ArrayList<ScheduledDanmu>()
    comments.forEach { comment ->
        if (!danmuKeptByDensity(comment, spec.density)) return@forEach
        val width = measure(comment.text)
        val lane = when (comment.mode) {
            1 -> pickScrollLane(free, exit, comment.timeMs, widthPx, width, spec.scrollDurationMs)
            5 -> firstFreeLane(top, comment.timeMs, spec.laneCount)
            4 -> firstFreeLane(bottom, comment.timeMs, spec.laneCount)
            else -> -1
        }
        if (lane < 0) return@forEach
        val duration = if (comment.mode == 1) spec.scrollDurationMs else spec.fixedDurationMs
        when (comment.mode) {
            1 -> {
                free[lane] = comment.timeMs + (duration * (width + DANMU_LANE_GAP_PX) / (widthPx + width)).toLong()
                exit[lane] = comment.timeMs + duration
            }
            5 -> top[lane] = comment.timeMs + duration
            4 -> bottom[lane] = comment.timeMs + duration
        }
        val row = if (comment.mode == 4) spec.laneCount - lane - 1 else lane
        entries += ScheduledDanmu(comment, width, spec.topInsetPx + row * spec.laneHeightPx, duration)
    }
    return DanmuTimeline(entries, widthPx, maxOf(spec.scrollDurationMs, spec.fixedDurationMs))
}

/** 文本宽度缓存：同一字号下重复出现的弹幕不再逐帧 measureText。 */
private class TextWidthCache(private val paint: android.graphics.Paint) {
    private val widths = HashMap<String, Float>()

    fun width(text: String): Float = widths[text] ?: run {
        if (widths.size >= MAX_CACHED_WIDTHS) widths.clear()
        paint.measureText(text).also { widths[text] = it }
    }
}

/** Smooth animation at up to 60fps while avoiding redundant layout work on 120Hz displays. */
private const val DANMU_TICK_MS = 16L

/** 车道起扫位置的轮转步长：每 1 秒把起点后移一条车道。 */
private const val LANE_ROTATE_STEP_MS = 1_000L

/** 宽度缓存条目上限（超出整表清空，避免长视频里无限增长）。 */
private const val MAX_CACHED_WIDTHS = 4096
