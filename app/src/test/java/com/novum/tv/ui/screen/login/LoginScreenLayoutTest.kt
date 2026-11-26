package com.novum.tv.ui.screen.login

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.novum.tv.core.result.NovumResult
import com.novum.tv.data.repository.AuthRepository
import com.novum.tv.data.session.Session
import com.novum.tv.testing.NoopAuthRepository
import com.novum.tv.testing.RepoPaths
import com.novum.tv.ui.component.NovumFocusScrollTuning
import com.novum.tv.ui.theme.NovumTheme
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w1280dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LoginScreenLayoutTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var observedImeBottomPx = 0

    @Test
    fun minimalSingleColumnFormShowsOnlyRequestedContentAndServerDefaults() {
        val viewModel = LoginViewModel(NoopAuthRepository)
        showLogin(viewModel)

        composeRule.onAllNodes(hasSetTextAction()).assertCountEquals(4)
        field(0).assertIsFocused().assertTextEquals("")
        field(1).assertTextEquals("443")
        field(2).assertTextEquals("")
        field(3).assertTextEquals("")
        rememberSwitch().assertIsOn()
        composeRule.onNode(hasContentDescription("端口") and hasSetTextAction()).assertIsDisplayed()
        composeRule.onAllNodes(hasText("服务器地址")).assertCountEquals(1)
        composeRule.onAllNodes(hasText("端口")).assertCountEquals(0)
        val allowed = setOf("NOVUM", "登录", "服务器地址", "HTTPS", "443", "用户名", "密码", "记住凭证")
        val visibleText = composeRule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true,
        ).fetchSemanticsNodes().flatMap { node -> node.config[SemanticsProperties.Text].map { it.text } }
            .filter(String::isNotBlank)
        assertTrue("Only requested content is visible: $visibleText", visibleText.all { it in allowed })
        listOf("NOVUM", "服务器地址", "用户名", "密码", "记住凭证").forEach {
            composeRule.onNode(hasText(it)).assertIsDisplayed()
        }
        val protocol = protocolSelector().fetchSemanticsNode().boundsInRoot
        val address = field(0).fetchSemanticsNode().boundsInRoot
        val port = field(1).fetchSemanticsNode().boundsInRoot
        val username = field(2).fetchSemanticsNode().boundsInRoot
        val password = field(3).fetchSemanticsNode().boundsInRoot
        val usernameLabel = composeRule.onNode(hasText("用户名")).fetchSemanticsNode().boundsInRoot
        val passwordLabel = composeRule.onNode(hasText("密码")).fetchSemanticsNode().boundsInRoot
        val remember = rememberSwitch().fetchSemanticsNode().boundsInRoot
        val button = loginButton().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(protocol.center.y, address.center.y, 1f)
        assertEquals(address.center.y, port.center.y, 1f)
        assertTrue("Three server parts share one row without overlap", protocol.right < address.left && address.right < port.left)
        assertEquals("Fields align with the server row", protocol.left, usernameLabel.left, 1f)
        assertEquals(usernameLabel.left, passwordLabel.left, 1f)
        assertEquals("Login uses the compact standard width", 200f, button.width, 1f)
        assertEquals("Login aligns with the right edge of the form", port.right + 20f, button.right, 1f)
        assertEquals("Form is centered", 640f, username.center.x, 1f)
        assertTrue("Fields remain above the action row", port.bottom < username.top && username.bottom < password.top &&
            password.bottom < remember.top)
        assertEquals("Remember switch and login share one row", remember.center.y, button.center.y, 1f)
        assertTrue("Login stays to the right of remember credentials", remember.right < button.left)
        val rememberLabel = composeRule.onNode(hasText("记住凭证")).fetchSemanticsNode().boundsInRoot
        assertEquals("Remember label has a little more space before its switch", 16f, remember.left - rememberLabel.right, 1f)
        savePreview("login-screen.png")
    }

    @Test
    fun protocolSelectionChangesDefaultPortsWithoutChangingAddress() {
        val viewModel = LoginViewModel(NoopAuthRepository)
        showLogin(viewModel)
        field(0).performTextInput("media.example/emby")
        chooseProtocol("http")
        field(0).assertTextEquals("media.example/emby")
        field(1).assertTextEquals("8080")
        composeRule.runOnIdle { assertEquals("http://media.example:8080/emby", viewModel.state.value.server) }
        chooseProtocol("https")
        field(1).assertTextEquals("443")
        composeRule.runOnIdle { assertEquals("https://media.example:443/emby", viewModel.state.value.server) }
    }

    @Test
    fun pastedUrlSplitsServerPartsAndSubmitsTheExplicitSelectedServer() {
        val repository = RecordingLoginRepository()
        showLogin(LoginViewModel(repository))
        field(0).performTextInput("http://media.example:8096/emby")
        field(2).performTextInput("viewer")
        field(3).performTextInput("sample-password")
        field(0).assertTextEquals("media.example/emby")
        field(1).assertTextEquals("8096")
        composeRule.onNode(hasText("HTTP") and hasClickAction()).assertIsDisplayed()
        savePreview("login-screen-filled.png")
        loginButton().performSemanticsAction(SemanticsActions.OnClick)
        confirmHttp()
        chooseProtocol("https")
        field(1).assertTextEquals("443")
        loginButton().performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        composeRule.onNode(hasText("使用 HTTP 明文连接？")).assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(listOf("http://media.example:8096/emby", "https://media.example:443/emby"), repository.servers)
        }
    }

    @Test
    fun imeNextMovesThroughFieldsAndDoneRequiresHttpConfirmation() {
        val repository = RecordingLoginRepository()
        showLogin(LoginViewModel(repository))
        composeRule.onAllNodes(hasSetTextAction()).assertCountEquals(4)

        field(0).assertIsFocused().performTextInput("http://tv.local:8096")
        field(0).performImeAction()
        field(1).assertIsFocused().assertTextEquals("8096").performImeAction()
        field(2).assertIsFocused().performTextInput("viewer")
        field(2).performImeAction()
        field(3).assertIsFocused().performTextInput("typed-password")
        field(3).performImeAction()

        composeRule.onNode(hasText("使用 HTTP 明文连接？")).assertIsDisplayed()
        composeRule.onNode(hasText("取消") and hasClickAction()).assertIsFocused()
        composeRule.runOnIdle {
            assertTrue("IME Done must not send credentials before confirmation", repository.servers.isEmpty())
        }

        composeRule.onNode(hasText("仍然登录") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        composeRule.onNode(hasText("使用 HTTP 明文连接？")).assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(listOf("http://tv.local:8096"), repository.servers) }
    }

    @Test
    fun editingServerRequiresNewHttpConfirmationAfterPreviousConsent() {
        val repository = RecordingLoginRepository()
        showLogin(LoginViewModel(repository))
        field(0).performTextInput("http://first.local:8096")
        field(2).performTextInput("viewer")
        field(3).performTextInput("typed-password")

        loginButton().performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNode(hasText("使用 HTTP 明文连接？")).assertIsDisplayed()
        composeRule.runOnIdle { assertTrue(repository.servers.isEmpty()) }
        composeRule.onNode(hasText("仍然登录") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(1, repository.servers.size) }

        // Consent may be reused for the same address after a failed authentication attempt.
        loginButton().performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        composeRule.onNode(hasText("使用 HTTP 明文连接？")).assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(2, repository.servers.size) }

        field(0).performTextReplacement("http://second.local:8096")
        loginButton().performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNode(hasText("使用 HTTP 明文连接？")).assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals("Changing the address must not reuse another server's consent", 2, repository.servers.size)
        }
        composeRule.onNode(hasText("仍然登录") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(
                listOf("http://first.local:8096", "http://first.local:8096", "http://second.local:8096"),
                repository.servers,
            )
        }
    }

    @Test
    fun passwordCommitAndImeDoneInTheSameFrameStillRequireHttpConfirmation() {
        val repository = RecordingLoginRepository()
        val viewModel = LoginViewModel(repository)
        showLogin(viewModel)
        field(0).performTextInput("http://tv.local:8096")
        field(2).performTextInput("viewer")
        val done = requireNotNull(field(3).fetchSemanticsNode().config[SemanticsActions.OnImeAction].action)

        // InputConnection may commit the final text and dispatch Done before another Compose frame.
        composeRule.runOnIdle {
            viewModel.onPasswordChange("typed-password")
            done()
        }

        composeRule.onNode(hasText("使用 HTTP 明文连接？")).assertIsDisplayed()
        composeRule.runOnIdle {
            assertTrue("The latest completed form still needs consent before credentials are sent", repository.servers.isEmpty())
        }
    }

    @Test
    fun serverChangeAndImeDoneInTheSameFrameCannotReusePreviousHttpConsent() {
        val repository = RecordingLoginRepository()
        val viewModel = LoginViewModel(repository)
        showLogin(viewModel)
        field(0).performTextInput("http://first.local:8096")
        field(2).performTextInput("viewer")
        field(3).performTextInput("typed-password")
        field(3).performImeAction()
        composeRule.onNode(hasText("仍然登录") and hasClickAction())
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(listOf("http://first.local:8096"), repository.servers) }
        val done = requireNotNull(field(3).fetchSemanticsNode().config[SemanticsActions.OnImeAction].action)

        composeRule.runOnIdle {
            viewModel.onServerChange("http://second.local:8096")
            done()
        }

        composeRule.onNode(hasText("使用 HTTP 明文连接？")).assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals("Consent for one server must not authorize another before recomposition", 1, repository.servers.size)
        }
    }

    @Test
    @Config(qualifiers = "w800dp-h400dp-land-mdpi")
    fun dpadTraversesTheShortFormAndEmptySubmitFocusesTheMissingField() {
        val viewModel = LoginViewModel(NoopAuthRepository)
        showLogin(viewModel, width = 800, height = 400)
        field(0).assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        field(1).assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        field(2).assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        field(3).assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }

        val rememberSwitch = composeRule.onNode(hasContentDescription("记住凭证") and hasClickAction())
        rememberSwitch.assertIsFocused().assertIsOn().performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.runOnIdle { assertFalse("Left explicitly disables the default-enabled choice", viewModel.state.value.remember) }
        rememberSwitch.assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.runOnIdle { assertTrue(viewModel.state.value.remember) }
        rememberSwitch.assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        loginButton().assertIsFocused().performKeyInput { pressKey(Key.DirectionLeft) }
        rememberSwitch.assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        loginButton().assertIsFocused().assertIsDisplayed()
        val buttonBounds = loginButton().fetchSemanticsNode().boundsInRoot
        assertTrue("D-pad focus scrolls the whole login button into the short viewport", buttonBounds.top >= 0f && buttonBounds.bottom <= 400f)

        loginButton().performKeyInput { pressKey(Key.DirectionCenter) }

        field(0).assertIsFocused().assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(LoginUiState.LoginField.Server, viewModel.state.value.missingField) }
        composeRule.onNode(hasText("请填写有效的服务器地址")).assertExists()
    }

    @Test
    fun invalidPortFocusesThePortFieldBeforeRequestingCredentials() {
        val repository = RecordingLoginRepository()
        val viewModel = LoginViewModel(repository)
        showLogin(viewModel)
        field(0).performTextInput("media.example")
        field(1).performTextReplacement("70000")
        loginButton().performSemanticsAction(SemanticsActions.OnClick)
        field(1).assertIsFocused()
        composeRule.onNode(hasText("端口须为 1–65535")).assertExists()
        composeRule.runOnIdle {
            assertEquals(LoginUiState.LoginField.Port, viewModel.state.value.missingField)
            assertTrue(repository.servers.isEmpty())
        }
    }

    @Test
    fun imeInsetsKeepPasswordAndLoginActionAboveTheKeyboard() {
        showLogin(LoginViewModel(NoopAuthRepository))
        field(3).performSemanticsAction(SemanticsActions.RequestFocus)
        val keyboardHeightPx = 280
        dispatchImeInsets(keyboardHeightPx)
        composeRule.runOnIdle {
            assertEquals("The test must deliver real IME insets to Compose", keyboardHeightPx, observedImeBottomPx)
        }

        val keyboardTopPx = 720f - keyboardHeightPx
        field(3).performScrollTo().assertIsDisplayed()
        val passwordBounds = field(3).fetchSemanticsNode().boundsInRoot
        assertTrue("Password remains fully visible above the keyboard", passwordBounds.top >= 0f && passwordBounds.bottom <= keyboardTopPx)
        loginButton().performScrollTo().assertIsDisplayed()
        val buttonBounds = loginButton().fetchSemanticsNode().boundsInRoot
        assertTrue("Login action can scroll fully above the keyboard", buttonBounds.top >= 0f && buttonBounds.bottom <= keyboardTopPx)

        dispatchImeInsets(0)
        composeRule.runOnIdle { assertEquals(0, observedImeBottomPx) }
        field(0).performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w800dp-h400dp-land-mdpi")
    fun shortViewportScrollsBetweenServerAndTheCompactLoginAction() {
        showLogin(LoginViewModel(NoopAuthRepository), width = 800, height = 400)
        composeRule.onNode(hasText("好故事，\n值得大屏看。")).assertDoesNotExist()
        field(0).assertIsDisplayed()
        loginButton().assertIsNotDisplayed()

        loginButton().performScrollTo().assertIsDisplayed()
        val buttonBounds = loginButton().fetchSemanticsNode().boundsInRoot
        assertTrue("Login action fits the narrow viewport", buttonBounds.left >= 0f && buttonBounds.right <= 800f)
        assertTrue("Login action fits the short viewport", buttonBounds.top >= 0f && buttonBounds.bottom <= 400f)
        assertEquals("Login remains compact on the narrow window", 200f, buttonBounds.width, 1f)

        field(0).performScrollTo().assertIsDisplayed()
        val addressBounds = field(0).fetchSemanticsNode().boundsInRoot
        val portBounds = field(1).fetchSemanticsNode().boundsInRoot
        assertTrue("Address and port do not overlap on the narrow window", addressBounds.right < portBounds.left)
    }

    private fun showLogin(viewModel: LoginViewModel, width: Int = 1280, height: Int = 720) {
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.enableEdgeToEdge()
            WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                .hide(WindowInsetsCompat.Type.systemBars())
        }
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 1f)) {
                val inputMode = LocalInputModeManager.current
                LaunchedEffect(Unit) { inputMode.requestInputMode(InputMode.Keyboard) }
                val imeBottomPx = WindowInsets.ime.getBottom(LocalDensity.current)
                SideEffect { observedImeBottomPx = imeBottomPx }
                NovumTheme {
                    NovumFocusScrollTuning {
                        Box(Modifier.requiredSize(width.dp, height.dp)) {
                            LoginScreen(onLoggedIn = {}, viewModel = viewModel)
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun dispatchImeInsets(bottomPx: Int) {
        composeRule.activityRule.scenario.onActivity { activity ->
            val decorView = activity.window.decorView
            assertEquals("IME assertions use the real 1280px window", 1280, decorView.width)
            assertEquals("IME assertions use the real 720px window", 720, decorView.height)
            ViewCompat.dispatchApplyWindowInsets(
                decorView,
                WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.NONE)
                    .setVisible(WindowInsetsCompat.Type.systemBars(), false)
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, bottomPx))
                    .setVisible(WindowInsetsCompat.Type.ime(), bottomPx > 0)
                    .build(),
            )
        }
        composeRule.waitForIdle()
    }

    private fun chooseProtocol(protocol: String) {
        protocolSelector().performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNode(hasText(protocol.uppercase()) and hasClickAction() and hasAnyAncestor(isDialog()))
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
    }

    private fun confirmHttp() {
        composeRule.onNode(hasText("使用 HTTP 明文连接？")).assertIsDisplayed()
        composeRule.onNode(hasText("仍然登录") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        composeRule.onNode(hasText("使用 HTTP 明文连接？")).assertDoesNotExist()
    }

    private fun savePreview(filename: String) {
        composeRule.mainClock.advanceTimeBy(800L)
        composeRule.waitForIdle()
        shadowOf(Looper.getMainLooper()).idleFor(32L, TimeUnit.MILLISECONDS)
        val output = RepoPaths.root.resolve("app/build/reports/ui-preview/$filename")
        assertTrue(output.parentFile!!.isDirectory || output.parentFile!!.mkdirs())
        composeRule.activityRule.scenario.onActivity { activity ->
            val view = activity.window.decorView
            assertEquals("Preview uses the real window", 1280, view.width)
            assertEquals("Preview uses the real window", 720, view.height)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.invalidate()
            view.draw(Canvas(bitmap))
            output.outputStream().use { stream -> assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) }
            bitmap.recycle()
        }
        assertTrue("Preview contains image data", output.length() > 0L)
        println("UI_PREVIEW ${output.absolutePath}")
    }

    // Labels and placeholders are separate Text nodes; select the editable controls themselves.
    private fun field(index: Int) = composeRule.onAllNodes(hasSetTextAction())[index]

    private fun loginButton() = composeRule.onNode(hasText("登录") and hasClickAction())
    private fun rememberSwitch() = composeRule.onNode(hasContentDescription("记住凭证") and hasClickAction())
    private fun protocolSelector() = composeRule.onNode((hasText("HTTPS") or hasText("HTTP")) and hasClickAction())
}

/** Keep authentication unsuccessful so repeated submissions stay on this screen. */
private class RecordingLoginRepository : AuthRepository by NoopAuthRepository {
    val servers = mutableListOf<String>()

    override suspend fun login(
        rawServer: String,
        username: String,
        password: String,
        remember: Boolean,
    ): NovumResult<Session> {
        servers += rawServer
        return NoopAuthRepository.login(rawServer, username, password, remember)
    }
}
