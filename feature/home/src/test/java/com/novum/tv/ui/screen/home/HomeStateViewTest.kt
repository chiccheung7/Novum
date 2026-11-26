package com.novum.tv.ui.screen.home

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.novum.tv.core.error.NovumError
import com.novum.tv.core.state.UiState
import com.novum.tv.ui.theme.NovumTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h720dp-xhdpi")
class HomeStateViewTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun rejectedSessionShowsFocusedLoginAction() {
        var calls = 0
        composeRule.setContent {
            NovumTheme {
                HomeStateView(UiState.Error(NovumError.Unauthorized()), {}, { calls++ })
            }
        }
        composeRule.onNodeWithText("重新登录").assertIsDisplayed().assertIsFocused()
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, calls)
        composeRule.onNodeWithText("重试").assertDoesNotExist()
    }

    @Test
    fun ordinaryNetworkFailureOffersRetryInsteadOfClearingSession() {
        var calls = 0
        composeRule.setContent {
            NovumTheme {
                HomeStateView(UiState.Error(NovumError.Network()), { calls++ }, { error("unexpected logout") })
            }
        }
        composeRule.onNodeWithText("重新登录").assertDoesNotExist()
        composeRule.onNodeWithText("重试").assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, calls)
    }

    @Test
    fun pendingSessionClearDisablesAction() {
        composeRule.setContent {
            NovumTheme {
                HomeStateView(UiState.Error(NovumError.Unauthorized()), {}, {}, reauthenticating = true)
            }
        }
        composeRule.onNodeWithText("重新登录").assertIsNotEnabled()
    }
}
