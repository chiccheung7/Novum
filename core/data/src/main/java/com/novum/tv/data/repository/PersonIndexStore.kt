package com.novum.tv.data.repository

import com.novum.tv.model.CastMember
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 本地人物索引，为人物名称搜索和作品页提供本地结果。
 *
 * 服务端人物搜索接口不可用时，可从条目的 `People[]` 建立索引；作品查询使用 PersonIds。
 *
 * 详情和搜索结果中出现的演职员会写入索引；搜索命中后通过
 * [MediaRepository.personWorks] 打开人物作品页。
 *
 * 索引仅覆盖应用中已出现过的演职员；[Mutex] 串行化读写。
 *
 * @param storeFile 索引落盘文件（Hilt 从 filesDir 提供；测试传临时文件）。
 * @param ioDispatcher 文件 IO 调度器；测试可注入测试调度器。
 */
class PersonIndexStore(
    private val storeFile: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()
    private var cache: MutableMap<String, PersonRef>? = null

    /** 批量并入（同名覆盖）；无有效人物 id 的条目跳过。 */
    suspend fun addAll(members: List<CastMember>) {
        val valid = members.mapNotNull { member ->
            val personId = member.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            PersonRef(name = member.name, personId = personId, imageUrl = member.imageUrl)
        }
        if (valid.isEmpty()) return
        mutex.withLock {
            // 读不出来就不写：拿空表落盘会把已积累的索引整片抹掉（下次访问会重试读盘）
            val map = loadLocked() ?: return@withLock
            valid.forEach { ref -> map[ref.name] = ref }
            writeLocked(map)
        }
    }

    /** 本地人名包含匹配（忽略大小写），按名字长度升序（短名=更准的命中优先）。 */
    suspend fun search(query: String, limit: Int = SEARCH_LIMIT): List<PersonRef> =
        mutex.withLock {
            val q = query.trim()
            if (q.isEmpty()) return@withLock emptyList()
            loadLocked()?.values.orEmpty()
                .filter { it.name.contains(q, ignoreCase = true) }
                .sortedBy { it.name.length }
                .take(limit)
        }

    /**
     * 读取索引；**null = 本次读盘失败**（IO 异常），调用方应放弃写入。
     *
     * 文件不存在 / 内容为空 → 正常空表（还没写过）；内容损坏 → 当空表重建
     * （索引是「见过即记」的可重建数据，卡死在损坏内容上更糟）；
     * 真正读失败 → null，且不缓存，下次访问重试读盘。
     */
    private suspend fun loadLocked(): MutableMap<String, PersonRef>? {
        cache?.let { return it }
        val loaded: Map<String, PersonRef> = withContext(ioDispatcher) {
            runCatching {
                if (!storeFile.exists()) return@runCatching emptyMap<String, PersonRef>()
                val text = storeFile.readText()
                if (text.isBlank()) return@runCatching emptyMap<String, PersonRef>()
                runCatching { json.decodeFromString<Map<String, PersonRef>>(text) }
                    .getOrElse { emptyMap<String, PersonRef>() }
            }.getOrNull()
        } ?: return null
        return loaded.toMutableMap().also { cache = it }
    }

    private suspend fun writeLocked(map: Map<String, PersonRef>) {
        withContext(ioDispatcher) {
            runCatching { storeFile.writeText(json.encodeToString(map)) }
        }
    }

    companion object {
        private const val SEARCH_LIMIT = 8
        private val json = Json { ignoreUnknownKeys = true }
    }
}

/** 索引条目：人物名 → 人物 id（作品页）+ 头像地址。 */
@Serializable
data class PersonRef(
    val name: String,
    val personId: String,
    val imageUrl: String? = null,
)
