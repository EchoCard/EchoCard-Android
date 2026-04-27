package com.vaca.callmate.core.network

import android.util.Log
import com.vaca.callmate.BuildConfig
import com.vaca.callmate.data.AppPreferences
import com.vaca.callmate.data.repository.CallRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 与 iOS `ChatSummaryService` 一致：POST `{VOICE_API_BASE_URL}/api/chat/summaries`，
 * 轮询至多约 20s，将摘要写回本地通话记录。
 */
object ChatSummaryService {

    private const val TAG = "ChatSummary"
    private val apiBase get() = BuildConfig.VOICE_API_BASE_URL
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun pollAndUpdate(
        callId: String,
        sessionId: String,
        preferences: AppPreferences,
        repository: CallRepository,
    ) = withContext(Dispatchers.IO) {
        val sid = sessionId.trim()
        if (sid.isEmpty()) {
            Log.i(TAG, "skip poll: empty session_id")
            return@withContext
        }
        Log.i(TAG, "poll start session_id=$sid callId=$callId")
        val result = pollChatSummary(sid, preferences) ?: run {
            Log.i(TAG, "poll finished: no summary session_id=$sid")
            return@withContext
        }
        val title = result.title?.trim().orEmpty()
        if (title.isEmpty()) {
            Log.i(TAG, "poll finished: empty title session_id=$sid")
            return@withContext
        }
        repository.applyChatSummaryPollResult(
            id = callId,
            title = title,
            identity = result.identity?.trim()?.takeIf { it.isNotEmpty() },
            responseResult = result.result?.trim()?.takeIf { it.isNotEmpty() }
                ?: result.suggestion?.trim()?.takeIf { it.isNotEmpty() },
            backendSummary = result.summary?.trim()?.takeIf { it.isNotEmpty() },
            tokenCount = result.tokenCount,
            aiDuration = result.duration,
        )
        Log.i(
            TAG,
            "updated session_id=$sid title=$title identity=${result.identity} tokens=${result.tokenCount} duration=${result.duration}"
        )
    }

    private data class PollResult(
        val title: String?,
        val identity: String?,
        val result: String?,
        val summary: String?,
        val suggestion: String?,
        val tokenCount: Int?,
        val duration: Int?,
    )

    private suspend fun pollChatSummary(sessionId: String, preferences: AppPreferences): PollResult? {
        val maxWaitMs = 20_000L
        val intervalMs = 1_000L
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (System.currentTimeMillis() <= deadline) {
            fetchChatSummary(sessionId, useAuth = true, preferences)?.let { return it }
            fetchChatSummary(sessionId, useAuth = false, preferences)?.let { return it }
            Log.i(TAG, "poll retry session_id=$sessionId")
            delay(intervalMs)
        }
        return null
    }

    private suspend fun fetchChatSummary(
        sessionId: String,
        useAuth: Boolean,
        preferences: AppPreferences,
    ): PollResult? = withContext(Dispatchers.IO) {
        val url = "$apiBase/api/chat/summaries"
        val bodyJson = JSONObject().put("session_ids", JSONArray().put(sessionId))
        val body = bodyJson.toString().toRequestBody(jsonMedia)
        val req = Request.Builder()
            .url(url)
            .post(body)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .apply {
                if (useAuth) {
                    val token = BackendAuthManager.ensureToken(preferences)
                    if (!token.isNullOrBlank()) {
                        header("Authorization", "Bearer $token")
                    }
                }
            }
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                val code = resp.code
                val respData = resp.body?.bytes() ?: return@withContext null
                if (code !in 200..299) {
                    Log.w(TAG, "http=$code auth=${if (useAuth) "on" else "off"}")
                    return@withContext null
                }
                val raw = String(respData, Charsets.UTF_8)
                Log.d(TAG, "← raw (${if (useAuth) "auth" else "no-auth"}): $raw")
                val items = JSONArray(raw)
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    if (item.optString("session_id") != sessionId) continue
                    val cs = item.optJSONObject("chat_summary") ?: continue
                    val t = cs.optString("title", "").trim()
                    if (t.isEmpty()) continue
                    return@withContext PollResult(
                        title = t,
                        identity = cs.optString("identity", "").trim().takeIf { it.isNotEmpty() },
                        result = cs.optString("result", "").trim().takeIf { it.isNotEmpty() },
                        summary = cs.optString("summary", "").trim().takeIf { it.isNotEmpty() },
                        suggestion = cs.optString("suggestion", "").trim().takeIf { it.isNotEmpty() },
                        tokenCount = if (item.has("token_count") && !item.isNull("token_count")) {
                            item.optInt("token_count")
                        } else {
                            null
                        },
                        duration = if (item.has("duration") && !item.isNull("duration")) {
                            item.optInt("duration")
                        } else {
                            null
                        },
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetch failed auth=${if (useAuth) "on" else "off"}: ${e.message}")
        }
        null
    }
}
