package com.novum.tv.data.remote.emby

import com.novum.tv.core.network.ServerAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * 按地址提供 [EmbyApi] 的能力抽象，便于在单元测试中替换为假实现。
 */
interface EmbyApiProvider {
    /**
     * 获取指定服务器的 API。
     *
     * @param baseUrl 规范化后的地址（不含尾部斜杠）。
     */
    fun api(baseUrl: String): EmbyApi
}

/**
 * 按服务器地址构建并缓存 [EmbyApi]。
 *
 * 为什么按地址缓存：多线路场景下用户可能切换到另一台服务器，
 * 每个 baseUrl 需要独立 Retrofit 实例；缓存避免每次请求都重建 OkHttp 连接池。
 *
 * @param json 全局 JSON 配置（忽略未知字段，兼容不同 Emby 版本）。
 * @param sessionHolder 提供 Token 与设备 id。
 */
@Singleton
class EmbyClientFactory @Inject constructor(
    private val json: Json,
    private val sessionHolder: SessionHolder,
) : EmbyApiProvider {

    private val cache = ConcurrentHashMap<String, EmbyApi>()

    /**
     * 获取指定服务器的 API。
     *
     * @param baseUrl 规范化后的地址（不含尾部斜杠）。
     */
    override fun api(baseUrl: String): EmbyApi = cache.getOrPut(baseUrl) { create(baseUrl) }

    private fun create(baseUrl: String): EmbyApi {
        val client = OkHttpClient.Builder()
            // 电视盒子网络抖动常见，连接超时不宜过长，避免登录页长时间无反馈
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val builder = chain.request().newBuilder()
                    // UHDNOW 网关会拦截没有浏览器 UA 的请求（直接 403），必须伪装成浏览器
                    .header("User-Agent", BROWSER_USER_AGENT)
                    .header("Accept", "application/json")
                    .header("X-Emby-Authorization", sessionHolder.authorizationHeader())
                if (sessionHolder.token.isNotBlank()) {
                    builder.header("X-Emby-Token", sessionHolder.token)
                }
                chain.proceed(builder.build())
            }
            .build()

        return Retrofit.Builder()
            .baseUrl(ServerAddress.toBaseUrl(baseUrl))
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(EmbyApi::class.java)
    }

    companion object {
        /**
         * 网关白名单 UA。
         *
         * 部分 Emby 托管商（如 UHDNOW）在 nginx 层按 UA 过滤，默认的 `okhttp/x.y.z`
         * 会被直接 403；这里统一使用桌面 Chrome 的 UA。
         */
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0 Safari/537.36"
    }
}
