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
 * - 延迟变更运行时生效：每个输入批次都会重新读取 provider，滞留量变化时
 *   重建环形缓冲并保留最近的未输出样本，无需重配置播放器；
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

    private var configuredPcm = false
    private var sampleRate = 0
    private var frameBytes = 0
    private var currentDelayMs = 0L

    private var active = false
    private var negate = false

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
        val rate = inputAudioFormat.sampleRate
        configuredPcm = pcm16 && channels > 0 && rate > 0
        outputFormat = inputAudioFormat
        if (!configuredPcm) {
            active = false
            return outputFormat
        }
        sampleRate = rate
        frameBytes = channels * 2
        currentDelayMs = delayMsProvider().coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)
        applyDelay(currentDelayMs, preserveBuffered = false)
        return outputFormat
    }

    /** 按给定延迟重建环形缓冲；运行时调整时保留尚未吐出的样本。 */
    private fun applyDelay(delayMs: Long, preserveBuffered: Boolean) {
        val kept = if (preserveBuffered && pending.isNotEmpty() && buffered > 0) {
            ByteArray(buffered).also { out ->
                for (i in 0 until buffered) {
                    out[i] = pending[(readPos + i) % pending.size]
                }
            }
        } else {
            ByteArray(0)
        }
        negate = delayMs < 0
        val absFrames = Math.round(sampleRate / 1000f * Math.abs(delayMs))
        targetBytes = absFrames * frameBytes
        dropBytes = if (negate) targetBytes else 0
        active = absFrames > 0
        val capacity = maxOf(targetBytes, frameBytes)
        pending = ByteArray(capacity)
        val keep = minOf(kept.size, targetBytes)
        if (keep > 0) {
            // 只保留最近的样本，让新延迟尽快生效
            System.arraycopy(kept, kept.size - keep, pending, 0, keep)
            buffered = keep
            readPos = 0
            writePos = keep % capacity
        } else {
            buffered = 0
            readPos = 0
            writePos = 0
        }
    }

    /** 每批输入都读一次 provider——抽屉里的 ±100ms 才能立即生效。 */
    private fun refreshDelay() {
        if (!configuredPcm) return
        val next = delayMsProvider().coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)
        if (next == currentDelayMs) return
        currentDelayMs = next
        applyDelay(next, preserveBuffered = true)
    }

    override fun isActive(): Boolean = active

    override fun queueInput(inputBuffer: ByteBuffer) {
        refreshDelay()
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
        // 正延迟：入环滞留，超出目标滞留量的帧输出
        val input = ByteArray(inputBuffer.remaining())
        inputBuffer.get(input)
        var offset = 0
        while (offset < input.size) {
            val chunk = minOf(input.size - offset, pending.size - writePos)
            if (chunk <= 0) break
            System.arraycopy(input, offset, pending, writePos, chunk)
            writePos = (writePos + chunk) % pending.size
            buffered += chunk
            offset += chunk
        }
        val emitBytes = ((buffered - targetBytes) / frameBytes).coerceAtLeast(0) * frameBytes
        if (emitBytes <= 0) {
            outputBuffer = EMPTY
            return
        }
        val out = ByteArray(emitBytes)
        var copied = 0
        while (copied < emitBytes) {
            val chunk = minOf(emitBytes - copied, pending.size - readPos, buffered)
            if (chunk <= 0) break
            System.arraycopy(pending, readPos, out, copied, chunk)
            readPos = (readPos + chunk) % pending.size
            buffered -= chunk
            copied += chunk
        }
        emit(out)
    }

    /** Media3 约定：输出 buffer 必须 position=0、limit=有效字节数。 */
    private fun emit(bytes: ByteArray) {
        if (bytes.isEmpty()) {
            outputBuffer = EMPTY
            return
        }
        if (outputBuffer.capacity() < bytes.size) {
            outputBuffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        } else {
            outputBuffer.clear()
        }
        outputBuffer.put(bytes)
        outputBuffer.flip()
    }

    private fun passthrough(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) {
            outputBuffer = EMPTY
            return
        }
        if (outputBuffer.capacity() < size) {
            outputBuffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        } else {
            outputBuffer.clear()
        }
        outputBuffer.put(inputBuffer.duplicate())
        outputBuffer.flip()
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
        dropBytes = if (negate) targetBytes else 0
        outputBuffer = EMPTY
    }

    override fun reset() {
        flush()
        inputFormat = null
        configuredPcm = false
        active = false
    }

    private companion object {
        const val MIN_DELAY_MS = -2_000L
        const val MAX_DELAY_MS = 2_000L
        val EMPTY: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    }
}
