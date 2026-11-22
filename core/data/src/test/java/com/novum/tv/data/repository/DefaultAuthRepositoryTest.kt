package com.novum.tv.data.repository

import com.novum.tv.core.dispatcher.DispatcherProvider
import com.novum.tv.core.error.NovumError
import com.novum.tv.core.result.NovumResult
import com.novum.tv.data.remote.emby.AuthRequestDto
import com.novum.tv.data.remote.emby.AuthResultDto
import com.novum.tv.data.remote.emby.EmbyApi
import com.novum.tv.data.remote.emby.UserDataDto
import com.novum.tv.data.remote.emby.EmbyApiProvider
import com.novum.tv.data.remote.emby.ItemDto
import com.novum.tv.data.remote.emby.PublicInfoDto
import com.novum.tv.data.remote.emby.PersonDto
import com.novum.tv.data.remote.emby.QueryResultDto
import com.novum.tv.data.remote.emby.SessionHolder
import com.novum.tv.data.remote.emby.UserDto
import com.novum.tv.data.session.Session
import com.novum.tv.data.session.SessionStore
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import com.novum.tv.testing.EmbyApiStub

/**
 * 登录仓储测试：验证协议探测、成功路径与错误分类。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultAuthRepositoryTest {

    private val dispatcher: CoroutineDispatcher = StandardTestDispatcher()

    private val dispatchers = object : DispatcherProvider {
        override val default = dispatcher
        override val io = dispatcher
        override val main = dispatcher
    }

    @Test
    fun logsInWithFirstReachableCandidate() = runTest(dispatcher) {
        val api = FakeEmbyApi()
        val store = FakeSessionStore()
        val repository = DefaultAuthRepository(
            clients = FixedProvider(api),
            sessionStore = store,
            sessionHolder = SessionHolder(),
            dispatchers = dispatchers,
        )

        val result = repository.login("192.168.5.26", "kid", "pw")

        assertTrue(result is NovumResult.Success)
        assertEquals("http://192.168.5.26:8096", (result as NovumResult.Success<Session>).value.server)
        assertEquals("token-1", store.saved?.token)
        assertEquals("我的影院", store.saved?.serverName)
    }

    @Test
    fun rememberDisabledStoresIdentityOnly() = runTest(dispatcher) {
        val api = FakeEmbyApi()
        val store = FakeSessionStore()
        val holder = SessionHolder()
        val repository = DefaultAuthRepository(
            clients = FixedProvider(api),
            sessionStore = store,
            sessionHolder = holder,
            dispatchers = dispatchers,
        )

        val result = repository.login("192.168.5.26", "kid", "pw", remember = false)

        assertTrue(result is NovumResult.Success)
        // 不落盘会话：回到本机会被判为未登录
        assertEquals(null, store.saved)
        // 但仍记住服务器+用户名供预填
        assertEquals("http://192.168.5.26:8096", store.identityServer)
        assertTrue(store.identityUsername.isNotBlank())
        // 本次内存会话照常建立，播放立即可用
        assertEquals("token-1", holder.token)
    }

    @Test
    fun fallsBackToHttpsWhenHttpFails() = runTest(dispatcher) {
        val api = FakeEmbyApi(failingBaseUrls = setOf("http://tv.local:8096"))
        val repository = DefaultAuthRepository(
            clients = FixedProvider(api),
            sessionStore = FakeSessionStore(),
            sessionHolder = SessionHolder(),
            dispatchers = dispatchers,
        )

        val result = repository.login("tv.local", "kid", "pw")

        assertTrue(result is NovumResult.Success)
        assertEquals("https://tv.local:443", (result as NovumResult.Success<Session>).value.server)
    }

    @Test
    fun mapsUnauthorizedToUnauthorizedError() = runTest(dispatcher) {
        val api = FakeEmbyApi(unauthorized = true)
        val repository = DefaultAuthRepository(
            clients = FixedProvider(api),
            sessionStore = FakeSessionStore(),
            sessionHolder = SessionHolder(),
            dispatchers = dispatchers,
        )

        val result = repository.login("192.168.5.26", "kid", "bad")

        assertTrue(result is NovumResult.Failure)
        assertTrue((result as NovumResult.Failure).error is NovumError.Unauthorized)
    }

    @Test
    fun rejectsInvalidAddress() = runTest(dispatcher) {
        val repository = DefaultAuthRepository(
            clients = FixedProvider(FakeEmbyApi()),
            sessionStore = FakeSessionStore(),
            sessionHolder = SessionHolder(),
            dispatchers = dispatchers,
        )

        val result = repository.login("   ", "kid", "pw")

        assertTrue(result is NovumResult.Failure)
    }
}

/** 固定返回同一个假 API，并把当前探测地址写回，便于模拟"某协议不可达"。 */
private class FixedProvider(private val api: FakeEmbyApi) : EmbyApiProvider {
    override fun api(baseUrl: String): EmbyApi {
        api.currentBaseUrl = baseUrl
        return api
    }
}

/**
 * 假 Emby API。
 *
 * @param failingBaseUrls 需要模拟连接失败的地址前缀（用于验证 HTTP → HTTPS 回退）。
 * @param unauthorized 是否模拟密码错误。
 */
private class FakeEmbyApi(
    private val failingBaseUrls: Set<String> = emptySet(),
    private val unauthorized: Boolean = false,
) : EmbyApiStub() {

    /** 当前被探测的地址，由 [FixedProvider] 写入。 */
    var currentBaseUrl: String = ""

    override suspend fun publicInfo(): PublicInfoDto {
        if (failingBaseUrls.any { currentBaseUrl.startsWith(it) }) throw IOException("connect failed")
        return PublicInfoDto(ServerName = "我的影院", Version = "4.9.0")
    }

    override suspend fun authenticate(body: AuthRequestDto): AuthResultDto {
        if (unauthorized) {
            throw HttpException(
                Response.error<Any>(401, "{}".toResponseBody("application/json".toMediaType())),
            )
        }
        return AuthResultDto(AccessToken = "token-1", User = UserDto(Id = "uid-1", Name = body.Username))
    }

    override suspend fun persons(
        userId: String?,
        searchTerm: String?,
        limit: Int,
        fields: String,
    ): QueryResultDto<PersonDto> = QueryResultDto()

    override suspend fun views(uid: String, fields: String): QueryResultDto<ItemDto> =
        QueryResultDto()

    override suspend fun items(
        uid: String,
        parentId: String?,
        ids: String?,
        personIds: String?,
        recursive: Boolean,
        includeItemTypes: String?,
        searchTerm: String?,
        sortBy: String,
        sortOrder: String,
        startIndex: Int,
        limit: Int,
        maxOfficialRating: String?,
        genres: String?,
        filters: String?,
        minPremiereDate: String?,
        minDateCreated: String?,
        fields: String,
    ): QueryResultDto<ItemDto> = QueryResultDto()

    override suspend fun markPlayed(uid: String, itemId: String): UserDataDto = UserDataDto()

    override suspend fun markUnplayed(uid: String, itemId: String): UserDataDto = UserDataDto()

    override suspend fun resume(uid: String, limit: Int, mediaTypes: String, fields: String) =
        QueryResultDto<ItemDto>()

    override suspend fun nextUp(uid: String, limit: Int, fields: String, seriesId: String?) = QueryResultDto<ItemDto>()

    override suspend fun latest(uid: String, parentId: String, limit: Int, fields: String) =
        emptyList<ItemDto>()
}

/** 内存会话存储。 */
private class FakeSessionStore : SessionStore {

    var saved: Session? = null

    override val session: Flow<Session?> = flowOf(saved)

    override suspend fun load(): Session? = saved

    override suspend fun save(
        server: String,
        token: String,
        userId: String,
        userName: String,
        serverName: String,
    ) {
        saved = Session(server, token, userId, userName, serverName, "device-1")
    }

    var identityServer: String = ""
    var identityUsername: String = ""

    override suspend fun saveIdentity(server: String, username: String) {
        identityServer = server
        identityUsername = username
        saved = null
    }

    override suspend fun clear() {
        saved = null
    }

    override suspend fun lastServer(): String = saved?.server ?: identityServer

    override suspend fun lastUsername(): String = saved?.userName ?: identityUsername

    override suspend fun deviceId(): String = "device-1"
}
