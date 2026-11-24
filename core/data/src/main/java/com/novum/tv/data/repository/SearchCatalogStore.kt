package com.novum.tv.data.repository

import com.novum.tv.core.dispatcher.DispatcherProvider
import com.novum.tv.core.log.NovumLogger
import com.novum.tv.data.remote.emby.EmbyApiProvider
import com.novum.tv.data.remote.emby.SessionHolder
import com.novum.tv.data.text.MediaStrings
import com.novum.tv.model.MediaItem
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 搜索目录：把电影/剧集/合集的名字、海报等基础信息一次性拉全，供本地模糊匹配。
 *
 * **为什么需要它**：Emby 的 `SearchTerm` 对中文只做「从头开始」或整词命中
 * （官方 FAQ 与社区反馈一致：输入「龙」搜不到「成龙」）。搜「大明」时
 * 「大明王朝1566」不会命中，而「1566」这种数字片段反而能命中——实机现象
 * 就是「只能搜完整片名或数字」。要让中文支持任意位置匹配，只能在客户端
 * 自己建一份目录做 `contains`。
 *
 * 目录在首次搜索（或搜索页打开时的预热）时按页拉取并缓存到进程内存：
 * 只取卡片需要的字段，不改变任何写操作。
 *
 * @param clients Emby API 提供者。
 * @param sessionHolder 当前会话（服务器、用户）。
 * @param strings 类型文案（副标题用）。
 * @param dispatchers 调度器（网络与映射走 IO）。
 * @param logger 日志门面（拉取失败/触顶留痕）。
 */
@Singleton
class SearchCatalogStore @Inject constructor(
    private val clients: EmbyApiProvider,
    private val sessionHolder: SessionHolder,
    private val strings: MediaStrings,
    private val dispatchers: DispatcherProvider,
    private val logger: NovumLogger,
) {

    private val mutex = Mutex()

    /** 已建好的目录；null = 尚未拉取。 */
    @Volatile
    private var catalog: List<MediaItem>? = null

    /**
     * 取目录（必要时拉取）。
     *
     * 失败时**不缓存空结果**：下一次搜索仍会重试，避免一次网络抖动让
     * 整个会话都搜不到东西。
     */
    suspend fun load(): List<MediaItem> {
        catalog?.let { return it }
        return mutex.withLock {
            catalog ?: fetchAll().also { catalog = it }
        }
    }

    /** 丢弃缓存（媒体库有大变动/重登时调用）。 */
    fun invalidate() {
        catalog = null
    }

    /** 逐页拉全库基础信息。 */
    private suspend fun fetchAll(): List<MediaItem> = withContext(dispatchers.io) {
        val server = sessionHolder.server
        val userId = sessionHolder.userId
        if (server.isBlank() || userId.isBlank()) return@withContext emptyList()
        val api = clients.api(server)
        val collected = mutableListOf<MediaItem>()
        var start = 0
        var pages = 0
        while (start < MAX_CATALOG_ITEMS && pages < MAX_CATALOG_PAGES) {
            val page = api.items(
                uid = userId,
                recursive = true,
                includeItemTypes = CATALOG_ITEM_TYPES,
                startIndex = start,
                limit = CATALOG_PAGE_SIZE,
                sortBy = "SortName",
                fields = CATALOG_FIELDS,
            )
            pages++
            collected += page.Items.mapNotNull { dto -> dto.toMediaItem(server, strings) }
            // 本页没有数据说明已经到底；用服务端总数判断，避免多打一次空请求
            if (page.Items.isEmpty()) break
            start += page.Items.size
            if (start >= page.TotalRecordCount) break
        }
        if (start >= MAX_CATALOG_ITEMS) {
            logger.w(TAG, "搜索目录达到上限（$MAX_CATALOG_ITEMS 条），超出部分不参与本地搜索")
        }
        collected
    }

    private companion object {
        const val TAG = "SearchCatalog"

        /** 单页条数：500 条只带基础字段，弱网电视也能接受。 */
        const val CATALOG_PAGE_SIZE = 500

        /** 目录条数上限：防止超大库把内存和首搜耗时拖垮。 */
        const val MAX_CATALOG_ITEMS = 8_000

        /** 分页次数上限：服务端若忽略 StartIndex，避免死循环。 */
        const val MAX_CATALOG_PAGES = 40

        /** 参与搜索的条目类型：海报网格能正常呈现的几类。 */
        const val CATALOG_ITEM_TYPES = "Movie,Series,BoxSet"

        /** 只取卡片需要的字段，避免把 MediaStreams 这类大块数据搬回来。 */
        const val CATALOG_FIELDS = "ProductionYear,CommunityRating,ImageTags,SeriesName,SortName"
    }
}
