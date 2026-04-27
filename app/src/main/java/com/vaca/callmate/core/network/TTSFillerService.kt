package com.vaca.callmate.core.network

import android.util.Log
import com.vaca.callmate.data.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 与 iOS `TTSFillerService.swift` 对齐：`POST /api/tts/fillers`，返回当前音色（预置或
 * 克隆）绑定的 6 条短 filler 音频（"嗯/哦/诶"）。401 时刷新 token 重试一次。
 */
data class TTSFillerItem(
    val fillerId: String,
    val text: String,
    val audioUrl: String,
    val audioFormat: String,
)

data class TTSFillerResponse(
    val voiceId: String,
    val voiceSource: String,
    val fillers: List<TTSFillerItem>,
)

object TTSFillerService {

    private const val TAG = "TTSFillers"
    private const val ENDPOINT = "/api/tts/fillers"

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun fetchFillers(
        voiceId: String,
        deviceId: String,
        preferences: AppPreferences,
    ): TTSFillerResponse = withContext(Dispatchers.IO) {
        val vId = voiceId.trim()
        val dId = deviceId.trim()
        require(vId.isNotEmpty()) { "voice_id must not be empty" }
        require(dId.isNotEmpty()) { "device_id must not be empty" }

        var token = BackendAuthManager.ensureToken(preferences)
            ?: throw IOException("TTSFillerService: missing JWT")
        if (!BackendAuthManager.looksLikeJWT(token)) {
            throw IOException("TTSFillerService: malformed JWT")
        }

        try {
            requestOnce(vId, dId, token)
        } catch (e: IOException) {
            if (e.message?.startsWith("HTTP 401") != true) throw e
            Log.i(TAG, "401 — refreshing JWT and retrying once")
            BackendAuthManager.invalidateCachedToken(preferences)
            token = BackendAuthManager.bootstrap(preferences)
                ?: throw IOException("TTSFillerService: token refresh failed")
            requestOnce(vId, dId, token)
        }
    }

    private fun requestOnce(voiceId: String, deviceId: String, bearer: String): TTSFillerResponse {
        val url = BackendAuthManager.API_BASE.trimEnd('/') + ENDPOINT
        val body = JSONObject()
            .put("voice_id", voiceId)
            .put("device_id", deviceId)
            .toString()
            .toRequestBody(jsonMedia)
        val req = Request.Builder()
            .url(url)
            .post(body)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $bearer")
            .build()
        Log.i(TAG, "POST $url voice_id=$voiceId device_id=$deviceId")
        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.w(TAG, "HTTP ${resp.code} body=${raw.take(200)}")
                throw IOException("HTTP ${resp.code}")
            }
            val envelope = JSONObject(raw)
            val data = envelope.optJSONObject("data")
                ?: throw IOException("TTSFillerService: missing data envelope")
            val voiceIdOut = data.optString("voice_id", "")
            val voiceSource = data.optString("voice_source", "")
            val fillersArr = data.optJSONArray("fillers")
                ?: throw IOException("TTSFillerService: missing fillers array")
            val items = ArrayList<TTSFillerItem>(fillersArr.length())
            for (i in 0 until fillersArr.length()) {
                val it = fillersArr.optJSONObject(i) ?: continue
                val fillerId = it.optString("filler_id", "")
                val audioUrl = it.optString("audio_url", "")
                if (fillerId.isEmpty() || audioUrl.isEmpty()) continue
                items.add(
                    TTSFillerItem(
                        fillerId = fillerId,
                        text = it.optString("text", ""),
                        audioUrl = audioUrl,
                        audioFormat = it.optString("audio_format", "").ifEmpty { "mp3" },
                    ),
                )
            }
            Log.i(TAG, "OK voice_id=$voiceIdOut source=$voiceSource count=${items.size}")
            return TTSFillerResponse(voiceIdOut, voiceSource, items)
        }
    }
}
