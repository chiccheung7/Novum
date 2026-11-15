package com.novum.tv.ui.screen.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.novum.tv.ui.component.LANDSCAPE_ASPECT
import com.novum.tv.ui.component.NovumSkeletonBlock
import com.novum.tv.ui.component.PosterArtwork
import com.novum.tv.ui.theme.NovumDimens
import com.novum.tv.ui.theme.NovumShapes

/**
 * 首页骨架屏：全屏背景占位 + 顶栏占位 + Hero 信息块占位 + 两行宽卡占位，带呼吸动画。
 *
 * 结构按现代流媒体方案：背景撑满整屏（[PosterArtwork] 灰渐变兜底
 * 数据到达后由 [FullScreenBackdrop] 接管）+ 27dp 顶距 → 顶栏占位 56dp（列表
 * item 0）→ RowGap → Hero 信息块占位（[NovumDimens.SafeHorizontal] 对齐线）→
 * 继续观看宽卡行（380dp）。比「正在加载…」文字更有质感：首屏结构提前出现
 * 图片到位时不会整页跳变。呼吸动画由 [NovumSkeletonBlock] 统一提供
 * 全站骨架节奏一致。
 */
@Composable
fun HomeSkeleton(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize()) {
        // 背景占位：全屏灰渐变（与真实背景层同位，数据到达后无缝接管）
        PosterArtwork(seed = "home-skeleton", modifier = Modifier.fillMaxSize())
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = NovumDimens.SafeVertical),
        ) {
            // 顶栏行占位（流式 item 0）
            NovumSkeletonBlock(
                modifier = Modifier
                    .padding(horizontal = NovumDimens.SafeHorizontal)
                    .fillMaxWidth()
                    .height(NovumDimens.TopBarHeight),
                shape = NovumShapes.Pill,
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = NovumDimens.SafeHorizontal)
                    .padding(top = NovumDimens.RowGap),
                verticalArrangement = Arrangement.spacedBy(NovumDimens.RowGap),
            ) {
                // Hero 信息块占位（信息块在列表流里的自然高度近似）
                NovumSkeletonBlock(width = 88.dp, height = 14.dp)
                NovumSkeletonBlock(
                    width = 460.dp,
                    height = 48.dp,
                    shape = NovumShapes.Card,
                )
                NovumSkeletonBlock(width = 300.dp, height = 18.dp)
                repeat(SKELETON_ROWS) {
                    NovumSkeletonBlock(width = 120.dp, height = 18.dp)
                    Row(horizontalArrangement = Arrangement.spacedBy(NovumDimens.CardGap)) {
                        repeat(SKELETON_CARDS_PER_ROW) {
                            NovumSkeletonBlock(
                                modifier = Modifier
                                    .width(NovumDimens.CardWidth)
                                    .height(NovumDimens.CardWidth / LANDSCAPE_ASPECT),
                                shape = NovumShapes.Card,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 骨架行数。 */
private const val SKELETON_ROWS = 2

/** 每行骨架卡片数（16:9 卡 261dp：内容区 3 列）。 */
private const val SKELETON_CARDS_PER_ROW = 3
