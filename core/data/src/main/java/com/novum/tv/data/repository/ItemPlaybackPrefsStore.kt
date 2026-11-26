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
 * 存储：单 JSON 文件 + Mutex 串行读改写；
 * 写失败静默（记忆丢了不伤功能，下次再记即可）。
 */
class ItemPlaybackPrefsStore(
    private val storeFile: File,
    /** 文件 IO 调度器；测试可注入测试调度器以便统一控制协程执行。 */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** 生产实例必须注入会话命名空间；null 仅保留独立存储的旧构造方式。 */
    private val namespaceProvider: (() -> String?)? = null,
) {

    @Serializable
    data class TrackRef(
        val label: String,
        val language: String? = null,
        /**
         * 容器内原始 `Format.label`（空串 = 容器没给标题）；null = 旧版记录（只有展示名）。
         * 记忆恢复按它比对：展示名可能带「· 5.1」后缀或回退成语言本地名，与 Format.label 对不上。
         */
        val rawLabel: String? = null,
        /** 音轨声道数：区分同语言的「立体声 / 5.1」两条轨；字幕/未知为 null。 */
        val channels: Int? = null,
        /** Media3 format id distinguishes equal-language, unlabelled tracks in the same media source. */
        val formatId: String? = null,
    )

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
        /** 手动选定的弹幕分集（dandanplay episodeId）；null = 走自动匹配。 */
        val danmuEpisodeId: String? = null,
        /** 弹幕时间偏移（毫秒，正=延后）；只记用户改过的值，0 不写。 */
        val danmuOffsetMs: Long = 0L,
    )

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private var cache: MutableMap<String, ItemPrefs>? = null

    /** 读取某条目的记忆偏好；无记录（或读盘失败）返回 null。 */
    suspend fun get(itemId: String): ItemPrefs? {
        val key = storageKey(itemId) ?: return null
        return mutex.withLock { loadLocked()?.get(key) }
    }

    /**
     * 合并写入：只改传入的字段，其余保持（版本/字幕/音轨互不覆盖）。
     *
     * 注意：null 表示「本次不改」，因此要**清空**某个已记忆字段必须显式置
     * `clearXxx = true`——否则「关闭字幕」后旧的字幕轨仍留在记忆里，
     * 下次开字幕会莫名跳回上次那条轨。
     */
    suspend fun update(
        itemId: String,
        mediaSourceId: String? = null,
        subtitleTrack: TrackRef? = null,
        audioTrack: TrackRef? = null,
        subtitleDisabled: Boolean? = null,
        danmuEpisodeId: String? = null,
        danmuOffsetMs: Long? = null,
        clearMediaSourceId: Boolean = false,
        clearSubtitleTrack: Boolean = false,
        clearAudioTrack: Boolean = false,
        clearDanmuEpisodeId: Boolean = false,
    ) {
        // 挂起等待锁或 IO 前固定账号，切会话不能把旧操作写进新账号。
        val key = storageKey(itemId) ?: return
        mutex.withLock {
            // 读盘失败就放弃本次写入：拿空表落盘会把文件里**其它条目**的记忆整片抹掉，
            // 用户下次播放会发现所有手动轨/版本记忆都没了。宁可这次不记。
            val map = loadLocked() ?: return@withLock
            val old = map[key] ?: ItemPrefs(itemId = itemId)
            map[key] = old.copy(
                mediaSourceId = if (clearMediaSourceId) null else mediaSourceId ?: old.mediaSourceId,
                subtitleTrack = if (clearSubtitleTrack) null else subtitleTrack ?: old.subtitleTrack,
                audioTrack = if (clearAudioTrack) null else audioTrack ?: old.audioTrack,
                subtitleDisabled = subtitleDisabled ?: old.subtitleDisabled,
                danmuEpisodeId = if (clearDanmuEpisodeId) null else danmuEpisodeId ?: old.danmuEpisodeId,
                danmuOffsetMs = danmuOffsetMs ?: old.danmuOffsetMs,
            )
            persistLocked(map)
        }
    }

    private fun storageKey(itemId: String): String? {
        val provider = namespaceProvider ?: return itemId
        val namespace = provider()?.takeIf { it.isNotBlank() } ?: return null
        // 旧裸 itemId 无服务器/用户归属，保留但不向任何账号兜底或自动迁移。
        return "v2:$namespace:$itemId"
    }

    /**
     * 读取记忆表；**null = 本次读盘失败**（IO 异常）。
     *
     * 三种「读不出内容」要分开处理：
     * - 文件不存在 / 内容为空 → 正常空表（还没写过，或上次写被截断成空）；
     * - 内容损坏（JSON 解不开）→ 先把原文件另存为 `.corrupt`，再当空表继续：
     *   既留下现场，也不会让记忆永久失效写不进去；
     * - 真正读失败 → 返回 null，调用方放弃写入：拿空表落盘会把磁盘上**其它
     *   条目**的记忆整片抹掉，宁可这次不记（下次访问会重试读盘）。
     */
    private suspend fun loadLocked(): MutableMap<String, ItemPrefs>? {
        cache?.let { return it }
        val loaded: Map<String, ItemPrefs> = withContext(ioDispatcher) {
            runCatching {
                if (!storeFile.exists()) return@runCatching emptyMap<String, ItemPrefs>()
                val text = storeFile.readText()
                if (text.isBlank()) return@runCatching emptyMap<String, ItemPrefs>()
                runCatching { json.decodeFromString<Map<String, ItemPrefs>>(text) }
                    .getOrElse {
                        runCatching {
                            storeFile.copyTo(File(storeFile.path + CORRUPT_SUFFIX), overwrite = true)
                        }
                        emptyMap<String, ItemPrefs>()
                    }
            }.getOrNull()
        } ?: return null
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

/** 损坏文件的另存后缀（只留证据，不参与读取）。 */
private const val CORRUPT_SUFFIX = ".corrupt"
