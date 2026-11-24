package com.novum.tv.data.repository

import com.novum.tv.core.dispatcher.DispatcherProvider
import com.novum.tv.core.error.NovumError
import com.novum.tv.core.log.NovumLogger
import com.novum.tv.core.network.toNovumError
import com.novum.tv.core.result.NovumResult
import com.novum.tv.core.result.failureOf
import com.novum.tv.core.result.successOf
import com.novum.tv.data.remote.emby.EmbyApi
import com.novum.tv.data.remote.emby.EmbyApiProvider
import com.novum.tv.data.remote.emby.ItemDto
import com.novum.tv.data.remote.emby.MediaSourceDto
import com.novum.tv.data.remote.emby.MediaStreamDto
import com.novum.tv.data.remote.emby.SessionHolder
import com.novum.tv.data.text.MediaStrings
import com.novum.tv.model.CastMember
import com.novum.tv.model.Chapter
import com.novum.tv.model.Episode
import com.novum.tv.model.HOME_ROW_RESUME
import com.novum.tv.model.HomeEntry
import com.novum.tv.model.MediaTrackOption
import com.novum.tv.model.MediaVersionOption
import com.novum.tv.model.HomeFeed
import com.novum.tv.model.HomeLatestRow
import com.novum.tv.model.HomeRow
import com.novum.tv.model.MediaDetail
import com.novum.tv.model.MediaItem
import com.novum.tv.model.MediaLibrary
import com.novum.tv.model.MediaPage
import com.novum.tv.model.Season
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * 媒体库浏览仓储默认实现。
 *
 * 依赖 [SessionHolder] 提供服务器与用户 id：未登录时直接返回 `Unauthorized`，
 * 不发起任何网络请求。
 *
 * @param clients Emby API 提供者。
 * @param sessionHolder 当前会话（服务器、用户、Token）。
 * @param dispatchers 调度器，网络与映射统一切到 IO。
 */
class DefaultMediaRepository @Inject constructor(
    private val clients: EmbyApiProvider,
    private val sessionHolder: SessionHolder,
    private val dispatchers: DispatcherProvider,
    private val personIndex: PersonIndexStore,
    private val searchCatalog: SearchCatalogStore,
    private val strings: MediaStrings,
    private val logger: NovumLogger,
) : MediaRepository {

    override suspend fun libraries(): NovumResult<List<MediaLibrary>> = withContext(dispatchers.io) {
        val server = sessionHolder.server
        val userId = sessionHolder.userId
        if (server.isBlank() || userId.isBlank()) {
            return@withContext failureOf(NovumError.Unauthorized())
        }
        safeCall {
            clients.api(server)
                .views(uid = userId)
                .Items
                .mapNotNull { dto -> dto.toLibrary(server) }
        }
    }

    override suspend fun items(
        parentId: String?,
        startIndex: Int,
        limit: Int,
        sortBy: String,
        ascending: Boolean,
        filters: String?,
        searchTerm: String?,
        includeItemTypes: String?,
    ): NovumResult<MediaPage> = withContext(dispatchers.io) {
        val server = sessionHolder.server
        val userId = sessionHolder.userId
        if (server.isBlank() || userId.isBlank()) {
            return@withContext failureOf(NovumError.Unauthorized())
        }
        // 搜索走本地目录：Emby 的 SearchTerm 对中文只认从头匹配/整词，
        // 「大明」搜不到「大明王朝1566」（数字片段反而能中）。目录已在
        // SearchCatalogStore 里按页拉全，这里只做 contains + 相关度排序。
        if (searchTerm != null) {
            return@withContext searchInCatalog(
                query = searchTerm,
                startIndex = startIndex,
                limit = limit,
                includeItemTypes = includeItemTypes,
            )
        }
        safeCall {
            val response = clients.api(server).items(
                uid = userId,
                parentId = parentId,
                startIndex = startIndex,
                limit = limit,
                sortBy = sortBy,
                sortOrder = if (ascending) "Ascending" else "Descending",
                filters = filters,
                includeItemTypes = includeItemTypes,
                fields = EmbyApi.DEFAULT_FIELDS,
            )
            MediaPage(
                items = response.Items.mapNotNull { dto -> dto.toMediaItem(server, strings) },
                total = response.TotalRecordCount,
            )
        }
    }

    /**
     * 在本地目录里做模糊搜索：任意位置命中 + 相关度排序 + 分页。
     *
     * @param query 关键词。
     * @param startIndex 起始下标（网格分页）。
     * @param limit 本页条数。
     * @param includeItemTypes 类型过滤（逗号分隔，null/空 = 不过滤）。
     */
    private suspend fun searchInCatalog(
        query: String,
        startIndex: Int,
        limit: Int,
        includeItemTypes: String?,
    ): NovumResult<MediaPage> = safeCall {
        val types = includeItemTypes
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            .orEmpty()
        val hits = searchCatalog.load()
            .asSequence()
            .filter { types.isEmpty() || it.type in types }
            .filter { it.title.contains(query, ignoreCase = true) }
            .sortedWith(
                compareByDescending<MediaItem> { titleRelevance(it.title, query) }
                    .thenBy { it.title },
            )
            .toList()
        MediaPage(items = hits.drop(startIndex).take(limit), total = hits.size)
    }

    /**
     * 预热搜索目录：搜索页一打开就拉，等用户打完字基本已就绪。
     *
     * 失败只留日志——真正的搜索会再试一次并把错误抛给界面。
     */
    override suspend fun warmSearchCatalog() {
        withContext(dispatchers.io) {
            runCatching { searchCatalog.load() }
                .onFailure { logger.w(TAG, "搜索目录预热失败", it) }
        }
    }

    override suspend fun detail(itemId: String): NovumResult<MediaDetail> = withContext(dispatchers.io) {
        val server = sessionHolder.server
        val userId = sessionHolder.userId
        if (server.isBlank() || userId.isBlank()) {
            return@withContext failureOf(NovumError.Unauthorized())
        }
        safeCall {
            val detail = clients.api(server)
                .detail(uid = userId, itemId = itemId)
                .toDetail(server = server, fallbackId = itemId, strings = strings)
            // 演职员进本地人物索引（搜演员名的客户端桥接；同名覆盖，无感增量）
            personIndex.addAll(detail.cast)
            detail
        }
    }

    /** 人物全部作品：按 PersonIds 过滤，并按首播日期和年份倒序。 */
    override suspend fun personWorks(personId: String): NovumResult<List<MediaItem>> =
        withContext(dispatchers.io) {
            val server = sessionHolder.server
            val userId = sessionHolder.userId
            if (server.isBlank() || userId.isBlank()) {
                return@withContext failureOf(NovumError.Unauthorized())
            }
            safeCall {
                clients.api(server)
                    .items(
                        uid = userId,
                        personIds = personId,
                        recursive = true,
                        includeItemTypes = "Movie,Series",
                        sortBy = "PremiereDate,ProductionYear",
                        sortOrder = "Descending",
                        limit = 60,
                    )
                    .Items
                    .mapNotNull { dto -> dto.toMediaItem(server, strings) }
            }
        }

    /**
     * 人物搜索：三路来源合并，按相关度排序。
     *
     * 1. 服务端 `/Persons` 端点（权威口径，但部分自建/托管网关不转发该路由，
     *    直接 404/403，此时静默降级）；
     * 2. **条目搜索结果的 `People[]`**：走全站都在用的 `/Users/{uid}/Items`，
     *    把命中条目的演职员按姓名过滤、按出现次数排序——这条路一定能通，
     *    是"搜演员名搜不到人"的兜底；
     * 3. 本地人物索引（已浏览过的演职员，离线/断网时仍可命中）。
     *
     * @param query 关键词（按姓名匹配）。
     * @param limit 返回条数上限。
     */
    override suspend fun searchPersons(query: String, limit: Int): List<PersonRef> =
        withContext(dispatchers.io) {
            val server = sessionHolder.server
            val userId = sessionHolder.userId
            val keyword = query.trim()
            if (server.isBlank() || userId.isBlank() || keyword.isEmpty()) {
                return@withContext emptyList()
            }
            val remote = runCatching {
                clients.api(server)
                    .persons(userId = userId, searchTerm = keyword, limit = limit)
                    .Items
                    .mapNotNull { dto -> dto.toPersonRef(server) }
            }.onFailure {
                logger.w(TAG, "守卫：/Persons 不可用，改用条目 People 兜底：$keyword", it)
            }.getOrDefault(emptyList())

            val derived = runCatching { personsFromItemSearch(server, userId, keyword) }
                .onFailure { logger.w(TAG, "条目 People 兜底搜索失败：$keyword", it) }
                .getOrDefault(emptyList())

            val local = runCatching { personIndex.search(keyword, limit) }.getOrDefault(emptyList())

            mergePersonHits(keyword = keyword, limit = limit, remote, derived, local)
        }

    /**
     * 从条目搜索结果里挖人物：对命中条目的 `People[]` 按姓名过滤，
     * 同一人按出现条目数计权重（主角在前、客串在后）。
     */
    private suspend fun personsFromItemSearch(
        server: String,
        userId: String,
        keyword: String,
    ): List<PersonRef> {
        val items = clients.api(server)
            .items(
                uid = userId,
                searchTerm = keyword,
                recursive = true,
                includeItemTypes = PERSON_DERIVE_ITEM_TYPES,
                limit = PERSON_DERIVE_ITEMS,
                fields = "People",
            )
            .Items
        // personId → (引用, 出现次数)；LinkedHashMap 保证同权重时按首次出现顺序
        val counts = LinkedHashMap<String, Pair<PersonRef, Int>>()
        items.forEach { item ->
            item.People.forEach personLoop@{ person ->
                val ref = person.toPersonRef(server) ?: return@personLoop
                if (!ref.name.contains(keyword, ignoreCase = true)) return@personLoop
                val previous = counts[ref.personId]
                counts[ref.personId] = if (previous == null) {
                    ref to 1
                } else {
                    previous.first to previous.second + 1
                }
            }
        }
        return counts.values
            .sortedByDescending { (_, count) -> count }
            .map { (ref, _) -> ref }
    }

    /**
     * 合并三路人物命中：按 id 去重（优先保留有头像的来源），
     * 再按"完全相同 > 前缀 > 包含"排序，最后按姓名收敛到 [limit]。
     */
    private fun mergePersonHits(
        keyword: String,
        limit: Int,
        vararg sources: List<PersonRef>,
    ): List<PersonRef> {
        val merged = LinkedHashMap<String, PersonRef>()
        sources.forEach { source ->
            source.forEach { ref ->
                val existing = merged[ref.personId]
                merged[ref.personId] = when {
                    existing == null -> ref
                    existing.imageUrl == null && ref.imageUrl != null -> ref
                    else -> existing
                }
            }
        }
        return merged.values
            .sortedWith(
                compareByDescending<PersonRef> { personRelevance(it.name, keyword) }
                    .thenBy { it.name.length }
                    .thenBy { it.name },
            )
            .take(limit)
    }

    /** 片名命中强度：完全相同 3 / 前缀 2 / 包含 1（搜「大明」时「大明王朝1566」优先）。 */
    private fun titleRelevance(title: String, keyword: String): Int = when {
        title.equals(keyword, ignoreCase = true) -> 3
        title.startsWith(keyword, ignoreCase = true) -> 2
        title.contains(keyword, ignoreCase = true) -> 1
        else -> 0
    }

    /** 姓名命中强度：完全相同 3 / 前缀 2 / 包含 1 / 其它 0（服务端按别名命中时保留原序）。 */
    private fun personRelevance(name: String, keyword: String): Int = when {
        name.equals(keyword, ignoreCase = true) -> 3
        name.startsWith(keyword, ignoreCase = true) -> 2
        name.contains(keyword, ignoreCase = true) -> 1
        else -> 0
    }

    override suspend fun seasons(seriesId: String): NovumResult<List<Season>> =
        withContext(dispatchers.io) {
            val server = sessionHolder.server
            val userId = sessionHolder.userId
            if (server.isBlank() || userId.isBlank()) {
                return@withContext failureOf(NovumError.Unauthorized())
            }
            safeCall {
                clients.api(server)
                    .seasons(seriesId = seriesId, userId = userId)
                    .Items
                    .mapIndexedNotNull { index, dto -> dto.toSeason(index, strings) }
                    .sortedBy { it.index }
            }
        }

    override suspend fun episodes(seriesId: String, seasonId: String?): NovumResult<List<Episode>> =
        withContext(dispatchers.io) {
            val server = sessionHolder.server
            val userId = sessionHolder.userId
            if (server.isBlank() || userId.isBlank()) {
                return@withContext failureOf(NovumError.Unauthorized())
            }
            safeCall {
                clients.api(server)
                    .episodes(seriesId = seriesId, userId = userId, seasonId = seasonId)
                    .Items
                    .mapIndexedNotNull { index, dto -> dto.toEpisode(server, index, strings) }
                    // 服务端偶尔把同一集下发两次（多版本/虚拟集）：不去重会让
                    // 选集列表出现重复 key（LazyRow 直接崩溃），也会让集号跳号
                    .distinctBy { it.id }
                    .sortedBy { it.index }
            }
        }

    /**
     * 季级演职员：取该季分集的 `People[]` 合并。
     *
     * 剧集条目只挂全剧演职员，季级换角/客串只能从分集拿。按出现集数排序，
     * 主角（每集都在）在前、客串在后；请求失败由页面回落剧集级演职员。
     */
    override suspend fun seasonCast(seriesId: String, seasonId: String): NovumResult<List<CastMember>> =
        withContext(dispatchers.io) {
            val server = sessionHolder.server
            val userId = sessionHolder.userId
            if (server.isBlank() || userId.isBlank()) {
                return@withContext failureOf(NovumError.Unauthorized())
            }
            safeCall {
                val cast = clients.api(server)
                    .episodes(
                        seriesId = seriesId,
                        userId = userId,
                        seasonId = seasonId,
                        // 长剧集不必整季拉取：前若干集已覆盖常驻主创与主要客串
                        limit = CAST_SAMPLE_EPISODES,
                        fields = "${EmbyApi.EPISODE_FIELDS},People",
                    )
                    .Items
                    .toSeasonCast(server, strings)
                // 季级出现的演员同样进本地人物索引（搜名字能命中）
                personIndex.addAll(cast)
                cast
            }
        }

    override suspend fun seriesNextUp(seriesId: String): NovumResult<Episode?> =
        withContext(dispatchers.io) {
            val server = sessionHolder.server
            val userId = sessionHolder.userId
            if (server.isBlank() || userId.isBlank()) {
                return@withContext failureOf(NovumError.Unauthorized())
            }
            safeCall {
                val api = clients.api(server)
                // 单剧集 NextUp——详情页默认定位的「当前集」。字段用
                // 分集口径（带 MediaSources），让焦点集的多版本信息开箱即用；
                // 全部看完且无上新时服务端返回空列表 → null（页面回落剧集级）。
                //
                // 部分 Emby 兼容网关会忽略 SeriesId，直接返回全库 NextUp。
                // 必须在客户端按 SeriesId 再过滤，否则详情页会拿别的剧集的分集当
                // 定位锚点，默认季随之错到第一季（提示集不在本剧任何一季里）。
                // 取一批而不是 1 条：网关忽略 SeriesId 时，本剧的那条会排在这批
                // 里面，limit=1 就只能拿到别剧的分集然后整体失败。
                val candidates = api.nextUp(
                    uid = userId,
                    limit = NEXTUP_HINT_LIMIT,
                    fields = EmbyApi.EPISODE_FIELDS,
                    seriesId = seriesId,
                ).Items
                val next = candidates.firstOrNull { dto -> dto.SeriesId == seriesId }
                    // 极简网关可能完全不下发 SeriesId（无从核对）：只有整批都缺该
                    // 字段时才采信首条，避免把「有 SeriesId 但都不是本剧」当本剧
                    ?: candidates
                        .takeIf { list -> list.isNotEmpty() && list.all { it.SeriesId == null } }
                        ?.first()
                // NextUp 被网关忽略/为空时，回落到该剧最近的续播分集（同样按
                // SeriesId 过滤，防止 ParentId 也被忽略）。仍无进度则返回 null。
                val hint = next ?: api.resume(
                    uid = userId,
                    limit = RESUME_HINT_LIMIT,
                    parentId = seriesId,
                ).Items.firstOrNull { dto -> dto.SeriesId == seriesId }
                hint?.let { dto -> dto.toEpisode(server, index = 0, strings = strings) }
            }
        }

    override suspend fun setFavorite(itemId: String, favorite: Boolean): NovumResult<Boolean> =
        withContext(dispatchers.io) {
            val server = sessionHolder.server
            val userId = sessionHolder.userId
            if (server.isBlank() || userId.isBlank()) {
                return@withContext failureOf(NovumError.Unauthorized())
            }
            safeCall {
                val api = clients.api(server)
                val data = if (favorite) {
                    api.addFavorite(uid = userId, itemId = itemId)
                } else {
                    api.removeFavorite(uid = userId, itemId = itemId)
                }
                data.IsFavorite
            }
        }

    override suspend fun setPlayed(itemId: String, played: Boolean): NovumResult<Boolean> =
        withContext(dispatchers.io) {
            val server = sessionHolder.server
            val userId = sessionHolder.userId
            if (server.isBlank() || userId.isBlank()) {
                return@withContext failureOf(NovumError.Unauthorized())
            }
            safeCall {
                val api = clients.api(server)
                val data = if (played) {
                    api.markPlayed(uid = userId, itemId = itemId)
                } else {
                    api.markUnplayed(uid = userId, itemId = itemId)
                }
                data.Played
            }
        }

    override suspend fun feed(): NovumResult<HomeFeed> = withContext(dispatchers.io) {
        val server = sessionHolder.server
        val userId = sessionHolder.userId
        if (server.isBlank() || userId.isBlank()) {
            return@withContext failureOf(NovumError.Unauthorized())
        }
        safeCall {
            val api = clients.api(server)
            // 只有影片与剧集两类库上首页；音乐 / 图片等类型的内容形态不同，不强行混排
            val libraries = api.views(uid = userId).Items
                .mapNotNull { dto -> dto.toLibrary(server) }
                .filter { it.kind == "movies" || it.kind == "tvshows" }
            // Hero 候选的双口径：电影看发行时间，剧综看近期是否上新
            val movieCutoff = isoDateDaysAgo(DAYS_MOVIE_WINDOW)
            val updateCutoff = isoDateDaysAgo(DAYS_UPDATE_WINDOW)
            coroutineScope {
                // 各区块并发拉取，单个区块失败只让该区块为空，不拖垮整页
                val resumeItems = asyncOrNull { api.resume(uid = userId).Items }
                val nextUpItems = asyncOrNull { api.nextUp(uid = userId).Items }
                val moviePicks = asyncOrNull {
                    api.items(
                        uid = userId,
                        includeItemTypes = "Movie",
                        sortBy = "PremiereDate,SortName",
                        limit = REQUEST_MOVIE_LIMIT,
                        minPremiereDate = movieCutoff,
                    ).Items
                }
                // Emby 的 PlayCount 排序按累计播放次数提供 Hero 热播候选。
                // 电影+剧集一起取，只要可播放条目（IsNotFolder 排除合集/目录）。
                val hotPicks = asyncOrNull {
                    api.items(
                        uid = userId,
                        includeItemTypes = "Movie,Series",
                        sortBy = "PlayCount,CommunityRating",
                        sortOrder = "Descending",
                        filters = "IsNotFolder",
                        limit = REQUEST_HOT_LIMIT,
                    ).Items
                }
                val latestPerLibrary = libraries.map { library ->
                    asyncOrNull { library.id to api.latest(uid = userId, parentId = library.id) }
                }

                // 各库最新：保留原始 DTO（Hero 需要背景图标签），再映射成行数据
                val rawLatest = latestPerLibrary.mapNotNull { deferred ->
                    val (libraryId, items) = deferred.await() ?: return@mapNotNull null
                    val library = libraries.firstOrNull { it.id == libraryId } ?: return@mapNotNull null
                    library to items
                }
                val latest = rawLatest.mapNotNull { (library, items) ->
                    val entries = items.mapNotNull { dto -> dto.toHomeEntry(server, strings) }
                    if (entries.isEmpty()) {
                        null
                    } else {
                        HomeLatestRow(libraryId = library.id, libraryName = library.name, items = entries)
                    }
                }

                val movies = moviePicks.await().orEmpty()
                    // 服务端可能忽略 MinPremiereDate，因此客户端再过滤一次。
                    .filter { dto -> dto.PremiereDate.isOnOrAfter(movieCutoff) }
                    .sortedByDescending { dto -> dto.CommunityRating ?: 0f }
                    .take(PICK_MOVIE_LIMIT)
                // 剧综池复用各库最新结果，避免额外请求；按条目类型筛选，不依赖库类型标记。
                val seriesPool = rawLatest
                    .flatMap { (_, items) -> items }
                    .filter { dto -> dto.Type == "Series" }
                    .distinctBy { dto -> dto.Id }
                // 「近一周有更新」优先看最后添加新集的时间；服务端不返回该字段时退回入库时间
                val seriesInWeek = seriesPool.filter { dto -> dto.isUpdatedSince(updateCutoff) }
                // 一周内没有新集就退回一个月；latest 本身就是最近入库，字段全缺时直接采用
                val fallbackCutoff = isoDateDaysAgo(DAYS_UPDATE_FALLBACK)
                val series = seriesInWeek.ifEmpty { seriesPool.filter { dto -> dto.isUpdatedSince(fallbackCutoff) } }
                    .ifEmpty { seriesPool }
                    .sortedByDescending { dto -> dto.DateLastMediaAdded ?: dto.DateCreated.orEmpty() }
                    .take(PICK_SERIES_LIMIT)
                // 电影在前（评分优先），剧综在后（按最近上新时间）

                val resumeDtoItems = resumeItems.await().orEmpty()
                // 正在续播的条目不占用 Hero 推荐位，避免重复展示。
                val resumeIds = resumeDtoItems.mapNotNull { dto -> dto.Id }.toSet()
                // 继续观看行合并 NextUp：播放中的集保留在行内，看完或追平的剧集由 NextUp 递补。
                // 同一剧集只保留一张卡：
                // 在看的优先，NextUp 条目按分集 id + 剧集 id 双重去重后追加行尾。
                val resumeSeriesIds = resumeDtoItems.mapNotNull { dto -> dto.SeriesId }.toSet()
                val mergedResume = (
                    resumeDtoItems +
                        nextUpItems.await().orEmpty()
                            .filter { dto -> dto.isUnfinishedForResume() }
                            .filter { dto -> dto.Id !in resumeIds && dto.SeriesId !in resumeSeriesIds }
                            // 同剧集的多个 NextUp 只留首个；SeriesId 缺失时按分集 id 去重，
                            // 不能把所有无剧集归属的条目折叠成一张卡。
                            .distinctBy { dto -> dto.SeriesId ?: dto.Id }
                    )
                    .distinctBy { dto -> dto.Id }
                    .take(HOME_ROW_RESUME_LIMIT)

                // Hero 优先使用热播候选；数量不足时从电影、剧集和各库最新条目构建候选池。
                // 两条路径都按同一规则洗牌并限制容量。
                // 先按「可展示」过滤再判断数量：热播候选里常混入没有背景图的
                // 合集/低质量条目，若按过滤前数量走热播分支，结果会不足甚至为空，
                // 且永远不会进入电影/剧集/latest 三级兜底。
                val hot = hotPicks.await().orEmpty()
                    .filter { dto -> dto.Id !in resumeIds && dto.BackdropImageTags.isNotEmpty() }
                val hero = if (hot.size >= HERO_LIMIT) {
                    hot.seededShuffle(_heroSeedProvider())
                        .take(HERO_LIMIT)
                } else {
                    heroRotationPool(
                        movies = movies.filter { dto -> dto.Id !in resumeIds },
                        series = series.filter { dto -> dto.Id !in resumeIds },
                        latest = rawLatest.flatMap { (_, items) -> items }
                            .filter { dto -> dto.Id !in resumeIds },
                    )
                }
                    // /Items/Latest 不含 MediaStreams 和 OfficialRating；按 Ids 回捞完整字段，
                    // 请求失败时保留原 DTO。
                    .let { pool ->
                        val poolIds = pool.mapNotNull { it.Id }
                        val fullItems = if (poolIds.isEmpty()) {
                            null
                        } else {
                            asyncOrNull {
                                api.items(
                                    uid = userId,
                                    ids = poolIds.joinToString(","),
                                    limit = poolIds.size,
                                ).Items
                            }.await().orEmpty()
                        }
                        val fullById = fullItems?.associateBy { it.Id }
                        pool.mapNotNull { dto ->
                            (fullById?.get(dto.Id) ?: dto).toHomeEntry(server, strings)
                        }
                    }

                HomeFeed(
                    hero = hero,
                    resume = mergedResume
                        .mapNotNull { dto -> dto.toHomeEntry(server, strings) }
                        .toRow(HOME_ROW_RESUME),
                    // 首页按媒体库目录构建分库内容行。
                    libraries = libraries,
                    nextUp = null,
                    latest = latest,
                )
            }
        }
    }

    /** 统一异常映射，保证仓储不向 UI 抛异常。 */
    private suspend fun <T> safeCall(block: suspend () -> T): NovumResult<T> = try {
        successOf(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        failureOf(throwable.toNovumError())
    }
}

/**
 * NextUp 条目是否适合放进「继续观看」行。
 *
 * NextUp 服务端偶尔会把已看完/已追平的剧集再次返回；这类条目没有可续播进度，
 * 放进继续观看行会出现「点进去没有进度」的空壳卡。UserData 缺失时不过滤，
 * 避免服务端精简字段导致所有 NextUp 被误杀。
 */
private fun com.novum.tv.data.remote.emby.ItemDto.isUnfinishedForResume(): Boolean {
    val data = UserData ?: return true
    return !data.Played && data.PlayedPercentage < 100.0
}

/** 日志标签。 */
private const val TAG = "MediaRepo"

/** 季级演职员取样集数：足够覆盖常驻主创与主要客串，又不至于整季大请求。 */
private const val CAST_SAMPLE_EPISODES = 24

/** NextUp 为空时回查续播列表的条数上限。 */
private const val RESUME_HINT_LIMIT = 12

/** 人物兜底搜索取样条目数：20 条主角阵容足够覆盖常见演员名。 */
private const val PERSON_DERIVE_ITEMS = 20

/** 人物兜底搜索的条目类型：剧集/电影/分集都挂 People。 */
private const val PERSON_DERIVE_ITEM_TYPES = "Movie,Series,Episode"

/**
 * 单剧集 NextUp 的取样条数：网关忽略 `SeriesId` 时用来在客户端捞出本剧那条，
 * 太小会漏、太大浪费，8 条足够覆盖若干在追剧集。
 */
private const val NEXTUP_HINT_LIMIT = 8
