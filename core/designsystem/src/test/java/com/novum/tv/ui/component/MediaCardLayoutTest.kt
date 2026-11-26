package com.novum.tv.ui.component

import androidx.compose.ui.unit.dp
import com.novum.tv.ui.theme.NovumDimens
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Browsing rows, grids and placeholders must share a moderate card size.
 * Explicit 180/280dp examples describe the visual target independently of token defaults.
 */
class MediaCardLayoutTest {

    @Test
    fun landscapeRowsAreLargerWithoutChangingPosterRows() {
        assertEquals(304f, mediaRowCardWidth(1144.dp, LANDSCAPE_ASPECT).value, EPSILON)
        assertTrue(mediaRowCardWidth(1144.dp, LANDSCAPE_ASPECT) > mediaCardWidth(1144.dp, 280.dp))
        assertEquals(mediaCardWidth(1144.dp), mediaRowCardWidth(1144.dp, POSTER_ASPECT))
        assertEquals(200.dp, mediaRowCardWidth(200.dp, LANDSCAPE_ASPECT))
    }

    @Test
    fun widerCanvasAddsPosterColumnsInsteadOfEnlargingPosters() {
        val baseContent = 1280f - 136f
        val wideContent = 1920f - 136f

        assertEquals(6, mediaGridColumns(baseContent, preferredWidth = 180f, gap = 20f))
        assertEquals(9, mediaGridColumns(wideContent, preferredWidth = 180f, gap = 20f))
        assertEquals(174f, mediaCardWidth(baseContent.dp, 180.dp, 20.dp).value, EPSILON)
        assertEquals(180.44444f, mediaCardWidth(wideContent.dp, 180.dp, 20.dp).value, EPSILON)
    }

    @Test
    fun widerCanvasKeepsLandscapeCardsNearTheSameSize() {
        val baseContent = 1280f - 136f
        val wideContent = 1920f - 136f

        assertEquals(4, mediaGridColumns(baseContent, preferredWidth = 280f, gap = 20f))
        assertEquals(6, mediaGridColumns(wideContent, preferredWidth = 280f, gap = 20f))
        assertEquals(271f, mediaCardWidth(baseContent.dp, 280.dp, 20.dp).value, EPSILON)
        assertEquals(280.66666f, mediaCardWidth(wideContent.dp, 280.dp, 20.dp).value, EPSILON)
    }

    @Test
    fun defaultsFollowSharedPosterAndSpacingTokens() {
        val available = 1144.dp

        assertEquals(
            mediaGridColumns(
                available.value,
                NovumDimens.PosterCardWidth.value,
                NovumDimens.CardGap.value,
            ),
            mediaGridColumns(available.value),
        )
        assertEquals(
            mediaCardWidth(available, NovumDimens.PosterCardWidth, NovumDimens.CardGap).value,
            mediaCardWidth(available).value,
            EPSILON,
        )
    }

    @Test
    fun commonTvCanvasesKeepBothCardFamiliesModerate() {
        for (canvasWidth in listOf(1280f, 1440f, 1600f, 1920f)) {
            for (preferred in listOf(180f, 280f)) {
                val width = mediaCardWidth((canvasWidth - 136f).dp, preferred.dp, 20.dp).value
                assertTrue(
                    "$canvasWidth dp canvas produced an oversized or undersized $preferred dp card: $width",
                    abs(width - preferred) <= preferred * 0.15f,
                )
            }
        }
    }

    @Test
    fun cardsAndGapsExactlyFillTheAvailableContentWidth() {
        for (available in listOf(1f, 80f, 320f, 664f, 1144f, 1464f, 1784f, 3000f)) {
            for (preferred in listOf(180f, 280f)) {
                for (gap in listOf(0f, 12f, 20f, 32f)) {
                    val columns = mediaGridColumns(available, preferred, gap)
                    val cardWidth = mediaCardWidth(available.dp, preferred.dp, gap.dp).value
                    val occupied = cardWidth * columns + gap * (columns - 1)

                    assertEquals(
                        "Cards must fit the same bounds as their placeholders and image requests",
                        available,
                        occupied,
                        EPSILON,
                    )
                }
            }
        }
    }

    @Test
    fun narrowContentUsesOneCardWithoutOverflow() {
        for (available in listOf(1f, 16f, 80f, 160f)) {
            for (preferred in listOf(180f, 280f)) {
                assertEquals(1, mediaGridColumns(available, preferred, gap = 20f))
                assertEquals(
                    available,
                    mediaCardWidth(available.dp, preferred.dp, 20.dp).value,
                    EPSILON,
                )
            }
        }
    }

    @Test
    fun invalidGeometryAlwaysProducesFinitePositiveCardSizes() {
        val invalid = listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)
        val cases = buildList {
            invalid.forEach { add(Triple(it, 180f, 20f)) }
            invalid.forEach { add(Triple(1144f, it, 20f)) }
            invalid.filter { it != 0f }.forEach { add(Triple(1144f, 180f, it)) }
            add(Triple(Float.NaN, Float.NaN, Float.NaN))
        }

        for ((available, preferred, gap) in cases) {
            val columns = mediaGridColumns(available, preferred, gap)
            val cardWidth = mediaCardWidth(available.dp, preferred.dp, gap.dp).value

            assertTrue("Invalid geometry must still provide at least one column", columns >= 1)
            assertTrue(
                "Invalid geometry ($available, $preferred, $gap) produced $cardWidth dp",
                cardWidth.isFinite() && cardWidth > 0f,
            )
        }
    }

    @Test
    fun spacingWiderThanTheContentCannotCreateAnImpossibleSecondColumn() {
        val available = 6000f
        val gap = 10000f

        assertEquals(1, mediaGridColumns(available, preferredWidth = 180f, gap = gap))
        assertEquals(
            available,
            mediaCardWidth(available.dp, 180.dp, gap.dp).value,
            EPSILON,
        )
    }

    private companion object {
        const val EPSILON = 0.001f
    }
}
