package com.novum.tv.core.network

/**
 * 服务器地址解析与规范化。
 *
 * 用户可能在登录页输入任意形式的地址：
 * `192.168.5.26`、`192.168.5.26:8096`、`http://tv.local`、`https://demo.example.com/`。
 * HTTP 默认 8096，HTTPS 默认 443（标准端口；部分自建 Emby 用 8920，需显式填写），这里统一补齐协议与端口，并去掉尾部斜杠，
 * 保证 Retrofit 的 baseUrl 合法（必须以 `/` 结尾，由调用方拼接）。
 *
 * 纯函数实现，便于单元测试穷举输入。
 */
object ServerAddress {

    /** Emby 默认 HTTP 端口。 */
    const val DEFAULT_HTTP_PORT = 8096

    /** Emby 默认 HTTPS 端口。 */
    const val DEFAULT_HTTPS_PORT = 443

    /**
     * 把用户输入解析为若干候选 baseUrl（不带尾部斜杠），按尝试顺序排列。
     *
     * 规则：
     * - 显式带 `http://` 或 `https://` 时只返回该协议；
     * - 不带协议时先 HTTP（局域网自建服务器最常见），再 HTTPS 兜底；
     * - 不带端口时补默认端口；
     * - 非法输入返回空列表。
     *
     * @param raw 用户原始输入。
     * @return 规范化后的候选地址，例如 `http://192.168.5.26:8096`。
     */
    fun candidates(raw: String): List<String> {
        val input = raw.trim().trimEnd('/')
        if (input.isEmpty()) return emptyList()

        val hasScheme = input.startsWith("http://", ignoreCase = true) ||
            input.startsWith("https://", ignoreCase = true)
        return if (hasScheme) {
            val scheme = input.substringBefore("://").lowercase()
            val normalized = build(scheme, input.substringAfter("://"))
            listOfNotNull(normalized)
        } else {
            listOfNotNull(
                build("http", input),
                build("https", input),
            ).distinct()
        }
    }

    /**
     * 判断输入是否已经包含端口。
     *
     * IPv6 字面量（含 `:`）不在此处理范围内：家庭电视场景不会用到。
     */
    private fun build(scheme: String, hostAndPort: String): String? {
        val hostPart = hostAndPort.substringBefore('/')
        if (hostPart.isBlank()) return null

        val hasPort = hostPart.substringAfterLast(':', missingDelimiterValue = "")
            .toIntOrNull() != null
        val hostWithPort = if (hasPort) {
            hostPart
        } else {
            val port = if (scheme == "https") DEFAULT_HTTPS_PORT else DEFAULT_HTTP_PORT
            "$hostPart:$port"
        }
        return "$scheme://$hostWithPort"
    }

    /**
     * Retrofit 需要的 baseUrl（带尾部斜杠）。
     *
     * @param baseUrl 规范化后的地址（不带尾部斜杠）。
     */
    fun toBaseUrl(baseUrl: String): String = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

    /**
     * 该地址是否将以**明文 HTTP** 传输（登录页据此要求用户显式确认）。
     *
     * 判定规则（避免对「域名 + HTTPS 兜底」的无谓打扰）：
     * - 显式 `https://` → false；
     * - 显式 `http://` → true；
     * - 无 scheme 但带显式端口（且非 443）→ true；
     * - 无 scheme 的私有网段 IP（10/8、172.16/12、192.168/16、localhost）→ true；
     * - 其余（裸域名，会先试 HTTP 再兜底 HTTPS）→ false。
     *
     * 家庭 Emby 绝大多数是「局域网 IP + 8096 明文」，这条路必须能走通；
     * 但对「确定会明文」的输入要把风险讲清楚，而不是静默发送凭据。
     *
     * @param raw 用户原始输入。
     */
    fun usesCleartext(raw: String): Boolean {
        val input = raw.trim().trimEnd('/')
        if (input.isEmpty()) return false
        val lower = input.lowercase()
        if (lower.startsWith("https://")) return false
        if (lower.startsWith("http://")) return true

        val hostPart = lower.substringBefore('/')
        val portText = hostPart.substringAfterLast(':', missingDelimiterValue = "")
        val port = portText.toIntOrNull()
        if (port != null && port != DEFAULT_HTTPS_PORT) return true
        val host = if (port != null) hostPart.substringBeforeLast(':') else hostPart
        return isPrivateHost(host)
    }

    /** 私有网段 / 本机地址判定（不含 IPv6，家庭电视场景用不到）。 */
    private fun isPrivateHost(host: String): Boolean {
        if (host == "localhost") return true
        val octets = host.split('.').mapNotNull { it.toIntOrNull() }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        return when {
            octets[0] == 10 -> true
            octets[0] == 192 && octets[1] == 168 -> true
            octets[0] == 172 && octets[1] in 16..31 -> true
            octets[0] == 127 -> true
            else -> false
        }
    }
}
