package com.novum.tv.data.repository

import com.novum.tv.data.remote.emby.SessionHolder
import com.novum.tv.data.session.Session
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun serverSubtitleOffAndItsVersionSurviveDiskRoundTripWithoutChangingOtherPrefs() = runTest {
        val store = newStore()
        val audio = ItemPlaybackPrefsStore.TrackRef("English", "en")
        store.update("item", audioTrack = audio, danmuOffsetMs = 400)
        store.update("item", mediaSourceId = "source-4k", subtitleDisabled = true,
            serverSubtitleStreamIndex = -1, serverSubtitleMediaSourceId = "source-4k")
        val restored = newStore().get("item")
        assertEquals(-1, restored?.serverSubtitleStreamIndex)
        assertEquals("source-4k", restored?.serverSubtitleMediaSourceId)
        assertEquals(true, restored?.subtitleDisabled)
        assertEquals(audio, restored?.audioTrack)
        assertEquals(400L, restored?.danmuOffsetMs)
    }

    @Test
    fun selectingLocalTextClearsOnlyServerSubtitleMemory() = runTest {
        val store = newStore()
        val text = ItemPlaybackPrefsStore.TrackRef("Text", "en")
        store.update("item", mediaSourceId = "source", serverSubtitleStreamIndex = 0,
            serverSubtitleMediaSourceId = "source")
        store.update("item", subtitleTrack = text, subtitleDisabled = false, clearServerSubtitle = true)
        val restored = newStore().get("item")
        assertNull(restored?.serverSubtitleStreamIndex)
        assertNull(restored?.serverSubtitleMediaSourceId)
        assertEquals("source", restored?.mediaSourceId)
        assertEquals(text, restored?.subtitleTrack)
    }

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
    fun persistsPreferencesAcrossStoreRecreation() = runTest {
        val expected = ItemPlaybackPrefsStore.ItemPrefs(
            itemId = "item",
            mediaSourceId = "fixture-source",
            subtitleTrack = ItemPlaybackPrefsStore.TrackRef("简体", "chi", "简体字幕"),
            audioTrack = ItemPlaybackPrefsStore.TrackRef("English · 5.1", "eng", "English", 6),
            danmuEpisodeId = "fixture-episode",
            danmuOffsetMs = 1_250L,
        )
        newStore().update(
            itemId = expected.itemId,
            mediaSourceId = expected.mediaSourceId,
            subtitleTrack = expected.subtitleTrack,
            audioTrack = expected.audioTrack,
            danmuEpisodeId = expected.danmuEpisodeId,
            danmuOffsetMs = expected.danmuOffsetMs,
        )

        assertEquals(expected, newStore().get("item"))
        assertTrue(File(folder.root, "item_prefs.json").exists())
        assertFalse(File(folder.root, "item_prefs.json.corrupt").exists())
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

    @Test
    fun blankFileIsTreatedAsEmpty() = runTest {
        // 新建的临时文件是空内容（0 字节），这不是"读失败"：必须当空表处理，
        // 否则第一次写入就被放弃，用户的手动记忆永远存不下来
        val file = File(folder.root, "item_prefs.json")
        file.writeText("")
        val store = newStore()

        store.update("item", mediaSourceId = "src-1")

        assertEquals("src-1", store.get("item")?.mediaSourceId)
        assertFalse("空文件不应被当成损坏另存", File(folder.root, "item_prefs.json.corrupt").exists())
    }

    @Test
    fun corruptFileIsQuarantinedBeforeRewrite() = runTest {
        // 上次写到一半被截断、内容解不开：先另存一份 .corrupt 留现场，再按空表重建。
        // 既不丢原数据可追查，也不会让记忆永久失效（永远写不进去）。
        val corruption = """{"a": not-json}"""
        val file = File(folder.root, "item_prefs.json")
        file.writeText(corruption)
        val store = newStore()

        assertNull("损坏内容不应读出记忆", store.get("item"))
        store.update("item", mediaSourceId = "src-1")

        assertEquals("写入应恢复", "src-1", store.get("item")?.mediaSourceId)
        assertEquals(
            "损坏内容应另存留证",
            corruption,
            File(folder.root, "item_prefs.json.corrupt").readText(),
        )
    }

    @Test
    fun sameItemIdHasIndependentPreferencesPerServerAndUserAfterReload() = runTest {
        val holder = SessionHolder()
        val store = scopedStore(holder)
        val identities = listOf(
            session("https://one.example/emby", "fixture-user"),
            session("https://two.example/emby", "fixture-user"),
            session("https://two.example/emby", "other-user"),
        )
        identities.forEachIndexed { index, identity ->
            holder.update(identity)
            assertNull(store.get("100"))
            store.update("100", mediaSourceId = "source-$index", danmuOffsetMs = index * 1_000L)
        }

        val reloaded = scopedStore(holder)
        identities.forEachIndexed { index, identity ->
            holder.update(identity.copy(token = "rotated-fixture-token"))
            assertEquals("source-$index", store.get("100")?.mediaSourceId)
            assertEquals("source-$index", reloaded.get("100")?.mediaSourceId)
            assertEquals(index * 1_000L, reloaded.get("100")?.danmuOffsetMs)
            assertEquals("100", reloaded.get("100")?.itemId)
        }
        val persisted = File(folder.root, "item_prefs.json").readText()
        assertFalse(persisted.contains("fixture-token"))
        assertFalse(persisted.contains("fixture-user"))
        assertFalse(persisted.contains("one.example"))
    }

    @Test
    fun scopedStoreDoesNotReadOrWriteWithoutAnActiveSession() = runTest {
        val holder = SessionHolder()
        val store = scopedStore(holder)
        val file = File(folder.root, "item_prefs.json")
        assertNull(store.get("100"))
        store.update("100", mediaSourceId = "anonymous")
        assertFalse(file.exists())

        val identity = session("https://one.example", "fixture-user")
        holder.update(identity)
        store.update("100", mediaSourceId = "remembered")
        val beforeLogout = file.readText()
        holder.update(null)
        assertNull(store.get("100"))
        store.update("100", mediaSourceId = "logged-out")
        assertEquals(beforeLogout, file.readText())
        holder.update(identity)
        assertEquals("remembered", store.get("100")?.mediaSourceId)
    }

    @Test
    fun legacyUnscopedEntriesArePreservedButNeverAdoptedByAnAccount() = runTest {
        val file = File(folder.root, "item_prefs.json")
        file.writeText("""{"100":{"itemId":"100","mediaSourceId":"legacy-source"}}""")
        val holder = SessionHolder().apply { update(session("https://one.example", "fixture-user")) }
        val store = scopedStore(holder)

        assertNull(store.get("100"))
        store.update("100", mediaSourceId = "scoped-source")
        assertEquals("scoped-source", store.get("100")?.mediaSourceId)
        assertEquals("legacy-source", newStore().get("100")?.mediaSourceId)
        assertTrue(file.readText().contains("v2:"))
        holder.update(session("https://two.example", "fixture-user"))
        assertNull(store.get("100"))
    }

    @Test
    fun queuedWriteKeepsTheNamespaceFromBeforeWaitingForTheMutex() = runTest {
        val original = session("https://one.example", "fixture-user")
        val holder = SessionHolder().apply { update(original) }
        val store = scopedStore(holder, StandardTestDispatcher(testScheduler))
        // 首次读取挂起在 IO 上并持有锁；第二次写入必须在等锁前固定原账号。
        val blocking = launch(start = CoroutineStart.UNDISPATCHED) {
            store.update("other-item", mediaSourceId = "other-source")
        }
        val queued = launch(start = CoroutineStart.UNDISPATCHED) {
            store.update("100", mediaSourceId = "original-source")
        }
        holder.update(session("https://two.example", "fixture-user"))
        advanceUntilIdle()
        blocking.join()
        queued.join()

        assertNull(store.get("100"))
        holder.update(original)
        assertEquals("original-source", store.get("100")?.mediaSourceId)
    }

    @Test
    fun queuedReadKeepsTheNamespaceFromBeforeWaitingForTheMutex() = runTest {
        val original = session("https://one.example", "fixture-user")
        val holder = SessionHolder().apply { update(original) }
        val store = scopedStore(holder, StandardTestDispatcher(testScheduler))
        val blocking = launch(start = CoroutineStart.UNDISPATCHED) {
            store.update("100", mediaSourceId = "original-source")
        }
        val queued = async(start = CoroutineStart.UNDISPATCHED) { store.get("100") }
        holder.update(session("https://two.example", "fixture-user"))
        advanceUntilIdle()
        blocking.join()

        assertEquals("original-source", queued.await()?.mediaSourceId)
        assertNull(store.get("100"))
    }

    private fun scopedStore(
        holder: SessionHolder,
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    ) = ItemPlaybackPrefsStore(
        storeFile = File(folder.root, "item_prefs.json"),
        ioDispatcher = dispatcher,
        namespaceProvider = holder::playbackPrefsNamespace,
    )

    private fun session(server: String, userId: String) = Session(
        server = server,
        token = "fixture-token",
        userId = userId,
        userName = "Fixture",
        serverName = "Fixture server",
        deviceId = "fixture-device",
    )
}
