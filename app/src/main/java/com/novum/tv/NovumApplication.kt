package com.novum.tv

import android.app.Application
import android.os.SystemClock
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okio.Path.Companion.toOkioPath
import com.novum.tv.data.remote.emby.EmbyClientFactory
import com.novum.tv.data.remote.emby.SessionHolder
import com.novum.tv.ui.component.NovumFastNav
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import okhttp3.OkHttpClient

/**
 * Novum 应用入口。
 *
 * 职责：
 * - Hilt 依赖图根；
 * - 配置 Coil 的全局 ImageLoader（海报必须带 Emby Token 与浏览器 UA，否则 403）。
 *
 * 注意：`newImageLoader` 首次加载图片时才调用，此时 Hilt 已完成字段注入。
 */
@HiltAndroidApp
class NovumApplication : Application(), SingletonImageLoader.Factory {

    @Inject
    lateinit var sessionHolder: SessionHolder

    /**
     * 图片请求共用同一个 OkHttpClient 连接池；callFactory 每张图都会被调用，
     * 如果每次 new 一个 client，海报网格快滚时连接与线程会迅速膨胀。
     */
    private val imageClient: OkHttpClient by lazy { buildImageHttpClient() }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            // 显式配置缓存：TV 内存远小于手机，内存缓存压到 20%；
            // 磁盘缓存给到 256MB，海报密度高、翻页频繁，默认值偏小
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, MEMORY_CACHE_PERCENT)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve(DISK_CACHE_DIR).toOkioPath())
                    .maxSizeBytes(DISK_CACHE_BYTES)
                    .build()
            }
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = { imageClient },
                    ),
                )
                // 快滚降载：长按连移窗口内延后非内存缓存的图片请求——
                // 解码/网络离开滚动关键路径，可见卡有占位灰渐变兜底
                add(FastNavImageDelay)
            }
            .build()

    /**
     * 图片加载闸：快扫等待 + 全局解码并发限流。
     *
     * 快扫期间等待 [NovumFastNav] 窗口结束，松手后才放行。等待期间使用灰渐变占位；
     * 内存缓存命中不进入拦截器，因此已预取图片不受影响。
     * 等待实现精确睡到窗口到期后复查，避免高频轮询唤醒主线程。
     * 解码/取数并发由 [DECODE_CONCURRENCY] 限制为 4，兼顾海报填充速度和资源占用。
     */
    private object FastNavImageDelay : Interceptor {
        private val decodePermits = Semaphore(DECODE_CONCURRENCY)

        override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
            while (true) {
                val until = NovumFastNav.activeUntilMs
                val now = SystemClock.uptimeMillis()
                if (now >= until) break
                // 精确睡到当前窗口到期；途中再 poke 只会顺延，醒来复查即可
                delay(until - now)
            }
            return decodePermits.withPermit { chain.proceed() }
        }
    }

    /**
     * 图片专用 OkHttp：补 UA 与 Token，并跟随 301 到图片 CDN。
     *
     * 不复用 API 客户端的连接池，避免图片请求与 JSON 请求互相排队。
     */
    private fun buildImageHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor { chain ->
            val builder = chain.request().newBuilder()
                .header("User-Agent", EmbyClientFactory.BROWSER_USER_AGENT)
            if (sessionHolder.token.isNotBlank()) {
                builder.header("X-Emby-Token", sessionHolder.token)
            }
            chain.proceed(builder.build())
        }
        .build()

    private companion object {
        /** 图片内存缓存占进程可用内存的比例。 */
        const val MEMORY_CACHE_PERCENT = 0.2

        /** 图片磁盘缓存目录（位于应用 cache 下，清缓存与系统清理都能回收）。 */
        const val DISK_CACHE_DIR = "novum_image_cache"

        /** 图片磁盘缓存上限：256MB。 */
        const val DISK_CACHE_BYTES = 256L * 1024 * 1024

        /** 全局图片解码与取数并发上限。 */
        const val DECODE_CONCURRENCY = 4
    }
}

