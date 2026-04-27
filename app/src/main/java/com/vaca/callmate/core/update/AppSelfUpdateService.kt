package com.vaca.callmate.core.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.vaca.callmate.core.firmware.FirmwareUpdateService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * 与 [FirmwareUpdateService] 同基址，复用 `/api/firmware/latest` + download；
 * `device=callmate-android`，`version` 为 [versionCode] 的十进制字符串。
 */
object AppSelfUpdateService {

    private const val TAG = "AppSelfUpdate"

    const val ANDROID_DEVICE_ID = "callmate-android"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val mutex = Mutex()

    data class RemoteMeta(
        val device: String,
        val version: String,
        val versionCodeLong: Long,
        val size: Int,
        val sha256: String,
        val relativeUrl: String,
    )

    private val _remoteMeta = MutableStateFlow<RemoteMeta?>(null)
    val remoteMeta: StateFlow<RemoteMeta?> = _remoteMeta.asStateFlow()

    private val _isChecking = MutableStateFlow(false)
    val isChecking: StateFlow<Boolean> = _isChecking.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    fun parseVersionCode(versionStr: String): Long? {
        val s = versionStr.trim()
        if (s.isEmpty()) return null
        return s.toLongOrNull()
    }

    private fun buildAbsoluteUrl(base: String, relative: String): String {
        val b = base.trimEnd('/')
        return if (relative.startsWith("http")) relative else b + relative
    }

    suspend fun checkForUpdate(
        localVersionCode: Long,
        serverBaseUrl: String = FirmwareUpdateService.DEFAULT_SERVER_BASE_URL,
    ) {
        mutex.withLock {
            if (_isChecking.value) return@withLock
            _isChecking.value = true
            _lastError.value = null
            try {
                val base = serverBaseUrl.trimEnd('/')
                val url = "$base/api/firmware/latest?device=$ANDROID_DEVICE_ID"
                Log.i(TAG, "checkForUpdate url=$url")
                withContext(Dispatchers.IO) {
                    val req = Request.Builder().url(url).get().build()
                    client.newCall(req).execute().use { resp ->
                        val code = resp.code
                        val body = resp.body?.string().orEmpty()
                        if (code == 404) {
                            _remoteMeta.value = null
                            return@withContext
                        }
                        if (code != 200) {
                            Log.w(TAG, "HTTP $code body=${body.take(200)}")
                            _lastError.value = "http_$code"
                            _remoteMeta.value = null
                            return@withContext
                        }
                        val json = JSONObject(body)
                        val verStr = json.getString("version")
                        val vc = parseVersionCode(verStr) ?: run {
                            _lastError.value = "bad_version"
                            _remoteMeta.value = null
                            return@withContext
                        }
                        val meta = RemoteMeta(
                            device = json.getString("device"),
                            version = verStr,
                            versionCodeLong = vc,
                            size = json.getInt("size"),
                            sha256 = json.getString("sha256"),
                            relativeUrl = json.getString("url"),
                        )
                        _remoteMeta.value = meta
                        Log.i(TAG, "remote versionCode=$vc local=$localVersionCode")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "checkForUpdate failed", e)
                _lastError.value = e.message
                _remoteMeta.value = null
            } finally {
                _isChecking.value = false
            }
        }
    }

    fun hasNewerRemote(localVersionCode: Long): Boolean {
        val remote = _remoteMeta.value ?: return false
        return remote.versionCodeLong > localVersionCode
    }

    suspend fun downloadApkToCache(
        context: Context,
        serverBaseUrl: String = FirmwareUpdateService.DEFAULT_SERVER_BASE_URL,
    ): File {
        val meta = _remoteMeta.value ?: error("no metadata")
        val dir = File(context.cacheDir, "app_updates").apply { mkdirs() }
        val outFile = File(dir, "callmate-update.apk")
        val absUrl = buildAbsoluteUrl(serverBaseUrl, meta.relativeUrl)
        Log.i(TAG, "download from $absUrl")
        withContext(Dispatchers.IO) {
            _isDownloading.value = true
            try {
                val req = Request.Builder().url(absUrl).get().build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("HTTP ${resp.code}")
                    }
                    val body = resp.body ?: throw IllegalStateException("empty body")
                    FileOutputStream(outFile).use { fos ->
                        body.byteStream().use { ins -> ins.copyTo(fos) }
                    }
                }
            } finally {
                _isDownloading.value = false
            }
        }
        return outFile
    }

    /**
     * @return true 已发起安装界面；false 已跳转「允许安装未知应用」设置，需用户授权后再次点击。
     */
    fun startInstallApk(context: Context, apkFile: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return false
            }
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
        return true
    }
}
