package com.novum.tv.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import com.novum.tv.ui.theme.NovumDimens
import com.novum.tv.ui.theme.NovumPalette
import com.novum.tv.ui.theme.NovumType
import kotlinx.coroutines.launch

/**
 * 通用单选列表浮层（详情页版本选择 / 播放页选集换版本共用）：
 * 标题 + 若干 [NovumOptionRow] 行，初始焦点落当前生效项。
 *
 * 使用独立 `Dialog` 窗口，使焦点限制在浮层内。
 *
 * **长按呼出的收尾吞键**：遥控器长按 OK 呼出本层时，
 * 手指/按键**松开**那一下的 KeyUp 会落进刚挂上的首个聚焦行，被当成一次点击
 * 直接选中播放。传 [awaitKeyRelease] = true
 * 时本层先吞掉同一次按键的收尾（KeyUp 到达前不武装焦点、其间所有 OK 事件
 * 一律消费），armed 之后才把焦点交给当前项。
 *
 * @param title 浮层标题。
 * @param options 行文案（已由调用方拼好，含规格/集号等）。
 * @param selectedIndex 当前生效项下标（高亮 + 初始焦点）；null = 不高亮、焦点落首行。
 * @param awaitKeyRelease 长按呼出时传 true（见上方收尾吞键说明）。
 * @param onSelect 选中回调（携带下标）。
 * @param onDismiss 关闭回调。
 * @param width 浮层宽度；默认 [OPTION_DIALOG_WIDTH]（容纳「名 · SxxExx · 清晰度 ·
 *  动态范围 · 码率」整行不截断）。
 */
@Composable
fun NovumOptionListDialog(
    title: String,
    options: List<String>,
    selectedIndex: Int?,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    awaitKeyRelease: Boolean = false,
    width: Dp = OPTION_DIALOG_WIDTH,
) {
    val currentRowFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val focusIndex = selectedIndex?.takeIf { it in options.indices } ?: 0
    // 长按呼出：先不武装焦点，等「松手」那一下过去
    var armed by remember { mutableStateOf(!awaitKeyRelease) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        if (armed && options.isNotEmpty()) {
            // 异步选项到达、选中项改变或长按松开后，目标挂载完成才抓焦。
            NovumInitialFocus(currentRowFocus, key = focusIndex to options[focusIndex])
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(NovumPalette.Scrim.copy(alpha = OPTION_DIALOG_SCRIM_ALPHA))
                // 收尾吞键：未武装时消费一切 OK 事件，见到 KeyUp（松手）才武装焦点
                .onPreviewKeyEvent { event ->
                    if (!armed && event.isOkKey()) {
                        if (event.type == KeyEventType.KeyUp) armed = true
                        true
                    } else {
                        false
                    }
                }
                .padding(vertical = NovumDimens.SafeVertical),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .width(width)
                    .novumGlassPanel()
                    .padding(vertical = 12.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = title,
                    style = NovumType.Section,
                    color = NovumPalette.Paper,
                    modifier = Modifier.padding(
                        horizontal = NovumDimens.OverlayPaddingHorizontal,
                        vertical = NovumDimens.OverlayPaddingVertical,
                    ),
                )
                // selectedIndex 为 null / 越界（持久化值不在选项里）时不高亮任何行，
                // 但初始焦点仍要落到首行——否则没有任何行持有索取器，遥控器按 OK 无处可去
                options.forEachIndexed { index, option ->
                    val bringIntoView = remember { BringIntoViewRequester() }
                    NovumOptionRow(
                        text = option,
                        selected = index == selectedIndex,
                        onClick = { onSelect(index) },
                        focusRequester = currentRowFocus.takeIf { index == focusIndex },
                        onFocused = { focused ->
                            if (focused) scope.launch { bringIntoView.bringIntoView() }
                        },
                        modifier = Modifier
                            .padding(horizontal = 8.dp)
                            .bringIntoViewRequester(bringIntoView),
                    )
                }
            }
        }
    }
}

/** OK 键（遥控器确认）键码集合。 */
private fun KeyEvent.isOkKey(): Boolean =
    key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter

/** 浮层遮罩（与用户菜单/确认框同一档）。 */
private const val OPTION_DIALOG_SCRIM_ALPHA = 0.55f

/** 浮层宽度：容纳「电影名 · SxxExx · 清晰度 · 动态范围 · 码率」整行。 */
private val OPTION_DIALOG_WIDTH = 900.dp
