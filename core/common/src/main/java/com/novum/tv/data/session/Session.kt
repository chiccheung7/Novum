package com.novum.tv.data.session

/**
 * 登录会话。
 *
 * @param server 规范化后的服务器地址（不含尾部斜杠），例如 `http://192.168.5.26:8096`。
 * @param token Emby 访问令牌。
 * @param userId 用户 id。
 * @param userName 用户名。
 * @param serverName 服务器展示名，可能为空。
 * @param deviceId 稳定的设备标识，首次启动生成并持久化。
 */
data class Session(
    val server: String,
    val token: String,
    val userId: String,
    val userName: String,
    val serverName: String,
    val deviceId: String,
)
