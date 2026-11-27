package com.novum.tv.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.novum.tv.ui.theme.NovumPalette
import com.novum.tv.ui.theme.NovumShapes

/** 播放抽屉与弹层共用的深色玻璃材质；仅裁切面板，不影响内部文字与控件清晰度。 */
fun Modifier.novumGlassPanel(shape: Shape = NovumShapes.Large): Modifier =
    clip(shape)
        .background(NovumPalette.GlassDeep)
        .border(width = 1.dp, color = NovumPalette.Border, shape = shape)
