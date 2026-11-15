package com.novum.tv.core.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PCM 延迟处理器：把输入样本在内部环形
 * 缓冲里滞留 [delayMsProvider] 返回的毫秒数后再输出——正延迟 = 音频推迟；
 * 负延迟 = 丢弃等量样本（音频提前）。
 *
 * 实现口径：
 * - **逐帧处理**（帧 = 声道数 × 位深字节），避免撕裂样本；
 * - 延迟变更运行时生效：目标滞留帧数变化时输出自然多吐/多吞差额，无需
 *   重配置（seek/flush 时清零重来）；
 * - 仅 16-bit PCM 生效；直通 bitstream 由 AudioSink 直传不经处理器——
 *   直通模式音画延迟调不了是硬件语义（界面文案会标注）。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class DelayAudioProcessor(
    private val delayMsProvider: () -> Long,
) : AudioProcessor {

    private var inputFormat: AudioProcessor.AudioFormat? = null
    private var outputFormat: AudioProcessor.AudioFormat =
        AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT)

    private var active = false
    private var negate = false

    /** 帧字节数（声道 × 位深字节）。 */
    private var frameBytes = 0

    /** 目标滞留字节数。 */
    private var targetBytes = 0

    /** 负延迟模式下的剩余丢弃字节数。 */
    private var dropBytes = 0

    private var pending = ByteArray(0)
    private var readPos = 0
    private var writePos = 0
    private var buffered = 0

    private var outputBuffer: ByteBuffer = EMPTY
    private var inputEnded = false

    override fun configure(
        inputAudioFormat: AudioProcessor.AudioFormat,
    ): AudioProcessor.AudioFormat {
        val pcm16 = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT
        val channels = inputAudioFormat.channelCount
        val sampleRate = inputAudioFormat.sampleRate
        if (!pcm16 || channels == 0 || sampleRate == 0) {
            active = false
            outputFormat = inputAudioFormat
            return outputFormat
        }
        frameBytes = channels * 2
        val delayMs = delayMsProvider().coerceIn(-2_000L, 2_000L)
        negate = delayMs < 0
        val absFrames = Math.round(sampleRate / 1000f * Math.abs(delayMs))
        targetBytes = absFrames * frameBytes
        dropBytes = if (negate) targetBytes else 0
        active = absFrames > 0
        pending = ByteArray(targetBytes)
        readPos = 0
        writePos = 0
        buffered = 0
        outputFormat = inputAudioFormat
        return outputFormat
    }

    override fun isActive(): Boolean = active

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!active) {
            passthrough(inputBuffer)
            return
        }
        // 负延迟：丢帧（音频提前）
        if (dropBytes > 0) {
            val drop = minOf(inputBuffer.remaining(), dropBytes)
            inputBuffer.position(inputBuffer.position() + drop)
            dropBytes -= drop
        }
        // 正延迟：入环滞留，超出目标滞留的帧继续输出
        val input = ByteArray(inputBuffer.remaining())
        inputBuffer.get(input)
        var offset = 0
        while (offset < input.size) {
            val chunk = minOf(input.size - offset, pending.size - writePos)
            if (chunk == 0) break
            System.arraycopy(input, offset, pending, writePos, chunk)
            writePos = (writePos + chunk) % pending.size
            buffered += chunk
            offset += chunk
        }
        val canEmit = ((buffered - targetBytes) / frameBytes).coerceAtLeast(0)
        var toEmit = canEmit * frameBytes
        while (toEmit > 0) {
            val chunk = minOf(toEmit, pending.size - readPos, buffered)
            outputBuffer = growOutput(outputBuffer, chunk)
            val arr = outputBuffer.array()
            val pos = outputBuffer.position()
            System.arraycopy(pending, readPos, arr, pos, chunk)
            outputBuffer.position(pos + chunk)
            readPos = (readPos + chunk) % pending.size
            buffered -= chunk
            toEmit -= chunk
        }
    }

    private fun passthrough(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        outputBuffer = growOutput(outputBuffer, size)
        outputBuffer.put(inputBuffer.duplicate())
        outputBuffer.position(0)
        outputBuffer.limit(size)
    }

    private fun growOutput(current: ByteBuffer, size: Int): ByteBuffer {
        if (current.capacity() < size) {
            return ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        }
        current.clear()
        return current
    }

    override fun queueEndOfStream() {
        inputEnded = true
        // 播放结束：滞留样本不再有意义（延迟的尾部直接舍弃）
        buffered = 0
        readPos = 0
        writePos = 0
    }

    override fun getOutput(): ByteBuffer = outputBuffer

    override fun isEnded(): Boolean = buffered == 0 && inputEnded

    override fun flush() {
        buffered = 0
        readPos = 0
        writePos = 0
        dropBytes = 0
        outputBuffer.clear()
    }

    override fun reset() {
        flush()
        inputFormat = null
    }

    private companion object {
        val EMPTY: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    }
}
