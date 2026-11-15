package com.novum.tv.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * 圆角令牌：按官方 960×540dp 设计画布口径换算。
 * TV 卡片使用适度圆角，避免低分辨率输出下出现明显锯齿。
 */
object NovumShapes {

    /** 角标、徽章。 */
    val Badge = RoundedCornerShape(4.dp)

    /** 横版卡片（16:9，规范 corner.landscape 10u）。 */
    val Card = RoundedCornerShape(7.dp)

    /** 竖版海报卡（2:3，规范 corner.poster 8u）。 */
    val PosterCard = RoundedCornerShape(4.dp)

    /** 大卡片 / 浮层（规范 corner.modal 20u）。 */
    val Large = RoundedCornerShape(14.dp)

    /** 按钮（胶囊）。 */
    val Pill = RoundedCornerShape(percent = 50)
}
