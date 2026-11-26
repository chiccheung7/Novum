package com.novum.tv.ui.screen.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import com.novum.tv.ui.component.LANDSCAPE_ASPECT
import com.novum.tv.ui.component.POSTER_ASPECT
import com.novum.tv.ui.component.NovumSkeletonBlock
import com.novum.tv.ui.component.NovumSkeletonCard
import com.novum.tv.ui.component.NovumSkeletonMediaInfo
import com.novum.tv.ui.component.mediaRowCardWidth
import com.novum.tv.ui.component.mediaGridColumns
import com.novum.tv.ui.theme.NovumDimens
import com.novum.tv.ui.theme.NovumPalette
import com.novum.tv.ui.theme.NovumShapes

/** Same flow as home: four navigation icons, a fixed Hero, resume cards and a poster row. */
@Composable
fun HomeSkeleton(modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier = modifier.fillMaxSize().background(NovumPalette.Ink).clipToBounds(),
    ) {
        val contentWidth = maxWidth - NovumDimens.SafeHorizontal * 2
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = NovumDimens.SafeVertical, bottom = NovumDimens.SafeVertical),
            verticalArrangement = Arrangement.spacedBy(NovumDimens.RowGap),
            userScrollEnabled = false,
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().height(NovumDimens.TopBarHeight)
                        .padding(horizontal = NovumDimens.SafeHorizontal),
                    horizontalArrangement = Arrangement.spacedBy(NovumDimens.Gutter),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.weight(1f))
                    repeat(4) {
                        NovumSkeletonBlock(width = 56.dp, height = 56.dp, shape = NovumShapes.Pill)
                    }
                }
            }
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().height(444.dp)
                        .padding(horizontal = NovumDimens.SafeHorizontal).padding(top = 24.dp),
                ) {
                    NovumSkeletonMediaInfo(Modifier.width(720.dp - NovumDimens.SafeHorizontal))
                }
            }
            items(listOf(LANDSCAPE_ASPECT, POSTER_ASPECT)) { aspect ->
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = NovumDimens.SafeHorizontal),
                    verticalArrangement = Arrangement.spacedBy(NovumDimens.TitleToRow),
                ) {
                    NovumSkeletonBlock(width = 144.dp, height = 35.dp)
                    Row(
                        modifier = Modifier.padding(vertical = NovumDimens.FocusBleed),
                        horizontalArrangement = Arrangement.spacedBy(NovumDimens.CardGap),
                    ) {
                        val target = if (aspect == POSTER_ASPECT) NovumDimens.PosterCardWidth else NovumDimens.CardWidth
                        repeat(mediaGridColumns(contentWidth.value, target.value)) {
                            NovumSkeletonCard(mediaRowCardWidth(contentWidth, aspect), aspectRatio = aspect)
                        }
                    }
                }
            }
        }
    }
}
