package com.novum.tv.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.novum.tv.ui.theme.NovumPalette
import com.novum.tv.ui.theme.NovumShapes
import com.novum.tv.ui.theme.NovumType

/**
 * TV 文本输入框。
 *
 * 与手机输入框的差异：
 * - 聚焦态用**边框变色**而不是放大，避免输入时布局抖动；
 * - 按遥控器「确定」键显式拉起软键盘（部分电视不会自动弹出）；
 * - 标签固定在上方，错误提示由调用方在下方渲染。
 *
 * @param value 当前文本。
 * @param onValueChange 文本变化回调。
 * @param label 字段标签。
 * @param modifier 外部修饰符。
 * @param placeholder 占位提示，为空时不显示。
 * @param isPassword 是否密码框（以圆点掩码显示）。
 * @param imeAction 软键盘回车键行为。
 * @param keyboardActions 软键盘动作回调，如 `onSearch`（搜索框专用）。
 */
@Composable
fun NovumTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    isPassword: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    var focused by remember { mutableStateOf(false) }
    val borderColor by animateColorAsState(
        targetValue = if (focused) NovumPalette.Paper else NovumPalette.Border,
        label = "fieldBorder",
    )
    val keyboard = LocalSoftwareKeyboardController.current

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            modifier = Modifier.padding(bottom = 6.dp),
            style = NovumType.Caption,
            color = NovumPalette.PaperMuted,
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .clip(NovumShapes.Card)
                .background(NovumPalette.Surface)
                .border(width = if (focused) 2.dp else 1.dp, color = borderColor, shape = NovumShapes.Card)
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .onFocusChanged { state -> focused = state.isFocused }
                // TV 上不在获得焦点时自动弹键盘；按确定键后才显示软键盘，避免遮挡页面内容。
                .onPreviewKeyEvent { event ->
                    // 只有「确定」键才拉起键盘；回车交给输入框处理 IME 动作（如搜索）
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionCenter) {
                        keyboard?.show()
                    }
                    false
                },
            textStyle = NovumType.Body.copy(color = NovumPalette.Paper),
            singleLine = true,
            cursorBrush = SolidColor(NovumPalette.Accent),
            visualTransformation = if (isPassword) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            keyboardOptions = KeyboardOptions(imeAction = imeAction),
            keyboardActions = keyboardActions,
            decorationBox = { innerTextField ->
                Box {
                    if (value.isEmpty() && placeholder != null) {
                        Text(
                            text = placeholder,
                            style = NovumType.Body,
                            color = NovumPalette.PaperMuted,
                        )
                    }
                    innerTextField()
                }
            },
        )
    }
}
