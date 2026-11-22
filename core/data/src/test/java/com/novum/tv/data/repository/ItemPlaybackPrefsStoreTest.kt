package com.novum.tv.data.repository

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 条目级播放偏好测试。
 *
 * 重点锁住「null = 不改，清空必须显式置位」的语义：关闭字幕后如果旧轨还留在
 * 记忆里，下次开启字幕会莫名跳回上次那条轨。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ItemPlaybackPrefsStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun newStore() = ItemPlaybackPrefsStore(
        storeFile = File(folder.root, "item_prefs.json"),
        ioDispatcher = Dispatchers.Unconfined,
    )

    @Test
    fun mergingKeepsUntouchedFields() = runTest {
        val store = newStore()
        val cn = ItemPlaybackPrefsStore.TrackRef("简体", "chi")
        val en = ItemPlaybackPrefsStore.TrackRef("English", "eng")

        store.update("item", subtitleTrack = cn)
        store.update("item", audioTrack = en)

        assertEquals(cn, store.get("item")?.subtitleTrack)
        assertEquals(en, store.get("item")?.audioTrack)
    }

    @Test
    fun closingSubtitlesClearsRememberedTrack() = runTest {
        val store = newStore()
        store.update(
            "item",
            subtitleTrack = ItemPlaybackPrefsStore.TrackRef("简体", "chi"),
        )
        assertNull("前置条件：尚未关闭字幕", store.get("item")?.subtitleDisabled.takeIf { it == true })

        store.update(
            "item",
            subtitleDisabled = true,
            clearSubtitleTrack = true,
        )

        val prefs = store.get("item")
        assertNull("关闭字幕后不应残留旧字幕轨", prefs?.subtitleTrack)
        assertEquals(true, prefs?.subtitleDisabled)
    }
}
