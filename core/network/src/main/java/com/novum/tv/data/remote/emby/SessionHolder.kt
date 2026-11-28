package com.novum.tv.data.remote.emby

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.HttpUrl
import com.novum.tv.core.network.BuildConfig
import com.novum.tv.data.session.Session
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Immutable request identity; retain one instance for the lifetime of an operation. */
data class SessionSnapshot(
    val token: String = "",
    val deviceId: String = "",
    val server: String = "",
    val userId: String = "",
    val userName: String = "",
) {
    /** Request tags may be included in exception messages; never expand session fields there. */
    override fun toString(): String = "SessionSnapshot(redacted)"

    /** A captured token belongs only to its server origin, including the scheme and port. */
    fun tokenForUrl(url: String): String? {
        if (token.isBlank()) return null
        val origin = server.toHttpUrlOrNull() ?: return null
        val target = url.toHttpUrlOrNull() ?: return null
        return token.takeIf {
            origin.scheme == target.scheme && origin.host == target.host && origin.port == target.port
        }
    }

    /** Stable account/server identity for local item preferences; never persists a credential. */
    fun playbackPrefsNamespace(): String? {
        if (token.isBlank() || userId.isBlank()) return null
        val server = server.toHttpUrlOrNull() ?: return null
        val canonicalServer = server.newBuilder()
            .username("")
            .password("")
            .query(null)
            .fragment(null)
            .encodedPath(server.encodedPath.trimEnd('/').ifEmpty { "/" })
            .build()
            .toString()
        val identity = "$canonicalServer\u0000$userId"
        return MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    /** Client metadata uses the same captured device identity as the request token. */
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

/**
 * 保存当前会话中「请求头需要、但不该每次读磁盘」的字段。
 *
 * 拦截器在 OkHttp 线程上同步执行，不能做 suspend IO；因此 Token 与设备 id
 * 由登录/启动流程写入内存，拦截器只读。
 */
@Singleton
class SessionHolder @Inject constructor() {

    /**
     * 会话字段必须整体替换：登录/登出/切服务器时 OkHttp 拦截器会在其他线程读取，
     * 逐个写 @Volatile 会发出「新 token + 旧服务器」这类撕裂状态。
     */
    @Volatile
    private var snapshot: SessionSnapshot = SessionSnapshot()

    @Volatile
    private var loginCleartextOrigin: HttpUrl? = null

    @Volatile
    private var pinnedCleartextLine: PinnedCleartextLine? = null

    /** One atomic read; reference equality also detects a switch to an otherwise identical session. */
    fun capture(): SessionSnapshot = snapshot

    val token: String get() = snapshot.token

    val deviceId: String get() = snapshot.deviceId

    /** 当前服务器地址（不含尾部斜杠），供仓储层拼接图片与接口地址。 */
    val server: String get() = snapshot.server

    /** 当前用户 id，用于用户级接口路径。 */
    val userId: String get() = snapshot.userId

    /** Current display name, including sessions whose credentials are not remembered on disk. */
    val userName: String get() = snapshot.userName

    /** A session token belongs only to its server origin, including the scheme and port. */
    fun tokenForUrl(url: String): String? = capture().tokenForUrl(url)

    /** Stable account/server identity for local item preferences; never persists a credential. */
    fun playbackPrefsNamespace(): String? = capture().playbackPrefsNamespace()

    /**
     * 同步当前会话；传 null 表示退出登录。
     *
     * @param session 最新会话。
     */
    fun update(session: Session?) {
        val previousAccountKey = snapshot.playbackPrefsNamespace()
        snapshot = SessionSnapshot(
            token = session?.token.orEmpty(),
            deviceId = session?.deviceId.orEmpty(),
            server = session?.server.orEmpty(),
            userId = session?.userId.orEmpty(),
            userName = session?.userName.orEmpty(),
        )
        loginCleartextOrigin = null
        if (previousAccountKey != snapshot.playbackPrefsNamespace()) {
            pinnedCleartextLine = null
        }
    }

    /** Temporarily allow the explicitly confirmed HTTP origin used by the login flow. */
    fun allowLoginCleartextOrigin(server: String) {
        loginCleartextOrigin = server.toHttpUrlOrNull()?.takeIf { it.scheme == "http" }
    }

    /** Remove the temporary login permission after the request completes or fails. */
    fun clearLoginCleartextOrigin(server: String) {
        val expected = server.toHttpUrlOrNull() ?: return
        if (loginCleartextOrigin?.sameOrigin(expected) == true) loginCleartextOrigin = null
    }

    /**
     * Restore the persisted HTTP line permission for the active account.
     * The caller must source [server] from that account's persisted pinned-line setting.
     */
    fun setPinnedLineCleartextPermission(server: String?, accountKey: String?, confirmed: Boolean) {
        val activeAccountKey = playbackPrefsNamespace()
        val parsed = server?.toHttpUrlOrNull()?.takeIf { it.scheme == "http" }
        pinnedCleartextLine = if (confirmed && parsed != null && !accountKey.isNullOrBlank() &&
            accountKey == activeAccountKey
        ) {
            PinnedCleartextLine(parsed, accountKey)
        } else {
            null
        }
    }

    /** HTTP is allowed only for the confirmed active server or the active account's pinned line. */
    fun isCleartextAllowed(url: String): Boolean {
        val target = url.toHttpUrlOrNull() ?: return false
        if (target.scheme != "http") return true
        val current = capture()
        val activeServer = current.server.toHttpUrlOrNull()
        if (activeServer?.sameOrigin(target) == true) return true
        if (loginCleartextOrigin?.sameOrigin(target) == true) return true
        val pinned = pinnedCleartextLine ?: return false
        return pinned.accountKey == current.playbackPrefsNamespace() && pinned.origin.sameOrigin(target)
    }

    private data class PinnedCleartextLine(val origin: HttpUrl, val accountKey: String)

    private fun HttpUrl.sameOrigin(other: HttpUrl): Boolean =
        scheme == other.scheme && host == other.host && port == other.port

    /**
     * 构造 Emby 要求的 `X-Emby-Authorization` 头。
     *
     * 格式固定为 `MediaBrowser Client="…", Device="…", DeviceId="…", Version="…"`，
     * 键名与顺序都不能改，否则 nginx 网关会直接 403。
     */
    fun authorizationHeader(): String = capture().authorizationHeader()
}
