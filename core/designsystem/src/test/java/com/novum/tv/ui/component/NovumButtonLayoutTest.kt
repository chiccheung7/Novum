package com.novum.tv.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.novum.tv.ui.icon.NovumIcons
import com.novum.tv.ui.theme.NovumTheme
import com.novum.tv.ui.theme.NovumType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h720dp-land-mdpi")
class NovumButtonLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun fixedWidthLabelStaysCentered() = checkCenteredLabel(fontScale = 1f)

    @Test
    fun largerTextStaysCentered() = checkCenteredLabel(fontScale = 1.5f)

    @Test
    fun tallerButtonKeepsItsLabelVerticallyCentered() = checkCenteredLabel(fontScale = 1f, tall = true)

    @Test
    fun contentSizedIconAndLabelStayCenteredWithoutFillingTheRow() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                NovumTheme {
                    Row(Modifier.requiredSize(600.dp, 120.dp)) {
                        NovumButton(onClick = {}, modifier = Modifier.testTag("button")) {
                            Icon(
                                imageVector = NovumIcons.Play,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp).testTag("icon"),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("立即播放", style = NovumType.ButtonLg, modifier = Modifier.testTag("label"))
                        }
                        NovumButton(onClick = {}, modifier = Modifier.testTag("sibling")) {
                            Text("详情", style = NovumType.ButtonLg)
                        }
                    }
                }
            }
        }
        val button = bounds("button")
        val icon = bounds("icon")
        val label = bounds("label")
        val sibling = bounds("sibling")
        assertEquals(button.center.x, (icon.left + label.right) / 2f, 1f)
        assertEquals(button.center.y, icon.center.y, 1f)
        assertEquals(button.center.y, label.center.y, 1f)
        assertTrue("Content-sized CTA must leave room for its sibling", button.width < 300f && sibling.width > 0f)
    }

    private fun checkCenteredLabel(fontScale: Float, tall: Boolean = false) {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                NovumTheme {
                    Box(Modifier.requiredSize(600.dp, 160.dp)) {
                        NovumButton(
                            onClick = {},
                            centerContent = true,
                            modifier = Modifier.width(240.dp)
                                .then(if (tall) Modifier.height(84.dp) else Modifier)
                                .testTag("button"),
                        ) {
                            Text("登录", style = NovumType.ButtonLg, modifier = Modifier.testTag("label"))
                        }
                    }
                }
            }
        }
        val button = bounds("button")
        val label = bounds("label")
        assertEquals("Label must be horizontally centered", button.center.x, label.center.x, 1f)
        assertEquals("Label must be vertically centered", button.center.y, label.center.y, 1f)
    }

    private fun bounds(tag: String): Rect =
        composeRule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
}
