package com.vaca.callmate.features.calls

/**
 * 与 Live WS 二进制 TTS 帧路径解耦：由 [LiveCallConversationRecorder] 在 Live 页挂载时注册。
 */
object LiveCallRecordingBridge {
    @Volatile
    var onTtsOpus: ((ByteArray) -> Unit)? = null
}
