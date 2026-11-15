package com.novum.tv.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
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
import com.novum.tv.ui.theme.NovumShapes
import com.novum.tv.ui.theme.NovumType

/**
 * 无图占位使用的中性灰渐变组合，按标题哈希选取，错开亮度以区分并排卡片。
 */
private val PosterPalettes = listOf(
    Color(0xFF2C2C2E) to Color(0xFF161618),
    Color(0xFF3A3A3C) to Color(0xFF1C1C1E),
    Color(0xFF252527) to Color(0xFF101012),
    Color(0xFF333335) to Color(0xFF19191B),
)

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
 *   描边透明、光晕三态 None；
 * - **整卡浮起**：`tvFocusScale` 挂在包住图+文字的 Column 上，
 *   聚焦时海报与片名一起放大 [NovumFocusTokens.CardScale]（1.10）——官方 Card 的
 *   focusedScale 设为 1f，避免两套动画叠加；
 * - **文字区（卡外）**：标题 [NovumType.BodyStrong] + 元数据 [NovumType.Caption]
 *   居中单行省略，间距 [NovumDimens.CardImageToTextGap]（8dp）；
 * - **Novum 叠加层**：占位分层（[PosterArtwork] 灰渐变恒居底层，加载中/失败
 *   永不黑卡）、观看进度条（[progress]）、右上角角标（[badge]）；
 * - **语义锚点**：contentDescription 挂整卡容器（无图卡不消失）。
 *
 * 卡宽由调用方决定（16:9 用 [NovumDimens.CardWidth] 320dp，2:3 用
 * [NovumDimens.PosterCardWidth] 177dp）。
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
 * @param glowing 是否绘制图区 Accent 选中描边，与焦点框独立。
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
    glowing: Boolean = false,
    onClick: (() -> Unit)? = null,
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
    // 焦点框保持透明，聚焦反馈由缩放提供。
    val cardBorder = CardDefaults.border(
        border = Border(BorderStroke(0.dp, Color.Transparent)),
        focusedBorder = Border(BorderStroke(0.dp, Color.Transparent)),
        pressedBorder = Border(BorderStroke(0.dp, Color.Transparent)),
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
    // 选中态使用图区 Accent 描边，与聚焦缩放一起变换。
    val selectedFrame = if (glowing) {
        Modifier.border(SELECTED_FRAME_WIDTH, NovumPalette.Accent, NovumShapes.Card)
    } else {
        Modifier
    }
    // 显式解码尺寸（尺寸配对）：限宽卡可预计算，与行/网格预取同公式
    // （cardRequestSize），Coil 内存缓存键恒一致；width=null（铺满型）回布局约束
    val density = LocalDensity.current
    val imageRequestSize = width?.let { cardRequestSize(it, aspectRatio, density) }

    if (showText) {
        Column(
            modifier = sizeModifier
                .tvFocusScale(NovumFocusTokens.CardScale),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 官方 Card 只包图区；图文缩放由外层容器统一提供，语义锚点挂在 Card 上。
            Card(
                onClick = onClick ?: {},
                modifier = Modifier
                    .fillMaxWidth()
                    .then(selectedFrame)
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
                    imageUrl = imageUrl,
                    logoUrl = logoUrl,
                    progress = progress,
                    progressText = progressText,
                    requestSize = imageRequestSize,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(modifier = Modifier.height(NovumDimens.CardImageToTextGap))
            Text(
                text = title,
                // 标题使用较低对比度，副标题再降低一档以拉开层级。
                style = NovumType.CardTitle,
                color = NovumPalette.PaperMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            if (subtitle != null && showSubtitle) {
                Text(
                    text = subtitle,
                    style = NovumType.Label,
                    color = NovumPalette.PaperFaint,
                    maxLines = subtitleMaxLines,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    } else {
        // 纯图卡：仅图区（语义锚点同样挂容器）
        Card(
            onClick = onClick ?: {},
            modifier = sizeModifier
                .then(selectedFrame)
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
                    imageUrl = imageUrl,
                    logoUrl = logoUrl,
                    progress = progress,
                    progressText = progressText,
                    requestSize = imageRequestSize,
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
) {
    Box(modifier = modifier.aspectRatio(aspectRatio)) {
        PosterArtwork(seed = title, modifier = Modifier.fillMaxSize())
        if (imageUrl != null) {
            NovumImage(
                url = imageUrl,
                // 无障碍描述已挂卡容器（整卡一个语义节点），图区置空防双层播报
                contentDescription = null,
                alignment = Alignment.TopCenter,
                requestSize = requestSize,
                modifier = Modifier.fillMaxSize(),
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
                contentDescription = null,
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

/**
 * 选中框描边宽度。
 * Accent 描边（品牌无彩色：高亮白），随聚焦缩放同变换。
 */
private val SELECTED_FRAME_WIDTH = 2.dp

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
 * 卡片占位画面：按标题哈希选一组中性灰渐变，相邻卡片通过灰阶区分。
 *
 * 这是纯绘制组件，不含任何图片请求，作为占位恒居图片层之下（加载中/失败透出）。
 *
 * @param seed 决定渐变配色的种子（通常传标题）。
 * @param modifier 外部修饰符。
 */
@Composable
fun PosterArtwork(seed: String, modifier: Modifier = Modifier) {
    // 调色板是常量，配色只与标题有关：记住结果，避免焦点动画触发重组时反复计算
    val pair = remember(seed) {
        PosterPalettes[(seed.hashCode() and Int.MAX_VALUE) % PosterPalettes.size]
    }
    Box(
        modifier = modifier.background(
            Brush.linearGradient(listOf(pair.first, pair.second)),
        ),
    )
}
