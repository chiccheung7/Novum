package com.novum.tv.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import com.novum.tv.ui.theme.NovumDimens
import com.novum.tv.ui.theme.NovumPalette
import com.novum.tv.ui.theme.NovumType

/**
 * 通用确认框：使用 Dialog 独立窗口，焦点自然限制在框内。
 * 默认焦点落在取消按钮，降低误触破坏性操作的风险。
 *
 * @param title 标题。
 * @param message 说明文案，讲清后果与替代方案。
 * @param confirmText 确认按钮文案。
 * @param cancelText 取消按钮文案。
 * @param onConfirm 确认回调。
 * @param onCancel 取消回调。
 * @param modifier 外部修饰符。
 */
@Composable
fun NovumConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    cancelText: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    scrimAlpha: Float = SCRIM_ALPHA,
) {
    val cancelRequester = remember { FocusRequester() }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        NovumInitialFocus(cancelRequester)
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(NovumPalette.Scrim.copy(alpha = scrimAlpha)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .width(DIALOG_WIDTH)
                    .novumGlassPanel(RoundedCornerShape(20.dp))
                    .padding(horizontal = 32.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(NovumDimens.TextBlockGap),
            ) {
                Text(
                    text = title,
                    // 弹窗尺寸受限：标题用 BodyStrong（22）而不是页级 Section（28），
                    // 否则 560dp 面板里一行字就占满，整块显得"糊脸"
                    style = NovumType.BodyStrong,
                    color = NovumPalette.Paper,
                )
                Text(
                    text = message,
                    style = NovumType.Caption,
                    color = NovumPalette.PaperMuted,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    // 操作按钮排列在右下角。
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                ) {
                    NovumButton(
                        onClick = onCancel,
                        modifier = Modifier
                            .width(176.dp)
                            .focusRequester(cancelRequester),
                        centerContent = true,
                    ) {
                        Text(text = cancelText, style = NovumType.ButtonLg)
                    }
                    NovumButton(
                        onClick = onConfirm,
                        modifier = Modifier.width(176.dp),
                        centerContent = true,
                    ) {
                        Text(text = confirmText, style = NovumType.ButtonLg)
                    }
                }
            }
        }
    }
}

/** 遮罩不透明度：压暗背景但保留画面轮廓，避免"跳转到另一个页面"的错觉。 */
private const val SCRIM_ALPHA = 0.78f

/** 对话框宽度（dp）。 */
private val DIALOG_WIDTH = 520.dp
