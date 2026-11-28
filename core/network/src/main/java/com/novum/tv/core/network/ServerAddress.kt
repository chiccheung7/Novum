package com.novum.tv.core.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
/**
 * 服务器地址解析与规范化。
 *
 * 用户可能在登录页输入任意形式的地址：
 * `192.168.5.26`、`192.168.5.26:8096`、`http://tv.local`、`https://demo.example.com/`。
 * HTTP 默认 8096，HTTPS 默认 443（标准端口；部分自建 Emby 用 8920，需显式填写），这里统一补齐协议与端口，并去掉尾部斜杠，
 * 保证 Retrofit 的 baseUrl 合法（必须以 `/` 结尾，由调用方拼接）。反代子路径（如 `/emby`）会被保留。
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
     * - 保留反代子路径（如 `https://tv.local/emby` → `https://tv.local:443/emby`）；
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
        // Split the authority before looking for a port. A pasted reverse-proxy
        // URL may carry a query or fragment, and those characters must never be
        // considered part of the host (otherwise `host?x=1` gets `:8096`
        // appended after the query).
        val boundary = hostAndPort.indexOfFirst { it == '/' || it == '?' || it == '#' }
            .takeIf { it >= 0 } ?: hostAndPort.length
        val authority = hostAndPort.substring(0, boundary)
        if (authority.isBlank() || '@' in authority || authority.any { it.isWhitespace() }) return null
        // 反代场景常见的子路径不能丢弃，否则请求会打到站点根路径而 404。
        val suffix = hostAndPort.substring(boundary)

        val (host, explicitPort) = when {
            authority.startsWith('[') -> {
                val close = authority.indexOf(']')
                if (close < 0) return null
                val tail = authority.substring(close + 1)
                when {
                    tail.isEmpty() -> authority to null
                    tail.startsWith(':') -> authority.substring(0, close + 1) to tail.substring(1)
                    else -> return null
                }
            }

            authority.count { it == ':' } == 1 ->
                authority.substringBefore(':') to authority.substringAfter(':')

            authority.count { it == ':' } > 1 ->
                // A bare IPv6 literal has no unambiguous port. Bracket it so
                // the resulting URL remains valid and use the default port.
                "[$authority]" to null

            else -> authority to null
        }
        if (host.isBlank()) return null
        val port = explicitPort?.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: if (explicitPort == null) {
                if (scheme == "https") DEFAULT_HTTPS_PORT else DEFAULT_HTTP_PORT
            } else {
                return null
            }
        val candidate = "$scheme://$host:$port$suffix"
        // Keep the explicit default port in the returned string for stable
        // Retrofit/cache identities, but reject malformed hosts and user info.
        return candidate.takeIf { it.toHttpUrlOrNull() != null }
    }

    /**
     * Retrofit 需要的 baseUrl（带尾部斜杠）。
     *
     * @param baseUrl 规范化后的地址（不带尾部斜杠）。
     */
    fun toBaseUrl(baseUrl: String): String {
        val boundary = baseUrl.indexOfFirst { it == '?' || it == '#' }.takeIf { it >= 0 } ?: baseUrl.length
        val path = baseUrl.substring(0, boundary)
        return (if (path.endsWith('/')) path else "$path/") + baseUrl.substring(boundary)
    }

    /** Keep API-relative media paths below the configured proxy prefix without duplicating it. */
    fun resolveUrl(server: String, path: String): String? {
        val base = toBaseUrl(server).toHttpUrlOrNull() ?: return null
        val resolved = base.resolve(path) ?: return null
        if (!path.startsWith('/') || path.startsWith("//")) return resolved.toString()
        val prefix = base.encodedPath.trimEnd('/')
        if (prefix.isEmpty() || resolved.encodedPath == prefix || resolved.encodedPath.startsWith("$prefix/")) {
            return resolved.toString()
        }
        // Emby may omit a proxy-owned prefix even when its API path starts with '/'.
        return base.resolve(path.trimStart('/'))?.toString()
    }

    /**
     * 候选地址是否可能使用**明文 HTTP**（登录页据此要求用户显式确认）。
     *
     * 与实际登录候选保持一致：未填写协议时，即使填写了域名或 443 端口，
     * 仍会尝试 HTTP，不能在发送凭据前跳过确认。显式 HTTPS 不需要明文确认。
     *
     * @param raw 用户原始输入。
     */
    fun usesCleartext(raw: String): Boolean = candidates(raw).any { it.startsWith("http://") }
}
