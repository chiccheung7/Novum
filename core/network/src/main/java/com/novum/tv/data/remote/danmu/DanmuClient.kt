package com.novum.tv.data.remote.danmu

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Url
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * UHDNOW 弹幕服务接口（dandanplay v2 协议，经 UHD 网关代理）。
 *
 * 端点结构：`{服务器}/api/v1/danmu/{token}/api/v2/{方法}`——token 走 URL 路径
 * 而不是请求头，弹幕服务据此鉴权。协议与 dandanplay 官方 API 一致：
 *
 * - `match`：POST `{fileName}` → 按资源文件名自动匹配分集；
 * - `search/anime`：GET `?keyword=` → 按片名搜动画；
 * - `bangumi/{id}`：GET → 动画的分集列表；
 * - `comment/{episodeId}`：GET `?format=json` → 弹幕列表（`p` 字段 = 时间/模式/颜色）。
 *
 * 所有方法返回 [JsonObject]：该协议字段类型松散（`episodeId` 可能是字符串也可能是
 * 数字），强类型 DTO 会因个别服务端实现差异整批解析失败，由仓储层按字段逐个提取。
 */
interface DanmuApi {

    /** 按资源文件名匹配弹幕分集。 */
    @POST
    suspend fun match(@Url url: String, @Body body: JsonObject): JsonObject

    /** 按片名搜索动画。 */
    @GET
    suspend fun search(@Url url: String): JsonObject

    /** 取指定动画的分集列表。 */
    @GET
    suspend fun episodes(@Url url: String): JsonObject

    /** 取指定分集的弹幕列表。 */
    @GET
    suspend fun comments(@Url url: String): JsonObject
}

/**
 * 弹幕 API 客户端：按「服务器 + token」构建并缓存 [DanmuApi]。
 *
 * 与 [com.novum.tv.data.remote.emby.EmbyClientFactory] 的差异：
 * - 鉴权走 URL 路径里的 token（弹幕服务自己的鉴权体系），不注入 Emby 头；
 * - 仍然要伪装浏览器 UA——UHDNOW 网关对非浏览器 UA 一律 403，弹幕端点在网关后面。
 */
@Singleton
class DanmuClient @Inject constructor(
    private val json: Json,
    private val holder: com.novum.tv.data.remote.emby.SessionHolder,
) {

    private val cache = ConcurrentHashMap<String, DanmuApi>()

    /** 弹幕服务的 URL 前缀（含 token）；未登录或地址无效时为 null。 */
    fun baseUrl(): String? {
        val server = holder.server.trimEnd('/')
        val token = holder.token
        if (server.isBlank() || token.isBlank()) return null
        return runCatching {
            (server + "/").toHttpUrl().newBuilder()
                // token 用 addPathSegment 转义，作为不透明凭证不能拼裸串
                .addPathSegments("api/v1/danmu")
                .addPathSegment(token)
                .addPathSegments("api/v2")
                .build()
        }.getOrNull()?.toString()?.trimEnd('/')
    }

    /** 指定前缀的 API（缓存按前缀复用连接池）。 */
    fun api(prefix: String): DanmuApi = cache.getOrPut(prefix) { create() }

    private fun create(): DanmuApi {
        val client = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                // 网关按 UA 过滤（与 Emby 端点同一层 nginx 规则）
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", com.novum.tv.data.remote.emby.EmbyClientFactory.BROWSER_USER_AGENT)
                        .header("Accept", "application/json")
                        .build(),
                )
            }
            .build()
        return Retrofit.Builder()
            // 实际请求全部通过 @Url 传入绝对地址；这里只满足 Retrofit 必填
            .baseUrl("https://localhost/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(DanmuApi::class.java)
    }
}
