package com.novum.tv.data.repository

import com.novum.tv.core.dispatcher.DispatcherProvider
import com.novum.tv.core.log.NovumLogger
import com.novum.tv.data.remote.emby.EmbyApiProvider
import com.novum.tv.data.remote.emby.SessionHolder
import com.novum.tv.data.text.MediaStrings
import com.novum.tv.model.CastMember
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
 * 建目录时顺带把每个条目的 `People[]` 并入本地人物索引——实测有的网关
 * `/Persons` 返回的是**条目**而不是人物（人物区出现一排电影海报），人物
 * 搜索不能只依赖那个端点；目录里的 `People[]` 永远是真实的演职员。
 *
 * @param clients Emby API 提供者。
 * @param sessionHolder 当前会话（服务器、用户）。
 * @param strings 类型文案（副标题/演职员职位用）。
 * @param dispatchers 调度器（网络与映射走 IO）。
 * @param logger 日志门面（建目录耗时/条数留痕，排查"搜不到"用）。
 * @param personIndex 本地人物索引（目录 People 并入）。
 */
@Singleton
class SearchCatalogStore @Inject constructor(
    private val clients: EmbyApiProvider,
    private val sessionHolder: SessionHolder,
    private val strings: MediaStrings,
    private val dispatchers: DispatcherProvider,
    private val logger: NovumLogger,
    private val personIndex: PersonIndexStore,
) {

    private val mutex = Mutex()

    /** 已建好的目录；null = 尚未拉取。 */
    @Volatile
    private var catalog: List<MediaItem>? = null

    /**
     * 目录所属的会话（服务器 + 用户）。本类是进程级单例：退出登录再换服务器/换账号后
     * [SessionHolder] 已指向新会话，不按会话区分就会拿旧服务器、旧账号可见范围里的
     * 条目（连同旧服务器的图片地址）去回答新会话的搜索。
     */
    @Volatile
    private var catalogSessionKey: String? = null

    /**
     * 取目录（必要时拉取）。
     *
     * 失败时**不缓存空结果**：下一次搜索仍会重试，避免一次网络抖动让
     * 整个会话都搜不到东西。
     */
    suspend fun load(): List<MediaItem> {
        if (catalogSessionKey == sessionKey()) {
            catalog?.let { return it }
        }
        return mutex.withLock {
            val lockedKey = sessionKey()
            // 会话变了（登出/换服务器/换账号）：旧目录作废，重新拉取
            if (catalogSessionKey != lockedKey) catalog = null
            catalog ?: fetchAll().also {
                catalog = it
                catalogSessionKey = lockedKey
            }
        }
    }

    /** 丢弃缓存（媒体库有大变动/重登时调用）。 */
    fun invalidate() {
        catalog = null
        catalogSessionKey = null
    }

    /** 目录归属键：同一服务器同一用户才复用缓存。 */
    private fun sessionKey(): String = "${sessionHolder.server}|${sessionHolder.userId}"

    /** 逐页拉全库基础信息；People[] 顺带并入人物索引。 */
    private suspend fun fetchAll(): List<MediaItem> = withContext(dispatchers.io) {
        val server = sessionHolder.server
        val userId = sessionHolder.userId
        if (server.isBlank() || userId.isBlank()) return@withContext emptyList()
        val api = clients.api(server)
        val collected = mutableListOf<MediaItem>()
        val cast = mutableListOf<CastMember>()
        // 按 id 去重：有的网关忽略 StartIndex，每页都回同样的数据，
        // 不去重会拿到一目录重复条目（独一条目数 ≈ 一页）
        val seenIds = HashSet<String>()
        var start = 0
        var pages = 0
        var reportedTotal = -1
        val beganAt = System.currentTimeMillis()
        while (pages < MAX_CATALOG_PAGES) {
            val page = api.items(
                uid = userId,
                recursive = true,
                includeItemTypes = CATALOG_ITEM_TYPES,
                startIndex = start,
                limit = CATALOG_PAGE_SIZE,
                sortBy = "SortName",
                sortOrder = "Ascending",
                fields = CATALOG_FIELDS,
            )
            pages++
            reportedTotal = page.TotalRecordCount
            if (page.Items.isEmpty()) break
            var fresh = 0
            page.Items.forEach { dto ->
                cast += dto.toCastMembers(server, strings)
                dto.toMediaItem(server, strings)?.let { item ->
                    if (seenIds.add(item.id)) {
                        collected += item
                        fresh++
                    }
                }
            }
            start += page.Items.size
            // 整页都是旧条目 = 服务端忽略 StartIndex，继续翻只会重复
            if (fresh == 0) break
            if (collected.size >= MAX_CATALOG_ITEMS) break
            if (start >= page.TotalRecordCount) break
        }
        personIndex.addAll(cast)
        if (collected.size >= MAX_CATALOG_ITEMS) {
            logger.w(TAG, "搜索目录达到上限（$MAX_CATALOG_ITEMS 条），超出部分不参与本地搜索")
        }
        logger.w(
            TAG,
            "搜索目录就绪：${collected.size} 条 / 服务端上报 $reportedTotal / $pages 页 / " +
                "${System.currentTimeMillis() - beganAt}ms；人物索引并入 ${cast.size} 条演职员",
        )
        collected
    }

    private companion object {
        const val TAG = "SearchCatalog"

        /** 单页条数：500 条只带基础字段，弱网电视也能接受。 */
        const val CATALOG_PAGE_SIZE = 500

        /** 目录条数上限：防止超大库把内存和首搜耗时拖垮。 */
        const val MAX_CATALOG_ITEMS = 12_000

        /** 分页次数上限：服务端若忽略 StartIndex，避免死循环。 */
        const val MAX_CATALOG_PAGES = 40

        /**
         * 参与搜索的条目类型：海报网格能正常呈现的几类。
         *
         * `Video`（未刮削的裸视频）也要：实测库里有只以 Video 类型存在的条目
         * （搜「信号」命不中的另一半原因），服务端搜索不带类型过滤时能搜到它们。
         */
        const val CATALOG_ITEM_TYPES = "Movie,Series,BoxSet,Video"

        /**
         * 只取卡片需要的字段 + `People`（人物索引来源），
         * 避免把 MediaStreams 这类大块数据搬回来。
         */
        const val CATALOG_FIELDS = "ProductionYear,CommunityRating,ImageTags,SeriesName,SortName,People"
    }
}
