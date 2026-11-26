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
 * - 延迟变更运行时生效：每个输入批次都会重新读取 provider。正延迟变化时
 *   重建环形缓冲并保留最近的未输出样本；切到负延迟时丢弃已缓冲样本，
 *   随后只丢弃输入流开头的等量样本并立即输出其余样本，无需重配置播放器；
 * - **负延迟是单向调节**：已丢弃的样本无法恢复。从 -100ms 调到 -50ms 时不再
 *   补丢，听感会保持 -100ms 直到下一次 seek/flush（不插入静音制造更长空档）；
 * - 仅 16-bit PCM 生效；直通 bitstream 由 AudioSink 直传不经处理器——
 *   直通模式音画延迟调不了是硬件语义（界面文案会标注）。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class DelayAudioProcessor(
    private val delayMsProvider: () -> Long,
) : AudioProcessor {

    private var pendingInputFormat = AudioProcessor.AudioFormat.NOT_SET

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

    /**
     * 当前负延迟档已丢弃的字节数。
     *
     * 运行时把 -100ms 调到 -200ms 只需补丢 100ms；没有这个累计值，每次
     * 调档都会从当前输入再丢一整个目标量，实际提前量越调越大。
     */
    private var negativeDropApplied = 0

    private var pending = ByteArray(0)
    private var readPos = 0
    private var writePos = 0
    private var buffered = 0

    private var outputStorage: ByteBuffer = EMPTY
    private var outputBuffer: ByteBuffer = EMPTY
    private var inputEnded = false

    override fun configure(
        inputAudioFormat: AudioProcessor.AudioFormat,
    ): AudioProcessor.AudioFormat {
        // Media3 先 configure 新格式，再排空旧格式，直到 flush 才切换。
        pendingInputFormat = inputAudioFormat.takeIf {
            it.encoding == C.ENCODING_PCM_16BIT && it.channelCount > 0 && it.sampleRate > 0
        } ?: AudioProcessor.AudioFormat.NOT_SET
        return pendingInputFormat
    }

    /** 按给定延迟重建环形缓冲；正延迟调整时保留尚未吐出的样本，切负延迟一律丢弃。 */
    private fun applyDelay(delayMs: Long, preserveBuffered: Boolean) {
        negate = delayMs < 0
        val kept = if (!negate && preserveBuffered && pending.isNotEmpty() && buffered > 0) {
            ByteArray(buffered).also { out ->
                for (i in 0 until buffered) {
                    out[i] = pending[(readPos + i) % pending.size]
                }
            }
        } else {
            ByteArray(0)
        }
        val absFrames = Math.round(sampleRate / 1000f * Math.abs(delayMs))
        targetBytes = absFrames * frameBytes
        // 负延迟只补足目标与已丢量之间的差额；切换回非负档时重置累计
        dropBytes = if (negate) (targetBytes - negativeDropApplied).coerceAtLeast(0) else 0
        if (!negate) negativeDropApplied = 0
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

    // 0ms 也要留在 PCM 链中，否则 AudioSink 会移除处理器，后续调档收不到输入。
    override fun isActive(): Boolean = pendingInputFormat != AudioProcessor.AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        refreshDelay()
        if (!active) {
            passthrough(inputBuffer)
            return
        }
        if (negate) {
            // 负延迟 = 从输入流开头丢弃等量样本后立刻输出，不能再入环滞留，
            // 否则稳定后的净效果会变成正延迟。
            if (dropBytes > 0) {
                val drop = minOf(inputBuffer.remaining(), dropBytes)
                inputBuffer.position(inputBuffer.position() + drop)
                dropBytes -= drop
                negativeDropApplied += drop
            }
            val advanced = ByteArray(inputBuffer.remaining())
            inputBuffer.get(advanced)
            emit(advanced)
            return
        }
        // 正延迟：入环滞留，超出目标滞留量的帧输出。
        // 先扩容：一批输入可能大于当前环容量，直接写会把尚未读出的旧样本盖掉
        // （输出变成最新样本，听感是周期性跳帧/重复）。
        val input = ByteArray(inputBuffer.remaining())
        inputBuffer.get(input)
        ensureCapacity(buffered + input.size)
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

    /** 确保环形缓冲能容纳 [required] 字节；扩容时按逻辑顺序搬运未读样本。 */
    private fun ensureCapacity(required: Int) {
        if (pending.size >= required) return
        var newSize = maxOf(pending.size, frameBytes)
        while (newSize < required) newSize *= 2
        val grown = ByteArray(newSize)
        for (i in 0 until buffered) {
            grown[i] = pending[(readPos + i) % pending.size]
        }
        pending = grown
        readPos = 0
        writePos = buffered % newSize
    }

    /** Media3 约定：输出 buffer 必须 position=0、limit=有效字节数。 */
    private fun emit(bytes: ByteArray) {
        if (bytes.isEmpty()) {
            outputBuffer = EMPTY
            return
        }
        replaceOutputBuffer(bytes.size)
        outputBuffer.put(bytes)
        outputBuffer.flip()
    }

    private fun passthrough(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) {
            outputBuffer = EMPTY
            return
        }
        replaceOutputBuffer(size)
        // 输入归调用方所有；复制到可复用 direct buffer，并推进原输入 position。
        outputBuffer.put(inputBuffer)
        outputBuffer.flip()
    }

    private fun replaceOutputBuffer(size: Int) {
        if (outputStorage.capacity() < size) {
            outputStorage = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        } else {
            outputStorage.clear()
        }
        outputBuffer = outputStorage
    }

    override fun queueEndOfStream() {
        inputEnded = true
        // 播放结束：滞留样本不再有意义（延迟的尾部直接舍弃）
        buffered = 0
        readPos = 0
        writePos = 0
    }

    override fun getOutput(): ByteBuffer = outputBuffer.also { outputBuffer = EMPTY }

    override fun isEnded(): Boolean = buffered == 0 && inputEnded && !outputBuffer.hasRemaining()

    override fun flush() {
        buffered = 0
        readPos = 0
        writePos = 0
        inputEnded = false
        outputBuffer = EMPTY
        configuredPcm = isActive()
        negativeDropApplied = 0
        if (configuredPcm) {
            sampleRate = pendingInputFormat.sampleRate
            frameBytes = pendingInputFormat.channelCount * 2
            currentDelayMs = delayMsProvider().coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)
            applyDelay(currentDelayMs, preserveBuffered = false)
        } else {
            active = false
            pending = ByteArray(0)
        }
    }

    override fun reset() {
        pendingInputFormat = AudioProcessor.AudioFormat.NOT_SET
        flush()
        outputStorage = EMPTY
    }

    private companion object {
        const val MIN_DELAY_MS = -2_000L
        const val MAX_DELAY_MS = 2_000L
        val EMPTY: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    }
}
