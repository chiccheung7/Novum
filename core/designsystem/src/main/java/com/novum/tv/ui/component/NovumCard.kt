package com.novum.tv.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.novum.tv.ui.theme.NovumDimens
import com.novum.tv.ui.theme.NovumFocusTokens
import com.novum.tv.ui.theme.NovumPalette
import androidx.compose.foundation.shape.RoundedCornerShape
import com.novum.tv.ui.theme.NovumShapes
import com.novum.tv.ui.theme.NovumType

/** 16:9 标准卡图区比例。 */
const val LANDSCAPE_ASPECT: Float = 16f / 9f

/** 2:3 竖版海报卡比例。 */
const val POSTER_ASPECT: Float = 2f / 3f

/**
 * 标准卡的显式解码尺寸（像素）：**显示与预取必须共用同一对**。
 *
 * Coil 内存缓存键包含解码尺寸；预取与显示尺寸不一致会重复解码并挤占内存缓存。
 *
 * 显示侧由 [NovumCard] 内部恒传（[NovumImage] 的 requestSize），预取侧
 * 由 [NovumRow] / 网格的入队请求传——同源同公式，键恒一致。
 *
 * @param width 卡宽（dp）；限宽卡（width != null）才可预计算。
 * @param aspectRatio 图区宽高比。
 * @param density 密度（像素换算）。
 */
fun cardRequestSize(width: Dp, aspectRatio: Float, density: Density): IntSize {
    val w = with(density) { width.roundToPx() }
    return IntSize(w, (w / aspectRatio).roundToInt())
}

/**
 * TV 标准卡：**官方 `Card` 包图区** + 图下 8dp 间距 + 居中标题 + 居中元数据。
 *
 * 结构与职责：
 * - **图区（官方 `Card` 承载）**：形状/描边/光晕全部经官方参数承载；
 *   常态描边透明、焦点态 2dp 白描边、光晕三态 None；
 * - **整卡浮起**：`tvFocusScale` 挂在包住图+文字的 Column 上，
 *   聚焦时海报与片名一起放大 [NovumFocusTokens.CardScale]（1.10）——官方 Card 的
 *   focusedScale 设为 1f，避免两套动画叠加；
 * - **文字区（卡外）**：标题 [NovumType.BodyStrong] + 元数据 [NovumType.Caption]
 *   居中单行省略，间距 [NovumDimens.CardImageToTextGap]（8dp）；
 * - **Novum 叠加层**：占位分层（[PosterArtwork] 灰渐变恒居底层，加载中/失败
 *   永不黑卡）、观看进度条（[progress]）、右上角角标（[badge]）；
 * - **语义锚点**：contentDescription 挂整卡容器（无图卡不消失）。
 *
 * 卡宽由调用方使用共享布局计算（16:9 目标 [NovumDimens.CardWidth]，2:3 目标
 * [NovumDimens.PosterCardWidth]）。
 *
 * 纯图形态（[showText] = false）：仅图区 `Card`，官方缩放承担聚焦反馈。
 *
 * @param title 主标题（影片名/剧集名；同时是卡容器的无障碍描述）。
 * @param modifier 外部修饰符。
 * @param subtitle 副标题（年份、集数、时长）。
 * @param width 卡片宽度；传 null 时铺满父容器。
 * @param aspectRatio 图区宽高比（16:9 用 [LANDSCAPE_ASPECT]，2:3 用 [POSTER_ASPECT]）。
 * @param badge 右上角角标文案（如 HDR、4K、评分、「下一集」）；与 [badgeIcon] 二选一。
 * @param badgeIcon 右上角角标图标（已看/未看状态对号等）；仅当 [badge] 为 null
 *   时渲染，胶囊尺寸与文案角标一致。
 * @param badgeIconTint 角标图标颜色；已看态传 [NovumPalette.Success]，null 使用纸白。
 * @param imageUrl 图地址；为空时回落渐变占位。
 * @param logoUrl 图区**左上角**透明底标题图（Clearlogo）：首页继续观看行与详情页
 *   选集卡展示所属剧集的 clearlogo；null 不渲染。
 * @param progress 观看进度（0f–1f）；非空在图底画进度条。
 * @param progressText 图区**右下角**小字（进度条同款黑底胶囊，如「剩 12 分钟」）；
 *   用于有观看进度的分集卡；null 不渲染。
 * @param showText 是否渲染下方标题/副标题文字区；false = 纯图卡。
 * @param showSubtitle 是否渲染副标题（16:9 卡只显示作品名，传 false）。
 * @param subtitleMaxLines 副标题行数；详情页选集卡的简介为 2 行。
 * @param glowing 是否为"当前选中"（如当前季/当前集）：图区左侧画 4dp 竖条，
 *   白描边专供焦点使用，两者不会互相抢占。
 * @param onClick 点击回调；null 时卡不可点击（仍可聚焦）。
 */
@Composable
fun NovumCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    width: Dp? = NovumDimens.CardWidth,
    aspectRatio: Float = LANDSCAPE_ASPECT,
    badge: String? = null,
    badgeIcon: ImageVector? = null,
    badgeIconTint: Color? = null,
    imageUrl: String? = null,
    logoUrl: String? = null,
    progress: Float? = null,
    progressText: String? = null,
    showText: Boolean = true,
    /** 是否显示副标题（16:9 卡只显示作品名）。 */
    showSubtitle: Boolean = true,
    subtitleMaxLines: Int = 1,
    subtitleStyle: TextStyle = NovumType.Label,
    subtitleColor: Color = NovumPalette.PaperMuted,
    imageAlpha: Float = 1f,
    glowing: Boolean = false,
    onClick: (() -> Unit)? = null,
    /** 图标角标的无障碍状态描述，不显示为文字。 */
    badgeIconContentDescription: String? = null,
) {
    // 品牌三态映射进官方参数（容器色恒深灰不改白）
    val cardShape = CardDefaults.shape(NovumShapes.Card, NovumShapes.Card, NovumShapes.Card)
    // 图文卡由外层 tvFocusScale 统一放大，官方缩放设为 1f，避免动画叠加。
    // 纯图卡由官方 Card 提供聚焦缩放。
    val imageOnlyScale = CardDefaults.scale(
        scale = 1f,
        focusedScale = NovumFocusTokens.CardScale,
        pressedScale = 1f,
    )
    // 标准卡（图下文字）：官方缩放归零，整卡浮起交给容器上的 tvFocusScale
    val noOfficialScale = CardDefaults.scale(
        scale = 1f,
        focusedScale = 1f,
        pressedScale = 1f,
    )
    // 焦点 = 白描边 + 整卡缩放：只靠 1.10 缩放在暗色海报上不够明确，
    // 官方也要求焦点态"一眼可见"。
    val cardBorder = CardDefaults.border(
        border = Border(BorderStroke(0.dp, Color.Transparent)),
        focusedBorder = Border(BorderStroke(FOCUS_FRAME_WIDTH, NovumPalette.Paper)),
        pressedBorder = Border(BorderStroke(FOCUS_FRAME_WIDTH, NovumPalette.Paper)),
    )
    // 显式关闭三态光晕，不依赖 tv-material 默认值。
    val cardGlow = CardDefaults.glow(
        glow = Glow.None,
        focusedGlow = Glow.None,
        pressedGlow = Glow.None,
    )
    val cardColors = CardDefaults.colors(
        containerColor = NovumPalette.InkElevated,
        contentColor = NovumPalette.Paper,
        focusedContainerColor = NovumPalette.InkElevated,
        focusedContentColor = NovumPalette.Paper,
    )
    val sizeModifier = if (width != null) Modifier.width(width) else Modifier.fillMaxWidth()
    // 显式解码尺寸（尺寸配对）：限宽卡可预计算，与行/网格预取同公式
    // （cardRequestSize），Coil 内存缓存键恒一致；width=null（铺满型）回布局约束
    val density = LocalDensity.current
    val imageRequestSize = width?.let { cardRequestSize(it, aspectRatio, density) }

    if (showText) {
        Column(
            modifier = modifier
                .then(sizeModifier)
                .tvFocusScale(NovumFocusTokens.CardScale),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 官方 Card 只包图区；图文缩放由外层容器统一提供，语义锚点挂在 Card 上。
            Card(
                onClick = onClick ?: {},
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) { contentDescription = title },
                shape = cardShape,
                colors = cardColors,
                scale = noOfficialScale,
                border = cardBorder,
                glow = cardGlow,
            ) {
                CardImageLayer(
                    title = title,
                    aspectRatio = aspectRatio,
                    badge = badge,
                    badgeIcon = badgeIcon,
                    badgeIconTint = badgeIconTint,
                    badgeIconContentDescription = badgeIconContentDescription,
                    imageUrl = imageUrl,
                    logoUrl = logoUrl,
                    progress = progress,
                    progressText = progressText,
                    requestSize = imageRequestSize,
                    selected = glowing,
                    imageAlpha = imageAlpha,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(modifier = Modifier.height(NovumDimens.CardImageToTextGap))
            Text(
                text = title,
                // 标题用 100% 白，副题降一档：10ft 距离下卡名是主要辨认信息
                style = NovumType.CardTitle,
                color = NovumPalette.Paper,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            if (showSubtitle) {
                Text(
                    text = subtitle.orEmpty(),
                    style = subtitleStyle,
                    color = subtitleColor,
                    maxLines = subtitleMaxLines,
                    minLines = subtitleMaxLines,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    } else {
        // 纯图卡：仅图区（语义锚点同样挂容器）
        Card(
            onClick = onClick ?: {},
            modifier = modifier.then(sizeModifier)
                .semantics(mergeDescendants = true) {
                    contentDescription = title
                },
            shape = cardShape,
            colors = cardColors,
            scale = imageOnlyScale,
            border = cardBorder,
            glow = cardGlow,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspectRatio),
            ) {
                CardImageLayer(
                    title = title,
                    aspectRatio = aspectRatio,
                    badge = badge,
                    badgeIcon = badgeIcon,
                    badgeIconTint = badgeIconTint,
                    badgeIconContentDescription = badgeIconContentDescription,
                    imageUrl = imageUrl,
                    logoUrl = logoUrl,
                    progress = progress,
                    progressText = progressText,
                    requestSize = imageRequestSize,
                    selected = glowing,
                    imageAlpha = imageAlpha,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * 卡图区（标准卡图区共用）：占位分层 + 图 + 左上角 clearlogo + 进度条 + 右下角
 * 进度文案 + 右上角角标。
 *
 * 占位分层：[PosterArtwork] 恒为底层——加载中与加载失败
 * （NovumImage 的 alpha 停在 0）都透出灰渐变，卡片不再出现纯黑空卡；
 * 图到达后 500ms 淡入覆盖。裁切对齐图顶部（构图主体在源图上部）。
 *
 * @param title 占位渐变与进度条种子。
 * @param aspectRatio 图区宽高比。
 * @param badge 右上角角标文案。
 * @param badgeIcon 右上角角标图标（[badge] 为 null 时渲染）。
 * @param badgeIconTint 角标图标颜色；null = 常规纸白（已看态实心对号传 Success 绿）。
 * @param imageUrl 图地址；null 时仅占位。
 * @param logoUrl 左上角 clearlogo 地址；null 不渲染。
 * @param progress 观看进度（0f–1f）。
 * @param progressText 右下角进度文案（如「剩 12 分钟」）；null 不渲染。
 * @param modifier 图区修饰符（调用方决定铺满或限宽）。
 * @param requestSize 显式解码尺寸（[cardRequestSize]；null = 按布局约束解码）。
 */
@Composable
private fun CardImageLayer(
    title: String,
    aspectRatio: Float,
    badge: String?,
    badgeIcon: ImageVector?,
    badgeIconTint: Color?,
    imageUrl: String?,
    logoUrl: String?,
    progress: Float?,
    progressText: String?,
    modifier: Modifier = Modifier,
    requestSize: IntSize? = null,
    selected: Boolean = false,
    imageAlpha: Float = 1f,
    badgeIconContentDescription: String? = null,
) {
    Box(modifier = modifier.aspectRatio(aspectRatio)) {
        PosterArtwork(seed = title, modifier = Modifier.fillMaxSize().alpha(imageAlpha.coerceIn(0f, 1f)))
        if (imageUrl != null) {
            NovumImage(
                url = imageUrl,
                // 无障碍描述已挂卡容器（整卡一个语义节点），图区置空防双层播报
                contentDescription = null,
                alignment = Alignment.TopCenter,
                requestSize = requestSize,
                modifier = Modifier.fillMaxSize().alpha(imageAlpha.coerceIn(0f, 1f)),
            )
        }
        // 选中标记：左侧 4dp 竖条（与列表行同一种"形状语言"）。
        // 白色 2dp 描边留给焦点——否则"没聚焦的已选卡"比"聚焦卡"还显眼。
        if (selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 12.dp, top = 14.dp, bottom = 14.dp)
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(NovumPalette.Paper, RoundedCornerShape(2.dp)),
            )
        }
        // 有叠加内容时在上下缘增加黑色渐变，保证 logo、角标和进度文案可读。
        if (logoUrl != null || progress != null || progressText != null ||
            badge != null || badgeIcon != null
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(CARD_SCRIM_BAND)
                    .background(CARD_TOP_SCRIM),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(CARD_SCRIM_BAND)
                    .background(CARD_BOTTOM_SCRIM),
            )
        }
        // 左上角显示所属剧集的 clearlogo，不额外叠加文字标题。
        if (logoUrl != null) {
            NovumImage(
                url = logoUrl,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(CARD_LOGO_PADDING)
                    .height(CARD_LOGO_HEIGHT)
                    .aspectRatio(CARD_LOGO_ASPECT),
            )
        }
        if (progress != null && progress > 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .height(3.dp)
                    .clip(NovumShapes.Pill)
                    .background(NovumPalette.SurfaceHigh),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .fillMaxSize()
                        .background(NovumPalette.Accent),
                )
            }
        }
        // 右下角进度文案（剩余时长）：与进度条同层，黑底胶囊兜亮底图可读性。
        // 进度文案与进度条错层显示；没有进度条时仍保持底部间距。
        if (progressText != null) {
            Text(
                text = progressText,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(horizontal = 8.dp)
                    .padding(bottom = 13.dp)
                    .clip(NovumShapes.Pill)
                    .background(NovumPalette.GlassDeep)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                style = NovumType.Label,
                color = NovumPalette.Paper,
            )
        }
        if (badge != null) {
            Text(
                text = badge,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .clip(NovumShapes.Pill)
                    .background(NovumPalette.GlassDeep)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                style = NovumType.Label,
                color = NovumPalette.Paper,
            )
        } else if (badgeIcon != null) {
            // 图标角标：与文案角标同款黑底胶囊，尺寸按图标自撑；已看态对号经
            // [badgeIconTint] 染绿，常规态纸白
            Icon(
                imageVector = badgeIcon,
                contentDescription = badgeIconContentDescription,
                tint = badgeIconTint ?: NovumPalette.Paper,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .clip(NovumShapes.Pill)
                    .background(NovumPalette.GlassDeep)
                    .padding(horizontal = 6.dp, vertical = 4.dp)
                    .size(BADGE_ICON_SIZE),
            )
        }
    }
}

/** 图标角标尺寸（与文案角标胶囊等高）。 */
private val BADGE_ICON_SIZE = 16.dp

/** 聚焦描边宽度（选中态改用左侧竖条，不再占用描边）。 */
private val FOCUS_FRAME_WIDTH = 2.dp

/** 卡图左上角 clearlogo 高度（小图位，与角标同量级、不喧宾夺主）。 */
private val CARD_LOGO_HEIGHT = 28.dp

/** 上下过渡遮罩带高度：覆盖 logo、进度和剩余时间。 */
private val CARD_SCRIM_BAND = 48.dp

/** 顶部遮罩：上缘压暗并向下淡出，保证 clearlogo 和角标可读。 */
private val CARD_TOP_SCRIM = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = 0.65f),
    1f to Color.Transparent,
)

/** 底部遮罩：下缘压暗并向上淡出，保证进度条和剩余时间可读。 */
private val CARD_BOTTOM_SCRIM = Brush.verticalGradient(
    0f to Color.Transparent,
    1f to Color.Black.copy(alpha = 0.70f),
)

/** clearlogo 容器比例：与首页顶栏/详情页信息区同款 3.2:1。 */
private const val CARD_LOGO_ASPECT = 3.2f

/** clearlogo 距图区边缘留白。 */
private val CARD_LOGO_PADDING = 8.dp

/**
 * 卡片占位画面：与页面骨架共用中性表面色，加载前后不出现随机色块。
 *
 * 这是纯绘制组件，不含任何图片请求，作为占位恒居图片层之下（加载中/失败透出）。
 *
 * @param seed 保留调用方的条目标识，所有条目采用同款占位。
 * @param modifier 外部修饰符。
 */
@Composable
fun PosterArtwork(seed: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.background(NovumPalette.Surface))
}
