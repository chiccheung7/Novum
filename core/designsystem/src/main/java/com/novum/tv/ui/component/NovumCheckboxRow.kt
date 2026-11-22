package com.novum.tv.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.novum.tv.ui.icon.NovumIcons
import com.novum.tv.ui.theme.NovumPalette
import com.novum.tv.ui.theme.NovumShapes
import com.novum.tv.ui.theme.NovumType

/**
 * 对号框选项行：左侧方框（勾选显示对勾）+ 右侧文案。
 *
 * 视觉与 [NovumOptionRow] 同口径：聚焦白底黑字、选中浅玻璃底、
 * 未选透明底；方框用 [NovumIcons.CheckFilled] 作对号，未选为空心描边。
 * 用于「记住登录」这类布尔开关，比「开/关」文字行更直观。
 *
 * @param text 行文案。
 * @param checked 是否勾选。
 * @param onClick 点击切换。
 * @param modifier 外部修饰符。
 */
@Composable
fun NovumCheckboxRow(
    text: String,
    checked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clip(NovumShapes.Card)
            .background(
                when {
                    focused -> NovumPalette.Paper
                    checked -> NovumPalette.Glass
                    else -> Color.Transparent
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        val contentColor = when {
            focused -> NovumPalette.Ink
            else -> interactiveTextColor(focused = false, selected = checked)
        }
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(4.dp))
                .border(
                    width = 2.dp,
                    color = contentColor,
                    shape = RoundedCornerShape(4.dp),
                )
                .background(if (checked) NovumPalette.Accent else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(
                    imageVector = NovumIcons.CheckFilled,
                    contentDescription = null,
                    tint = NovumPalette.Ink,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Text(
            text = text,
            style = NovumType.Body,
            color = contentColor,
            maxLines = 1,
            modifier = Modifier.padding(start = 14.dp),
        )
    }
}
