package com.novum.tv.testing

import com.novum.tv.core.error.NovumError
import com.novum.tv.core.result.NovumResult
import com.novum.tv.data.repository.AuthRepository
import com.novum.tv.data.session.Session
import com.novum.tv.data.session.SessionStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * 账号仓储的测试替身：登录/恢复一律失败，退出静默成功。
 *
 * 设置页只依赖 [AuthRepository.logout]，其余方法给最小合法实现即可。
 */
object NoopAuthRepository : AuthRepository {

    override suspend fun login(
        rawServer: String,
        username: String,
        password: String,
    ): NovumResult<Session> = NovumResult.Failure(NovumError.Unauthorized())

    override suspend fun restore(): Session? = null

    override suspend fun logout() = Unit
}

/**
 * 会话存储的测试替身：始终未登录，写入为空操作。
 */
object NoopSessionStore : SessionStore {

    override val session: Flow<Session?> = flowOf(null)

    override suspend fun load(): Session? = null

    override suspend fun save(
        server: String,
        token: String,
        userId: String,
        userName: String,
        serverName: String,
    ) = Unit

    override suspend fun clear() = Unit

    override suspend fun lastServer(): String = ""

    override suspend fun deviceId(): String = "test-device"
}
