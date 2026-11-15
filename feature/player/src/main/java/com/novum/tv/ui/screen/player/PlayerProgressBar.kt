package com.novum.tv.ui.screen.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.novum.tv.core.designsystem.R
import com.novum.tv.ui.theme.NovumPalette
import com.novum.tv.ui.theme.NovumType
import com.novum.tv.model.Chapter

/**
 * 可聚焦的播放进度条。
 *
 * 为什么不用 Material `Slider`：TV 上 Slider 的按键步进、焦点框与拖动语义都不可控，
 * 遥控器左右键会被它当成「按像素调值」，实际体验是「按十下不动一格」。
 * 这里自绘轨道，把按键完全交给外层状态机，进度条只负责画和报告焦点。
 *
 * 交互约定（按键处理在 [PlayerStage] 的 `onPreviewKeyEvent` 里）：
 * - 聚焦后左右键 = ±5 秒精细 seek，按住超过 3 次升到 ±10 秒；
 * - 聚焦时轨道加粗、滑块放大并浮出「当前时间 / 剩余时间」；
 * - 命中区高 48dp，保证遥控器焦点落上来时视觉上明确「选中了」。
 *
 * @param position 实时播放位置。
 * @param duration 总时长，未知时为 0。
 * @param buffered 已缓冲位置。
 * @param scrubTarget 尚未落地的 seek 目标；非空时优先展示它，保证进度条跟手。
 * @param interactive 控制条是否可交互（隐藏时不可聚焦）。
 * @param focused 是否处于聚焦态（由外层状态机持有，避免两套焦点状态打架）。
 * @param requester 焦点索取器。
 * @param onFocusChanged 焦点变化回调。
 * @param modifier 外部修饰符。
 */
@Composable
internal fun PlayerProgressBar(
    position: State<Long>,
    duration: State<Long>,
    buffered: State<Long>,
    scrubTarget: Long?,
    interactive: Boolean,
    focused: Boolean,
    requester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    chapters: List<Chapter> = emptyList(),
) {
    val total = duration.value
    val current = (scrubTarget ?: position.value).coerceAtLeast(0L)
    val fraction = if (total > 0) (current.toFloat() / total).coerceIn(0f, 1f) else 0f
    val bufferedFraction = if (total > 0) {
        (buffered.value.toFloat() / total).coerceIn(0f, 1f)
    } else {
        0f
    }
    val description = stringResource(R.string.player_progress)

    Column(modifier = modifier.fillMaxWidth()) {
        // 固定高度的时间行：聚焦时才显示内容，但不改变布局高度，避免控制条上下跳
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(TIME_ROW_HEIGHT),
            verticalArrangement = Arrangement.Center,
        ) {
            AnimatedVisibility(
                visible = focused,
                enter = fadeIn(tween(TIME_FADE_MS)),
                exit = fadeOut(tween(TIME_FADE_MS)),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = formatTime(current),
                            style = NovumType.Body,
                            color = NovumPalette.Paper,
                        )
                        // 章节预览：拖动时显示目标位置所在章名
                        chapters.lastOrNull { it.startMs <= current }?.name?.let { name ->
                            Text(
                                text = "  ·  $name",
                                style = NovumType.Caption,
                                color = NovumPalette.PaperMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Text(
                        text = formatRemaining(current, total),
                        style = NovumType.Caption,
                        color = NovumPalette.PaperMuted,
                    )
                }
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(HIT_HEIGHT)
                .focusRequester(requester)
                .focusProperties { canFocus = interactive }
                .onFocusChanged { onFocusChanged(it.isFocused) }
                .focusable()
                .semantics {
                    contentDescription = description
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                },
        ) {
            val centerY = size.height / 2f
            val trackHeight = if (focused) FOCUSED_TRACK_HEIGHT.toPx() else TRACK_HEIGHT.toPx()
            val radius = trackHeight / 2f
            val knobRadius = if (focused) FOCUSED_KNOB_RADIUS.toPx() else KNOB_RADIUS.toPx()

            // 底轨
            drawRoundRect(
                color = NovumPalette.Paper.copy(alpha = TRACK_ALPHA),
                topLeft = Offset(0f, centerY - trackHeight / 2f),
                size = Size(size.width, trackHeight),
                cornerRadius = CornerRadius(radius, radius),
            )
            // 缓冲进度：比底轨亮一档，让用户知道「还能往前跳多远」
            if (bufferedFraction > 0f) {
                drawRoundRect(
                    color = NovumPalette.Paper.copy(alpha = BUFFER_ALPHA),
                    topLeft = Offset(0f, centerY - trackHeight / 2f),
                    size = Size(size.width * bufferedFraction, trackHeight),
                    cornerRadius = CornerRadius(radius, radius),
                )
            }
            // 已播进度
            val maxX = (size.width - knobRadius).coerceAtLeast(knobRadius)
            val playedX = (size.width * fraction).coerceIn(knobRadius, maxX)
            drawRoundRect(
                color = NovumPalette.Accent,
                topLeft = Offset(0f, centerY - trackHeight / 2f),
                size = Size(playedX.coerceAtLeast(trackHeight), trackHeight),
                cornerRadius = CornerRadius(radius, radius),
            )

            if (focused) {
                // 外圈半透明描边：焦点不靠颜色区分，靠尺寸与描边
                drawCircle(
                    color = NovumPalette.Paper.copy(alpha = KNOB_RING_ALPHA),
                    radius = knobRadius + KNOB_RING_GAP.toPx(),
                    center = Offset(playedX, centerY),
                    style = Stroke(width = KNOB_RING_WIDTH.toPx()),
                )
            }
            drawCircle(
                color = NovumPalette.Paper,
                radius = knobRadius,
                center = Offset(playedX, centerY),
            )
        }
    }
}

/** 进度条命中区高度。 */
private val HIT_HEIGHT = 48.dp

/** 时间行高度。 */
private val TIME_ROW_HEIGHT = 26.dp

/** 未聚焦轨道高度。 */
private val TRACK_HEIGHT = 4.dp

/** 聚焦轨道高度。 */
private val FOCUSED_TRACK_HEIGHT = 6.dp

/** 未聚焦滑块半径。 */
private val KNOB_RADIUS = 4.dp

/** 聚焦滑块半径。 */
private val FOCUSED_KNOB_RADIUS = 7.dp

/** 聚焦滑块外圈间距。 */
private val KNOB_RING_GAP = 3.dp

/** 聚焦滑块外圈宽度。 */
private val KNOB_RING_WIDTH = 2.dp

/** 底轨透明度。 */
private const val TRACK_ALPHA = 0.22f

/** 缓冲轨透明度。 */
private const val BUFFER_ALPHA = 0.34f

/** 滑块外圈透明度。 */
private const val KNOB_RING_ALPHA = 0.55f

/** 时间行淡入淡出时长。 */
private const val TIME_FADE_MS = 150
