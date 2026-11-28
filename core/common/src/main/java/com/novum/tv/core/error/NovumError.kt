package com.novum.tv.core.error

/**
 * 领域错误模型：把所有底层异常（IO、HTTP、反序列化）收敛为可判定的少量类型，
 * UI 只根据类型决定文案与重试策略，不接触异常栈。
 */
sealed class NovumError {

    /** 网络不可达、超时、DNS 失败等连接层问题。可重试。 */
    data class Network(val cause: Throwable? = null) : NovumError()

    /** 鉴权失效（401/403 或 Emby Token 过期）。需要重新登录。 */
    data class Unauthorized(val cause: Throwable? = null) : NovumError()

    /**
     * 服务端返回了非 2xx 响应。
     *
     * @param code HTTP 状态码。
     * @param message 服务端返回的简短描述，可能为空。
     */
    data class Server(val code: Int, val message: String? = null) : NovumError()

    /** A valid response did not provide a stream that can fulfill the playback request. */
    data class PlaybackUnavailable(val reason: PlaybackFailure) : NovumError()

    enum class PlaybackFailure {
        SourceUnavailable,
        SubtitleUnavailable,
        SeekUnavailable,
    }

    /** 响应体无法解析（字段缺失、JSON 结构变化）。 */
    data class Parse(val cause: Throwable? = null) : NovumError()

    /** 资源不存在（404）。 */
    data class NotFound(val message: String? = null) : NovumError()

    /** 未归类的异常，附原始信息便于日志排查。 */
    data class Unknown(val cause: Throwable? = null) : NovumError()

    /** 该错误是否值得让用户点「重试」。 */
    val isRetryable: Boolean
        get() = this is Network || this is Server || this is PlaybackUnavailable || this is Unknown
}
