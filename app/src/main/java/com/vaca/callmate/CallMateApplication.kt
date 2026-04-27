package com.vaca.callmate

import android.app.Application
import com.vaca.callmate.core.audio.TTSFillerSyncCoordinator
import com.vaca.callmate.core.ble.BleManager
import com.vaca.callmate.data.AppPreferences
import com.vaca.callmate.data.Language
import com.vaca.callmate.data.local.AppDatabase
import com.vaca.callmate.data.repository.CallRepository
import com.vaca.callmate.data.repository.OutboundRepository
import com.vaca.callmate.features.outbound.ControlChannelService
import com.vaca.callmate.features.outbound.OutboundTaskQueueService

class CallMateApplication : Application() {

    /**
     * 进程级单例，与页面/Compose 生命周期解耦；来电 AI、Live WS、前台服务共用同一 BLE 栈，避免 Activity 重建导致重复连接或状态分裂。
     */
    val bleManager: BleManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { BleManager(this) }

    /** 与 [outboundTaskQueueService] 的 languageRaw 回调同步，供外呼落库语言一致 */
    @Volatile
    var outboundQueueLanguageRaw: String = "zh"
        private set

    fun syncOutboundQueueLanguage(language: Language) {
        outboundQueueLanguageRaw = when (language) {
            Language.Zh -> "zh"
            Language.En -> "en"
        }
    }

    /**
     * 与 iOS `OutboundTaskQueueService.shared` 一致：单例，供 UI 与 [controlChannelService] 共用，避免两套执行状态。
     */
    val outboundTaskQueueService: OutboundTaskQueueService by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OutboundTaskQueueService(
            this,
            callRepository,
            bleManager
        ) { outboundQueueLanguageRaw }
    }

    /** 与 iOS `ControlChannelService.shared` 对齐：远程任务经 APNs/FCM 推送（无 WebSocket） */
    val controlChannelService: ControlChannelService by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ControlChannelService(this)
    }

    override fun onCreate() {
        super.onCreate()
        TTSFillerSyncCoordinator.attach(this, bleManager, AppPreferences(this))
    }

    val database: AppDatabase by lazy {
        AppDatabase.getDatabase(this)
    }

    val callRepository: CallRepository by lazy {
        CallRepository(
            callLogDao = database.callLogDao(),
            transcriptDao = database.transcriptLineDao(),
            feedbackDao = database.callFeedbackDao()
        )
    }

    val outboundRepository: OutboundRepository by lazy {
        OutboundRepository(
            promptDao = database.outboundPromptTemplateDao(),
            contactDao = database.outboundContactBookDao()
        )
    }
}
