package com.novum.tv.ui.component

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import com.novum.tv.ui.theme.NovumTheme
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NovumImageTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun failedImageRequestDoesNotWriteTokenToLogcat() {
        val missingImage = File.createTempFile("novum-image-missing", ".png")
        check(missingImage.delete())
        ShadowLog.clear()
        composeRule.setContent {
            NovumTheme {
                NovumImage(
                    url = missingImage.toURI().toString() + "?api_key=image-test-token",
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                )
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            ShadowLog.getLogsForTag("NovumImage").isNotEmpty()
        }

        val logged = ShadowLog.getLogsForTag("NovumImage").joinToString { it.msg }
        assertFalse("图片失败日志不得泄漏URL里的访问令牌", logged.contains("image-test-token"))
        assertTrue("失败信息保留脱敏后的诊断参数", logged.contains("api_key=***"))
    }
}
