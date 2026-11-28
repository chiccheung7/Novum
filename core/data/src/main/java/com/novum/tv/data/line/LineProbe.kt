package com.novum.tv.data.line

import com.novum.tv.core.log.NovumLogger
import com.novum.tv.core.result.runCatchingSuspend
import com.novum.tv.data.remote.emby.EmbyApiProvider
import com.novum.tv.data.remote.emby.SessionHolder
import com.novum.tv.data.remote.emby.SessionSnapshot
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 一条可用线路。
 *
 * @param baseUrl 线路根地址（含 scheme 与端口，不带尾部斜杠）。
 * @param latencyMs 测速结果；探测失败的线路为 [Long.MAX_VALUE]。
 * @param name 线路显示名（官方 `data[].name`；递归兜底解析无 name 时为 null，
 *   展示层回退 [shortName]）。
 */
data class ServerLine(
    val baseUrl: String,
    val latencyMs: Long,
    val name: String? = null,
) {
    /** 展示用短名（去掉 scheme，便于信息面板一行放下）。 */
    val shortName: String get() = baseUrl.substringAfter("://")

    /** 展示名：官方名优先，无则回退域名短名。 */
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: shortName
}

/**
 * 多线路探测与测速，支持手动锁线和服务端线路名。
 *
 * 流程：`GET /System/Ext/ServerDomains`（服务端扩展，官方文档
 * docs.uhdnow.com/partner/emby-ext-domains：返回 `{ ok, data: [{ name, url }] }`，
 * 列表顺序即服务端配置顺序、无优先级字段）→ 结构化提取 `data[].name/url`，
 * 结构不认识时**递归兜底**提取其中所有 http(s) URL → 与当前服务器地址合并 →
 * **并发 HEAD 测速** → 按延迟升序排序。未被当前 HTTP 会话或固定线路授权的 HTTP
 * 地址只供线路选择器展示，不探测，也不会进入自动选线结果。
 *
 * [pinnedLine] 非空时排到列表首位且不被 [excludeBaseUrl] 过滤；测速结果仅供展示。
 * 切线只影响媒体流域名，API 鉴权域保持不变。
 *
 * 容错原则：
 * - 接口不存在、401、响应无法解析或网络不可用时，退化为「当前服务器」单线路；
 * - 解析不依赖字段名（兜底路径）：递归扫描任意层级的字符串，凡是以
 *   http(s) 开头的都视为候选线路；
 * - 结果进程内缓存 [CACHE_TTL_MS]；[invalidate] 供手动切线后强制重探。
 */
@Singleton
class LineProbe @Inject constructor(
    private val clients: EmbyApiProvider,
    private val sessionHolder: SessionHolder,
    private val logger: NovumLogger,
) {

    private data class CacheEntry(val at: Long, val lines: List<ServerLine>)

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /**
     * 取线路列表：手动锁线置顶（且不被 exclude 过滤），其余按延迟升序。
     *
     * @param excludeBaseUrl 需要排除的线路（换线重试时排除刚失败的那条；
     *   仅在未手动锁线时生效——锁定的线路用户点名要用的，不给自动换线让位）。
     * @param includeUnapprovedHttp 线路选择器可设为 true，以展示需用户确认的 HTTP 线路；
     *   该选项不会触发未授权 HTTP 请求。
     */
    suspend fun lines(
        excludeBaseUrl: String? = null,
        pinnedLine: String? = null,
        pinnedLineCleartextConfirmed: Boolean = false,
        includeUnapprovedHttp: Boolean = false,
    ): List<ServerLine> {
        val owner = sessionHolder.capture()
        return withContext(Dispatchers.IO) {
            val key = owner.playbackPrefsNamespace() ?: return@withContext emptyList()
            if (sessionHolder.capture() !== owner) return@withContext emptyList()
            sessionHolder.setPinnedLineCleartextPermission(pinnedLine, key, pinnedLineCleartextConfirmed)
            val now = System.currentTimeMillis()
            val cached = cache[key]?.takeIf { now - it.at < CACHE_TTL_MS }?.lines
            val base = cached ?: probe(owner)
            if (sessionHolder.capture() !== owner) return@withContext emptyList()
            if (cached == null) cache[key] = CacheEntry(now, base)
            val eligible = if (includeUnapprovedHttp) base else base.filter {
                sessionHolder.isCleartextAllowed(it.baseUrl)
            }
            val pinned = pinnedLine?.takeIf {
                it.isNotBlank() && (includeUnapprovedHttp || sessionHolder.isCleartextAllowed(it))
            }
            val pinnedEntry = pinned?.let { url -> eligible.find { it.baseUrl == url } }
            val rest = eligible.filter {
                (excludeBaseUrl == null || it.baseUrl != excludeBaseUrl) && it.baseUrl != pinned
            }
            // 锁线已由调用方核对账号归属；暂未发现/下线的同账号线路仍尊重手动选择。
            listOfNotNull(pinnedEntry ?: pinned?.let { ServerLine(it, 0L) }) + rest
        }
    }

    /** 手动切线后强制重探（清缓存；TTL 内的测速数据随线路变化失去意义）。 */
    fun invalidate() {
        sessionHolder.playbackPrefsNamespace()?.let(cache::remove)
    }

    /** 探测 + 测速；任何异常都退化为单线路。 */
    private suspend fun probe(owner: SessionSnapshot): List<ServerLine> {
        val server = owner.server
        val discovered = runCatchingSuspend {
            linesFromJson(clients.api(server, owner).serverDomains(), server)
        }.getOrElse {
            logger.w("LineProbe", "ServerDomains 不可用，退化为单线路: ${it.message}")
            listOf(ServerLine(server.trimEnd('/'), 0L))
        }
        if (discovered.size == 1) {
            return discovered
        }
        return coroutineScope {
            discovered.map { line ->
                async(Dispatchers.IO) {
                    if (sessionHolder.isCleartextAllowed(line.baseUrl)) {
                        line.copy(latencyMs = measureLatency(line.baseUrl))
                    } else {
                        line.copy(latencyMs = Long.MAX_VALUE)
                    }
                }
            }.awaitAll()
                .sortedBy { it.latencyMs }
                .also { logger.d("LineProbe", "线路排序: " + it.joinToString { l -> "${l.displayName}=${l.latencyMs}ms" }) }
        }
    }

    /**
     * HEAD 请求测首字节耗时；失败返回 [Long.MAX_VALUE]（排到最后）。
     *
     * 用 HttpURLConnection 而非 OkHttp：探测发生在 IO 线程，避免为 3 条线路
     * 引入新的客户端实例与连接池。
     */
    private fun measureLatency(baseUrl: String): Long {
        if (!sessionHolder.isCleartextAllowed(baseUrl)) return Long.MAX_VALUE
        val start = System.nanoTime()
        return runCatching {
            val connection = (URL("$baseUrl/System/Info/Public").openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = PROBE_TIMEOUT_MS
                readTimeout = PROBE_TIMEOUT_MS
                useCaches = false
                instanceFollowRedirects = false
            }
            try {
                connection.responseCode
                (System.nanoTime() - start) / 1_000_000
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(Long.MAX_VALUE)
    }

    private companion object {
        /** 线路列表缓存时长。 */
        const val CACHE_TTL_MS = 10 * 60 * 1000L

        /** 单线路测速超时。 */
        const val PROBE_TIMEOUT_MS = 2_000
    }
}

/**
 * 从线路接口的 JSON 提取线路（纯函数，便于单测）。
 *
 * 两级解析：
 * 1. **官方结构**（docs.uhdnow.com/partner/emby-ext-domains）：`ok` 为真且
 *    `data` 为数组 → 逐项取 `name` + `url`；
 * 2. **递归兜底**：结构不认识时扫描所有层级的字符串，凡 http(s) 开头即候选
 *    线路（无 name，展示回退域名短名）——服务端扩展版本繁多，唯一稳定的
 *    语义是「里面出现的 http(s) 字符串就是线路」。
 *
 * 两条路径都把当前服务器地址补进列表并去重（用户登录用的地址永远是候选）。
 *
 * @param element 接口返回的 JSON。
 * @param server 当前服务器地址（作为兜底线路，排最后）。
 * @return 规范化（去尾部斜杠）后的线路，当前服务器在最后。
 */
fun linesFromJson(element: JsonElement, server: String): List<ServerLine> {
    val out = mutableListOf<ServerLine>()
    val official = parseOfficial(element)
    if (official != null) {
        out += official
    } else {
        val urls = mutableListOf<String>()
        collectHttpUrls(element, urls)
        out += urls.map { ServerLine(it, 0L) }
    }
    out += ServerLine(server, 0L)
    val seen = mutableSetOf<String>()
    return out
        .map { it.copy(baseUrl = it.baseUrl.trimEnd('/')) }
        .filter { it.baseUrl.isNotBlank() && seen.add(it.baseUrl) }
}

/** 官方结构解析：`{ ok: true, data: [{ name, url }] }`；结构不符返回 null。 */
private fun parseOfficial(element: JsonElement): List<ServerLine>? {
    val obj = element as? JsonObject ?: return null
    if (obj["ok"]?.let { (it as? JsonPrimitive)?.contentOrNull } != "true") return null
    val data = obj["data"] as? JsonArray ?: return null
    return data.mapNotNull { entry ->
        val fields = entry as? JsonObject ?: return@mapNotNull null
        val url = fields["url"]?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: return@mapNotNull null
        ServerLine(
            baseUrl = url,
            latencyMs = 0L,
            name = fields["name"]?.let { (it as? JsonPrimitive)?.contentOrNull },
        )
    }
}

/** 递归收集 http(s) 字符串（兜底路径）。 */
private fun collectHttpUrls(element: JsonElement, out: MutableList<String>) {
    when (element) {
        is JsonPrimitive -> {
            val text = element.contentOrNull ?: return
            if (text.startsWith("http://") || text.startsWith("https://")) out += text
        }

        is JsonArray -> element.forEach { collectHttpUrls(it, out) }
        is JsonObject -> element.values.forEach { collectHttpUrls(it, out) }
        else -> Unit
    }
}
