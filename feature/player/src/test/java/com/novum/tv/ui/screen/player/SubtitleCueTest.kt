package com.novum.tv.ui.screen.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.View
import androidx.media3.common.text.Cue
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SubtitleCueTest {
    @Test fun bitmapCueHasAnActualDrawingPath() {
        val graphic = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        val cue = Cue.Builder().setBitmap(graphic)
            .setPosition(0.5f).setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
            .setLine(0.9f, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_END)
            .setSize(0.2f).setBitmapHeight(0.1f).build()
        assertFalse(usesDefaultSubtitlePosition(cue))
        val view = SubtitleView(ApplicationProvider.getApplicationContext<Context>())
        configureSubtitleView(view, listOf(cue), CaptionStyleCompat.DEFAULT, 24f, 0f)
        view.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 1280, 720)
        val frame = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(frame))
        assertTrue("Bitmap subtitle must produce pixels on the output frame", frame.getPixel(640, 600) == Color.MAGENTA)
        assertTrue(frame.getPixel(100, 100) != Color.MAGENTA)
    }

    @Test fun delayedCuesKeepBitmapPositionAndStyledTextWithoutFlattening() {
        val styled = SpannableString("styled text").apply { setSpan(ForegroundColorSpan(Color.RED), 0, 6, 0) }
        val cue = Cue.Builder().setText(styled).setPosition(0.25f).setLine(0.2f, Cue.LINE_TYPE_FRACTION).build()
        var delivered: List<Cue> = emptyList()
        val scheduler = CueDelayScheduler(Handler(Looper.getMainLooper())) { delivered = it }
        val cues = listOf(cue)
        scheduler.submit(cues, 100L)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        assertSame(cues, delivered)
        assertSame(cue, delivered.single())
        assertFalse(usesDefaultSubtitlePosition(cue))
        assertTrue(usesDefaultSubtitlePosition(Cue.Builder().setText("dialogue").build()))
        scheduler.submit(emptyList(), 100L)
        scheduler.cancel()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertSame(cues, delivered)
    }
}
