package com.example.sentriai.ui.screens

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.sentriai.agora.AgoraConfig
import com.example.sentriai.agora.AgoraConversationManager
import com.example.sentriai.agora.CallState
import com.example.sentriai.agora.ConversationMode
import com.example.sentriai.agora.Speaker
import com.example.sentriai.agora.TranscriptEvent
import com.example.sentriai.care.CareSchedule
import com.example.sentriai.care.CareScheduleStore
import com.example.sentriai.care.MealSchedule
import com.example.sentriai.care.Medicine
import com.example.sentriai.data.ProfileStore
import com.example.sentriai.engine.ConversationEscalationMonitor
import com.example.sentriai.engine.EmergencyDecision
import com.example.sentriai.engine.EmergencyPipeline
import com.example.sentriai.engine.EmergencyType
import com.example.sentriai.model_inference.speech_to_text.TranscriptionUiState
import com.example.sentriai.reminder.ReminderBatch
import com.example.sentriai.reminder.ReminderItem
import com.example.sentriai.reminder.ReminderKind
import com.example.sentriai.reminder.ReminderOverlayService
import com.example.sentriai.reminder.ReminderPlan
import com.example.sentriai.reminder.ReminderScheduler
import com.example.sentriai.reminder.ReminderSettings
import com.example.sentriai.reminder.ReminderStatus
import com.example.sentriai.service.ListeningService
import com.example.sentriai.service.ListeningStateHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/** What the UI knows about an alert raised from this call. */
data class CallEscalation(
    val emergencyType: String,
    val triggerPhrase: String,
    val source: EmergencyDecision.Source,
)

/**
 * Drives both care screens: the Daily Companion schedule view and the live Agora call.
 *
 * ### The wiring it owns
 *
 * ```
 *   AgoraConversationManager ──transcript──▶ ConversationEscalationMonitor
 *                                                       │ onEscalate
 *                                                       ▼
 *                                            EmergencyPipeline.dispatch  ──▶ SMS + trigger log
 * ```
 *
 * Each of those three is independently testable and none of them knows about the others; this
 * ViewModel is the only place they meet. In particular the escalation monitor is handed a plain
 * suspend lambda, so the rule "a conversation escalates through the existing pipeline" is one
 * line of code here rather than a convention spread across the Agora layer.
 *
 * ### The microphone
 *
 * `ListeningService` and Agora both want the mic, and only one can have it. A call stops passive
 * listening for its duration and restarts it afterwards if — and only if — it was running when
 * the call began. Restarting is legal because the call screen is in the foreground at that
 * point; a microphone foreground service cannot be started from the background on API 34+.
 */
class CompanionCallViewModel(application: Application) : AndroidViewModel(application) {

    private val manager = AgoraConversationManager(application, viewModelScope)

    val callState: StateFlow<CallState> = manager.state
    val agentSpeaking: StateFlow<Boolean> = manager.agentSpeaking

    private val _schedule = MutableStateFlow(CareSchedule.EMPTY)
    val schedule: StateFlow<CareSchedule> = _schedule.asStateFlow()

    private val _lines = MutableStateFlow<List<CallLine>>(emptyList())
    val lines: StateFlow<List<CallLine>> = _lines.asStateFlow()

    /**
     * Source of [CallLine.id]. Never reset, not even between calls: the list is cleared on start,
     * but a transcript event from the call just ended can still be in flight, and reusing ids
     * would put two lines with one identity in the same list.
     *
     * Only touched from the single transcript collector, so a plain var is enough.
     */
    private var nextLineId = 0L

    private val _escalation = MutableStateFlow<CallEscalation?>(null)
    val escalation: StateFlow<CallEscalation?> = _escalation.asStateFlow()

    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    /** Non-null when the feature cannot run at all — an unset Gradle property, in practice. */
    val configurationProblem: String? = AgoraConfig.missingRequirement()

    private var monitor: ConversationEscalationMonitor? = null

    /** Whether passive listening was running when the call started, so it can be put back. */
    private var resumeListeningAfterCall = false

    init {
        refreshSchedule()
        manager.onTranscript(::onTranscriptEvent)
    }

    // --- schedule ------------------------------------------------------------------

    fun refreshSchedule() {
        viewModelScope.launch(Dispatchers.IO) {
            _schedule.value = CareScheduleStore.load(getApplication())
        }
    }

    fun setMedicineTaken(medicineId: String, taken: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            _schedule.value = CareScheduleStore.setTaken(getApplication(), medicineId, taken)
            // A dose ticked off before its reminder time should not then be reminded about, and
            // one un-ticked should be. Both change which batch is next.
            ReminderScheduler.sync(getApplication())
        }
    }

    fun updateMeals(meals: MealSchedule) {
        viewModelScope.launch(Dispatchers.IO) {
            _schedule.value = CareScheduleStore.setMeals(getApplication(), meals)
            ReminderScheduler.sync(getApplication())
            Log.i(TAG, "meal times updated")
        }
    }

    /** Adds a dose, or replaces the one with the same id. */
    fun saveMedicine(medicine: Medicine) {
        viewModelScope.launch(Dispatchers.IO) {
            _schedule.value = CareScheduleStore.upsertMedicine(getApplication(), medicine)
            ReminderScheduler.sync(getApplication())
            Log.i(TAG, "saved medicine ${medicine.name} at ${medicine.timeMinutes} min")
        }
    }

    fun deleteMedicine(medicineId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _schedule.value = CareScheduleStore.removeMedicine(getApplication(), medicineId)
            ReminderScheduler.sync(getApplication())
            Log.i(TAG, "deleted medicine $medicineId")
        }
    }

    // --- reminders -----------------------------------------------------------------

    private val _reminderStatus = MutableStateFlow(ReminderStatus.UNKNOWN)
    val reminderStatus: StateFlow<ReminderStatus> = _reminderStatus.asStateFlow()

    /**
     * Re-reads the three consents. Called every time the companion screen resumes, because each
     * of them is granted on a system screen — coming back from one is the only moment the answer
     * can have changed.
     */
    fun refreshReminderStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            _reminderStatus.value = ReminderStatus.read(getApplication())
        }
    }

    /**
     * Shows the next reminder immediately, as a rehearsal.
     *
     * Reminders are the one part of this app whose whole job is to happen when nobody is looking at
     * it, which makes "is it working?" almost impossible to answer by waiting. This answers it in a
     * tap, and leaves no notification and no snooze behind.
     */
    fun previewReminder() {
        viewModelScope.launch(Dispatchers.IO) {
            val schedule = CareScheduleStore.load(getApplication())
            val batch = ReminderPlan.nextBatch(schedule, ReminderPlan.minutesOfDay(Calendar.getInstance()))
                ?: run {
                    val nowMinutes = ReminderPlan.minutesOfDay(Calendar.getInstance())
                    val isMeal = Calendar.getInstance().get(Calendar.SECOND) % 2 == 0
                    if (isMeal) {
                        ReminderBatch(
                            minutes = nowMinutes,
                            nextDay = false,
                            items = listOf(
                                ReminderItem(
                                    key = "preview:sample_meal",
                                    kind = ReminderKind.MEAL,
                                    minutes = nowMinutes,
                                    title = "Lunch",
                                    detail = "Time for lunch"
                                )
                            )
                        )
                    } else {
                        ReminderBatch(
                            minutes = nowMinutes,
                            nextDay = false,
                            items = listOf(
                                ReminderItem(
                                    key = "preview:sample_medicine",
                                    kind = ReminderKind.MEDICINE,
                                    minutes = nowMinutes,
                                    title = "Aspirin",
                                    detail = "1 tablet · After breakfast"
                                )
                            )
                        )
                    }
                }
            ReminderOverlayService.preview(getApplication(), batch)
        }
    }

    fun setRemindersEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            ReminderSettings.setEnabled(getApplication(), enabled)
            // Sync both arms and disarms: with reminders off it cancels every pending alarm, so
            // switching off takes effect immediately rather than after the next one fires.
            ReminderScheduler.sync(getApplication())
            _reminderStatus.value = ReminderStatus.read(getApplication())
            Log.i(TAG, "reminders ${if (enabled) "enabled" else "disabled"}")
        }
    }

    // --- calls ---------------------------------------------------------------------

    fun startCall(mode: ConversationMode) {
        if (manager.isLive) {
            Log.d(TAG, "call already live; ignoring start")
            return
        }
        viewModelScope.launch {
            _lines.value = emptyList()
            _escalation.value = null
            _muted.value = false

            val schedule = CareScheduleStore.load(getApplication())
            _schedule.value = schedule

            monitor = ConversationEscalationMonitor(
                scope = viewModelScope,
                // The whole of "an Agora conversation escalates into the existing pipeline".
                // dispatch() skips detection because the monitor has already decided — see
                // EmergencyPipeline.dispatch for why re-running it would be wrong.
                onEscalate = { decision, transcript ->
                    Log.w(TAG, "conversation escalated: ${decision.emergencyType} via ${decision.source}")
                    dispatchUninterruptibly(decision, transcript)
                    _escalation.value = CallEscalation(
                        emergencyType = decision.emergencyType,
                        triggerPhrase = decision.triggerPhrase,
                        source = decision.source,
                    )
                },
            )

            suspendPassiveListening()

            manager.startCall(mode, schedule, personName())
                .onFailure {
                    Log.e(TAG, "call failed to start: ${it.message}")
                    monitor?.stop()
                    monitor = null
                    resumePassiveListening()
                }
        }
    }

    fun endCall() {
        viewModelScope.launch {
            monitor?.stop()
            monitor = null
            manager.endCall()
            resumePassiveListening()
            // Adherence may have been discussed on the call; the screen behind it should not
            // show stale ticks.
            refreshSchedule()
        }
    }

    fun toggleMute() {
        val next = !_muted.value
        _muted.value = next
        manager.setMuted(next)
    }

    /**
     * The SOS button, pressed while a call is up.
     *
     * Goes through the monitor's latch rather than straight to the pipeline so a button press
     * and a distress phrase heard a second earlier cannot both send an SMS.
     */
    fun triggerSosDuringCall() {
        monitor?.onManualTrigger("SOS button pressed during a companion call")
            ?: Log.w(TAG, "SOS during call with no monitor — no call is running")
    }

    // --- transcripts ----------------------------------------------------------------

    /**
     * Called for every transcript update, interim and final.
     *
     * Only **final** utterances reach the escalation monitor. Interim ASR output is revised as
     * the person keeps talking — "I fell" is a legitimate prefix of "I felt much better today" —
     * and escalating on a prefix would alert on the opposite of what was said.
     */
    private suspend fun onTranscriptEvent(event: TranscriptEvent) {
        _lines.value = CallTranscript.append(_lines.value, event, nextLineId++)
        if (!event.isFinal) return

        when (event.speaker) {
            Speaker.PERSON -> monitor?.onUserSpeech(event.text)
            Speaker.AGENT -> monitor?.onAgentSpeech(event.text)
        }
    }

    /**
     * Sends the alert without letting the coroutine's cancellation stop it partway.
     *
     * Everything in this ViewModel runs on `viewModelScope`, which is cancelled when the screen
     * goes away — and "the person navigated away, or the process moved on, a moment after the
     * emergency was detected" is a completely ordinary thing to happen. Stage 2 of the pipeline
     * suspends before it sends (the tool-call model takes about a second to format the alert),
     * so an ordinary launch leaves a real window where the decision is made and the SMS never
     * goes out.
     *
     * [NonCancellable] closes it: once dispatch starts, it runs to the end.
     */
    private suspend fun dispatchUninterruptibly(decision: EmergencyDecision, transcript: String) {
        withContext(NonCancellable) {
            runCatching { EmergencyPipeline.dispatch(getApplication(), decision, transcript) }
                .onFailure { Log.e(TAG, "dispatch failed: ${it.message}", it) }
        }
    }

    // --- emergency screen ------------------------------------------------------------

    /**
     * The SOS button on the Emergency screen.
     *
     * Sends first and talks second, and asks nothing in between. The alert is dispatched through
     * the same pipeline as everything else; only once it is out does an emergency-mode Agora
     * call start, to keep the person company until someone arrives. If Agora is unconfigured or
     * the network is down, the SMS has already gone — the call is an addition to the alert, never
     * a precondition for it.
     *
     * @param onDispatched invoked on the caller's coroutine once the SMS attempt has completed,
     *   so the screen can confirm rather than guess.
     */
    fun raiseSosAlert(startCompanionCall: Boolean = true, onDispatched: () -> Unit = {}) {
        viewModelScope.launch {
            val decision = EmergencyDecision(
                emergencyType = EmergencyType.HELP,
                triggerPhrase = "SOS button pressed",
                confidence = 1.0f,
                source = EmergencyDecision.Source.MANUAL_SOS,
            )
            Log.w(TAG, "SOS pressed — dispatching immediately, no confirmation")
            dispatchUninterruptibly(decision, decision.triggerPhrase)

            _escalation.value = CallEscalation(
                decision.emergencyType,
                decision.triggerPhrase,
                decision.source,
            )
            onDispatched()

            // The call is an addition to the alert, so every reason not to place one is a
            // silent skip. In particular the microphone permission is checked rather than
            // requested: putting a system permission dialog in front of someone who has just
            // pressed SOS would be the worst possible moment to ask, and the alert is already
            // out either way.
            when {
                !startCompanionCall -> Unit
                configurationProblem != null ->
                    Log.w(TAG, "no emergency companion call: $configurationProblem")
                !hasMicPermission() ->
                    Log.w(TAG, "no emergency companion call: RECORD_AUDIO not granted")
                manager.isLive -> Log.d(TAG, "a call is already live; not starting another")
                else -> startCall(ConversationMode.EMERGENCY)
            }
        }
    }

    /** True when a call can actually carry the person's voice. */
    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Sends a clearly-labelled test message to the caregiver.
     *
     * Uses the real dispatch path on purpose: a test that takes a different route through the
     * code proves the SMS permission, the stored number and the log all work — which is the
     * whole question the caregiver is asking when they press it.
     */
    fun sendTestAlert(onDispatched: () -> Unit = {}) {
        viewModelScope.launch {
            val decision = EmergencyDecision(
                emergencyType = TEST_ALERT_TYPE,
                triggerPhrase = "Test alert sent from the Emergency screen",
                confidence = 1.0f,
                source = EmergencyDecision.Source.MANUAL_SOS,
            )
            dispatchUninterruptibly(decision, decision.triggerPhrase)
            onDispatched()
        }
    }

    /** The caregiver's number, for the "Call family" button. Null when the profile is incomplete. */
    fun caregiverNumber(): String? =
        ProfileStore.preferences(getApplication())
            .getString(ProfileStore.KEY_HANDLER_MOBILE, "")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    fun caregiverName(): String =
        ProfileStore.preferences(getApplication())
            .getString(ProfileStore.KEY_HANDLER_NAME, "")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "your emergency contact"

    // --- mic handover ------------------------------------------------------------------

    private fun suspendPassiveListening() {
        val listening = ListeningStateHolder.state.value
        resumeListeningAfterCall = listening is TranscriptionUiState.Listening ||
            listening is TranscriptionUiState.Preparing
        if (resumeListeningAfterCall) {
            Log.i(TAG, "pausing passive listening for the duration of the call")
            ListeningService.stop(getApplication())
        }
    }

    private fun resumePassiveListening() {
        if (!resumeListeningAfterCall) return
        resumeListeningAfterCall = false
        Log.i(TAG, "restoring passive listening after the call")
        // Legal here: the call screen is in the foreground, and a microphone foreground service
        // may only be started while the app is visible on API 34+.
        runCatching { ListeningService.start(getApplication()) }
            .onFailure { Log.w(TAG, "could not restart passive listening: ${it.message}") }
    }

    private fun personName(): String =
        ProfileStore.preferences(getApplication())
            .getString(ProfileStore.KEY_FULL_NAME, "")
            ?.trim()
            .orEmpty()

    override fun onCleared() {
        monitor?.stop()
        // Leaves the channel and stops the agent. viewModelScope is cancelled immediately after
        // onCleared returns, so this is launched on a scope that outlives it — an agent left
        // running would keep billing until its idle_timeout.
        val session = manager
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            runCatching { session.endCall() }
            session.release()
        }
        super.onCleared()
    }

    private companion object {
        const val TAG = "CompanionCallVM"
        const val TEST_ALERT_TYPE = "Test Alert"
    }
}
