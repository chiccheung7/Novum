package com.novum.tv.data.repository

import com.novum.tv.data.remote.emby.ItemDto
import com.novum.tv.model.HomeEntry
import com.novum.tv.model.HomeRow
import java.util.Calendar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/**
 * 首页 Hero 单库候选池组装与并发区块工具。
 *
 * 单库去重与当天种子确定性洗牌、条目信息完整度打分和并发拉取容错。
 */

/**
 * Hero 候选池容量，提供足够多的轮播条目。
 */
internal const val HERO_LIMIT = 16

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
 * Hero 只从指定源库取样，再按本地日种子确定性洗牌。
 * 仅保留有背景图的条目，按 id 去重并限制候选池容量。
 *
 * 候选不足 [HERO_LIMIT] 条时返回全部可用条目。
 */
internal fun heroRotationPool(items: List<ItemDto>): List<ItemDto> {
    // 源库也可能重复下发同一条目；保留信息完整度评分较高的 DTO。
    val bestById = LinkedHashMap<String, ItemDto>()
    items.forEach { dto ->
        val id = dto.Id?.takeIf { it.isNotBlank() } ?: return@forEach
        if (dto.BackdropImageTags.firstOrNull()?.isNotBlank() != true) return@forEach
        val existing = bestById[id]
        if (existing == null || scoreOf(dto) > scoreOf(existing)) {
            bestById[id] = dto
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
