package com.novum.tv.ui.screen.player

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NetworkSpeedMeterTest {
    private val source = Proxy.newProxyInstance(DataSource::class.java.classLoader, arrayOf(DataSource::class.java)) { _, _, _ -> null } as DataSource
    private val spec = DataSpec(Uri.parse("https://example.test/video"))

    @Test fun receivedBytesAreMeasuredAndIdleIntervalsReturnZero() {
        var now = 0L
        val meter = NetworkSpeedMeter { now }
        assertEquals(-1L, meter.sampleBitsPerSecond())
        meter.onTransferStart(source, spec, true)
        meter.onBytesTransferred(source, spec, true, 100_000)
        now = 500
        assertEquals(1_600_000L, meter.sampleBitsPerSecond())
        now = 1000
        assertEquals(0L, meter.sampleBitsPerSecond())
        meter.onBytesTransferred(source, spec, true, 400_000)
        now = 1500
        assertEquals(6_400_000L, meter.sampleBitsPerSecond())
    }

    @Test fun localReadsDoNotFabricateNetworkSpeed() {
        var now = 0L
        val meter = NetworkSpeedMeter { now }
        meter.onTransferStart(source, spec, false)
        meter.onBytesTransferred(source, spec, false, 999_999)
        now = 500
        assertEquals(-1L, meter.sampleBitsPerSecond())
    }
}
