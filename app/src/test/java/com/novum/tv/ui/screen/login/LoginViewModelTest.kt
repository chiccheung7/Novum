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

        assertFalse("首次登录默认不记住凭证", viewModel.state.value.remember)
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

        assertEquals("http://tv.local", viewModel.state.value.server)
        assertEquals("kid", viewModel.state.value.username)
        assertEquals("pw", viewModel.state.value.password)
        assertTrue(viewModel.state.value.remember)
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
        assertEquals("https://new.local", viewModel.state.value.server)
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

        assertEquals("https://new.local", viewModel.state.value.server)
        assertEquals("", viewModel.state.value.username)
        assertEquals("", viewModel.state.value.password)
    }

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
    private val shouldRememberCredentials: Boolean = false,
    private val lastServer: String = "",
    private val lastUsername: String = "",
    private val beforeRememberReadReturns: suspend () -> Unit = {},
) : AuthRepository {

    var rememberChoice = shouldRememberCredentials

    override suspend fun login(
        rawServer: String,
        username: String,
        password: String,
        remember: Boolean,
    ): NovumResult<Session> = result

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
