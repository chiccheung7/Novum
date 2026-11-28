package com.novum.tv.data.repository

import com.novum.tv.core.error.NovumError
import com.novum.tv.core.log.NovumLogger
import com.novum.tv.core.network.toNovumError
import com.novum.tv.core.result.NovumResult
import com.novum.tv.data.remote.danmu.DanmuClient
import com.novum.tv.model.DanmuAnime
import com.novum.tv.model.DanmuComment
import com.novum.tv.model.DanmuEpisode
import com.novum.tv.model.DanmuMatch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 弹幕仓储：UHDNOW 弹幕服务的匹配/搜索/取弹幕。
 *
 * 弹幕是 UHD 原生能力（Emby 协议没有），只在 UHDNOW 服务器上可用；
 * 其他 Emby 服务器调用会在鉴权处失败，调用方按「弹幕不可用」静默降级。
 */
interface DanmuRepository {

    /**
     * 按资源文件名自动匹配弹幕分集。
     *
     * @param fileName 资源文件名（PlaybackInfo 里 MediaSource 的 Path/Name）。
     * @return 匹配到的分集；`isMatched=false` 或无可用匹配时为 null（Success 包 null）。
     */
    suspend fun match(fileName: String): NovumResult<DanmuMatch?>

    /** 按片名搜索动画（手动匹配入口）。 */
    suspend fun searchAnime(keyword: String): NovumResult<List<DanmuAnime>>

    /** 取指定动画的分集列表（手动匹配选定动画后）。 */
    suspend fun episodes(bangumiId: String): NovumResult<List<DanmuEpisode>>

    /** 取指定分集的弹幕列表（已按时间排序）。 */
    suspend fun comments(episodeId: String): NovumResult<List<DanmuComment>>
}

/**
 * 默认实现：直连 UHDNOW 弹幕端点（dandanplay v2 协议）。
 *
 * 鉴权说明：token 由 [DanmuClient] 拼进 URL 路径；Emby 协议 token 能否通过
 * 弹幕服务校验属于服务端行为，401/403 时按「弹幕不可用」降级，不影响播放。
 */
@Singleton
class DefaultDanmuRepository @Inject constructor(
    private val client: DanmuClient,
    private val mediaStrings: com.novum.tv.data.text.MediaStrings,
    private val logger: NovumLogger,
) : DanmuRepository {

    override suspend fun match(fileName: String): NovumResult<DanmuMatch?> =
        danmuResult(mediaStrings, logger, TAG) {
            val prefix = requirePrefix()
            val response = client.api().match(
                url = "$prefix/match",
                body = buildJsonObject { put("fileName", fileName) },
            )
            parseMatch(response)
        }

    override suspend fun searchAnime(keyword: String): NovumResult<List<DanmuAnime>> =
        danmuResult(mediaStrings, logger, TAG) {
            val prefix = requirePrefix()
            val response = client.api().search(
                url = "$prefix/search/anime?keyword=${encode(keyword)}",
            )
            parseAnimes(response)
        }

    override suspend fun episodes(bangumiId: String): NovumResult<List<DanmuEpisode>> =
        danmuResult(mediaStrings, logger, TAG) {
            val prefix = requirePrefix()
            val response = client.api().episodes(
                url = "$prefix/bangumi/${encode(bangumiId)}",
            )
            parseEpisodes(response)
        }

    override suspend fun comments(episodeId: String): NovumResult<List<DanmuComment>> =
        danmuResult(mediaStrings, logger, TAG) {
            val prefix = requirePrefix()
            val response = client.api().comments(
                url = "$prefix/comment/${encode(episodeId)}?format=json",
            )
            parseComments(response)
        }

    /** 弹幕端点前缀；未登录时弹幕不可用。 */
    private fun requirePrefix(): String =
        client.baseUrl() ?: throw DanmuUnavailableException()

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())
}

/** 日志标签（弹幕请求失败留痕）。 */
private const val TAG = "DanmuRepo"

/** 未登录/无 token：弹幕不可用（与鉴权失败区分——这不是错误状态）。 */
class DanmuUnavailableException : Exception("danmu session unavailable")

/**
 * 弹幕请求统一结果封装：未登录 → Unauthorized（弹幕不可用）；
 * 其余异常走通用网络错误映射（401/403 → Unauthorized、429 → 过频提示文案）。
 * CancellationException 原样抛出。
 */
private suspend fun <T> danmuResult(
    mediaStrings: com.novum.tv.data.text.MediaStrings,
    logger: NovumLogger,
    tag: String,
    block: suspend () -> T,
): NovumResult<T> =
    try {
        NovumResult.Success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (unavailable: DanmuUnavailableException) {
        NovumResult.Failure(NovumError.Unauthorized(unavailable))
    } catch (error: Exception) {
        val mapped = error.toNovumError()
        val refined = if (mapped is NovumError.Server && mapped.code == 429) {
            mapped.copy(message = mediaStrings.danmuRateLimited())
        } else {
            mapped
        }
        logger.w(tag, "弹幕请求失败", error)
        NovumResult.Failure(refined)
    }

// ---- 协议解析（纯函数，单测直接覆盖）----

/**
 * 解析 match 响应：`{isMatched, matches: [{episodeId, episodeTitle, animeTitle}]}`
 *
 * 未匹配返回 null；结构异常抛 [IllegalStateException]（由调用方映射为 Parse 错误）。
 */
internal fun parseMatch(response: JsonObject): DanmuMatch? {
    val matched = response.parsedBoolean("isMatched") ?: false
    if (!matched) return null
    val row = response.objList("matches").firstOrNull() ?: return null
    val episodeId = row.idValue("episodeId") ?: return null
    return DanmuMatch(
        episodeId = episodeId,
        title = row.textValue("episodeTitle"),
        animeTitle = row.textValue("animeTitle"),
    )
}

/** 解析 search/anime 响应：`{animes: [{bangumiId, animeTitle}]}`（上限 200 条）。 */
internal fun parseAnimes(response: JsonObject): List<DanmuAnime> =
    response.objList("animes").take(200).mapNotNull { row ->
        val bangumiId = row.idValue("bangumiId") ?: return@mapNotNull null
        DanmuAnime(bangumiId, row.textValue("animeTitle"))
    }

/** 解析 bangumi 响应：`{bangumi: {episodes: [{episodeId, episodeTitle}]}}`（上限 2000 条）。 */
internal fun parseEpisodes(response: JsonObject): List<DanmuEpisode> {
    val bangumi = response["bangumi"] as? JsonObject ?: return emptyList()
    return bangumi.objList("episodes").take(2000).mapNotNull { row ->
        val episodeId = row.idValue("episodeId") ?: return@mapNotNull null
        DanmuEpisode(episodeId, row.textValue("episodeTitle"))
    }
}

/**
 * 解析 comment 响应：`{comments: [{p: "秒,模式,颜色,…", m: "文本"}]}`
 *
 * dandanplay 的 `p` 字段至少三段：时间（秒）、模式（1/4/5）、颜色（RGB）。
 * 解析口径与 UHDNOW 官方网页播放器一致：非法时间/模式丢弃、控制字符清洗、
 * 文本截 160 字、总量上限 20 万条（超过视为服务端异常，整批拒绝）。
 */
internal fun parseComments(response: JsonObject): List<DanmuComment> {
    val rows = (response["comments"] as? JsonArray) ?: throw IllegalStateException("comments missing")
    require(rows.size <= MAX_COMMENTS) { "Too many danmu comments" }
    val parsed = rows.mapNotNull { element ->
        val row = element as? JsonObject ?: return@mapNotNull null
        val fields = row.textValue("p")?.split(',') ?: return@mapNotNull null
        val seconds = fields.getOrNull(0)?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it in 0.0..MAX_SECONDS } ?: return@mapNotNull null
        val mode = fields.getOrNull(1)?.toIntOrNull()
            ?.takeIf { it in DANMU_MODES } ?: return@mapNotNull null
        val color = fields.getOrNull(2)?.toLongOrNull()
            ?.takeIf { it in 0L..0xFFFFFFL } ?: 0xFFFFFFL
        val text = row.textValue("m")
            ?.replace(CONTROL_CHARS, "")
            ?.trim()
            ?.take(MAX_TEXT_LENGTH)
            ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        DanmuComment(
            text = text,
            timeMs = (seconds * 1000).toLong(),
            mode = mode,
            color = 0xFF000000L or color,
        )
    }
    return parsed.sortedBy { it.timeMs }
}

/** 服务端接受的弹幕模式：1=滚动 4=底部 5=顶部。 */
internal val DANMU_MODES = setOf(1, 4, 5)

/** 单集弹幕总量上限（超过视为服务端异常）。 */
internal const val MAX_COMMENTS = 200_000

/** 弹幕时间上限（7 天，协议口径）。 */
internal const val MAX_SECONDS = 604_800.0

/** 单条弹幕文本上限。 */
internal const val MAX_TEXT_LENGTH = 160

/** 文本清洗：控制字符（含换行/制表符，弹幕是单行文本）。 */
internal val CONTROL_CHARS = Regex("[\\u0000-\\u001f\\u007f]")

/** id 字段取值：字符串（长度 ≤ 256）或正整数，都规范成 String。 */
internal fun JsonObject.idValue(key: String): String? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    val text = primitive.contentOrNull ?: primitive.longOrNull?.toString() ?: return null
    return text.takeIf { it.length in 1..256 }
}

internal fun JsonObject.textValue(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.take(300)

internal fun JsonObject.parsedBoolean(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

internal fun JsonObject.objList(key: String): List<JsonObject> =
    (this[key] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
