package com.novum.tv.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import com.novum.tv.ui.theme.NovumTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h720dp-land-mdpi")
class NovumSwitchInlineTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun focusAndToggleBelongOnlyToTheSwitchControl() {
        var checked by mutableStateOf(false)
        val label = "Remember credentials"

        composeRule.setContent {
            val inputMode = LocalInputModeManager.current
            LaunchedEffect(Unit) { inputMode.requestInputMode(InputMode.Keyboard) }
            NovumTheme {
                Box(Modifier.requiredSize(400.dp, 100.dp)) {
                    NovumSwitchInline(
                        text = label,
                        checked = checked,
                        onCheckedChange = { checked = it },
                    )
                }
            }
        }

        val switch = composeRule.onNodeWithContentDescription(label)
        switch.assertIsDisplayed()
        switch.performSemanticsAction(SemanticsActions.RequestFocus)
        switch.assertIsFocused()
        val labelSemantics = composeRule.onNodeWithText(label).fetchSemanticsNode().config
        assertFalse("Label stays outside the focus tree", labelSemantics.contains(SemanticsActions.RequestFocus))

        val switchBounds = switch.fetchSemanticsNode().boundsInRoot
        val labelBounds = composeRule.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
        assertTrue("Focus target stays on the switch, beside its label", switchBounds.left >= labelBounds.right)

        switch.performClick()
        composeRule.runOnIdle { assertTrue("Switch toggles from its own target", checked) }
    }
}
