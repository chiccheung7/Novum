package com.novum.tv.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.novum.tv.ui.theme.NovumDimens
import com.novum.tv.ui.theme.NovumFocusTokens
import com.novum.tv.ui.theme.NovumPalette
import com.novum.tv.ui.theme.NovumShapes

/** Stable neutral blocks avoid independent animation rhythms across a loading page. */
@Composable
fun NovumSkeletonBlock(
    modifier: Modifier = Modifier,
    shape: Shape = NovumShapes.Badge,
    width: Dp? = null,
    height: Dp? = null,
) {
    Box(
        modifier = modifier
            .then(if (width != null) Modifier.width(width) else Modifier)
            .then(if (height != null) Modifier.height(height) else Modifier)
            .clip(shape)
            .background(NovumPalette.Surface),
    )
}

/** For actual settings rows; media pages use the corresponding card grid. */
@Composable
fun NovumSkeletonRows(modifier: Modifier = Modifier, rows: Int = 6) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NovumDimens.TextBlockGap),
    ) {
        repeat(rows) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NovumSkeletonBlock(width = 260.dp, height = 20.dp)
                NovumSkeletonBlock(width = 96.dp, height = 36.dp, shape = NovumShapes.Pill)
            }
        }
    }
}

/** Same image ratio and text height as NovumCard. */
@Composable
fun NovumSkeletonCard(
    width: Dp,
    modifier: Modifier = Modifier,
    aspectRatio: Float = POSTER_ASPECT,
) {
    Column(modifier = modifier.width(width)) {
        NovumSkeletonBlock(
            modifier = Modifier.fillMaxWidth().aspectRatio(aspectRatio),
            shape = NovumShapes.Card,
        )
        Column(
            modifier = Modifier.fillMaxWidth().height(NovumDimens.CardTextBlockMaxHeight(1))
                .padding(top = NovumDimens.CardImageToTextGap),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NovumSkeletonBlock(modifier = Modifier.fillMaxWidth(0.72f), height = 20.dp)
            NovumSkeletonBlock(modifier = Modifier.fillMaxWidth(0.42f), height = 14.dp)
        }
    }
}

/** Same columns, padding and card dimensions as the corresponding loaded grid. */
@Composable
fun NovumSkeletonCardGrid(
    modifier: Modifier = Modifier,
    aspectRatio: Float = POSTER_ASPECT,
    rows: Int = 2,
    header: (@Composable () -> Unit)? = null,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val contentWidth = maxWidth - NovumDimens.SafeHorizontal * 2
        val isPoster = aspectRatio == POSTER_ASPECT
        val cardWidth = mediaRowCardWidth(contentWidth, aspectRatio)
        val columns = if (isPoster) {
            mediaGridColumns(contentWidth.value)
        } else {
            ((contentWidth + NovumDimens.CardGap) / (cardWidth + NovumDimens.CardGap)).toInt().coerceAtLeast(1)
        }
        val cardHeight = cardWidth / aspectRatio + NovumDimens.CardTextBlockMaxHeight(1)
        val bleed = NovumFocusTokens.bleed(maxOf(cardWidth, cardHeight))
        LazyVerticalGrid(
            columns = if (isPoster) GridCells.Fixed(columns) else GridCells.FixedSize(cardWidth),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = NovumDimens.SafeHorizontal,
                end = NovumDimens.SafeHorizontal,
                top = bleed,
                bottom = NovumDimens.SafeVertical,
            ),
            horizontalArrangement = Arrangement.spacedBy(NovumDimens.CardGap),
            verticalArrangement = Arrangement.spacedBy(maxOf(NovumDimens.CardGap, bleed)),
            userScrollEnabled = false,
        ) {
            if (header != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(modifier = Modifier.padding(bottom = NovumDimens.RowGap)) { header() }
                }
            }
            items(columns * rows) { index ->
                NovumSkeletonCard(cardWidth, aspectRatio = aspectRatio,
                    modifier = Modifier.testTag("media-skeleton-card-$index"))
            }
        }
    }
}
@Composable
fun NovumSkeletonPosterGrid(
    modifier: Modifier = Modifier,
    rows: Int = 2,
    header: (@Composable () -> Unit)? = null,
) = NovumSkeletonCardGrid(modifier = modifier, rows = rows, header = header)

/** Shared loading header: title, metadata, synopsis and two actions. */
@Composable
fun NovumSkeletonMediaInfo(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.widthIn(max = 720.dp).fillMaxWidth(),
    ) {
        NovumSkeletonBlock(modifier = Modifier.fillMaxWidth(0.65f), height = 64.dp)
        Spacer(Modifier.height(MEDIA_INFO_TITLE_TO_META))
        NovumSkeletonBlock(modifier = Modifier.fillMaxWidth(0.45f), height = 26.dp)
        Spacer(Modifier.height(MEDIA_INFO_META_TO_TAGS))
        Row(horizontalArrangement = Arrangement.spacedBy(MEDIA_INFO_CHIP_GAP)) {
            listOf(62.dp, 62.dp, 78.dp).forEach { width ->
                NovumSkeletonBlock(width = width, height = 30.dp, shape = NovumShapes.Pill)
            }
        }
        Spacer(Modifier.height(MEDIA_INFO_TAGS_TO_OVERVIEW))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NovumSkeletonBlock(modifier = Modifier.fillMaxWidth(0.95f), height = 18.dp)
            NovumSkeletonBlock(modifier = Modifier.fillMaxWidth(0.85f), height = 18.dp)
            NovumSkeletonBlock(modifier = Modifier.fillMaxWidth(0.6f), height = 18.dp)
        }
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(NovumDimens.CardGap)) {
            repeat(2) {
                NovumSkeletonBlock(
                    width = novumActionButtonWidth(),
                    height = NovumDimens.ButtonHeight,
                    shape = NovumShapes.Pill,
                )
            }
        }
    }
}

/** Follow the real full-width information header, rather than an unrelated poster column. */
@Composable
fun NovumSkeletonDetail(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(
            horizontal = NovumDimens.SafeHorizontal,
            vertical = NovumDimens.SafeVertical,
        ),
        verticalArrangement = Arrangement.spacedBy(NovumDimens.SectionGap),
    ) {
        NovumSkeletonBlock(width = 56.dp, height = 56.dp, shape = NovumShapes.Pill)
        NovumSkeletonMediaInfo()
    }
}
