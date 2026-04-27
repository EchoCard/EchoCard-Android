package com.vaca.callmate.features.device.latency

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vaca.callmate.core.ble.BleManager
import com.vaca.callmate.core.ble.audioStop
import com.vaca.callmate.core.ble.hfpConnect
import com.vaca.callmate.core.ble.latencyTestMode
import com.vaca.callmate.core.ble.latencyTestStart
import com.vaca.callmate.core.ble.latencyTestStop
import com.vaca.callmate.core.telecom.LatencyTestCallBridge
import com.vaca.callmate.core.telecom.LatencyTestTelecom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException

private const val TAG = "LatencyTestVM"

class LatencyTestViewModel(
    application: Application,
    private val ble: BleManager
) : AndroidViewModel(application) {

    private val audioEngine = LatencyAudioEngine(application.applicationContext)
    val continuousAnalyzer = ContinuousLatencyAnalyzer(16000.0)

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _measuredLatencyMs = MutableStateFlow<Double?>(null)
    val measuredLatencyMs: StateFlow<Double?> = _measuredLatencyMs.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _isContinuousRunning = MutableStateFlow(false)
    val isContinuousRunning: StateFlow<Boolean> = _isContinuousRunning.asStateFlow()

    private val _stageMeasurements = MutableStateFlow<List<LatencyStageMeasurement>>(emptyList())
    val stageMeasurements: StateFlow<List<LatencyStageMeasurement>> = _stageMeasurements.asStateFlow()

    private val _waveformTraces = MutableStateFlow<List<LatencyWaveformTrace>>(emptyList())
    val waveformTraces: StateFlow<List<LatencyWaveformTrace>> = _waveformTraces.asStateFlow()

    private val loopbackPackets = Collections.synchronizedList(mutableListOf<LatencyLoopbackPacket>())
    private val micOneShotSamples = mutableListOf<Float>()
    private val micOneShotLock = Any()
    private var activeJob: Job? = null

    fun startTest() {
        if (_isRunning.value || _isContinuousRunning.value) return
        if (!ble.isReady.value) {
            _errorMessage.value = "BLE not ready"
            return
        }
        if (!LatencyTestTelecom.isSupported()) {
            _errorMessage.value = "Requires Android 9+ (API 28) for Telecom self-managed call"
            return
        }
        _isRunning.value = true
        _statusMessage.value = "Starting…"
        _errorMessage.value = null
        _measuredLatencyMs.value = null
        _stageMeasurements.value = emptyList()
        _waveformTraces.value = emptyList()
        loopbackPackets.clear()
        synchronized(micOneShotLock) { micOneShotSamples.clear() }
        ble.setLatencyTestEchoMode(true)
        ble.latencyTestLoopbackOpusObserver = { payload, ns ->
            synchronized(loopbackPackets) {
                if (loopbackPackets.size < 512) {
                    loopbackPackets.add(LatencyLoopbackPacket(payload, ns))
                }
            }
        }

        activeJob = viewModelScope.launch {
            try {
                val waitActive = async {
                    withTimeout(60_000) {
                        ble.callStateEvents.filter { it == "active" }.first()
                    }
                }
                val audioReady = CompletableDeferred<Unit>()
                LatencyTestCallBridge.onAnswered = {
                    viewModelScope.launch {
                        _statusMessage.value = "Connecting HFP…"
                        ble.hfpConnect()
                    }
                }
                LatencyTestCallBridge.onAudioActivated = {
                    viewModelScope.launch {
                        _statusMessage.value = "Waiting for SCO…"
                        audioEngine.configureSco()
                        audioEngine.startPriming()
                        audioReady.complete(Unit)
                    }
                }
                LatencyTestCallBridge.onEnded = {
                    viewModelScope.launch { if (_isRunning.value) stopTest() }
                }
                LatencyTestCallBridge.onFailed = { msg ->
                    viewModelScope.launch { finishWithError(msg) }
                }
                ble.latencyTestMode(true)
                ble.hfpConnect()
                _statusMessage.value = "Connecting HFP…"
                if (!LatencyTestTelecom.reportIncomingCall(getApplication())) {
                    finishWithError("Telecom addNewIncomingCall failed")
                    return@launch
                }
                waitActive.await()
                withTimeout(15_000) { audioReady.await() }
                Log.i(TAG, "BLE active + BT audio ready → starting measurement")
                runOneShotMeasurement()
            } catch (e: TimeoutCancellationException) {
                finishWithError("Timeout: call_state=active or BT audio not ready")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "startTest", e)
                finishWithError(e.message ?: "Error")
            }
        }
    }

    fun startContinuousTest() {
        if (_isRunning.value || _isContinuousRunning.value) return
        if (!ble.isReady.value) {
            _errorMessage.value = "BLE not ready"
            return
        }
        if (!LatencyTestTelecom.isSupported()) {
            _errorMessage.value = "Requires Android 9+ (API 28) for Telecom self-managed call"
            return
        }
        _isContinuousRunning.value = true
        _statusMessage.value = "Starting…"
        _errorMessage.value = null
        continuousAnalyzer.reset()
        ble.setLatencyTestEchoMode(true)
        ble.latencyTestLoopbackOpusObserver = null

        activeJob = viewModelScope.launch {
            try {
                val waitActive = async {
                    withTimeout(60_000) {
                        ble.callStateEvents.filter { it == "active" }.first()
                    }
                }
                val audioReady = CompletableDeferred<Unit>()
                LatencyTestCallBridge.onAnswered = {
                    viewModelScope.launch {
                        _statusMessage.value = "Connecting HFP…"
                        ble.hfpConnect()
                    }
                }
                LatencyTestCallBridge.onAudioActivated = {
                    viewModelScope.launch {
                        _statusMessage.value = "Waiting for SCO…"
                        audioEngine.configureSco()
                        audioEngine.startPriming()
                        audioReady.complete(Unit)
                    }
                }
                LatencyTestCallBridge.onEnded = {
                    viewModelScope.launch { if (_isContinuousRunning.value) stopContinuousTest() }
                }
                LatencyTestCallBridge.onFailed = { msg ->
                    viewModelScope.launch { finishContinuousWithError(msg) }
                }
                ble.latencyTestMode(true)
                ble.hfpConnect()
                if (!LatencyTestTelecom.reportIncomingCall(getApplication())) {
                    finishContinuousWithError("Telecom addNewIncomingCall failed")
                    return@launch
                }
                waitActive.await()
                withTimeout(15_000) { audioReady.await() }
                Log.i(TAG, "BLE active + BT audio ready → starting continuous measurement")
                runContinuousMeasurement()
            } catch (e: TimeoutCancellationException) {
                finishContinuousWithError("Timeout: call_state=active not received (SCO)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "startContinuousTest", e)
                finishContinuousWithError(e.message ?: "Error")
            }
        }
    }

    private suspend fun runOneShotMeasurement() {
        _statusMessage.value = "Starting latency encoder…"
        ble.latencyTestStart()
        delay(500)
        audioEngine.stopAll()
        var waited = 0
        while (loopbackPackets.isEmpty() && waited < 15_000) {
            delay(200)
            waited += 200
        }
        if (loopbackPackets.isEmpty()) {
            Log.w(TAG, "No BLE loopback within 15s — measuring anyway")
        }
        _statusMessage.value = "Playing square wave & recording…"
        synchronized(micOneShotLock) { micOneShotSamples.clear() }
        var edgeDone = false
        audioEngine.startPlayAndRecord(
            durationSec = 5f,
            continuous = false,
            onMicSamples = { floats ->
                synchronized(micOneShotLock) {
                    val maxSamples = 16000 * 8
                    for (f in floats) {
                        if (micOneShotSamples.size >= maxSamples) break
                        micOneShotSamples.add(f)
                    }
                }
            },
            onFirstEdgeMs = { ms ->
                if (!edgeDone) {
                    edgeDone = true
                    _measuredLatencyMs.value = ms
                    _statusMessage.value = "Completed"
                    viewModelScope.launch { stopTest() }
                }
            },
            onCompleted = {
                viewModelScope.launch {
                    if (_measuredLatencyMs.value == null && _isRunning.value) {
                        _errorMessage.value =
                            "No first edge detected (check HFP route, BLE, MCU SCO)"
                    }
                    if (_isRunning.value) stopTest()
                }
            }
        )
    }

    private suspend fun runContinuousMeasurement() {
        _statusMessage.value = "Starting latency encoder…"
        ble.latencyTestStart()
        delay(500)
        audioEngine.stopAll()
        _statusMessage.value = "Playing square wave & recording…"
        continuousAnalyzer.reset()
        audioEngine.startPlayAndRecord(
            durationSec = 3600f,
            continuous = true,
            onMicSamples = { floats -> continuousAnalyzer.push(floats) },
            onFirstEdgeMs = { },
            onCompleted = { }
        )
    }

    fun stopTest() {
        if (!_isRunning.value) return
        val playbackSnap = audioEngine.lastPlaybackFloatsForAnalysis.copyOf()
        val playNsSnap = audioEngine.playStartNsForMeasurement.takeIf { it != 0L }
        val micSnap = synchronized(micOneShotLock) { micOneShotSamples.toFloatArray() }
        val loopSnap = synchronized(loopbackPackets) { loopbackPackets.toList() }
        val totalSnap = _measuredLatencyMs.value
        activeJob?.cancel()
        activeJob = null
        audioEngine.stopAll()
        audioEngine.releaseSco()
        ble.setLatencyTestEchoMode(false)
        ble.latencyTestLoopbackOpusObserver = null
        ble.stopRateMonitorForLocalTeardown("latency_test_ended")
        ble.latencyTestStop()
        ble.latencyTestMode(false)
        ble.audioStop(null)
        LatencyTestTelecom.endLatencyTestCall()
        LatencyTestCallBridge.clear()
        _isRunning.value = false
        if (_statusMessage.value != "Completed" && _statusMessage.value != "Error") {
            _statusMessage.value = if (_measuredLatencyMs.value != null) "Completed" else "Stopped"
        }
        if (playbackSnap.isNotEmpty() || micSnap.isNotEmpty() || loopSnap.isNotEmpty()) {
            viewModelScope.launch(Dispatchers.Default) {
                val (stages, traces) = LatencyWaveformAnalysis.buildCharts(
                    playbackSnap,
                    micSnap,
                    loopSnap,
                    playNsSnap,
                    totalSnap
                )
                withContext(Dispatchers.Main) {
                    _stageMeasurements.value = stages
                    _waveformTraces.value = traces
                    if (totalSnap != null) {
                        _statusMessage.value = "Completed"
                    }
                }
            }
        }
    }

    fun stopContinuousTest() {
        if (!_isContinuousRunning.value) return
        activeJob?.cancel()
        activeJob = null
        audioEngine.stopAll()
        audioEngine.releaseSco()
        continuousAnalyzer.stop()
        ble.setLatencyTestEchoMode(false)
        ble.latencyTestLoopbackOpusObserver = null
        ble.stopRateMonitorForLocalTeardown("latency_continuous_ended")
        ble.latencyTestStop()
        ble.latencyTestMode(false)
        ble.audioStop(null)
        LatencyTestTelecom.endLatencyTestCall()
        LatencyTestCallBridge.clear()
        _isContinuousRunning.value = false
        _statusMessage.value = "Stopped"
    }

    private fun finishWithError(msg: String) {
        activeJob?.cancel()
        activeJob = null
        audioEngine.stopAll()
        audioEngine.releaseSco()
        ble.setLatencyTestEchoMode(false)
        ble.latencyTestLoopbackOpusObserver = null
        ble.stopRateMonitorForLocalTeardown("latency_test_error")
        ble.latencyTestStop()
        ble.latencyTestMode(false)
        LatencyTestTelecom.endLatencyTestCall()
        LatencyTestCallBridge.clear()
        _errorMessage.value = msg
        _statusMessage.value = "Error"
        _isRunning.value = false
    }

    private fun finishContinuousWithError(msg: String) {
        activeJob?.cancel()
        activeJob = null
        audioEngine.stopAll()
        audioEngine.releaseSco()
        continuousAnalyzer.stop()
        ble.setLatencyTestEchoMode(false)
        ble.latencyTestLoopbackOpusObserver = null
        ble.stopRateMonitorForLocalTeardown("latency_continuous_error")
        ble.latencyTestStop()
        ble.latencyTestMode(false)
        LatencyTestTelecom.endLatencyTestCall()
        LatencyTestCallBridge.clear()
        _errorMessage.value = msg
        _statusMessage.value = "Error"
        _isContinuousRunning.value = false
    }

    override fun onCleared() {
        super.onCleared()
        if (_isRunning.value) stopTest()
        if (_isContinuousRunning.value) stopContinuousTest()
    }

    companion object {
        fun factory(app: Application, ble: BleManager): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return LatencyTestViewModel(app, ble) as T
                }
            }
    }
}
