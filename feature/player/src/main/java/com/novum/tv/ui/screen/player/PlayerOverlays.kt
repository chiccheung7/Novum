package com.novum.tv.ui.screen.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.text.Cue
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.novum.tv.ui.theme.NovumPalette
import com.novum.tv.ui.theme.NovumType

/** 字幕层、缓冲指示、兜底提示条及其专属常量。 */

/**
 * 字幕层：控制条可见时上移，避免被进度条压住。
 *
 * 默认落位：宽银幕影片时放进**下黑边区域**，且与画面
 * 下缘保持距离（黑边高度的 40% 深处，不紧贴黑边上部）；黑边不足时回落到
 * 画面底部之上的原位置；控制条可见时仍让位到控制条上方。
 *
 * @param cues Media3 完整字幕 cue（保留位图、定位和样式）。
 * @param sizeLevel 字号档位（1 小 – 4 超大）。
 * @param weightLevel 粗细档位（0 细、1 标准、2 粗）。
 * @param offsetLevel 高度档位：0 居中，1/2 上移，3/4 下移。
 * @param controlsShown 控制条是否可见。
 * @param bottomBarHeight 视频比例换算出的下黑边高度；0 表示无黑边（满屏画面）。
 * @param modifier 外部修饰符（调用方负责 align BottomCenter）。
 */
@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun SubtitleLayer(
    cues: List<Cue>,
    sizeLevel: Int,
    offsetLevel: Int,
    controlsShown: Boolean,
    bottomBarHeight: Dp,
    videoAspectRatio: Float?,
    scaleMode: VideoScaleMode,
    modifier: Modifier = Modifier,
    weightLevel: Int = 1,
) {
    if (cues.isEmpty()) return
    // 高度档位：0=按黑边自适应，1/2 上移，3/4 下移（负档在满屏画面触底钳制）
    val lift = when (offsetLevel.coerceIn(0, 4)) {
        1 -> SUBTITLE_LIFT_STEP
        2 -> SUBTITLE_LIFT_STEP * 2
        3 -> -SUBTITLE_LIFT_STEP
        4 -> -SUBTITLE_LIFT_STEP * 2
        else -> 0.dp
    }
    // 字号越大文字向上扩展越多——自适应档位下让字幕底部同步下沉（减小 bottom），
    // 抵消文字上扩，使字幕顶部始终留在黑边内、不压到「画面/黑边交界」。
    val sizeSink = when (sizeLevel.coerceIn(1, 4)) {
        1 -> 0.dp
        2 -> 6.dp
        3 -> 14.dp
        4 -> 22.dp
        else -> 6.dp
    }
    val inBar = bottomBarHeight >= SUBTITLE_BAR_MIN
    val baseBottom = when {
        // 控制条出现：上移到控制条上方，且随字号增大额外抬高，确保真正躲避进度条
        controlsShown -> SUBTITLE_BOTTOM_WITH_CONTROLS + sizeSink / 2
        inBar -> (bottomBarHeight * SUBTITLE_BAR_OFFSET_RATIO - sizeSink).coerceAtLeast(4.dp)
        else -> SUBTITLE_BOTTOM_IDLE
    }
    val bottom = animateDpAsState(
        targetValue = (baseBottom + lift).coerceAtLeast(SUBTITLE_BOTTOM_MIN),
        animationSpec = tween(SUBTITLE_SHIFT_MS),
        label = "subtitleBottom",
    )
    // 字幕四档入字阶表：档位值唯一来源 NovumType.SubtitleSizes
    val fontSize = NovumType.subtitleSize(sizeLevel)
    val fontPx = with(LocalDensity.current) { fontSize.toPx() }
    val typeface = remember(weightLevel) {
        android.graphics.Typeface.create(
            if (weightLevel == 0) "sans-serif-light" else "sans-serif",
            if (weightLevel == 2) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL,
        )
    }
    val captionStyle = remember(typeface) {
        CaptionStyleCompat(android.graphics.Color.WHITE, android.graphics.Color.TRANSPARENT,
            android.graphics.Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_OUTLINE,
            android.graphics.Color.BLACK, typeface)
    }
    // Normal dialogue can use the adaptive lower bar. Positioned text and bitmap cues remain
    // in video coordinates so PGS/DVB graphics and ASS positioning keep their original geometry.
    val defaultCues = remember(cues) { cues.filter(::usesDefaultSubtitlePosition) }
    val positionedCues = remember(cues) { cues.filterNot(::usesDefaultSubtitlePosition) }
    BoxWithConstraints(modifier = modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        if (defaultCues.isNotEmpty()) {
            NativeSubtitleView(defaultCues, captionStyle, fontPx,
                (bottom.value.value / maxHeight.value.coerceAtLeast(1f)).coerceIn(0f, 0.9f), Modifier.fillMaxSize())
        }
        if (positionedCues.isNotEmpty()) {
            val viewport = videoViewport(maxWidth.value, maxHeight.value, videoAspectRatio, scaleMode)
            NativeSubtitleView(positionedCues, captionStyle, fontPx, 0f,
                Modifier.requiredSize(viewport.width.dp, viewport.height.dp))
        }
    }
}

internal fun usesDefaultSubtitlePosition(cue: Cue): Boolean = cue.bitmap == null &&
    cue.line == Cue.DIMEN_UNSET && cue.position == Cue.DIMEN_UNSET

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun NativeSubtitleView(cues: List<Cue>, style: CaptionStyleCompat, fontPx: Float, bottomFraction: Float, modifier: Modifier) {
    AndroidView(
        factory = { context -> SubtitleView(context).apply {
            isFocusable = false
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setApplyEmbeddedStyles(true)
            setApplyEmbeddedFontSizes(true)
        } },
        update = { view -> configureSubtitleView(view, cues, style, fontPx, bottomFraction) },
        modifier = modifier,
    )
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun configureSubtitleView(view: SubtitleView, cues: List<Cue>, style: CaptionStyleCompat, fontPx: Float, bottomFraction: Float) {
    view.setStyle(style)
    view.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, fontPx)
    view.setBottomPaddingFraction(bottomFraction)
    view.setCues(cues)
}

/**
 * 缓冲指示：36dp 圆弧，低透明度，200ms 淡入淡出。
 *
 * 刻意做得小且暗：电视上大号转圈会把注意力从画面上拽走。
 *
 * @param visible 是否显示。
 * @param speedBps 网速状态（缓冲时在圆弧下方展示）；负值表示还没有样本。
 * @param modifier 外部修饰符。
 */
@Composable
internal fun BufferingIndicator(
    visible: Boolean,
    modifier: Modifier = Modifier,
    speedBps: State<Long>? = null,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(BUFFERING_FADE_MS)),
        exit = fadeOut(tween(BUFFERING_FADE_MS)),
        modifier = modifier,
    ) {
        Column(
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        ) {
            val transition = rememberInfiniteTransition(label = "buffering")
            val angle = transition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = BUFFERING_SPIN_MS, easing = LinearEasing),
                ),
                label = "bufferingAngle",
            )
            Canvas(
                modifier = Modifier
                    .size(36.dp)
                    .graphicsLayer { rotationZ = angle.value },
            ) {
                drawArc(
                    color = NovumPalette.Paper.copy(alpha = BUFFERING_ALPHA),
                    startAngle = 0f,
                    sweepAngle = 270f,
                    useCenter = false,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                )
            }
            speedBps?.let { BufferingSpeedLabel(it) }
        }
    }
}

/** Read network samples in this leaf so updates never recompose the player stage. */
@Composable
private fun BufferingSpeedLabel(speedBps: State<Long>) {
    val value = speedBps.value
    if (value < 0L) return
    Text(
        text = formatSpeed(value),
        style = NovumType.Caption,
        color = NovumPalette.PaperMuted,
        modifier = Modifier.padding(top = 12.dp),
    )
}

/**
 * 兜底提示条：直连失败切转码时给一句人话。
 *
 * @param notice 提示文本，null 时不显示。
 * @param modifier 外部修饰符。
 */
@Composable
internal fun NoticeBanner(notice: String?, modifier: Modifier = Modifier) {
    // 淡出过程中 notice 已经是 null，保留最后一次文案，否则会闪一个空壳
    var display by remember { mutableStateOf("") }
    LaunchedEffect(notice) {
        if (notice != null) display = notice
    }
    AnimatedVisibility(
        visible = notice != null,
        enter = fadeIn(tween(NOTICE_FADE_MS)),
        exit = fadeOut(tween(NOTICE_FADE_MS)),
        modifier = modifier,
    ) {
        Text(
            text = display,
            style = NovumType.Caption,
            color = NovumPalette.Paper,
            modifier = Modifier
                .padding(top = 96.dp)
                .background(
                    color = NovumPalette.PanelOverlay,
                    shape = RoundedCornerShape(10.dp),
                )
                .padding(horizontal = 20.dp, vertical = 10.dp),
        )
    }
}

/** 字幕跟随控制条上移的时长。 */
private const val SUBTITLE_SHIFT_MS = 200

/** 控制条可见时字幕底部留白：抬到进度条/按钮行上方（含大字号余量）。 */
private val SUBTITLE_BOTTOM_WITH_CONTROLS = 152.dp

private val SUBTITLE_BOTTOM_IDLE = 40.dp

/**
 * 下黑边达到该高度即启用「放入黑边」自适应。
 * 从 60dp 降到 28dp：常见 2.35:1 影片在 16:9 屏上的单侧黑边约 40dp，
 * 旧阈值会把它误判为「无黑边」而贴底，字幕顶部因此压到画面交界。
 */
private val SUBTITLE_BAR_MIN = 28.dp

/** 字幕在下黑边内的位置比例（距屏幕底部 = 黑边高度的该比例；越小越贴底）。 */
private const val SUBTITLE_BAR_OFFSET_RATIO = 0.3f

/** 字幕高度档位步长。 */
private val SUBTITLE_LIFT_STEP = 40.dp

/** 低档位的物理下限：负档在满屏画面触此底（黑边内可继续下沉不受此限）。 */
private val SUBTITLE_BOTTOM_MIN = 12.dp

/** 缓冲动画淡入淡出时长。 */
private const val BUFFERING_FADE_MS = 200

/** 缓冲圆弧转速（一圈）。 */
private const val BUFFERING_SPIN_MS = 900

/** 缓冲圆弧透明度。 */
private const val BUFFERING_ALPHA = 0.55f

/** 兜底提示淡入淡出时长。 */
private const val NOTICE_FADE_MS = 180

/** 兜底提示停留时间。 */
internal const val NOTICE_DURATION_MS = 6_000L
