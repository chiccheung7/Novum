package com.novum.tv.core.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PCM 延迟处理器测试。
 *
 * 锁住两个曾经静默失效的行为：
 * 1. 运行时改延迟必须立刻生效（原来只在 configure 时读一次 provider）；
 * 2. 输出 buffer 必须 position=0 / limit=有效字节数（原来延迟路径没有 flip）。
 */
@OptIn(androidx.media3.common.util.UnstableApi::class)
class DelayAudioProcessorTest {

    private val format = AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT)

    @Test
    fun increasingDelayAtRuntimeStartsBuffering() {
        var delayMs = 0L
        val processor = DelayAudioProcessor { delayMs }
        processor.configure(format)

        feed(processor, ByteArray(FRAME_BYTES))
        assertEquals("0 延迟时应直通", FRAME_BYTES, processor.output.remaining())

        delayMs = 100L
        feed(processor, ByteArray(100 * 48 * FRAME_BYTES))

        assertEquals(
            "调大延迟后必须开始滞留，而不是继续把全部样本直通出去",
            0,
            processor.output.remaining(),
        )
    }

    @Test
    fun delayedOutputIsReadyForReading() {
        val processor = DelayAudioProcessor { 10L }
        processor.configure(format)

        // 20ms 输入、10ms 延迟 → 应吐出 10ms
        feed(processor, ByteArray(20 * 48 * FRAME_BYTES))

        val out = processor.output
        assertEquals("输出 position 必须为 0", 0, out.position())
        assertEquals("输出 limit 必须为有效字节数", 10 * 48 * FRAME_BYTES, out.limit())
    }

    @Test
    fun decreasingDelayBackToZeroResumesPassthrough() {
        var delayMs = 100L
        val processor = DelayAudioProcessor { delayMs }
        processor.configure(format)

        feed(processor, ByteArray(100 * 48 * FRAME_BYTES))
        assertEquals(0, processor.output.remaining())

        delayMs = 0L
        feed(processor, ByteArray(FRAME_BYTES))

        assertEquals("调回 0 后应恢复直通", FRAME_BYTES, processor.output.remaining())
    }

    private fun feed(processor: DelayAudioProcessor, bytes: ByteArray) {
        val buffer = ByteBuffer.allocateDirect(bytes.size).order(java.nio.ByteOrder.nativeOrder())
        buffer.put(bytes)
        buffer.flip()
        processor.queueInput(buffer)
    }

    private companion object {
        const val FRAME_BYTES = 4 // 2 声道 × 16bit
    }
}
