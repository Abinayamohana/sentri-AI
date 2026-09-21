package com.example.sentriai.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.sentriai.data.TriggerLogEntry
import com.example.sentriai.data.TriggerLogStore
import com.example.sentriai.engine.EmergencyPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Represents the readiness state of the on-device emergency detection models. */
enum class EngineState {
    LOADING,

    /** Classifier model loaded. */
    READY,

    /** Classifier LLM absent — only the safe word can raise an alert. */
    DEGRADED,

    UNAVAILABLE,
    ERROR
}

/**
 * ViewModel exposing trigger log entries and managing emergency pipeline engine state.
 */
class TriggerLogViewModel(application: Application) : AndroidViewModel(application) {

    private val _logEntries = MutableStateFlow<List<TriggerLogEntry>>(emptyList())
    val logEntries: StateFlow<List<TriggerLogEntry>> = _logEntries.asStateFlow()

    private val _entryCount = MutableStateFlow(0)
    val entryCount: StateFlow<Int> = _entryCount.asStateFlow()

    private val _engineState = MutableStateFlow(EngineState.LOADING)
    val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    private val _engineErrorMessage = MutableStateFlow<String?>(null)
    val engineErrorMessage: StateFlow<String?> = _engineErrorMessage.asStateFlow()

    init {
        refresh()
        initEngine()
    }

    /**
     * Loads the detection model asynchronously.
     *
     * A missing classifier is reported as DEGRADED rather than UNAVAILABLE: the phrase trigger
     * still works, so the user can still summon help deliberately.
     */
    fun initEngine() {
        viewModelScope.launch(Dispatchers.IO) {
            _engineState.value = EngineState.LOADING
            val fullyLoaded = EmergencyPipeline.initialize(getApplication())
            if (fullyLoaded) {
                _engineState.value = EngineState.READY
                _engineErrorMessage.value = null
            } else {
                _engineState.value = EngineState.DEGRADED
                _engineErrorMessage.value = EmergencyPipeline.statusMessage()
            }
        }
    }

    /** Re-read entries from disk. */
    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            _logEntries.value = TriggerLogStore.loadEntries(ctx)
            _entryCount.value = _logEntries.value.size
        }
    }

    /**
     * Processes a transcript through the emergency pipeline.
     */
    fun processTranscript(transcript: String, onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            val triggered = EmergencyPipeline.processTranscript(getApplication(), transcript)
            refresh()
            onComplete(triggered)
        }
    }

    /** Wipe every entry (testing / developer reset). */
    fun clearLog() {
        viewModelScope.launch(Dispatchers.IO) {
            TriggerLogStore.clearAll(getApplication())
            _logEntries.value = emptyList()
            _entryCount.value = 0
        }
    }

     // Deliberately no onCleared teardown: EmergencyPipeline is a process-global object
    // that ListeningService also uses, and listening outlives this ViewModel. Closing it
    // here used to pull the engine out from under a background session, leaving the
    // emergency check silently dead while the mic was still running.
}
