package com.vaca.callmate.features.outbound

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.ArrayDeque

/**
 * 对标 iOS `TTSCharacterStreamBuffer`：逐句入队、逐字显示；`markDone` 对应 `tts_stop`。
 * 参数与 `CallSessionController.ttsStreamBuffer` 一致：`baseSpeedMs=30`, `bufferSpeedK=10`。
 *
 * [delay] 仅用于**气泡打字动画**，与云端 TTS→BLE 音频转发无关；后台时动画节拍也会变慢，属预期。
 */
internal class TtsCharacterStreamBuffer(
    private val scope: CoroutineScope,
    private val baseSpeedMs: Double = 30.0,
    private val bufferSpeedK: Double = 10.0,
    private val onDisplayUpdate: (String) -> Unit,
    private val onFinished: (String) -> Unit,
) {
    private val buffer = ArrayDeque<Char>()
    private var streamJob: Job? = null
    private var isDone: Boolean = false
    private val displayedText = StringBuilder()

    fun append(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        for (ch in trimmed) buffer.addLast(ch)
        startStreamingIfNeeded()
    }

    fun markDone() {
        isDone = true
        if (streamJob == null && buffer.isEmpty()) {
            finalize()
        }
    }

    fun reset() {
        streamJob?.cancel()
        streamJob = null
        buffer.clear()
        displayedText.clear()
        isDone = false
        onDisplayUpdate("")
    }

    /** 新 `tts_start` 到来：上一轮流式未播完的文字先固化为一条消息 */
    fun flushAndReset() {
        streamJob?.cancel()
        streamJob = null
        val remaining = buffer.joinToString("")
        buffer.clear()
        val full = displayedText.toString() + remaining
        displayedText.clear()
        isDone = false
        onDisplayUpdate("")
        val cleaned = full.trim()
        if (cleaned.isNotEmpty()) {
            onFinished(cleaned)
        }
    }

    private fun startStreamingIfNeeded() {
        if (streamJob?.isActive == true) return
        streamJob = scope.launch {
            while (isActive) {
                if (buffer.isEmpty()) {
                    if (isDone) finalize()
                    break
                }
                val bufferLen = buffer.size
                val delayMs = baseSpeedMs / (1.0 + bufferLen / bufferSpeedK)
                delay(delayMs.toLong().coerceAtLeast(1L))
                if (!isActive) break
                if (buffer.isNotEmpty()) {
                    val ch = buffer.removeFirst()
                    displayedText.append(ch)
                    onDisplayUpdate(displayedText.toString())
                }
            }
            if (isActive) {
                streamJob = null
            }
        }
    }

    private fun finalize() {
        val finalText = displayedText.toString()
        displayedText.clear()
        isDone = false
        val cleaned = finalText.trim()
        if (cleaned.isNotEmpty()) {
            onFinished(cleaned)
        }
        onDisplayUpdate("")
    }
}
