package com.novum.tv.ui.screen.login

import com.novum.tv.core.error.NovumError
import com.novum.tv.core.result.NovumResult
import com.novum.tv.core.result.failureOf
import com.novum.tv.core.result.successOf
import com.novum.tv.data.repository.AuthRepository
import com.novum.tv.data.session.Session
import com.novum.tv.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 登录页 ViewModel 测试：必填校验、成功跳转标记、错误透传。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun requiresServerFirst() {
        val viewModel = LoginViewModel(FakeAuthRepository())

        assertTrue("未保存选择时默认记住凭证", viewModel.state.value.remember)
        assertEquals(LoginServerInput(), viewModel.state.value.serverInput)
        viewModel.submit()

        assertEquals(LoginUiState.LoginField.Server, viewModel.state.value.missingField)
        assertFalse(viewModel.state.value.loading)
    }

    @Test
    fun requiresUsernameWhenServerFilled() {
        val viewModel = LoginViewModel(FakeAuthRepository())
        viewModel.onServerChange("192.168.5.26")

        viewModel.submit()

        assertEquals(LoginUiState.LoginField.Username, viewModel.state.value.missingField)
    }

    @Test
    fun successfulLoginMarksSuccess() = runTest {
        val viewModel = LoginViewModel(FakeAuthRepository(result = successOf(fakeSession())))

        fillForm(viewModel)
        viewModel.submit()

        assertTrue(viewModel.state.value.success)
        assertNull(viewModel.state.value.networkError)
    }

    @Test
    fun failedLoginExposesError() = runTest {
        val viewModel = LoginViewModel(
            FakeAuthRepository(result = failureOf(NovumError.Unauthorized())),
        )

        fillForm(viewModel)
        viewModel.submit()

        assertFalse(viewModel.state.value.success)
        assertTrue(viewModel.state.value.networkError is NovumError.Unauthorized)
    }

    @Test
    fun restoresRememberedCredentials() = runTest {
        val viewModel = LoginViewModel(
            FakeAuthRepository(
                rememberedPassword = "pw",
                shouldRememberCredentials = true,
                lastServer = "http://tv.local",
                lastUsername = "kid",
            ),
        )

        runCurrent()

        assertEquals(LoginServerInput("http", "tv.local", "8080"), viewModel.state.value.serverInput)
        assertEquals("http://tv.local:8080", viewModel.state.value.server)
        assertEquals("kid", viewModel.state.value.username)
        assertEquals("pw", viewModel.state.value.password)
        assertTrue(viewModel.state.value.remember)
    }

    @Test
    fun savedRememberOptOutIsRestoredWithoutPrefillingPassword() = runTest {
        val viewModel = LoginViewModel(
            FakeAuthRepository(
                lastServer = "https://media.example",
                lastUsername = "viewer",
                rememberedPassword = "old-password",
                shouldRememberCredentials = false,
            ),
        )
        runCurrent()

        assertFalse("A saved opt-out takes precedence over the new default", viewModel.state.value.remember)
        assertEquals("", viewModel.state.value.password)
    }

    @Test
    fun rememberOptOutPersistsAcrossViewModelInstances() = runTest {
        val repository = FakeAuthRepository()
        val first = LoginViewModel(repository)
        runCurrent()
        assertTrue(first.state.value.remember)

        first.onRememberChange(false)
        runCurrent()
        val restored = LoginViewModel(repository)
        runCurrent()

        assertFalse(restored.state.value.remember)
    }

    @Test
    fun rememberReadFailureKeepsTheEnabledDefault() = runTest {
        val viewModel = LoginViewModel(FakeAuthRepository(beforeRememberReadReturns = { error("read failed") }))
        runCurrent()

        assertTrue(viewModel.state.value.remember)
    }

    @Test
    fun editingServerAfterPrefillClearsSavedPassword() = runTest {
        val viewModel = rememberedViewModel()
        runCurrent()
        assertEquals("saved-password", viewModel.state.value.password)

        viewModel.onServerChange("https://new.local")
        viewModel.submit()

        assertEquals("https://new.local:443", viewModel.state.value.server)
        assertEquals("", viewModel.state.value.password)
        assertEquals(LoginUiState.LoginField.Password, viewModel.state.value.missingField)
        assertFalse(viewModel.state.value.success)
    }

    @Test
    fun editingUsernameAfterPrefillClearsSavedPassword() = runTest {
        val viewModel = rememberedViewModel()
        runCurrent()

        viewModel.onUsernameChange("new-user")
        viewModel.submit()

        assertEquals("new-user", viewModel.state.value.username)
        assertEquals("", viewModel.state.value.password)
        assertEquals(LoginUiState.LoginField.Password, viewModel.state.value.missingField)
        assertFalse(viewModel.state.value.success)
    }

    @Test
    fun unchangedIdentityRetainsPrefilledPassword() = runTest {
        val viewModel = rememberedViewModel()
        runCurrent()

        viewModel.onServerChange("https://saved.local")
        viewModel.onUsernameChange("saved-user")

        assertEquals("saved-password", viewModel.state.value.password)
    }

    @Test
    fun manuallyEditedPasswordSurvivesIdentityChanges() = runTest {
        val viewModel = rememberedViewModel()
        runCurrent()
        viewModel.onPasswordChange("typed-password")

        viewModel.onServerChange("https://new.local")
        viewModel.onUsernameChange("new-user")

        assertEquals("typed-password", viewModel.state.value.password)
    }

    @Test
    fun uncheckingRememberClearsSavedCredentialsButKeepsFormValues() = runTest {
        val repository = FakeAuthRepository(rememberedPassword = "pw")
        val viewModel = LoginViewModel(repository)
        runCurrent()
        viewModel.onServerChange("https://new.local")
        viewModel.onUsernameChange("new-user")
        viewModel.onPasswordChange("new-pw")

        viewModel.onRememberChange(false)
        runCurrent()

        assertFalse(viewModel.state.value.remember)
        assertEquals("https://new.local:443", viewModel.state.value.server)
        assertEquals("new-user", viewModel.state.value.username)
        assertEquals("new-pw", viewModel.state.value.password)
        assertTrue(repository.forgotRememberedCredentials)
        assertFalse(repository.rememberChoice)
    }

    @Test
    fun delayedPrefillDoesNotRestoreFieldsTheUserHasClearedOrRememberChoice() = runTest {
        val restore = CompletableDeferred<Unit>()
        val repository = FakeAuthRepository(
            rememberedPassword = "saved-password",
            shouldRememberCredentials = true,
            lastServer = "https://saved.local",
            lastUsername = "saved-user",
            beforeRememberReadReturns = { restore.await() },
        )
        val viewModel = LoginViewModel(repository)
        runCurrent()

        viewModel.onServerChange("")
        viewModel.onUsernameChange("")
        viewModel.onPasswordChange("")
        viewModel.onRememberChange(false)
        restore.complete(Unit)
        runCurrent()

        assertEquals("", viewModel.state.value.server)
        assertEquals("", viewModel.state.value.username)
        assertEquals("", viewModel.state.value.password)
        assertFalse(viewModel.state.value.remember)
    }

    @Test
    fun delayedPrefillDoesNotAttachSavedCredentialsToANewServer() = runTest {
        val restore = CompletableDeferred<Unit>()
        val viewModel = LoginViewModel(
            FakeAuthRepository(
                rememberedPassword = "saved-password",
                shouldRememberCredentials = true,
                lastServer = "https://saved.local",
                lastUsername = "saved-user",
                beforeRememberReadReturns = { restore.await() },
            ),
        )
        runCurrent()

        viewModel.onServerChange("https://new.local")
        restore.complete(Unit)
        runCurrent()

        assertEquals("https://new.local:443", viewModel.state.value.server)
        assertEquals("", viewModel.state.value.username)
        assertEquals("", viewModel.state.value.password)
    }

    @Test
    fun protocolChangesResetThePortToTheSelectedProtocolDefault() {
        val viewModel = LoginViewModel(FakeAuthRepository())
        viewModel.onServerChange("tv.local")
        viewModel.onPortChange("9443")

        viewModel.onProtocolChange("http")

        assertEquals(LoginServerInput("http", "tv.local", "8080"), viewModel.state.value.serverInput)
        assertEquals("http://tv.local:8080", viewModel.state.value.server)
        viewModel.onPortChange("9000")
        viewModel.onProtocolChange("https")
        assertEquals(LoginServerInput("https", "tv.local", "443"), viewModel.state.value.serverInput)
    }

    @Test
    fun reselectingTheCurrentProtocolKeepsTheCustomPort() {
        val viewModel = LoginViewModel(FakeAuthRepository())
        viewModel.onPortChange("9443")

        viewModel.onProtocolChange("https")

        assertEquals("9443", viewModel.state.value.serverInput.port)
    }

    @Test
    fun pastedFullUrlUpdatesAllServerFields() {
        val viewModel = LoginViewModel(FakeAuthRepository())

        viewModel.onServerChange("http://tv.local:8096/proxy/emby?name=one")

        assertEquals(LoginServerInput("http", "tv.local/proxy/emby?name=one", "8096"), viewModel.state.value.serverInput)
        assertEquals("http://tv.local:8096/proxy/emby?name=one", viewModel.state.value.server)
    }

    @Test
    fun rejectsEmptyNonnumericAndOutOfRangePortsWithoutCallingRepository() {
        listOf("", "0", "65536", "-1", "+443", "wrong", "443.5", "999999999999").forEach { port ->
            val repository = FakeAuthRepository()
            val viewModel = LoginViewModel(repository)
            fillForm(viewModel)
            viewModel.onPortChange(port)

            viewModel.submit()

            assertEquals("Invalid port: $port", LoginUiState.LoginField.Port, viewModel.state.value.missingField)
            assertFalse(viewModel.state.value.loading)
            assertTrue(repository.loginAttempts.isEmpty())
        }
    }

    @Test
    fun acceptsBothPortRangeBoundaries() = runTest {
        listOf("1", "65535").forEach { port ->
            val repository = FakeAuthRepository()
            val viewModel = LoginViewModel(repository)
            fillForm(viewModel)
            viewModel.onPortChange(port)

            viewModel.submit()
            runCurrent()

            assertNull(viewModel.state.value.missingField)
            assertEquals("https://192.168.5.26:$port", repository.loginAttempts.single().server)
        }
    }

    @Test
    fun invalidAddressesFailLocallyBeforeCallingRepository() {
        listOf("not a host", "https://", "ftp://tv.local", "/emby", "[not-an-ipv6]", "viewer@tv.local", "http://viewer:pw@tv.local").forEach { address ->
            val repository = FakeAuthRepository()
            val viewModel = LoginViewModel(repository)
            viewModel.onServerChange(address)
            viewModel.onUsernameChange("viewer")
            viewModel.onPasswordChange("typed-password")

            viewModel.submit()

            assertEquals("Invalid server: $address", LoginUiState.LoginField.Server, viewModel.state.value.missingField)
            assertTrue(repository.loginAttempts.isEmpty())
        }
    }

    @Test
    fun submitsTheComposedServerAndUnchangedCredentials() = runTest {
        val repository = FakeAuthRepository()
        val viewModel = LoginViewModel(repository)
        viewModel.onServerChange("[2001:db8::1]/emby?name=one")
        viewModel.onProtocolChange("http")
        viewModel.onPortChange("9000")
        viewModel.onUsernameChange("viewer")
        viewModel.onPasswordChange("typed-password")
        viewModel.onRememberChange(true)

        viewModel.submit()
        runCurrent()

        assertEquals(
            LoginAttempt("http://[2001:db8::1]:9000/emby?name=one", "viewer", "typed-password", true),
            repository.loginAttempts.single(),
        )
    }

    @Test
    fun restoresSavedServerIntoProtocolAddressAndExplicitPort() = runTest {
        val viewModel = LoginViewModel(
            FakeAuthRepository(
                lastServer = "http://tv.local:8096/proxy/emby",
                lastUsername = "viewer",
                rememberedPassword = "saved-password",
                shouldRememberCredentials = true,
            ),
        )
        runCurrent()

        assertEquals(LoginServerInput("http", "tv.local/proxy/emby", "8096"), viewModel.state.value.serverInput)
        assertEquals("http://tv.local:8096/proxy/emby", viewModel.state.value.server)
        assertEquals("saved-password", viewModel.state.value.password)
    }

    @Test
    fun restoresLegacyServerWithoutProtocolAsHttps() = runTest {
        val viewModel = LoginViewModel(FakeAuthRepository(lastServer = "tv.local/emby"))
        runCurrent()

        assertEquals(LoginServerInput("https", "tv.local/emby", "443"), viewModel.state.value.serverInput)
        assertEquals("https://tv.local:443/emby", viewModel.state.value.server)
    }

    @Test
    fun changingProtocolAfterPrefillClearsSavedPassword() = runTest {
        val viewModel = rememberedViewModel()
        runCurrent()

        viewModel.onProtocolChange("http")

        assertEquals("http://saved.local:8080", viewModel.state.value.server)
        assertEquals("", viewModel.state.value.password)
    }

    @Test
    fun changingPortAfterPrefillClearsSavedPassword() = runTest {
        val viewModel = rememberedViewModel()
        runCurrent()

        viewModel.onPortChange("9443")

        assertEquals("https://saved.local:9443", viewModel.state.value.server)
        assertEquals("", viewModel.state.value.password)
    }

    @Test
    fun unchangedProtocolAndPortKeepSavedPassword() = runTest {
        val viewModel = rememberedViewModel()
        runCurrent()

        viewModel.onProtocolChange("https")
        viewModel.onPortChange("443")

        assertEquals("saved-password", viewModel.state.value.password)
    }

    @Test
    fun manuallyEditedPasswordSurvivesProtocolAndPortChanges() = runTest {
        val viewModel = rememberedViewModel()
        runCurrent()
        viewModel.onPasswordChange("typed-password")

        viewModel.onProtocolChange("http")
        viewModel.onPortChange("9000")

        assertEquals("typed-password", viewModel.state.value.password)
    }

    @Test
    fun delayedPrefillCannotOverwriteProtocolOrPortEditsOrAttachSavedCredentials() = runTest {
        val restore = CompletableDeferred<Unit>()
        val viewModel = LoginViewModel(
            FakeAuthRepository(
                rememberedPassword = "saved-password",
                shouldRememberCredentials = true,
                lastServer = "https://saved.local:9443",
                lastUsername = "saved-user",
                beforeRememberReadReturns = { restore.await() },
            ),
        )
        runCurrent()

        viewModel.onProtocolChange("http")
        viewModel.onPortChange("9000")
        restore.complete(Unit)
        runCurrent()

        assertEquals(LoginServerInput("http", "", "9000"), viewModel.state.value.serverInput)
        assertEquals("", viewModel.state.value.username)
        assertEquals("", viewModel.state.value.password)
    }

    @Test
    fun typingACompleteUrlOneCharacterAtATimeKeepsTheDraftUntilCommit() {
        val viewModel = LoginViewModel(FakeAuthRepository())
        val url = "http://media.example:9000/emby"
        url.indices.forEach { viewModel.onServerChange(url.take(it + 1)) }

        assertEquals(url, viewModel.state.value.serverInput.address)
        assertEquals(url, viewModel.state.value.server)
        viewModel.commitServerInput()

        assertEquals(LoginServerInput("http", "media.example/emby", "9000"), viewModel.state.value.serverInput)
    }

    @Test
    fun typingAnIpv6AddressOneCharacterAtATimeDoesNotLoseTheColons() {
        val viewModel = LoginViewModel(FakeAuthRepository())
        val address = "2001:db8::1"
        address.indices.forEach { viewModel.onServerChange(address.take(it + 1)) }
        viewModel.commitServerInput()

        assertEquals(LoginServerInput("https", "[2001:db8::1]", "443"), viewModel.state.value.serverInput)
    }

    @Test
    fun protocolAndPortChangesTakePrecedenceOverAnUncommittedUrl() {
        val viewModel = LoginViewModel(FakeAuthRepository())
        val url = "http://media.example:9000/emby"
        url.indices.forEach { viewModel.onServerChange(url.take(it + 1)) }
        viewModel.onProtocolChange("https")
        assertEquals(LoginServerInput("https", "media.example/emby", "443"), viewModel.state.value.serverInput)

        viewModel.onPortChange("8920")
        assertEquals("https://media.example:8920/emby", viewModel.state.value.server)
    }

    private fun rememberedViewModel() = LoginViewModel(
        FakeAuthRepository(
            rememberedPassword = "saved-password",
            shouldRememberCredentials = true,
            lastServer = "https://saved.local",
            lastUsername = "saved-user",
        ),
    )

    private fun fillForm(viewModel: LoginViewModel) {
        viewModel.onServerChange("192.168.5.26")
        viewModel.onUsernameChange("kid")
        viewModel.onPasswordChange("pw")
    }

    private fun fakeSession() = Session(
        server = "http://192.168.5.26:8096",
        token = "token",
        userId = "uid",
        userName = "kid",
        serverName = "我的影院",
        deviceId = "device",
    )
}

/**
 * 假登录仓储：按需返回固定结果。
 *
 * @param result 登录返回值。
 */
private class FakeAuthRepository(
    private val result: NovumResult<Session> = successOf(
        Session("http://host:8096", "t", "u", "n", "s", "d"),
    ),
    private val rememberedPassword: String? = null,
    private val shouldRememberCredentials: Boolean = true,
    private val lastServer: String = "",
    private val lastUsername: String = "",
    private val beforeRememberReadReturns: suspend () -> Unit = {},
) : AuthRepository {

    var rememberChoice = shouldRememberCredentials
    val loginAttempts = mutableListOf<LoginAttempt>()

    override suspend fun login(
        rawServer: String,
        username: String,
        password: String,
        remember: Boolean,
    ): NovumResult<Session> {
        loginAttempts += LoginAttempt(rawServer, username, password, remember)
        return result
    }

    override suspend fun restore(): Session? = null

    override suspend fun logout() = Unit

    override suspend fun lastServer(): String = lastServer

    override suspend fun lastUsername(): String = lastUsername

    override suspend fun lastPassword(): String? = rememberedPassword

    override suspend fun shouldRememberCredentials(): Boolean {
        val remembered = rememberChoice
        beforeRememberReadReturns()
        return remembered
    }

    override suspend fun setRememberCredentials(remember: Boolean) {
        rememberChoice = remember
        if (!remember) forgotRememberedCredentials = true
    }

    var forgotRememberedCredentials = false
}

private data class LoginAttempt(val server: String, val username: String, val password: String, val remember: Boolean)
