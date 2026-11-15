package com.novum.tv.data.repository

import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 条目级播放偏好：用户在播放器抽屉里**手动**改过的
 * 字幕/音轨，以及详情页选定的版本，按条目记忆——下次播放同一作品直接
 * 恢复，优先级高于语言偏好（那只决定「从没改过」时的默认轨）。
 *
 * 轨道用「标题 + 语言」双字段定位而非索引：MediaStreams 顺序在会话间
 * 稳定（同一媒体文件），但跨版本/换线后索引不可靠，展示名才是稳定锚。
 *
 * 存储：单 JSON 文件 + Mutex 串行读改写（同 [PersonIndexStore] 模式）；
 * 写失败静默（记忆丢了不伤功能，下次再记即可）。
 */
class ItemPlaybackPrefsStore(
    private val storeFile: File,
    /** 文件 IO 调度器；测试可注入测试调度器以便统一控制协程执行。 */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    @Serializable
    data class TrackRef(val label: String, val language: String? = null)

    @Serializable
    data class ItemPrefs(
        val itemId: String,
        /** 手动选定的版本（MediaSource.Id）；null = 从未手动选过。 */
        val mediaSourceId: String? = null,
        /** 手动选定的字幕轨；null = 未手动改过（走语言偏好）。 */
        val subtitleTrack: TrackRef? = null,
        /** 手动选定的音轨；null = 未手动改过。 */
        val audioTrack: TrackRef? = null,
        /** 手动关闭过字幕（抽屉「关闭字幕」项）。 */
        val subtitleDisabled: Boolean = false,
    )

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private var cache: MutableMap<String, ItemPrefs>? = null

    /** 读取某条目的记忆偏好；无记录返回 null。 */
    suspend fun get(itemId: String): ItemPrefs? = mutex.withLock {
        loadLocked()[itemId]
    }

    /** 合并写入：只改传入的字段，其余保持（版本/字幕/音轨互不覆盖）。 */
    suspend fun update(
        itemId: String,
        mediaSourceId: String? = null,
        subtitleTrack: TrackRef? = null,
        audioTrack: TrackRef? = null,
        subtitleDisabled: Boolean? = null,
    ) = mutex.withLock {
        val map = loadLocked()
        val old = map[itemId] ?: ItemPrefs(itemId = itemId)
        map[itemId] = old.copy(
            mediaSourceId = mediaSourceId ?: old.mediaSourceId,
            subtitleTrack = subtitleTrack ?: old.subtitleTrack,
            audioTrack = audioTrack ?: old.audioTrack,
            subtitleDisabled = subtitleDisabled ?: old.subtitleDisabled,
        )
        persistLocked(map)
    }

    private fun loadLocked(): MutableMap<String, ItemPrefs> {
        cache?.let { return it }
        val loaded = runCatching {
            if (storeFile.exists()) {
                json.decodeFromString<Map<String, ItemPrefs>>(storeFile.readText())
            } else {
                emptyMap()
            }
        }.getOrDefault(emptyMap())
        return loaded.toMutableMap().also { cache = it }
    }

    private suspend fun persistLocked(map: Map<String, ItemPrefs>) {
        withContext(ioDispatcher) {
            runCatching {
                storeFile.parentFile?.mkdirs()
                storeFile.writeText(json.encodeToString(map))
            }
        }
    }
}
