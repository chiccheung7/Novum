package com.novum.tv.data.repository

import com.novum.tv.core.result.NovumResult
import com.novum.tv.data.session.Session

/**
 * 登录与登出。
 *
 * 所有方法都返回 [NovumResult]，UI 层不处理异常。
 */
interface AuthRepository {

    /**
     * 使用服务器地址 + 账号密码登录。
     *
     * 地址会自动规范化（补协议/端口），并按 HTTP → HTTPS 顺序探测可达性。
     *
     * @param rawServer 用户输入的服务器地址。
     * @param username 用户名。
     * @param password 密码。
     * @return 成功时返回已持久化的会话；失败时返回已归类的错误。
     */
    suspend fun login(
        rawServer: String,
        username: String,
        password: String,
        /**
         * 是否记住凭证：true 加密保存 token 与密码；false 只保留服务器和用户名预填。
         */
        remember: Boolean = false,
    ): NovumResult<Session>

    /**
     * 读取本地会话用于启动路由。
     *
     * @return 已登录返回会话；未登录返回 null。
     */
    suspend fun restore(): Session?

    /** 退出登录：清除本地会话，保留设备 id、服务器地址与播放设置。 */
    suspend fun logout()

/** 上次登录的服务器地址，用于登录页预填；没有记录时返回空串。 */
    suspend fun lastServer(): String = ""

    /** 上次登录的用户名，用于登录页预填；没有记录时返回空串。 */
    suspend fun lastUsername(): String = ""

    /** Saved password is returned only after secure storage decrypts it. */
    suspend fun lastPassword(): String? = null

    /** User's remembered-credentials choice, defaulting to false on first use. */
    suspend fun shouldRememberCredentials(): Boolean = false

    /** Persists the current choice and immediately removes secrets when disabled. */
    suspend fun setRememberCredentials(remember: Boolean) = Unit

}
