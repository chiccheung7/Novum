package com.novum.tv.core.state

import com.novum.tv.core.error.NovumError

/**
 * 页面级 UI 状态三态封装。
 *
 * 约定：每个页面只暴露一个 [UiState]，避免「加载中/有数据/报错」三个布尔量互相打架。
 * 空数据用 [Empty] 显式表达，让空态文案与重试行为可控。
 *
 * @param T 成功时的数据类型。
 */
sealed interface UiState<out T> {

    /** 首屏加载中（骨架屏或进度指示）。 */
    data object Loading : UiState<Nothing>

    /**
     * 加载成功且有数据。
     *
     * @param data 页面数据。
     */
    data class Success<T>(val data: T) : UiState<T>

    /**
     * 加载成功但没有数据（服务端返回空集合）。
     *
     * @param reason 可选的空态原因，用于区分「媒体库为空」与「搜索无结果」。
     */
    data class Empty(val reason: EmptyReason = EmptyReason.NoContent) : UiState<Nothing>

    /**
     * 加载失败。
     *
     * 这里携带 [NovumError] 而不是成品文案：文案属于表现层，由 UI 统一映射到字符串资源，
     * 便于多语言与统一口径。
     *
     * @param error 已归类的领域错误。
     * @param retryable 是否展示重试入口，默认取错误的可重试属性。
     */
    data class Error(
        val error: NovumError,
        val retryable: Boolean = error.isRetryable,
    ) : UiState<Nothing>
}

/** 空态原因，决定页面展示哪一句提示。 */
enum class EmptyReason {
    /** 通用「暂无内容」。 */
    NoContent,

    /** 搜索无结果。 */
    NoSearchResult,

    /** 收藏为空。 */
    NoFavorites,
}
