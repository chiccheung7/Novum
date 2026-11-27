package com.novum.tv.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import com.novum.tv.core.designsystem.R
import com.novum.tv.ui.theme.NovumDimens
import com.novum.tv.ui.theme.NovumPalette
import com.novum.tv.ui.theme.NovumType
import kotlinx.coroutines.launch

/**
 * 线路选择界面：从服务端读取线路与延迟，选择后锁定媒体流域名；API 鉴权域不变。
 * 未锁定时默认选中延迟最低线路。
 *
 * @param lines 按延迟升序排列的线路；null 表示加载中。
 * @param pinnedUrl 当前锁定线路；null/空表示未锁定。
 * @param onSelectLine 选中线路回调。
 * @param loading 是否加载中。
 * @param failed 是否加载失败。
 * @param onDismiss 关闭回调。
 */
@Composable
fun NovumLinePickerDialog(
    lines: List<LineOption>?,
    pinnedUrl: String?,
    onSelectLine: (LineOption?) -> Unit,
    loading: Boolean,
    failed: Boolean,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(NovumPalette.Scrim.copy(alpha = LINE_PICKER_SCRIM_ALPHA))
                .padding(vertical = NovumDimens.SafeVertical),
            contentAlignment = Alignment.Center,
        ) {
            NovumLinePickerPanel(
                lines = lines,
                pinnedUrl = pinnedUrl,
                onSelectLine = onSelectLine,
                loading = loading,
                failed = failed,
            )
        }
    }
}

/** 线路选择面板（弹窗内容层，单测直接驱动）；lines 为 null 表示加载中。 */
@Composable
fun NovumLinePickerPanel(
    lines: List<LineOption>?,
    pinnedUrl: String?,
    onSelectLine: (LineOption?) -> Unit,
    loading: Boolean,
    failed: Boolean,
) {
    val firstRowFocus = remember { FocusRequester() }
    val currentUrl = lines?.let { available ->
        pinnedUrl?.takeIf { pinned -> available.any { it.baseUrl == pinned } }
            ?: available.minByOrNull { it.latencyMs }?.baseUrl
    }

    if (!loading && !failed && currentUrl != null) {
        // 数据到位并挂上目标行后抓焦；窗口尚未接受焦点时沿用统一重试。
        NovumInitialFocus(firstRowFocus, key = currentUrl)
    }
    Column(
        modifier = Modifier
            .width(LINE_PICKER_PANEL_WIDTH)
            .novumGlassPanel()
            .padding(vertical = 24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text(
            text = stringResource(R.string.line_picker_title),
            style = NovumType.Section,
            color = NovumPalette.Paper,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
        when {
            loading -> Text(
                text = stringResource(R.string.line_picker_loading),
                style = NovumType.Body,
                color = NovumPalette.PaperMuted,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 24.dp),
            )

            failed -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp),
            ) {
                Text(
                    text = stringResource(R.string.line_picker_failed),
                    style = NovumType.Body,
                    color = NovumPalette.Paper,
                )
                Text(
                    text = stringResource(R.string.line_picker_failed_hint),
                    style = NovumType.Caption,
                    color = NovumPalette.PaperMuted,
                )
            }

            lines != null -> Column(
                modifier = Modifier.padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 未锁定时默认选中延迟最低线路；锁定后选中锁定线路。
                lines.forEach { line ->
                    val isCurrent = line.baseUrl == currentUrl
                    LineRow(
                        title = line.displayName,
                        detail = "${line.latencyMs}ms",
                        selected = isCurrent,
                        requestFocus = isCurrent,
                        focusRequester = if (isCurrent) firstRowFocus else null,
                        onClick = { onSelectLine(line) },
                    )
                }
            }
        }
    }
}

/** 线路行以 Paper 白底黑字表达焦点，以 Paper/PaperMuted 文字亮度表达选中态。 */
@Composable
private fun LineRow(
    title: String,
    detail: String,
    selected: Boolean,
    requestFocus: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val bringIntoView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val rowLabel = if (selected) "$title（${stringResource(R.string.line_picker_current)}）" else title
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoView)
            .then(
                if (requestFocus && focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                },
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) scope.launch { bringIntoView.bringIntoView() }
            }
            .background(if (focused) NovumPalette.Paper else Color.Transparent)
            .clickable(onClick = onClick)
            .semantics { contentDescription = rowLabel }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NovumDimens.Gutter),
    ) {
        Text(
            text = title,
            style = NovumType.Body,
            color = when {
                focused -> NovumPalette.Ink
                selected -> NovumPalette.Paper
                else -> NovumPalette.PaperMuted
            },
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = detail,
            style = NovumType.Caption,
            color = if (focused) NovumPalette.Ink else NovumPalette.Accent,
        )
    }
}

/** 线路选项（UI 层投影，domain 层 ServerLine 的展示子集）。 */
data class LineOption(
    val baseUrl: String,
    val displayName: String,
    val shortName: String,
    val latencyMs: Long,
)

/** 遮罩透明度（与 Profile 级弹层一致）。 */
private const val LINE_PICKER_SCRIM_ALPHA = 0.65f

/** 线路选择弹窗宽度。 */
private val LINE_PICKER_PANEL_WIDTH = 560.dp
