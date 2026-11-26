package com.novum.tv.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.novum.tv.core.designsystem.R
import com.novum.tv.ui.theme.NovumPalette
import com.novum.tv.ui.theme.NovumShapes
import com.novum.tv.ui.theme.NovumType

/**
 * 开关行：布尔设置的整行形态（设置页、播放抽屉）。
 *
 * 为什么不是文字「已开启/已关闭」：文字值在远处只剩一行灰字，扫视时根本看不出
 * 当前是开还是关；轨道 + 圆点的位置本身就是形状信号，不依赖阅读。
 *
 * 焦点不用整行反白：布尔项是"开关"不是"选项"，反白会让人误以为要按 OK 选中；
 * 聚焦时只描 2dp 白边，同时**左右键直接切换**（左=关、右=开，方向语义固定，
 * 连按不会来回抖）。切换动效 150ms，与全站焦点动画同档。
 *
 * @param text 行文案。
 * @param checked 是否开启。
 * @param onCheckedChange 切换回调。
 * @param modifier 外部修饰符。
 * @param focusRequester 焦点索取器。
 * @param onFocused 焦点变化回调。
 * @param enabled 是否可操作；false = 灰字 + 开关淡出 + 不可聚焦
 *   （例如「没有字幕轨」时的字幕开关）。
 */
@Composable
fun NovumSwitchRow(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onFocused: ((Boolean) -> Unit)? = null,
    /** 已在开/关两端时是否继续吞掉方向键（抽屉里传 true，防焦点逃出面板）。 */
    consumeAtBoundary: Boolean = false,
    enabled: Boolean = true,
) {
    SwitchRow(
        text = text,
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier.fillMaxWidth(),
        focusRequester = focusRequester,
        onFocused = onFocused,
        horizontalPadding = 20.dp,
        verticalPadding = 16.dp,
        consumeAtBoundary = consumeAtBoundary,
        enabled = enabled,
    )
}

/**
 * 行内开关：标签只作说明，焦点与高亮仅落在开关控件上。
 *
 * @param text 文案。
 * @param checked 是否开启。
 * @param onCheckedChange 切换回调。
 * @param modifier 外部修饰符。
 * @param focusRequester 焦点索取器。
 * @param onFocused 焦点变化回调。
 */
@Composable
fun NovumSwitchInline(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onFocused: ((Boolean) -> Unit)? = null,
    labelSpacing: Dp = 8.dp,
) {
    var focused by remember { mutableStateOf(false) }
    val stateText = stringResource(
        if (checked) R.string.settings_state_on else R.string.settings_state_off,
    )
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(labelSpacing, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = NovumType.Body,
            color = NovumPalette.Paper,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Box(
            modifier = Modifier
                .size(width = 72.dp, height = 48.dp)
                .clip(NovumShapes.Pill)
                .background(if (focused) NovumPalette.RowSelected else Color.Transparent)
                .border(
                    width = if (focused) 2.dp else 0.dp,
                    color = if (focused) NovumPalette.Paper else Color.Transparent,
                    shape = NovumShapes.Pill,
                )
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .onFocusChanged { focused = it.isFocused; onFocused?.invoke(it.isFocused) }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft -> if (checked) {
                            onCheckedChange(false)
                            true
                        } else {
                            false
                        }

                        Key.DirectionRight -> if (!checked) {
                            onCheckedChange(true)
                            true
                        } else {
                            false
                        }

                        else -> false
                    }
                }
                .toggleable(
                    value = checked,
                    role = Role.Switch,
                    onValueChange = onCheckedChange,
                )
                .semantics {
                    contentDescription = text
                    stateDescription = stateText
                },
            contentAlignment = Alignment.Center,
        ) {
            NovumSwitch(checked = checked)
        }
    }
}

/**
 * 开关行的共同实现：文案 + 右侧开关，焦点只描白边，左右键直接切换。
 */
@Composable
private fun SwitchRow(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier,
    focusRequester: FocusRequester?,
    onFocused: ((Boolean) -> Unit)?,
    horizontalPadding: Dp,
    verticalPadding: Dp,
    consumeAtBoundary: Boolean,
    enabled: Boolean,
) {
    var focused by remember { mutableStateOf(false) }
    val stateText = stringResource(
        if (checked) R.string.settings_state_on else R.string.settings_state_off,
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused; onFocused?.invoke(it.isFocused) }
            .clip(NovumShapes.Card)
            // 焦点只描边不反白（与卡片同一套"白描边 = 焦点"语义）
            .border(
                width = 2.dp,
                color = if (focused) NovumPalette.Paper else Color.Transparent,
                shape = NovumShapes.Card,
            )
            .onPreviewKeyEvent { event ->
                if (!enabled) return@onPreviewKeyEvent false
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    // 左=关、右=开：方向语义固定，长按/连按不会来回抖。
                    // 已经在端点时不消费——否则焦点被这一行困住，右移不到下一个控件，
                    // 只能先按返回（设置页/登录页的实测问题）。
                    Key.DirectionLeft -> when {
                        checked -> { onCheckedChange(false); true }
                        else -> consumeAtBoundary
                    }

                    Key.DirectionRight -> when {
                        !checked -> { onCheckedChange(true); true }
                        else -> consumeAtBoundary
                    }

                    else -> false
                }
            }
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .semantics {
                role = Role.Switch
                stateDescription = stateText
            }
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
    ) {
        Text(
            text = text,
            style = NovumType.Body,
            // 不可操作：灰字（与禁用行同口径），一眼看出"这一项现在没法动"
            color = if (enabled) NovumPalette.Paper else NovumPalette.PaperFaint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(16.dp))
        NovumSwitch(
            checked = checked,
            modifier = Modifier.alpha(if (enabled) 1f else 0.4f),
        )
    }
}

/**
 * 纯开关控件（无文案）：轨道 56×32、圆点 24、内缩 4。
 *
 * @param checked 是否开启。
 */
@Composable
private fun NovumSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    val trackColor by animateColorAsState(
        targetValue = if (checked) NovumPalette.Paper else NovumPalette.Border,
        animationSpec = tween(SWITCH_ANIM_MS),
        label = "switchTrack",
    )
    val knobColor by animateColorAsState(
        targetValue = if (checked) NovumPalette.Ink else NovumPalette.PaperMuted,
        animationSpec = tween(SWITCH_ANIM_MS),
        label = "switchKnob",
    )
    val offset by animateDpAsState(
        targetValue = if (checked) SWITCH_TRAVEL else 0.dp,
        animationSpec = tween(SWITCH_ANIM_MS),
        label = "switchOffset",
    )
    Box(
        modifier = modifier
            .size(width = SWITCH_WIDTH, height = SWITCH_HEIGHT)
            .clip(NovumShapes.Pill)
            .background(trackColor),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                // lambda 重载：位移在布局阶段读取，动画每帧不触发重组
                .offset { IntOffset(x = (SWITCH_INSET + offset).roundToPx(), y = 0) }
                .size(SWITCH_KNOB)
                .clip(CircleShape)
                .background(knobColor),
        )
    }
}

/** 开关轨道高度。 */
private val SWITCH_HEIGHT = 32.dp

/** 开关轨道宽度。 */
private val SWITCH_WIDTH = 56.dp

/** 开关圆点直径。 */
private val SWITCH_KNOB = 24.dp

/** 圆点与轨道边缘的内缩。 */
private val SWITCH_INSET = 4.dp

/** 圆点开启态位移：轨宽 − 圆点 − 两倍内缩。 */
private val SWITCH_TRAVEL = SWITCH_WIDTH - SWITCH_KNOB - SWITCH_INSET * 2

/** 开关切换动画时长（与焦点动画同档）。 */
private const val SWITCH_ANIM_MS = 150
