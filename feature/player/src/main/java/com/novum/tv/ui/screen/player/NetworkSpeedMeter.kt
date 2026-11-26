package com.novum.tv.ui.screen.player

import android.os.SystemClock
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/** Counts received network bytes instead of inferring download speed from the encoded video bitrate. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class NetworkSpeedMeter(private val clockMs: () -> Long = SystemClock::elapsedRealtime) : TransferListener {
    private var sampleStartedMs: Long? = null
    private var pendingBytes = 0L
    private var receivedAnyBytes = false

    override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

    @Synchronized
    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        if (isNetwork && sampleStartedMs == null) sampleStartedMs = clockMs()
    }

    @Synchronized
    override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
        if (!isNetwork || bytesTransferred <= 0) return
        if (sampleStartedMs == null) sampleStartedMs = clockMs()
        receivedAnyBytes = true
        pendingBytes += bytesTransferred
    }

    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

    /** -1 means no sample yet; zero means the current sampling interval downloaded no data. */
    @Synchronized
    fun sampleBitsPerSecond(): Long {
        val now = clockMs()
        val started = sampleStartedMs
        sampleStartedMs = now
        if (!receivedAnyBytes || started == null) return -1L
        val elapsed = (now - started).coerceAtLeast(1L)
        val speed = pendingBytes * 8_000L / elapsed
        pendingBytes = 0L
        return speed
    }
}
