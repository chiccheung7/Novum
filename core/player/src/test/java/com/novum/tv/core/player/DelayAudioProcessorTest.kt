package com.novum.tv.core.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
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

    @Test
    fun negativeDelayDropsLeadingSamplesAndPassesRemainderImmediately() {
        var delayMs = -10L
        val processor = DelayAudioProcessor { delayMs }
        processor.configure(format)

        val tenMsBytes = 10 * 48 * FRAME_BYTES
        val input = ByteArray(2 * tenMsBytes) { (it % 251).toByte() }
        feed(processor, input)

        val out = processor.output
        assertEquals("负延迟应只丢开头 10ms，其余立即输出", tenMsBytes, out.remaining())
        val actual = ByteArray(out.remaining()).also { out.get(it) }
        assertArrayEquals(input.copyOfRange(tenMsBytes, input.size), actual)
    }

    @Test
    fun switchingFromPositiveToNegativeDiscardsBufferedSamples() {
        var delayMs = 10L
        val processor = DelayAudioProcessor { delayMs }
        processor.configure(format)
        val tenMsBytes = 10 * 48 * FRAME_BYTES

        feed(processor, ByteArray(2 * tenMsBytes))
        assertEquals(tenMsBytes, processor.output.remaining())

        delayMs = -10L
        feed(processor, ByteArray(tenMsBytes))
        assertEquals("旧缓冲必须丢弃，新输入开头 10ms 也必须丢弃", 0, processor.output.remaining())

        feed(processor, ByteArray(tenMsBytes))
        assertEquals("丢弃量用尽后应立即直通", tenMsBytes, processor.output.remaining())
    }

    @Test
    fun switchingFromNegativeToZeroResumesPassthrough() {
        var delayMs = -10L
        val processor = DelayAudioProcessor { delayMs }
        processor.configure(format)

        feed(processor, ByteArray(10 * 48 * FRAME_BYTES))
        assertEquals(0, processor.output.remaining())

        delayMs = 0L
        feed(processor, ByteArray(FRAME_BYTES))
        assertEquals("调回 0 后不应再有滞留或丢弃", FRAME_BYTES, processor.output.remaining())
    }

    @Test
    fun largeInputChunkDoesNotOverwriteBufferedSamples() {
        // 一批输入大于环形缓冲时不能覆盖尚未读出的旧样本：
        // 10ms 延迟 + 20ms 输入 → 应输出**前** 10ms（延迟队列头），不是后 10ms。
        val processor = DelayAudioProcessor { 10L }
        processor.configure(format)
        val tenMsBytes = 10 * 48 * FRAME_BYTES
        val input = ByteArray(2 * tenMsBytes) { (it % 251).toByte() }

        feed(processor, input)

        val out = processor.output
        assertEquals(tenMsBytes, out.remaining())
        val actual = ByteArray(out.remaining()).also { out.get(it) }
        assertArrayEquals(input.copyOfRange(0, tenMsBytes), actual)
    }

    @Test
    fun increasingNegativeDelayDropsOnlyTheDelta() {
        var delayMs = -10L
        val processor = DelayAudioProcessor { delayMs }
        processor.configure(format)
        val tenMsBytes = 10 * 48 * FRAME_BYTES

        feed(processor, ByteArray(tenMsBytes))
        assertEquals("首次 -10ms 应丢掉全部输入", 0, processor.output.remaining())

        // -10 → -20：只补丢新增的 10ms，20ms 输入应吐出后 10ms
        delayMs = -20L
        val input = ByteArray(2 * tenMsBytes) { (it % 251).toByte() }
        feed(processor, input)
        assertEquals("调大提前量只补差额，不能再次丢满目标值", tenMsBytes, processor.output.remaining())

        // -20 → -10：已丢样本无法恢复，但也不能再补丢
        delayMs = -10L
        feed(processor, ByteArray(tenMsBytes))
        assertEquals("调小提前量不应继续丢样本", tenMsBytes, processor.output.remaining())
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
