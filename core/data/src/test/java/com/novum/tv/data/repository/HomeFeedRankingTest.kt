package com.novum.tv.data.repository

import com.novum.tv.data.remote.emby.ItemDto
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFeedRankingTest {
    @Test
    fun explicitAnimationLabelsAreRecognizedInEitherGenresOrTags() {
        for (label in listOf("动画", "動畫", "动漫", "動漫", "Animation", "Anime", "  ANIMATION  ")) {
            assertTrue("genre: $label", ItemDto(Genres = listOf("Adventure", label)).hasAnimationLabel())
            assertTrue("tag: $label", ItemDto(Tags = listOf("Featured", label)).hasAnimationLabel())
        }
    }

    @Test
    fun labelNormalizationDoesNotDependOnTheDeviceLocale() {
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))

            assertTrue(ItemDto(Tags = listOf("ANIMATION")).hasAnimationLabel())
            assertTrue(ItemDto(Genres = listOf("ANIME")).hasAnimationLabel())
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun titlesAndUnrelatedPartialLabelsNeverImplyAnimation() {
        val item = ItemDto(
            Id = "live-action",
            Name = "动画 Animation Anime",
            Type = "Movie",
            Genres = listOf("剧情", "Live Action"),
            Tags = listOf("Animation Studio", "Reanimation", "非动画"),
            BackdropImageTags = listOf("backdrop"),
        )

        assertFalse(item.hasAnimationLabel())
        assertEquals(listOf(item), heroRotationPool(listOf(item)))
    }

    @Test
    fun labeledDuplicateExcludesTheItemEvenWhenASparseCopyHasMoreMetadata() {
        val sparse = ItemDto(
            Id = "same-item",
            CommunityRating = 9f,
            Overview = "Complete overview",
            BackdropImageTags = listOf("backdrop"),
        )
        // 即使带标签的副本缺少背景图，另一份可展示的副本也不能绕过排除。
        val labeled = ItemDto(Id = "same-item", Tags = listOf("Animation"))

        assertTrue(heroRotationPool(listOf(sparse, labeled)).isEmpty())
        assertTrue(heroRotationPool(listOf(labeled, sparse)).isEmpty())
    }
}
