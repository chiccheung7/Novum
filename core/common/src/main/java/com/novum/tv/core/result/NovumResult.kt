package com.novum.tv.core.result

import com.novum.tv.core.error.NovumError

/**
 * 统一的调用结果封装。
 *
 * 约定：Repository / DataSource 的公开方法一律返回 [NovumResult]，
 * 不向调用方抛异常；ViewModel 负责把失败映射为 UI 状态。
 *
 * @param T 成功时的数据类型。
 */
sealed interface NovumResult<out T> {

    /**
     * 成功。
     *
     * @param value 业务数据。
     */
    data class Success<T>(val value: T) : NovumResult<T>

    /**
     * 失败。
     *
     * @param error 已归类的领域错误。
     */
    data class Failure(val error: NovumError) : NovumResult<Nothing>

    /** 成功时取值，失败返回 null。 */
    fun valueOrNull(): T? = (this as? Success)?.value

    /** 失败时取错误，成功返回 null。 */
    fun errorOrNull(): NovumError? = (this as? Failure)?.error

    /**
     * 映射成功值，失败原样透传。
     *
     * @param transform 成功值的转换函数。
     */
    fun <R> map(transform: (T) -> R): NovumResult<R> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
    }
}

/**
 * 构造成功结果。
 *
 * @param T 数据类型。
 * @param value 业务数据。
 */
fun <T> successOf(value: T): NovumResult<T> = NovumResult.Success(value)

/**
 * 构造失败结果。
 *
 * @param error 领域错误。
 */
fun failureOf(error: NovumError): NovumResult<Nothing> = NovumResult.Failure(error)
