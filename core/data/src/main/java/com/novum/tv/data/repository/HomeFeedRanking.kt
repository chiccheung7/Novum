package com.novum.tv.data.repository

import com.novum.tv.data.remote.emby.ItemDto
import com.novum.tv.model.HOME_ROW_RESUME
import com.novum.tv.model.HomeEntry
import com.novum.tv.model.HomeRow
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/**
 * 首页 Hero 候选池组装与日期处理。
 *
 * 三池交错 + 当天种子确定性洗牌、条目信息完整度打分、并发拉取容错
 * 与日期窗口工具。全部为纯函数/常量，不含仓储状态。
 */

/**
 * Hero 候选池容量，提供足够多的轮播条目。
 */
internal const val HERO_LIMIT = 16

/** Hero 候选口径：电影取近一年内发行的。 */
internal const val DAYS_MOVIE_WINDOW = 365

/** Hero 候选口径：剧综取近一周有新集入库的。 */
internal const val DAYS_UPDATE_WINDOW = 7

/** Hero 候选池里电影的展示条数上限（本地过滤后按评分排序再截断）；预留空间给剧综。 */
internal const val PICK_MOVIE_LIMIT = 8

/** Hero 候选池里剧综的展示条数上限（按剧去重后截断）。 */
internal const val PICK_SERIES_LIMIT = 10

/** Hero 电影池请求条数：按首播时间倒序多取一些，避免服务端忽略时间过滤后本地无料可筛。 */
internal const val REQUEST_MOVIE_LIMIT = 120

/** 热播榜请求条数：多取一些，洗牌后取 HERO_LIMIT。 */
internal const val REQUEST_HOT_LIMIT = 60

/**
 * 继续观看行容量：resume 上限 12 + NextUp 递补，全行截到 16——
 * 宽卡行过长会让图片解码堆积（快滚降载只救急不救常态）。
 */
internal const val HOME_ROW_RESUME_LIMIT = 16

/**
 * Hero 轮换种子提供者：默认取「本地日序号」（epochDay 同义，每天 +1）——
 * **按天轮换**（Netflix 日更口径）。生产路径零感知；公开仅为让 :app 单测
 * 注入固定种子获得确定性输出（跨模块 internal 不可见），请勿在生产代码改写。
 *
 * 用 Calendar 而非 LocalDate：minSdk 24 且未开 core library desugaring（lint NewApi）。
 */
var DefaultMediaRepository.heroSeedProvider: () -> Long
    get() = _heroSeedProvider
    set(value) { _heroSeedProvider = value }

private const val MILLIS_PER_DAY = 86_400_000L

internal var _heroSeedProvider: () -> Long = {
    Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis / MILLIS_PER_DAY
}

/**
 * Hero 候选池按电影、剧集和各库最新条目交错取样，再按本地日种子确定性洗牌。
 * 仅保留有背景图的条目，按 id 去重并限制候选池容量。
 *
 * 候选不足 [HERO_LIMIT] 条时返回全部可用条目。
 */
internal fun heroRotationPool(movies: List<ItemDto>, series: List<ItemDto>, latest: List<ItemDto>): List<ItemDto> {
    val interleave = buildList {
        val depth = maxOf(movies.size, series.size, latest.size)
        for (i in 0 until depth) {
            movies.getOrNull(i)?.let(::add)
            series.getOrNull(i)?.let(::add)
            latest.getOrNull(i)?.let(::add)
        }
    }
        .filter { it.BackdropImageTags.isNotEmpty() }
    // 同一条目可能来自多个来源；保留信息完整度评分较高的 DTO。
    val bestById = LinkedHashMap<String?, ItemDto>()
    interleave.forEach { dto ->
        val existing = bestById[dto.Id]
        if (existing == null || scoreOf(dto) > scoreOf(existing)) {
            bestById[dto.Id] = dto
        }
    }
    return bestById.values.toList()
        .seededShuffle(_heroSeedProvider())
        .take(HERO_LIMIT)
}

/** 条目信息完整度打分（Hero 去重时保留信息更全的那份）。 */
private fun scoreOf(dto: ItemDto): Int =
    (if (dto.CommunityRating != null) 4 else 0) +
        (if (dto.OfficialRating != null) 2 else 0) +
        (if (dto.ProductionYear != null) 1 else 0) +
        (if (dto.Overview != null) 1 else 0)

/**
 * 确定性 Fisher–Yates 洗牌：同 (输入, seed) 恒同输出。
 * 不能用无参 [kotlin.collections.shuffled]——不确定顺序破坏「同日稳定」。
 */
internal fun <T> List<T>.seededShuffle(seed: Long): List<T> {
    val list = toMutableList()
    val rng = kotlin.random.Random(seed)
    for (i in list.size - 1 downTo 1) {
        val j = rng.nextInt(i + 1)
        list[i] = list[j].also { list[j] = list[i] }
    }
    return list
}

/** 一周内没有剧综更新时的兜底窗口（天）。 */
internal const val DAYS_UPDATE_FALLBACK = 30

/**
 * 并发拉取的容错封装：区块接口失败时返回 null，不让单点异常扩散到整页。
 *
 * @param block 区块请求。
 */
internal fun <T> CoroutineScope.asyncOrNull(block: suspend () -> T): Deferred<T?> = async {
    try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        null
    }
}

/** 空列表转 null，界面据此跳过空区块。 */
internal fun List<HomeEntry>.toRow(id: String): HomeRow? =
    takeIf { isNotEmpty() }?.let { HomeRow(id = id, items = it) }

/**
 * 生成 N 天前的日期字符串（`yyyy-MM-dd`），供 Emby 的日期过滤参数使用。
 *
 * 项目 minSdk 24 且未开 desugaring，不能用 `java.time`，这里用 Calendar。
 *
 * @param days 相对今天的天数（正数表示过去）。
 */
internal fun isoDateDaysAgo(days: Int): String {
    val calendar = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -days) }
    return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(calendar.time)
}

/**
 * 剧集是否在给定日期之后有新内容（新集入库或新剧上线）。
 *
 * @param cutoff 日期下限（含当天）。
 */
internal fun ItemDto.isUpdatedSince(cutoff: String): Boolean =
    DateLastMediaAdded.isOnOrAfter(cutoff) || DateCreated.isOnOrAfter(cutoff)

/**
 * ISO-8601 时间是否不早于给定日期（`yyyy-MM-dd`）。
 *
 * 服务端可能忽略 `MinPremiereDate` / `MinDateCreated`，因此客户端再校验日期范围。
 *
 * @param cutoff 日期下限（含当天）。
 */
internal fun String?.isOnOrAfter(cutoff: String): Boolean =
    this != null && take(10) >= cutoff
