package com.novum.tv.data.session

import kotlinx.coroutines.flow.Flow

/**
 * 会话持久化契约。
 *
 * 会话与普通设置分开存放（同一个 DataStore，键前缀 `session_`）：
 * 退出登录只清会话，不影响播放偏好。
 */
interface SessionStore {

    /** 当前会话；未登录时发射 null。 */
    val session: Flow<Session?>

    /** 读取一次会话（用于启动时决定首页还是登录页）。 */
    suspend fun load(): Session?

    /**
     * 保存会话。
     *
     * @param server 规范化服务器地址。
     * @param token 访问令牌。
     * @param userId 用户 id。
     * @param userName 用户名。
     * @param serverName 服务器名。
     */
    suspend fun save(
        server: String,
        token: String,
        userId: String,
        userName: String,
        serverName: String,
        rememberedPassword: String? = null,
    )

    /**
     * 只保存服务器与用户名（「记住登录」关闭时）：供登录页预填，不落盘 token。
     * 下次启动因无 token 仍进登录页，但地址/账号已填好。
     */
    suspend fun saveIdentity(server: String, username: String)

    /** 清除会话（退出登录），保留设备 id、服务器地址与设置。 */
    suspend fun clear()

    /**
     * 上次登录的服务器地址，用于登录页预填。
     *
     * 退出登录不清它：电视上用遥控器重输一遍域名非常痛苦。
     */
    suspend fun lastServer(): String

    /** 上次登录的用户名，用于登录页预填。 */
    suspend fun lastUsername(): String

    /** Returns the saved password after decrypting it with the device Keystore, if present. */
    suspend fun rememberedPassword(): String? = null

    /** Whether the user last enabled credential remembering. */
    suspend fun shouldRememberCredentials(): Boolean = false

    /** Persists the user's choice before a login attempt. */
    suspend fun setRememberCredentials(remember: Boolean) = Unit

    /** 获取稳定的设备 id，不存在时生成并持久化。 */
    suspend fun deviceId(): String
}
