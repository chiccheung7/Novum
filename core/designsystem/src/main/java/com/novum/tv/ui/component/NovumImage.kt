package com.novum.tv.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import android.util.Log
import coil3.compose.AsyncImage
import coil3.decode.DataSource
import coil3.request.ImageRequest

/**
 * 统一的网络图片组件。
 *
 * Coil 3 移除了 `crossfade` 配置项，渐变改由调用方控制：这里在加载完成时用 500ms
 * 淡入替代「啪一下出现」的硬切。三米外看 TV 时，这 500ms 是「高级感」与「廉价感」的分界。
 *
 * **内存缓存命中不重放淡入**：卡片滚出视口被销毁、
 * 移回重组时 `remember(url)` 重置，若一律重放 500ms 淡入，占位灰渐变先顶上来再
 * 淡回真图——肉眼就是一暗一亮。重组场景唯一路径是内存缓存命中，瞬跳可见；
 * 只有真正的取数加载（磁盘/网络）才走淡入。
 *
 * 动画值在 `graphicsLayer` 的绘制回调里读，避免淡入过程每帧重组卡片
 * （与焦点动效同一个坑，见 `FocusEffect`）。
 *
 * 加载中与加载失败时本组件不可见（alpha 停在 0）——由调用方分层：底层恒画
 * [PosterArtwork] 占位（见 NovumCard / 分集缩略图），占位与失败回退由同一
 * 分层机制承载，本组件无需 error 分支。
 *
 * @param url 图片地址；为空时不渲染任何内容，由外层负责占位。
 * @param contentDescription 无障碍描述。
 * @param modifier 外部修饰符。
 * @param contentScale 缩放方式。
 * @param alignment 裁切对齐（Crop 时有效）。默认居中；构图主体在上部的
 *   海报源传 [Alignment.TopCenter]——显示图顶部才符合直观逻辑
 *   使构图主体位于视口上方。
 * @param requestSize 显式解码尺寸（像素）。默认 null = 按布局约束解码；
 *   与 `ImageRequest.size()` **预取**配对时必须传——Coil 的缓存 key 含解码
 *   尺寸，两边必须保持一致。
 * @param onLoadState 加载结果回调：true = 成功显示，false = 失败。供调用方
 *   在图片盖上来之后收起自己的占位内容（如 [NovumAvatar] 的首字）。
 */
@Composable
fun NovumImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    alignment: Alignment = Alignment.Center,
    requestSize: IntSize? = null,
    onLoadState: ((Boolean) -> Unit)? = null,
) {
    if (url.isNullOrBlank()) return
    var loaded by remember(url) { mutableStateOf(false) }
    // 内存缓存命中标记，避免行内左右移动时重复播放淡入：
    // 卡片滚出行视口被 LazyRow 销毁、移回时重组，remember(url) 重置导致 500ms 淡入
    // **重放**——占位灰渐变先顶上来再淡回真图，肉眼就是一暗一亮。内存缓存命中
    // （重组场景的唯一路径）瞬跳可见，只有真正的取数加载（磁盘/网络）才走淡入。
    var fromMemoryCache by remember(url) { mutableStateOf(false) }
    val alpha = remember(url) { Animatable(0f) }
    LaunchedEffect(loaded) {
        if (loaded) {
            if (fromMemoryCache) {
                alpha.snapTo(1f)
            } else {
                alpha.animateTo(1f, animationSpec = tween(durationMillis = IMAGE_FADE_MS))
            }
        }
    }
    val context = LocalContext.current
    Box(modifier = modifier) {
        AsyncImage(
            model = remember(url, requestSize) {
                if (requestSize != null) {
                    ImageRequest.Builder(context)
                        .data(url)
                        .size(requestSize.width, requestSize.height)
                        .build()
                } else {
                    url
                }
            },
            contentDescription = contentDescription,
            contentScale = contentScale,
            alignment = alignment,
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer { this.alpha = alpha.value },
            onSuccess = { value ->
                fromMemoryCache = value.result.dataSource == DataSource.MEMORY_CACHE
                loaded = true
                onLoadState?.invoke(true)
            },
            onError = { value ->
                // 静默失败排查入口（NovumImage 无 error 分支，失败恒隐形）：
                // 没有这行日志，「图为什么没上屏」只能靠猜
                Log.e("NovumImage", "load error: $url -> ${value.result.throwable}")
                onLoadState?.invoke(false)
            },
        )
    }
}

/** 图片淡入时长：与提示词约定的 500ms 对齐。 */
private const val IMAGE_FADE_MS = 500

/**
 * 全屏背景图的统一解码尺寸。
 *
 * Coil 的缓存 key 含解码尺寸：首页 Hero 预取、首页背景、详情页背景必须共用
 * 同一函数，才能命中同一份内存/磁盘缓存。否则详情页按布局全尺寸解码，
 * 与首页预取尺寸不同 → 每次进详情都要重新解码大图，翻页重组时反复加载。
 *
 * 尺寸取窗口宽度的 2/3、高度的 2/3 × 5/4（适配背景扩展比例）。
 */
fun backdropRequestSize(windowSize: IntSize, density: Density): IntSize = with(density) {
    IntSize(
        width = (windowSize.width * 2 / 3).coerceAtLeast(1),
        height = (windowSize.height * 2 / 3 * 5 / 4).coerceAtLeast(1),
    )
}
