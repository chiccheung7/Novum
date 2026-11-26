package com.novum.tv.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h720dp-land-mdpi")
class NovumAdjustmentBarTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun discreteControlExposesTheDisplayedFractionAndNumberOfIntervals() {
        composeRule.setContent {
            NovumAdjustmentBar(
                fraction = 0.75f,
                stepCount = 5,
                modifier = Modifier.testTag("bar"),
            )
        }

        assertProgress("bar", current = 0.75f, steps = 3)
    }

    @Test
    fun malformedValuesStayFiniteAndKeepBipolarZeroAsTheFallback() {
        composeRule.setContent {
            Column {
                NovumAdjustmentBar(Float.NaN, Modifier.testTag("nan"), centerFraction = 0.5f)
                NovumAdjustmentBar(Float.POSITIVE_INFINITY, Modifier.testTag("positive-infinity"))
                NovumAdjustmentBar(Float.NEGATIVE_INFINITY, Modifier.testTag("negative-infinity"))
                NovumAdjustmentBar(Float.NaN, Modifier.testTag("invalid-center"), centerFraction = Float.NaN)
            }
        }

        assertProgress("nan", current = 0.5f)
        assertProgress("positive-infinity", current = 1f)
        assertProgress("negative-infinity", current = 0f)
        assertProgress("invalid-center", current = 0.5f)
    }

    @Test
    fun disabledControlKeepsItsSizeAndDoesNotCreateASecondClickTarget() {
        composeRule.setContent {
            NovumAdjustmentBar(
                fraction = 0.5f,
                enabled = false,
                modifier = Modifier.testTag("disabled"),
            )
        }

        composeRule.onNodeWithTag("disabled")
            .assertWidthIsEqualTo(128.dp)
            .assertHeightIsEqualTo(18.dp)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
    }

    private fun assertProgress(tag: String, current: Float, steps: Int = 0) {
        composeRule.onNodeWithTag(tag).assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo(current, 0f..1f, steps),
            ),
        )
    }
}
