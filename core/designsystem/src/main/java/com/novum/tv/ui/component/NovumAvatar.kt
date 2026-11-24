package com.novum.tv.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.novum.tv.ui.theme.NovumPalette
import com.novum.tv.ui.theme.NovumType

/**
 * 圆形人物头像，**无图时也有占位**：姓名首字 + 按姓名哈希出的低饱和底色。
 *
 * 之前演员行/搜索命中直接调 [NovumImage]，url 为空时组件整体不渲染，
 * 80dp 的圆形位置上就是一块空白——一排演员里缺图的那几个像"掉了图"。
 * 现在占位画在图片**下层**：加载中透出首字，加载完成由 [NovumImage] 淡入盖住，
 * 加载失败则一直保留首字，三种状态都不会出现空洞。
 *
 * 底色刻意压低饱和度与明度（S 0.32 / V 0.30）：深色界面里只是"有块底色"，
 * 不会变成一排彩色圆点抢内容焦点；白字在其上对比度足够。
 *
 * @param url 头像地址；null/空串 = 只显示首字占位。
 * @param name 姓名（取首字与哈希底色都基于它）。
 * @param size 直径。
 * @param modifier 外部修饰符。
 */
@Composable
fun NovumAvatar(
    url: String?,
    name: String,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    // 图片加载成功后收起首字：头像多是透明底 PNG 时，首字会从人像后面透出来
    var imageLoaded by remember(url) { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(avatarPlaceholderColor(name)),
        contentAlignment = Alignment.Center,
    ) {
        if (!imageLoaded) {
            Text(
                text = avatarInitials(name),
                // 字号随头像尺寸走：80dp → 24sp；中文姓名两字并排仍留得下
                style = NovumType.BodyStrong.copy(
                    fontSize = (size.value * AVATAR_TEXT_RATIO).sp,
                    lineHeight = (size.value * AVATAR_TEXT_RATIO * 1.1f).sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = NovumPalette.Paper,
                maxLines = 1,
            )
        }
        // 有图时淡入覆盖占位；加载失败保持透明，首字自然兜底
        NovumImage(
            url = url,
            contentDescription = name,
            modifier = Modifier
                .matchParentSize()
                .clip(CircleShape),
            onLoadState = { imageLoaded = it },
        )
    }
}

/**
 * 姓名首字：中文/日文/韩文取前两个字，拉丁等拼音文字取前两个词首字母。
 *
 * 例：「张伟」→「张伟」；「Tom Hanks」→「TH」；「Cher」→「C」；空白 →「?」。
 */
internal fun avatarInitials(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "?"
    if (isCjk(trimmed[0])) return trimmed.take(2)
    return trimmed
        .split(' ', '\t', '　')
        .filter { it.isNotBlank() }
        .take(2)
        .map { it.first().uppercaseChar() }
        .joinToString("")
        .ifEmpty { "?" }
}

/**
 * 占位底色：按姓名哈希取色相，固定低饱和/低明度。
 *
 * 同一人物在任何页面（演员行、搜索、人物页）颜色一致，方便记忆；
 * 哈希用 [String.hashCode]（跨进程稳定，不依赖随机种子）。
 */
internal fun avatarPlaceholderColor(name: String): Color {
    // 取模后再补正：hashCode 可能是 Int.MIN_VALUE，absoluteValue 仍是负数，
    // 直接喂给 Color.hsv 会因色相越界抛异常
    val hue = (((name.hashCode() % 360) + 360) % 360).toFloat()
    return Color.hsv(hue = hue, saturation = 0.32f, value = 0.30f)
}

/** 判定是否为 CJK 字符（汉字/假名/谚文），用于决定取"字"还是取"首字母"。 */
private fun isCjk(char: Char): Boolean {
    val code = char.code
    return code in 0x4E00..0x9FFF || // 汉字
        code in 0x3040..0x30FF || // 平假名/片假名
        code in 0xAC00..0xD7AF // 谚文
}

/** 首字字号占头像直径的比例（80dp → 24sp）。 */
private const val AVATAR_TEXT_RATIO = 0.30f
