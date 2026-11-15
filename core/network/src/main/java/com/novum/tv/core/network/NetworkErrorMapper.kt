package com.novum.tv.core.network

import com.novum.tv.core.error.NovumError
import java.io.IOException
import kotlinx.serialization.SerializationException
import retrofit2.HttpException

/**
 * 把网络层异常统一映射为领域错误。
 *
 * 映射规则：
 * - `HttpException` 401/403 → 鉴权失效；404 → 资源不存在；其它 → 服务端错误；
 * - `IOException`（含超时、DNS、连接拒绝）→ 网络错误；
 * - 反序列化失败 → 解析错误；
 * - 其余 → 未知错误。
 *
 * @return 可直接进入 `NovumResult.Failure` 的错误对象。
 */
fun Throwable.toNovumError(): NovumError = when (this) {
    is HttpException -> when (code()) {
        401, 403 -> NovumError.Unauthorized(this)
        404 -> NovumError.NotFound(message())
        else -> NovumError.Server(code(), message())
    }

    is IOException -> NovumError.Network(this)
    is SerializationException -> NovumError.Parse(this)
    else -> NovumError.Unknown(this)
}
