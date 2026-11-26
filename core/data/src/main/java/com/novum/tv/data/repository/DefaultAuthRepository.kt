package com.novum.tv.data.repository

import com.novum.tv.core.dispatcher.DispatcherProvider
import com.novum.tv.core.error.NovumError
import com.novum.tv.core.network.ServerAddress
import com.novum.tv.core.network.toNovumError
import com.novum.tv.core.result.NovumResult
import com.novum.tv.core.result.failureOf
import com.novum.tv.core.result.runCatchingSuspend
import com.novum.tv.core.result.successOf
import com.novum.tv.data.remote.emby.AuthRequestDto
import com.novum.tv.data.remote.emby.UserDto
import com.novum.tv.data.remote.emby.EmbyApiProvider
import com.novum.tv.data.remote.emby.SessionHolder
import com.novum.tv.data.session.Session
import com.novum.tv.data.session.SessionStore
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 登录仓储默认实现。
 *
 * 登录流程：
 * 1. 规范化地址，得到 HTTP / HTTPS 候选；
 * 2. 依次探测 `System/Info/Public`，第一个可达的地址作为正式服务器；
 * 3. 用 `Users/AuthenticateByName` 换取 Token；
 * 4. 持久化会话并更新内存中的请求头字段。
 *
 * @param clients 按地址提供 API 的抽象（生产环境为 Retrofit 工厂）。
 * @param sessionStore 会话持久化。
 * @param sessionHolder 内存会话（供拦截器读取）。
 * @param dispatchers 调度器，网络调用统一切到 IO。
 */
class DefaultAuthRepository @Inject constructor(
    private val clients: EmbyApiProvider,
    private val sessionStore: SessionStore,
    private val sessionHolder: SessionHolder,
    private val dispatchers: DispatcherProvider,
) : AuthRepository {

    // Network calls may finish out of order. Only the latest login intent can publish a session;
    // persistence and the in-memory header snapshot must then advance as one short commit.
    private data class SessionIntent(
        val revision: Long = 0,
        val rememberRevision: Long = 0,
        val remember: Boolean = false,
    )

    private val sessionIntent = AtomicReference(SessionIntent())
    private val sessionCommit = Mutex()
    private var committedRevision = 0L // Protected by sessionCommit.

    override suspend fun login(
        rawServer: String,
        username: String,
        password: String,
        remember: Boolean,
    ): NovumResult<Session> {
        val revision = sessionIntent.updateAndGet {
            SessionIntent(it.revision + 1, it.rememberRevision + 1, remember)
        }.revision
        return withContext(dispatchers.io) {
            val candidates = ServerAddress.candidates(rawServer)
            if (candidates.isEmpty()) {
                return@withContext failureOf(NovumError.Unknown())
            }

            var lastError: NovumError = NovumError.Unknown()
            // 多候选地址时，网络错误常常只是「另一个候选不可达」，而鉴权错误才是用户真正要修的。
            // 不能让最后一条候选的网络错误覆盖前面更准确的 401。
            var sawUnauthorized = false
            for (baseUrl in candidates) {
                try {
                    val api = clients.api(baseUrl)
                    val info = api.publicInfo()
                    val auth = api.authenticate(AuthRequestDto(Username = username, Pw = password))
                    val token = auth.AccessToken
                    val user = auth.User ?: UserDto()
                    val userId = user.Id
                    if (token.isNullOrBlank() || userId.isNullOrBlank()) {
                        lastError = NovumError.Unauthorized()
                        sawUnauthorized = true
                        continue
                    }

                    val deviceId = sessionStore.deviceId()
                    val serverName = info.ServerName.orEmpty()
                    val userName = user.Name ?: username
                    val session = Session(
                        server = baseUrl,
                        token = token,
                        userId = userId,
                        userName = userName,
                        serverName = serverName,
                        deviceId = deviceId,
                    )
                    val committed = sessionCommit.withLock {
                        currentCoroutineContext().ensureActive()
                        val currentIntent = sessionIntent.get()
                        if (revision != currentIntent.revision) return@withLock false
                        // DataStore may commit just before cancellation is delivered. Finish the
                        // matching holder update so a cancelled caller cannot leave disk/memory split.
                        withContext(NonCancellable) {
                            if (currentIntent.remember) {
                                sessionStore.save(
                                    server = baseUrl,
                                    token = token,
                                    userId = userId,
                                    userName = userName,
                                    serverName = serverName,
                                    rememberedPassword = password,
                                )
                            } else {
                                // 不记住登录：只存服务器+用户名供预填，绝不落盘 token/身份
                                sessionStore.saveIdentity(server = baseUrl, username = userName)
                            }
                            sessionHolder.update(session)
                            committedRevision = revision
                        }
                        true
                    }
                    if (!committed) return@withContext failureOf(NovumError.Unknown())
                    return@withContext successOf(session)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    val error = throwable.toNovumError()
                    if (error is NovumError.Unauthorized) sawUnauthorized = true
                    lastError = error
                }
            }
            failureOf(if (sawUnauthorized) NovumError.Unauthorized() else lastError)
        }
    }

    override suspend fun restore(): Session? {
        val revision = sessionIntent.get().revision
        return withContext(dispatchers.io) {
            sessionCommit.withLock {
                val session = sessionStore.load()
                if (revision != sessionIntent.get().revision) return@withLock null
                sessionHolder.update(session)
                session
            }
        }
    }

    override suspend fun lastServer(): String = withContext(dispatchers.io) {
        runCatchingSuspend { sessionStore.lastServer() }.getOrDefault("")
    }

    override suspend fun lastUsername(): String = withContext(dispatchers.io) {
        runCatchingSuspend { sessionStore.lastUsername() }.getOrDefault("")
    }

    override suspend fun lastPassword(): String? = withContext(dispatchers.io) {
        runCatchingSuspend { sessionStore.rememberedPassword() }.getOrNull()
    }

    override suspend fun shouldRememberCredentials(): Boolean = withContext(dispatchers.io) {
        runCatchingSuspend { sessionStore.shouldRememberCredentials() }.getOrDefault(true)
    }

    override suspend fun setRememberCredentials(remember: Boolean) {
        val choice = sessionIntent.updateAndGet {
            it.copy(rememberRevision = it.rememberRevision + 1, remember = remember)
        }
        // A successful login can navigate away and cancel the form while this choice waits
        // behind its disk commit. An explicit opt-out must still remove the saved secrets.
        withContext(NonCancellable + dispatchers.io) {
            sessionCommit.withLock {
                if (choice.rememberRevision != sessionIntent.get().rememberRevision) return@withLock
                sessionStore.setRememberCredentials(remember)
            }
        }
    }

    override suspend fun logout() {
        val revision = sessionIntent.updateAndGet { it.copy(revision = it.revision + 1) }.revision
        withContext(dispatchers.io) {
            sessionCommit.withLock {
                // A newer login may already have committed before this IO task was scheduled.
                if (committedRevision > revision) return@withLock
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    sessionStore.clear()
                    sessionHolder.update(null)
                    committedRevision = revision
                }
            }
        }
    }
}
