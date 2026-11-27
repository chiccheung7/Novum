package com.novum.tv.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.novum.tv.testing.RepoPaths
import com.novum.tv.ui.component.LineOption
import com.novum.tv.ui.component.NovumConfirmDialog
import com.novum.tv.ui.component.NovumLinePickerDialog
import com.novum.tv.ui.component.NovumOptionListDialog
import com.novum.tv.ui.theme.NovumTheme
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/** Render the actual dialog windows over an offline bright background to inspect translucency. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w1280dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DialogMaterialPreviewTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test fun exitConfirmation() = render("exit-glass.png", "退出播放") {
        NovumConfirmDialog(
            title = "退出播放", message = "确定要退出当前播放吗？",
            confirmText = "退出播放", cancelText = "取消",
            onConfirm = {}, onCancel = {}, scrimAlpha = 0.45f,
        )
    }

    @Test fun versionSelection() = render("version-glass.png", "选择版本") {
        NovumOptionListDialog(
            title = "选择版本",
            options = listOf("星海来信 · S01E08 · 4K · HDR10 · 25 Mbps", "星海来信 · S01E08 · 1080p · SDR · 8 Mbps"),
            selectedIndex = 0, onSelect = {}, onDismiss = {},
        )
    }

    @Test fun lineSelection() = render("line-glass.png", "选择线路") {
        NovumLinePickerDialog(
            lines = listOf(LineOption("https://one.example.com", "线路一", "one.example.com", 45)),
            pinnedUrl = null, onSelectLine = {}, loading = false, failed = false, onDismiss = {},
        )
    }

    private fun render(filename: String, title: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.enableEdgeToEdge()
            WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                .hide(WindowInsetsCompat.Type.systemBars())
        }
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                NovumTheme {
                    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
                        listOf(Color(0xFF7587A2), Color(0xFFBE8A65), Color(0xFF335A70)),
                    )))
                    content()
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(800)
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText(title).onFirst().assertIsDisplayed()
        shadowOf(Looper.getMainLooper()).idleFor(32, TimeUnit.MILLISECONDS)
        val bitmap = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
        composeRule.activityRule.scenario.onActivity { activity ->
            val canvas = Canvas(bitmap)
            activity.window.decorView.draw(canvas)
            val window = requireNotNull(ShadowDialog.getLatestDialog().window)
            // System compositor dimming is outside View.draw; include its actual window value.
            canvas.drawARGB((255 * window.attributes.dimAmount).toInt(), 0, 0, 0)
            window.decorView.draw(canvas)
        }
        val output = RepoPaths.root.resolve("app/build/reports/ui-preview/$filename")
        val directory = requireNotNull(output.parentFile)
        assertTrue(directory.isDirectory || directory.mkdirs())
        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
