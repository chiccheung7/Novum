package com.novum.tv.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.novum.tv.ui.theme.NovumPalette
import com.novum.tv.ui.theme.NovumShapes
import com.novum.tv.ui.theme.NovumType

/**
 * 档位行：多档设置（界面大小/字号/速度/百分比…）的统一形态。
 *
 * 三档及以下按 OK 循环，不显示控制条；更多档位显示控制条，可用左右键调节。
 * OK 在所有档位行均按 +1 循环。
 *
 * 焦点只描 2dp 白边、不反白：这类行是"当前值"，不是"待选中的选项"。
 *
 * @param label 行文案。
 * @param value 当前档位文案（如 `80%` / `大`）。
 * @param level 当前档位下标（0 起）。
 * @param levelCount 总档数（≥2）。
 * @param onLevelChange 档位变化回调（绝对下标，已钳在 0..levelCount-1）。
 * @param modifier 外部修饰符。
 * @param focusRequester 焦点索取器。
 */
@Composable
fun NovumStepperRow(
    label: String,
    value: String,
    level: Int,
    levelCount: Int,
    onLevelChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val safeCount = levelCount.coerceAtLeast(2)
    val usesControlBar = safeCount > 3
    val current = level.coerceIn(0, safeCount - 1)
    // 本地乐观档位：设置写回是异步的，连按左右键若每次都读旧状态会丢步
    var pending by remember { mutableStateOf<Int?>(null) }
    val shown = (pending ?: current).coerceIn(0, safeCount - 1)
    LaunchedEffect(current, safeCount) {
        if (pending != null && current == pending) pending = null
    }
    fun emit(next: Int) {
        val clamped = next.coerceIn(0, safeCount - 1)
        pending = clamped
        onLevelChange(clamped)
    }
    val textColor = if (focused) NovumPalette.Paper else NovumPalette.PaperMuted

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clip(NovumShapes.Card)
            .border(
                width = 2.dp,
                color = if (focused) NovumPalette.Paper else Color.Transparent,
                shape = NovumShapes.Card,
            )
            .onPreviewKeyEvent { event ->
                if (!usesControlBar || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    // 到端点不消费：让焦点能左移到分区栏 / 右移到下一个控件
                    Key.DirectionLeft -> if (shown > 0) {
                        emit(shown - 1)
                        true
                    } else {
                        false
                    }

                    Key.DirectionRight -> if (shown < safeCount - 1) {
                        emit(shown + 1)
                        true
                    } else {
                        false
                    }

                    else -> false
                }
            }
            // OK：按 +1 循环（到顶回到第一档）
            .clickable { emit(if (shown >= safeCount - 1) 0 else shown + 1) }
            .semantics { stateDescription = value }
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text(
            text = label,
            style = NovumType.Body,
            color = NovumPalette.Paper,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (usesControlBar) {
            NovumAdjustmentBar(fraction = shown.toFloat() / (safeCount - 1), stepCount = safeCount)
            Spacer(modifier = Modifier.width(14.dp))
        }
        Text(
            text = value,
            style = NovumType.Caption,
            color = textColor,
            modifier = Modifier.width(112.dp),
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

