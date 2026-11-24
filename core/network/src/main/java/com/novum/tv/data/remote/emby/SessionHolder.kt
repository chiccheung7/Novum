package com.novum.tv.data.remote.emby

import com.novum.tv.core.network.BuildConfig
import com.novum.tv.data.session.Session
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 保存当前会话中「请求头需要、但不该每次读磁盘」的字段。
 *
 * 拦截器在 OkHttp 线程上同步执行，不能做 suspend IO；因此 Token 与设备 id
 * 由登录/启动流程写入内存，拦截器只读。
 */
@Singleton
class SessionHolder @Inject constructor() {

    /**
     * 四个字段必须整体替换：登录/登出/切服务器时 OkHttp 拦截器会在其他线程读取，
     * 逐个写 @Volatile 会发出「新 token + 旧服务器」这类撕裂状态。
     */
    private data class Snapshot(
        val token: String = "",
        val deviceId: String = "",
        val server: String = "",
        val userId: String = "",
    )

    @Volatile
    private var snapshot: Snapshot = Snapshot()

    val token: String get() = snapshot.token

    val deviceId: String get() = snapshot.deviceId

    /** 当前服务器地址（不含尾部斜杠），供仓储层拼接图片与接口地址。 */
    val server: String get() = snapshot.server

    /** 当前用户 id，用于用户级接口路径。 */
    val userId: String get() = snapshot.userId

    /**
     * 同步当前会话；传 null 表示退出登录。
     *
     * @param session 最新会话。
     */
    fun update(session: Session?) {
        snapshot = Snapshot(
            token = session?.token.orEmpty(),
            deviceId = session?.deviceId.orEmpty(),
            server = session?.server.orEmpty(),
            userId = session?.userId.orEmpty(),
        )
    }

    /**
     * 构造 Emby 要求的 `X-Emby-Authorization` 头。
     *
     * 格式固定为 `MediaBrowser Client="…", Device="…", DeviceId="…", Version="…"`，
     * 键名与顺序都不能改，否则 nginx 网关会直接 403。
     */
    fun authorizationHeader(): String = buildString {
        append("MediaBrowser Client=\"Novum\"")
        append(", Device=\"Android TV\"")
        append(", DeviceId=\"")
        append(deviceId.ifBlank { "unknown" })
        append("\", Version=\"")
        append(BuildConfig.VERSION_NAME)
        append("\"")
    }
}
