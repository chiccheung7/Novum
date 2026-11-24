package com.novum.tv.ui.screen.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.ExoPlayer
import com.novum.tv.model.DanmuComment
import com.novum.tv.model.DanmuConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

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
        require(laneCount >= 1) { "laneCount must be positive" }
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
    val maxDuration = maxOf(spec.scrollDurationMs, spec.fixedDurationMs)
    // 窗口起点：最早仍可能在画面上的弹幕（二分，O(log n)）
    var low = 0
    var high = comments.size
    while (low < high) {
        val mid = (low + high) ushr 1
        if (comments[mid].timeMs < positionMs - maxDuration) low = mid + 1 else high = mid
    }
    // 车道状态：重放窗口重建
    val scrollLaneFreeAt = LongArray(spec.laneCount) // 每车道上一条滚动弹幕尾部让出进入间隙的时间
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
        val textWidth = measure(comment.text)
        when (comment.mode) {
            1 -> {
                if (elapsed > spec.scrollDurationMs) continue
                val lane = pickScrollLane(scrollLaneFreeAt, positionMs)
                if (lane < 0) continue // 全部车道都放不下：本条丢弃（不阻塞后续更短的）
                // 尾部完全进入画面（右边缘退回 widthPx-gap）的时刻
                val enterProgress = (textWidth + DANMU_LANE_GAP_PX) / (widthPx + textWidth)
                scrollLaneFreeAt[lane] = comment.timeMs + (spec.scrollDurationMs.toFloat() * enterProgress).toLong()
                val progress = elapsed.toFloat() / spec.scrollDurationMs
                val x = widthPx - (widthPx + textWidth) * progress
                items += DanmuItem(comment.text, x, spec.topInsetPx + lane * spec.laneHeightPx, comment.color)
            }

            5 -> { // 顶部：车道从区域顶部向下
                if (elapsed > spec.fixedDurationMs) continue
                val lane = firstFreeLane(topLaneBusyUntil, positionMs, spec.laneCount)
                if (lane < 0) continue
                topLaneBusyUntil[lane] = comment.timeMs + spec.fixedDurationMs
                items += DanmuItem(comment.text, (widthPx - textWidth) / 2f, spec.topInsetPx + lane * spec.laneHeightPx, comment.color)
            }

            4 -> { // 底部：车道从区域底部向上
                if (elapsed > spec.fixedDurationMs) continue
                val lane = firstFreeLane(bottomLaneBusyUntil, positionMs, spec.laneCount)
                if (lane < 0) continue
                bottomLaneBusyUntil[lane] = comment.timeMs + spec.fixedDurationMs
                val y = spec.topInsetPx + (spec.laneCount - lane - 1) * spec.laneHeightPx
                items += DanmuItem(comment.text, (widthPx - textWidth) / 2f, y, comment.color)
            }

            else -> continue // 协议外的模式直接忽略（解析层已过滤，防御）
        }
    }
    return items
}

/** 滚动车道选择：挑「空闲最久」的车道（分散使用，避免弹幕扎堆同一区域）。 */
private fun pickScrollLane(laneFreeAt: LongArray, positionMs: Long): Int {
    var best = -1
    var bestFreeAt = Long.MAX_VALUE
    for (lane in laneFreeAt.indices) {
        val freeAt = laneFreeAt[lane]
        if (freeAt <= positionMs && freeAt < bestFreeAt) {
            best = lane
            bestFreeAt = freeAt
        }
    }
    return best
}

/** 定型弹幕车道选择：挑停留期结束最久的车道（同样的分散原则）。 */
private fun firstFreeLane(busyUntil: LongArray, positionMs: Long, laneCount: Int): Int {
    var best = -1
    var bestBusyUntil = Long.MAX_VALUE
    for (lane in 0 until laneCount) {
        val until = busyUntil[lane]
        if (until <= positionMs && until < bestBusyUntil) {
            best = lane
            bestBusyUntil = until
        }
    }
    return best
}

/** 基准字号（sp）：密度归一化画布下的弹幕字号基准，档位在其上缩放。 */
internal val DANMU_BASE_FONT_SP = 20

/**
 * 弹幕覆盖层：挂在播放器 Surface 之上、控制层之下。
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
    var positionMs by remember(player) { mutableLongStateOf(player.currentPosition) }
    LaunchedEffect(player, comments, config.enabled) {
        while (isActive) {
            positionMs = player.currentPosition
            delay(DANMU_FRAME_INTERVAL_MS)
        }
    }
    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val fontPx = with(density) { DANMU_BASE_FONT_SP.dp.toPx() } * config.size.scale
        val laneHeightPx = fontPx * 1.5f
        val topInsetPx = with(density) { 8.dp.toPx() }
        val areaHeight = heightPx * config.area.fraction
        val laneCount = ((areaHeight - topInsetPx) / laneHeightPx).toInt().coerceAtLeast(1)
        val scrollDurationMs = (DANMU_SCROLL_DURATION_MS * config.speed.durationScale).toLong()
        val spec = DanmuLayoutSpec(
            laneCount = laneCount,
            fontPx = fontPx,
            laneHeightPx = laneHeightPx,
            topInsetPx = topInsetPx,
            scrollDurationMs = scrollDurationMs,
            fixedDurationMs = DANMU_FIXED_DURATION_MS,
            density = config.density,
        )
        // 文本测量与绘制共用一支 Paint；字号档位变化时重建
        val paint = remember(fontPx) {
            android.graphics.Paint().apply {
                isAntiAlias = true
                isFakeBoldText = true
                textSize = fontPx
                // 描边阴影：白字黑边，三米外可读
                setShadowLayer(fontPx * 0.15f, 1.5f, 1.5f, android.graphics.Color.BLACK)
            }
        }
        // 渲染时间 = 播放位置 - 偏移（偏移正=弹幕延后出现）
        val renderPosition = positionMs - offsetMs
        val items = remember(comments, renderPosition, spec, widthPx) {
            danmuLayout(comments, renderPosition, spec, widthPx, paint::measureText)
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            val alpha = (config.opacity.coerceIn(0.1f, 1f) * 255).toInt()
            items.forEach { item ->
                paint.color = item.color.toInt()
                paint.alpha = alpha
                drawContext.canvas.nativeCanvas.drawText(item.text, item.x, item.y + fontPx, paint)
            }
        }
    }
}

/** 轮询播放位置的帧间隔（20fps 足够平滑；弹幕位移每帧约 2% 屏宽）。 */
internal const val DANMU_FRAME_INTERVAL_MS = 50L
