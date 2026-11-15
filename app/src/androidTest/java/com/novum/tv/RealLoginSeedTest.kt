package com.novum.tv

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.novum.tv.data.remote.emby.AuthRequestDto
import com.novum.tv.data.remote.emby.EmbyClientFactory
import com.novum.tv.data.remote.emby.SessionHolder
import com.novum.tv.data.session.DataStoreSessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真实服务器登录冒烟测试（默认跳过，需显式传参）。
 *
 * 用途：在没有可用输入法的电视上验证「UA + Token + 账号密码」整条链路，
 * 并把会话写进应用私有 DataStore，方便后续页面验证。
 *
 * 运行方式：
 * ```
 * adb shell am instrument -w \
 *   -e server https://example.com -e user xxx -e pass xxx \
 *   -e class com.novum.tv.RealLoginSeedTest \
 *   com.novum.tv.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 * 账号密码不落库，只通过 instrumentation 参数传入。
 */
@RunWith(AndroidJUnit4::class)
class RealLoginSeedTest {

    @Test
    fun logsInAndPersistsSession() = runBlocking {
        val args = androidx.test.platform.app.InstrumentationRegistry.getArguments()
        val server = args.getString("server")
        val user = args.getString("user")
        val pass = args.getString("pass")
        assumeTrue("缺少 server/user/pass 参数，跳过真实登录", server != null && user != null && pass != null)

        val context = ApplicationProvider.getApplicationContext<Context>()
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            coerceInputValues = true
        }
        val holder = SessionHolder()
        val factory = EmbyClientFactory(json, holder)
        val baseUrl = requireNotNull(server).trimEnd('/')

        val api = factory.api(baseUrl)
        val info = api.publicInfo()
        val auth = api.authenticate(AuthRequestDto(Username = requireNotNull(user), Pw = requireNotNull(pass)))
        val token = auth.AccessToken
        val user0 = auth.User ?: com.novum.tv.data.remote.emby.UserDto()
        val uid = user0.Id
        assertNotNull("未拿到 Token", token)
        assertNotNull("未拿到用户 id", uid)

        // 写入与应用相同的 DataStore 文件（instrumentation 与目标应用同进程同 UID）
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { context.preferencesDataStoreFile("novum_settings") },
        )
        val store = DataStoreSessionStore(dataStore)
        store.save(
            server = baseUrl,
            token = requireNotNull(token),
            userId = requireNotNull(uid),
            userName = user0.Name ?: user,
            serverName = info.ServerName.orEmpty(),
        )

        assertTrue("会话未写入", store.load() != null)
    }
}
