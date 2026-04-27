package com.vaca.callmate.core.network

/** 与 iOS `TTSVoice` / `SettingsVoiceModels.swift` 对齐 */
data class TtsVoiceDto(
    val id: String,
    val name: String,
    val demoURL: String?
) {
    companion object {
        fun fromJson(o: org.json.JSONObject): TtsVoiceDto {
            return TtsVoiceDto(
                id = o.optString("voice_id", ""),
                name = o.optString("voice_name", ""),
                demoURL = o.optString("voice_demo_url", "").takeIf { it.isNotBlank() }
            )
        }
    }
}

/** `POST /api/voice-clone/check-purchase` 的 data 段 */
data class VoiceCloneCheckPurchaseData(
    val speakerId: String,
    val state: String?,
    val isNew: Boolean?
)

data class VoiceCloneInfoDto(
    val speakerId: String,
    val state: String?,
    val trainFailedReason: String?,
    val demoAudio: String?,
    val expireTime: String?,
    val canTrain: Boolean?
)

data class DeviceVoiceCloneResponse(
    val voiceClone: VoiceCloneInfoDto?
) {
    companion object {
        fun fromJson(root: org.json.JSONObject): DeviceVoiceCloneResponse {
            val data = root.optJSONObject("data") ?: return DeviceVoiceCloneResponse(null)
            val vc = data.optJSONObject("voice_clone") ?: return DeviceVoiceCloneResponse(null)
            return DeviceVoiceCloneResponse(
                voiceClone = VoiceCloneInfoDto(
                    speakerId = vc.optString("speaker_id", ""),
                    state = vc.optString("state", "").takeIf { it.isNotBlank() },
                    trainFailedReason = vc.optString("train_failed_reason", "").takeIf { it.isNotBlank() },
                    demoAudio = vc.optString("demo_audio", "").takeIf { it.isNotBlank() },
                    expireTime = vc.optString("expire_time", "").takeIf { it.isNotBlank() },
                    canTrain = if (vc.has("can_train")) vc.optBoolean("can_train") else null
                )
            )
        }
    }
}

data class VoiceCloneTrainResponse(
    val speakerId: String,
    val state: String?
) {
    companion object {
        fun fromJson(root: org.json.JSONObject): VoiceCloneTrainResponse {
            val data = root.getJSONObject("data")
            return VoiceCloneTrainResponse(
                speakerId = data.optString("speaker_id", ""),
                state = data.optString("state", "").takeIf { it.isNotBlank() }
            )
        }
    }
}

data class VoiceCloneStatusResponse(
    val speakerId: String,
    val state: String?,
    val trainFailedReason: String?,
    val demoAudio: String?,
    val canTrain: Boolean?
) {
    companion object {
        fun fromJson(root: org.json.JSONObject): VoiceCloneStatusResponse {
            val data = root.getJSONObject("data")
            return VoiceCloneStatusResponse(
                speakerId = data.optString("speaker_id", ""),
                state = data.optString("state", "").takeIf { it.isNotBlank() },
                trainFailedReason = data.optString("train_failed_reason", "").takeIf { it.isNotBlank() },
                demoAudio = data.optString("demo_audio", "").takeIf { it.isNotBlank() },
                canTrain = if (data.has("can_train")) data.optBoolean("can_train") else null
            )
        }
    }
}
