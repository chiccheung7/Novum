package com.novum.tv.core.log

/**
 * 日志脱敏。
 *
 * 为什么需要：Emby 的鉴权既可能放在 `X-Emby-Token` 请求头，也可能塞在 URL 的
 * `api_key` 查询参数里。一旦异常信息或调试日志把整条 URL 打出来，token 就等于
 * 明文写进了 logcat（电视端 logcat 可被同一设备上的其他应用/调试会话读取）。
 *
 * 策略：**在日志门面统一脱敏**，而不是要求每个调用点自己记得处理——
 * 统一处理可避免调用方遗漏规则。
 */
object LogRedaction {

    /** 脱敏后的占位符。 */
    const val MASK = "***"

    /**
     * 需要脱敏的「键 = 值」模式。
     *
     * 覆盖：URL 查询参数（api_key/token/password）、Emby 鉴权头、通用 Authorization。
     * 大小写不敏感，值匹配到 `&`、空白、引号为止。
     */
    private val patterns: List<Regex> = listOf(
        Regex("""(?i)((?:api[_-]?key|token|password|passwd|pwd)\s*=\s*)([^&\s"']+)"""),
        // 鉴权头：`Bearer <token>` 要连 scheme 一起算前缀，否则只遮住 "Bearer"
        Regex("""(?i)((?:x-emby-token|authorization|proxy-authorization)\s*[:=]\s*(?:bearer\s+)?)([^\s,"']+)"""),
        // URL 里的 user:pass@host 形式
        Regex("""(?i)(://[^/\s:@]+:)([^/\s@]+)(@)"""),
    )

    /**
     * 对一条日志文本脱敏。
     *
     * @param message 原始日志。
     * @return 敏感值被替换为 [MASK] 的文本；无敏感内容时原样返回。
     */
    fun redact(message: String): String {
        if (message.isEmpty()) return message
        var result = message
        patterns.forEach { pattern ->
            result = pattern.replace(result) { match ->
                val groups = match.groupValues
                // 第三条模式是 scheme://user:pass@host，需要保留前后的 : 与 @
                if (groups.size == 4 && groups[3] == "@") {
                    groups[1] + MASK + groups[3]
                } else {
                    groups[1] + MASK
                }
            }
        }
        return result
    }
}
