package com.vaca.callmate.core.network

import com.vaca.callmate.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 与 iOS `SettingsVoiceRepository.swift` / `SettingsVoiceToneSheet` HTTP 对齐。
 */
object SettingsVoiceRepository {

    private val SUMMARY_BASE get() = BuildConfig.VOICE_API_BASE_URL

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun fetchVoices(token: String): List<TtsVoiceDto> = withContext(Dispatchers.IO) {
        val url = "$SUMMARY_BASE/api/tts/voices"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("voices http ${resp.code} $raw")
            }
            val arr = JSONArray(raw)
            val out = ArrayList<TtsVoiceDto>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(TtsVoiceDto.fromJson(o))
            }
            out
        }
    }

    /**
     * 与 iOS `OnboardingView.fetchVoiceCloneSpeakerId`：`POST /api/voice-clone/check-purchase`
     */
    suspend fun checkVoiceClonePurchase(deviceId: String, token: String): VoiceCloneCheckPurchaseData? =
        withContext(Dispatchers.IO) {
            val url = "$SUMMARY_BASE/api/voice-clone/check-purchase"
            val body = JSONObject().put("device_id", deviceId).toString()
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@withContext null
                try {
                    val root = JSONObject(raw)
                    val data = root.optJSONObject("data") ?: return@withContext null
                    VoiceCloneCheckPurchaseData(
                        speakerId = data.optString("speaker_id", ""),
                        state = data.optString("state", "").takeIf { it.isNotBlank() },
                        isNew = if (data.has("is_new")) data.optBoolean("is_new") else null
                    )
                } catch (_: Exception) {
                    null
                }
            }
        }

    suspend fun fetchBoundCloneVoice(deviceId: String, token: String): DeviceVoiceCloneResponse =
        withContext(Dispatchers.IO) {
            val url = "$SUMMARY_BASE/api/device/$deviceId/voice-clone"
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("voice-clone http ${resp.code} $raw")
                }
                DeviceVoiceCloneResponse.fromJson(JSONObject(raw))
            }
        }

    suspend fun trainClone(
        token: String,
        deviceId: String,
        speakerId: String,
        text: String,
        audioFile: File
    ): VoiceCloneTrainResponse = withContext(Dispatchers.IO) {
        val url = "$SUMMARY_BASE/api/voice-clone/train"
        val audioBody = audioFile.asRequestBody("audio/mp4".toMediaType())
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("device_id", deviceId)
            .addFormDataPart("speaker_id", speakerId)
            .addFormDataPart("text", text)
            .addFormDataPart("audio", "voice_clone.m4a", audioBody)
            .build()
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()
        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("train http ${resp.code} $raw")
            }
            VoiceCloneTrainResponse.fromJson(JSONObject(raw))
        }
    }

    suspend fun queryCloneStatus(
        token: String,
        deviceId: String,
        speakerId: String
    ): VoiceCloneStatusResponse = withContext(Dispatchers.IO) {
        val url = "$SUMMARY_BASE/api/voice-clone/status?device_id=${encode(deviceId)}&speaker_id=${encode(speakerId)}"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("status http ${resp.code} $raw")
            }
            VoiceCloneStatusResponse.fromJson(JSONObject(raw))
        }
    }

    suspend fun pollCloneStatus(
        token: String,
        deviceId: String,
        speakerId: String,
        onProgress: (attempt: Int, max: Int) -> Unit = { _, _ -> }
    ): VoiceCloneStatusResponse {
        val maxAttempts = 20
        repeat(maxAttempts) { attempt ->
            onProgress(attempt + 1, maxAttempts)
            val status = queryCloneStatus(token, deviceId, speakerId)
            val state = (status.state ?: "").lowercase()
            if (state == "success" || state == "failed" || state == "expired") {
                return status
            }
            delay(2000)
        }
        return queryCloneStatus(token, deviceId, speakerId)
    }

    private fun encode(s: String): String = java.net.URLEncoder.encode(s, Charsets.UTF_8.name())
}
