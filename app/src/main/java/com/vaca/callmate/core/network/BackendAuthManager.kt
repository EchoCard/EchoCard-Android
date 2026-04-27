package com.vaca.callmate.core.network

import android.util.Base64
import android.util.Log
import com.vaca.callmate.BuildConfig
import com.vaca.callmate.data.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 与 iOS `BackendAuthManager` / `BackendAuthHTTPClient` 对齐：注册 + 拉 JWT + 过期检测 + 401 重试。
 */
object BackendAuthManager {

    private const val TAG = "BackendAuth"
    val API_BASE get() = BuildConfig.API_BASE_URL

    /** 与 iOS `BackendAuthManager.swift` 测试凭据一致 */
    private const val HARDCODED_PID_ID = "31ead3ac-ede8-4e81-b405-12be65f7b7e8"
    private const val HARDCODED_APP_CODE = "3c4ff237-7501-f967-f21a-34a43bd650f5"

    /** 与 iOS `jwtExpiryRefreshSkew` 一致：过期前 5 分钟即视为需刷新 */
    private const val JWT_EXPIRY_REFRESH_SKEW_SEC = 5 * 60

    private val bootstrapMutex = Mutex()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    fun looksLikeJWT(token: String): Boolean {
        val t = token.trim()
        return t.split(".").size >= 3 && t.length > 20
    }

    /**
     * 与 iOS `jwtExpirationDate` 对齐：解析 JWT payload 中的 `exp` 声明（秒级 epoch）。
     * 返回 null 表示无 `exp` 或解析失败。
     */
    fun jwtExpirationEpochSec(token: String): Long? {
        val parts = token.trim().split(".")
        if (parts.size < 2) return null
        var payload = parts[1]
            .replace('-', '+')
            .replace('_', '/')
        val pad = (4 - payload.length % 4) % 4
        if (pad > 0) payload += "=".repeat(pad)
        val decoded = try {
            Base64.decode(payload, Base64.DEFAULT)
        } catch (_: Exception) {
            return null
        }
        val json = try {
            JSONObject(String(decoded, Charsets.UTF_8))
        } catch (_: Exception) {
            return null
        }
        return when {
            json.has("exp") -> json.optLong("exp", 0).takeIf { it > 0 }
            else -> null
        }
    }

    /**
     * 与 iOS `ensureToken()` 对齐：有缓存且未过期（含 5 分钟 skew）直接返回；否则 bootstrap 拿新 token。
     */
    suspend fun ensureToken(preferences: AppPreferences): String? {
        val cached = preferences.jwtTokenFlow.first()
        if (cached != null && looksLikeJWT(cached)) {
            val exp = jwtExpirationEpochSec(cached)
            if (exp != null) {
                val nowSec = System.currentTimeMillis() / 1000
                if (exp - nowSec > JWT_EXPIRY_REFRESH_SKEW_SEC) {
                    return cached
                }
                Log.i(TAG, "JWT exp=$exp nowSec=$nowSec (expired or within ${JWT_EXPIRY_REFRESH_SKEW_SEC}s) — refreshing")
                invalidateCachedToken(preferences)
                return bootstrap(preferences)
            }
            return cached
        }
        return bootstrap(preferences)
    }

    /** 与 iOS `invalidateCachedToken()` 对齐 */
    suspend fun invalidateCachedToken(preferences: AppPreferences) {
        preferences.setJwtToken(null)
        Log.i(TAG, "invalidated cached JWT")
    }

    suspend fun bootstrap(preferences: AppPreferences): String? = bootstrapMutex.withLock {
        withContext(Dispatchers.IO) {
            bootstrapInner(preferences)
        }
    }

    private suspend fun bootstrapInner(preferences: AppPreferences): String? {
        val alreadyCached = preferences.jwtTokenFlow.first()
        if (alreadyCached != null && looksLikeJWT(alreadyCached)) {
            val exp = jwtExpirationEpochSec(alreadyCached)
            if (exp != null && exp - System.currentTimeMillis() / 1000 > JWT_EXPIRY_REFRESH_SKEW_SEC) {
                return alreadyCached
            }
        }

        seedIfNeeded(preferences)
        val pid = preferences.pidIdString().ifBlank { HARDCODED_PID_ID }
        val appCode = preferences.appCodeString().ifBlank { HARDCODED_APP_CODE }
        val hasRegistered = preferences.hasRegisteredFlow.first()
        if (!hasRegistered) {
            try {
                register(pid, appCode)
                preferences.setHasRegistered(true)
            } catch (e: Exception) {
                Log.w(TAG, "register failed: ${e.message}")
            }
        }

        return try {
            val (token, resolved) = getToken(appCode)
            preferences.setAppCode32(resolved)
            preferences.setJwtToken(token)
            Log.i(TAG, "bootstrap OK token.len=${token.length} exp=${jwtExpirationEpochSec(token)}")
            token
        } catch (e: Exception) {
            Log.w(TAG, "get_token failed: ${e.message}")
            preferences.jwtTokenFlow.first()?.takeIf { looksLikeJWT(it) }
        }
    }

    private suspend fun seedIfNeeded(preferences: AppPreferences) {
        if (preferences.pidIdString().isBlank()) preferences.setPidId(HARDCODED_PID_ID)
        if (preferences.appCodeString().isBlank()) preferences.setAppCode32(HARDCODED_APP_CODE)
    }

    /**
     * 与 iOS `reportDevice` 对齐：401 时 invalidate + re-bootstrap + 重试一次。
     */
    suspend fun reportDevice(
        preferences: AppPreferences,
        deviceId: String,
        bluetoothId: String,
        token: String?
    ): Unit = withContext(Dispatchers.IO) {
        val appCode = preferences.appCodeFlow.first().ifBlank { HARDCODED_APP_CODE }
        val url = "$API_BASE/api/device/report"

        fun buildBody() = JSONObject()
            .put("device_id", deviceId)
            .put("app_code", appCode)
            .put("bluetooth_id", bluetoothId)

        fun req(bearer: String?) = Request.Builder()
            .url(url)
            .post(buildBody().toString().toRequestBody(jsonMedia))
            .header("Accept", "application/json")
            .apply { if (bearer != null && looksLikeJWT(bearer)) header("Authorization", "Bearer $bearer") }
            .build()

        var bearer = token
        if (bearer == null || !looksLikeJWT(bearer)) {
            bearer = ensureToken(preferences)
        }

        if (bearer != null && looksLikeJWT(bearer)) {
            try {
                client.newCall(req(bearer)).execute().use { resp ->
                    if (resp.isSuccessful) {
                        Log.i(TAG, "reportDevice OK device_id=$deviceId")
                        return@withContext
                    }
                    if (resp.code == 401) {
                        Log.w(TAG, "reportDevice 401 — refreshing JWT and retrying once")
                        invalidateCachedToken(preferences)
                        val fresh = bootstrap(preferences)
                        if (fresh != null && looksLikeJWT(fresh)) {
                            client.newCall(req(fresh)).execute().use { retry ->
                                if (retry.isSuccessful) {
                                    Log.i(TAG, "reportDevice OK after refresh device_id=$deviceId")
                                    return@withContext
                                }
                                Log.w(TAG, "reportDevice retry failed HTTP ${retry.code}")
                            }
                        }
                    }
                    Log.w(TAG, "reportDevice failed HTTP ${resp.code}, retrying without auth")
                }
            } catch (e: Exception) {
                Log.w(TAG, "reportDevice auth attempt failed: ${e.message}, retrying without auth")
            }
        }

        try {
            client.newCall(req(null)).execute().use { resp ->
                if (resp.isSuccessful) Log.i(TAG, "reportDevice OK(no-auth) device_id=$deviceId")
                else Log.w(TAG, "reportDevice no-auth failed HTTP ${resp.code}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "reportDevice: ${e.message}")
        }
    }

    private fun register(pidId: String, appCode: String) {
        val url = "$API_BASE/api/app/register"
        val body = JSONObject()
            .put("pid_id", pidId)
            .put("app_code", appCode)
            .toString()
            .toRequestBody(jsonMedia)
        val req = Request.Builder().url(url).post(body)
            .header("Accept", "application/json")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("register http ${resp.code}")
            }
        }
    }

    private fun getToken(appCode: String): Pair<String, String> {
        fun postWithCode(code: String): Pair<String, String> {
            val url = "$API_BASE/api/app/token"
            val body = JSONObject().put("app_code", code).toString().toRequestBody(jsonMedia)
            val req = Request.Builder().url(url).post(body)
                .header("Accept", "application/json")
                .build()
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("token http ${resp.code} $raw")
                }
                val json = JSONObject(raw)
                val token = json.getJSONObject("data").getString("token")
                return Pair(token, code)
            }
        }
        return try {
            postWithCode(appCode)
        } catch (e: Exception) {
            if (appCode.length == 32 && !appCode.contains("-")) {
                val uuid = toUUIDFormat(appCode)
                postWithCode(uuid)
            } else throw e
        }
    }

    private fun toUUIDFormat(hex: String): String {
        if (hex.length != 32) return hex
        val s = hex.lowercase()
        return "${s.substring(0, 8)}-${s.substring(8, 12)}-${s.substring(12, 16)}-${s.substring(16, 20)}-${s.substring(20, 32)}"
    }

    /**
     * 与 iOS `syncPushRegistration` 对齐：`POST /api/app/register` 带 `os_type` + `apns_token`（Android 填 FCM token 时同字段）。
     * 无 token 时仍上报 `os_type=2`，便于后端登记平台。
     */
    suspend fun syncPushRegistration(
        preferences: AppPreferences,
        fcmToken: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            seedIfNeeded(preferences)
            val pid = preferences.pidIdString().ifBlank { HARDCODED_PID_ID }
            val appCode = preferences.appCodeFlow.first().ifBlank { HARDCODED_APP_CODE }
            val clipped = fcmToken?.trim()?.takeIf { it.isNotEmpty() }?.let { t ->
                if (t.length > 64) t.take(64) else t
            }
            val body = JSONObject()
                .put("pid_id", pid)
                .put("app_code", appCode)
                .put("os_type", 2)
            if (clipped != null) body.put("apns_token", clipped)
            val url = "$API_BASE/api/app/register"
            val req = Request.Builder()
                .url(url)
                .post(body.toString().toRequestBody(jsonMedia))
                .header("Accept", "application/json")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    Log.i(TAG, "syncPushRegistration OK fcm.len=${clipped?.length ?: 0}")
                    true
                } else {
                    Log.w(TAG, "syncPushRegistration HTTP ${resp.code}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "syncPushRegistration FAILED: ${e.message}")
            false
        }
    }

    /**
     * 与 iOS `postControlCallback` 对齐：查询类指令经 `POST /api/callback` 回传结果。
     */
    suspend fun postControlCallback(
        preferences: AppPreferences,
        requestId: String,
        data: JSONObject
    ): Unit = withContext(Dispatchers.IO) {
        if (requestId.isEmpty()) {
            Log.w(TAG, "postControlCallback skipped: empty request_id")
            return@withContext
        }
        val token = ensureToken(preferences) ?: run {
            Log.w(TAG, "postControlCallback skipped: no JWT")
            return@withContext
        }
        if (!looksLikeJWT(token)) {
            Log.w(TAG, "postControlCallback skipped: invalid JWT")
            return@withContext
        }
        val url = "$API_BASE/api/callback"
        val body = JSONObject()
            .put("request_id", requestId)
            .put("data", data)
        val bodyStr = body.toString()
        val req = Request.Builder()
            .url(url)
            .post(bodyStr.toRequestBody(jsonMedia))
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $token")
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    Log.i(TAG, "postControlCallback OK request_id=$requestId")
                } else {
                    val raw = resp.body?.string().orEmpty()
                    Log.w(TAG, "postControlCallback HTTP ${resp.code} request_id=$requestId body=$raw")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "postControlCallback error: ${e.message}")
        }
    }
}
