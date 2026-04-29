package com.vaca.callmate.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "callmate_prefs")

private object Keys {
    val LEGAL_ACCEPTED = booleanPreferencesKey("legal_accepted")
    val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
    val LANGUAGE = stringPreferencesKey("language") // "zh" | "en"
    val BOUND_DEVICE_ID = stringPreferencesKey("bound_device_id") // 预留

    /** 与 iOS `UserDefaults` / `@AppStorage` 键名一致，便于跨端对照 */
    val PICKUP_DELAY = intPreferencesKey("callmate.pickup_delay")
    val MCU_SILENT_UPDATE = booleanPreferencesKey("callmate.mcu_silent_update_enabled")
    val LIVE_ACTIVITY_RESIDENT = booleanPreferencesKey("callmate.live_activity_resident_enabled")
    val VOICE_TONE = stringPreferencesKey("callmate.voiceTone")
    val VOICE_ID = stringPreferencesKey("callmate.voiceId")
    val VOICE_DISPLAY_NAME_OVERRIDE = stringPreferencesKey("callmate.voiceDisplayNameOverride")
    /** 与 iOS `UserDefaults` 对齐 */
    val JWT_TOKEN = stringPreferencesKey("callmate_jwt_token")
    val HAS_REGISTERED = booleanPreferencesKey("callmate_has_registered")
    val PID_ID = stringPreferencesKey("callmate_pid_id")
    val APP_CODE = stringPreferencesKey("callmate_app_code_32")
    val USER_MANUAL_VOICE = booleanPreferencesKey("callmate.userManuallySelectedVoice")
    val RUNTIME_BLUETOOTH_ID = stringPreferencesKey("callmate.runtime_bluetooth_id")
    /** 与 iOS `@AppStorage("callmate.ai_calls_total")` 对齐 */
    val AI_CALLS_TOTAL = intPreferencesKey("callmate.ai_calls_total")
    val USER_APPELLATION = stringPreferencesKey("callmate.userAppellation")
    /** 与 iOS / update_config v1 `template_vars.greeting` 对齐：用户偏好开场白 */
    val USER_GREETING = stringPreferencesKey("callmate.userGreeting")
    /** 与 iOS `WebSocketService.ws_avatar_send_prompt_enabled` 一致；默认 false 不在 hello 里带长 prompt */
    val WS_AVATAR_SEND_PROMPT = booleanPreferencesKey("ws_avatar_send_prompt_enabled")
    /** 与 iOS `isInitConfigSendPromptEnabled()` 一致：键未设置时默认 false（init_config hello 不传 prompt） */
    val WS_INIT_CONFIG_SEND_PROMPT = booleanPreferencesKey("ws_init_config_send_prompt_enabled")
    /** 与 iOS `UserDefaults` key `callmate_active_mode` 一致：`standby` | `semi` | `full` */
    val ACTIVE_MODE = stringPreferencesKey("callmate_active_mode")
    /** 与 iOS `callmate.control_ws_url` 一致；空则使用 BuildConfig.WS_BASE_URL */
    val CONTROL_WS_URL = stringPreferencesKey("callmate.control_ws_url")
}

class AppPreferences(private val context: Context) {

    val legalAccepted: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.LEGAL_ACCEPTED] ?: false
    }

    val onboardingDone: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.ONBOARDING_DONE] ?: false
    }

    val languageFlow: Flow<Language> = context.dataStore.data.map { prefs ->
        when (prefs[Keys.LANGUAGE]) {
            "en" -> Language.En
            else -> Language.Zh
        }
    }

    val boundDeviceId: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[Keys.BOUND_DEVICE_ID]
    }

    val pickupDelayFlow: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.PICKUP_DELAY] ?: 5
    }

    val mcuSilentUpdateFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.MCU_SILENT_UPDATE] ?: true
    }

    val liveActivityResidentFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.LIVE_ACTIVITY_RESIDENT] ?: false
    }

    val voiceToneFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.VOICE_TONE] ?: VoiceTone.Taiwan.raw
    }

    val voiceIdFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.VOICE_ID] ?: ""
    }

    val voiceDisplayNameOverrideFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.VOICE_DISPLAY_NAME_OVERRIDE] ?: ""
    }

    val jwtTokenFlow: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[Keys.JWT_TOKEN]
    }

    val hasRegisteredFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.HAS_REGISTERED] ?: false
    }

    val appCodeFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.APP_CODE].orEmpty()
    }

    val userManuallySelectedVoiceFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.USER_MANUAL_VOICE] ?: false
    }

    val aiCallsTotalFlow: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.AI_CALLS_TOTAL] ?: 0
    }

    val userAppellationFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.USER_APPELLATION].orEmpty()
    }

    /** 与 update_config v1 `template_vars.greeting` 对齐 */
    val userGreetingFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.USER_GREETING].orEmpty()
    }

    /** 与 iOS `isAvatarSendPromptEnabled()` 一致：未设置时默认 false */
    val avatarSendPromptEnabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.WS_AVATAR_SEND_PROMPT] ?: false
    }

    /** 与 iOS `isInitConfigSendPromptEnabled()` 一致：未设置时默认 false */
    val initConfigSendPromptEnabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.WS_INIT_CONFIG_SEND_PROMPT] ?: false
    }

    /** 与 iOS `CallsView` `@AppStorage("callmate_active_mode")` 一致 */
    val activeModeFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.ACTIVE_MODE] ?: "semi"
    }

    /** 历史遗留：控制通道已改为推送（与 iOS 一致），不再使用 WebSocket；保留键以免旧数据丢失 */
    val controlWsUrlFlow: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[Keys.CONTROL_WS_URL]?.trim()?.takeIf { it.isNotEmpty() }
    }

    suspend fun pidIdString(): String =
        context.dataStore.data.first()[Keys.PID_ID].orEmpty()

    suspend fun appCodeString(): String =
        context.dataStore.data.first()[Keys.APP_CODE].orEmpty()

    suspend fun setJwtToken(token: String?) {
        context.dataStore.edit {
            if (token != null) it[Keys.JWT_TOKEN] = token
            else it.remove(Keys.JWT_TOKEN)
        }
    }

    suspend fun setHasRegistered(registered: Boolean) {
        context.dataStore.edit { it[Keys.HAS_REGISTERED] = registered }
    }

    suspend fun setPidId(pid: String) {
        context.dataStore.edit { it[Keys.PID_ID] = pid }
    }

    suspend fun setAppCode32(code: String) {
        context.dataStore.edit { it[Keys.APP_CODE] = code }
    }

    suspend fun setUserManuallySelectedVoice(selected: Boolean) {
        context.dataStore.edit { it[Keys.USER_MANUAL_VOICE] = selected }
    }

    suspend fun getOrCreateRuntimeBluetoothId(): String {
        val prefs = context.dataStore.data.first()
        val existing = prefs[Keys.RUNTIME_BLUETOOTH_ID]
        if (!existing.isNullOrBlank()) return existing
        val uuid = java.util.UUID.randomUUID().toString()
        context.dataStore.edit { it[Keys.RUNTIME_BLUETOOTH_ID] = uuid }
        return uuid
    }

    suspend fun setLegalAccepted(accepted: Boolean) {
        context.dataStore.edit { it[Keys.LEGAL_ACCEPTED] = accepted }
    }

    suspend fun setOnboardingDone(done: Boolean) {
        context.dataStore.edit { it[Keys.ONBOARDING_DONE] = done }
    }

    suspend fun setLanguage(lang: Language) {
        context.dataStore.edit {
            it[Keys.LANGUAGE] = when (lang) {
                Language.Zh -> "zh"
                Language.En -> "en"
            }
        }
    }

    suspend fun setBoundDeviceId(deviceId: String?) {
        context.dataStore.edit {
            if (deviceId != null) it[Keys.BOUND_DEVICE_ID] = deviceId
            else it.remove(Keys.BOUND_DEVICE_ID)
        }
    }

    suspend fun setPickupDelay(seconds: Int) {
        context.dataStore.edit { it[Keys.PICKUP_DELAY] = seconds.coerceIn(1, 60) }
    }

    suspend fun setMcuSilentUpdate(enabled: Boolean) {
        context.dataStore.edit { it[Keys.MCU_SILENT_UPDATE] = enabled }
    }

    suspend fun setLiveActivityResident(enabled: Boolean) {
        context.dataStore.edit { it[Keys.LIVE_ACTIVITY_RESIDENT] = enabled }
    }

    suspend fun setVoiceTone(raw: String) {
        context.dataStore.edit { it[Keys.VOICE_TONE] = raw }
    }

    suspend fun setVoiceId(id: String) {
        context.dataStore.edit { it[Keys.VOICE_ID] = id }
    }

    suspend fun setVoiceDisplayNameOverride(name: String) {
        context.dataStore.edit { it[Keys.VOICE_DISPLAY_NAME_OVERRIDE] = name }
    }

    suspend fun setAiCallsTotal(total: Int) {
        context.dataStore.edit { it[Keys.AI_CALLS_TOTAL] = total.coerceAtLeast(0) }
    }

    suspend fun setUserAppellation(value: String?) {
        context.dataStore.edit {
            if (value.isNullOrBlank()) it.remove(Keys.USER_APPELLATION)
            else it[Keys.USER_APPELLATION] = value.trim()
        }
    }

    suspend fun setUserGreeting(value: String?) {
        context.dataStore.edit {
            if (value.isNullOrBlank()) it.remove(Keys.USER_GREETING)
            else it[Keys.USER_GREETING] = value.trim()
        }
    }

    suspend fun setActiveMode(mode: String) {
        val m = when (mode) {
            "standby", "semi", "full" -> mode
            else -> "semi"
        }
        context.dataStore.edit { it[Keys.ACTIVE_MODE] = m }
    }
}
