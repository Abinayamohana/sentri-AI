package com.example.sentriai.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.sentriai.R
import com.example.sentriai.engine.EmergencyPipeline
import com.example.sentriai.model_inference.speech_to_text.AnalysisState
import com.example.sentriai.model_inference.speech_to_text.AudioRecorder
import com.example.sentriai.model_inference.speech_to_text.TranscriptionUiState
import com.example.sentriai.model_inference.speech_to_text.WhisperModel
import com.example.sentriai.model_inference.speech_to_text.cleanTranscriptText
import com.example.sentriai.model_inference.speech_to_text.voice_activity_detection.AudioSegmenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * Foreground service that owns the whole listening pipeline: mic capture, Whisper
 * transcription and the FunctionGemma emergency check.
 *
 * All three used to live in `TranscriptionViewModel` on `viewModelScope`, which meant
 * listening died the moment the UI went away. Worse, a backgrounded app without a
 * microphone-type foreground service gets *silence* rather than an error from
 * [AudioRecorder] — the loop kept ticking and transcribed zeros. Owning the pipeline in a
 * `microphone`-type FGS with its own scope fixes both.
 *
 * State is published to [ListeningStateHolder] rather than returned, so the UI can attach
 * and detach freely while this keeps running.
 *
 * Must be started while the app is visible: from API 34 a microphone-type FGS cannot be
 * started from the background.
 */
class ListeningService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val recorder = AudioRecorder()

    /** Splits the stream into utterances so silence never reaches the model. */
    private val segmenter = AudioSegmenter()

    @Volatile private var model: WhisperModel? = null
    private var streamJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotificationText: String? = null

    /** Transcribe calls this session — the number VAD exists to keep near the speech count. */
    private var transcribeCount = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_STOP -> stopListening()
            else -> Log.w(TAG, "unknown action ${intent?.action}, ignoring")
        }
        // Deliberately not sticky: a restart by the OS would happen from the background,
        // where a microphone FGS cannot legally start on API 34+. The user reopens the app.
        return START_NOT_STICKY
    }

    private fun start() {
        if (streamJob?.isActive == true) {
            Log.d(TAG, "already listening, ignoring start")
            return
        }
        if (!hasMicPermission()) {
            Log.e(TAG, "start without RECORD_AUDIO")
            fail(getString(R.string.listening_error_no_mic_permission))
            return
        }

        ListeningNotifications.ensureChannel(this)
        // The platform allows ~5 s between onStartCommand and startForeground before it
        // throws, so this happens before any model loading.
        val started = runCatching {
            ServiceCompat.startForeground(
                this,
                ListeningNotifications.NOTIFICATION_ID,
                ListeningNotifications.build(this, getString(R.string.listening_notification_starting)),
                // FOREGROUND_SERVICE_TYPE_MICROPHONE only exists from API 30 (the location
                // and dataSync types landed in 29). Passing 0 below that is correct — types
                // are not enforced until 34.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    0
                },
            )
        }.onFailure {
            // API 31+ throws ForegroundServiceStartNotAllowedException if we were started
            // from the background, which is the one case we cannot recover from.
            Log.e(TAG, "startForeground failed", it)
            fail(getString(R.string.listening_error_foreground_denied))
        }.isSuccess
        if (!started) return

        runCatching { recorder.start() }.onFailure {
            Log.e(TAG, "mic start failed", it)
            fail(getString(R.string.listening_error_mic, it.message ?: ""))
            return
        }

        acquireWakeLock()
        // A fresh session starts with no background estimate — the previous one may have been
        // in a completely different acoustic environment.
        segmenter.reset()
        transcribeCount = 0
        ListeningStateHolder.clearChatHistory()
        ListeningStateHolder.setAnalysis(AnalysisState.Idle)
        // Capture is live but the model isn't; the loop flips this to Listening once
        // WhisperModel.load() returns, which is when transcription can actually begin.
        ListeningStateHolder.setState(TranscriptionUiState.Preparing)

        streamJob = scope.launch { streamLoop() }
    }

    /** Wind the loop down; it emits the final transcript and then stops the service. */
    private fun stopListening() {
        Log.d(TAG, "stop requested")
        if (!recorder.isRecording) {
            stopSelfAndCleanUp()
            return
        }
        ListeningStateHolder.setState(TranscriptionUiState.Finishing)
        recorder.signalStop()
    }

    // --- the listening loop ----------------------------------------------------
    //
    // Speech-driven, not clock-driven: [AudioSegmenter] watches the stream and hands back
    // complete utterances, each of which is transcribed exactly once. Silence never reaches
    // the model at all.
    //
    // This matters more than it looks. WhisperModel pads every input to a fixed 30 s window,
    // so a transcribe() call costs a full encoder pass regardless of how much audio is in it.
    // The previous fixed-cadence design re-transcribed a growing window every 5 s — ~12
    // encoder passes a minute, whether or not anyone had spoken — and cut at an arbitrary
    // 20 s boundary, so a phrase spanning it never reached the emergency check intact.

    private suspend fun streamLoop() {
        val whisper = runCatching {
            model ?: WhisperModel.load(this).also { model = it }
        }.getOrElse {
            Log.e(TAG, "model load failed", it)
            recorder.stop()
            fail(it.message ?: getString(R.string.listening_error_model))
            stopSelfAndCleanUp()
            return
        }

        // The emergency pipeline is a process-global object that the UI normally warms up.
        // In a background session there may be no UI, so make sure it is ready here too;
        // initialize() is a no-op once loaded.
        runCatching { EmergencyPipeline.initialize(this) }
            .onFailure { Log.e(TAG, "Emergency pipeline init failed", it) }

        // Loading can take long enough for a stop to arrive first; don't announce a live
        // stream we are about to wind down.
        if (recorder.isRecording) {
            ListeningStateHolder.setState(TranscriptionUiState.Listening(""))
            updateNotification(getString(R.string.listening_notification_listening))
        }

        var lastText = ""

        try {
            while (coroutineContext.isActive && recorder.isRecording) {
                // Short poll purely to keep the detector fed with low latency. Transcription
                // happens off this cadence, and audio captured while a transcribe is running
                // stays buffered in the recorder, so nothing is lost when a pass runs long.
                delay(DRAIN_INTERVAL_MS)

                val segments = segmenter.offer(recorder.drain())
                if (segments.isEmpty()) continue

                // Every segment gets transcribed even if a stop has already landed. Skipping
                // them would discard captured speech, and in this app the phrase dropped
                // could be the distress phrase.
                for (segment in segments) {
                    transcribeSegment(whisper, segment)?.let { lastText = it }
                }
            }
        } finally {
            finishUp(whisper, lastText)
        }
    }

    /**
     * Transcribe one complete utterance and publish it as a single finalized chat bubble.
     *
     * @return the text, or null if the model heard nothing in it — which happens on noise that
     *   cleared the energy gate but wasn't speech.
     */
    private suspend fun transcribeSegment(
        whisper: WhisperModel,
        segment: AudioSegmenter.Segment,
    ): String? {
        val text = cleanTranscriptText(
            runCatching { whisper.transcribe(segment.samples) }
                .onFailure { Log.e(TAG, "segment transcribe failed", it) }
                .getOrDefault(""),
        )
        transcribeCount++
        Log.d(
            TAG,
            "segment #$transcribeCount: ${"%.2f".format(segment.durationSeconds)}s " +
                "reason=${segment.reason} peak=${"%.3f".format(peak(segment.samples))} text='$text'",
        )
        if (text.isBlank()) return null

        // Only claim to still be live if we are. A transcribe started before a stop request
        // finishes after it, and publishing Listening then would overwrite Finishing and make
        // the UI look live again while the loop is already winding down.
        if (recorder.isRecording) {
            ListeningStateHolder.setState(TranscriptionUiState.Listening(text))
        }
        ListeningStateHolder.updateActiveMessage(text)
        // The notification is deliberately not updated with the transcript: it would put the
        // user's speech in the notification shade for anyone holding the phone to read.

        // Each segment is a whole utterance, so the bubble closes as soon as it is judged —
        // no separate rollover or timer is needed to finalize it.
        val triggered = analyze(text)
        ListeningStateHolder.finalizeActiveMessage(toolInvoked = triggered)
        return text
    }

    /**
     * Run the completed text through the emergency pipeline, which decides, sends the SMS
     * and writes the trigger log.
     *
     * @return true if an emergency alert fired.
     */
    private suspend fun analyze(text: String): Boolean {
        ListeningStateHolder.setAnalysis(AnalysisState.Analyzing)
        val triggered = runCatching { EmergencyPipeline.processTranscript(this, text) }
            .onFailure { Log.e(TAG, "emergency pipeline failed", it) }
            .getOrDefault(false)
        if (triggered) {
            ListeningStateHolder.setAnalysis(AnalysisState.Emergency(EMERGENCY_LABEL))
        } else {
            ListeningStateHolder.setAnalysis(AnalysisState.Safe)
        }
        return triggered
    }

    /**
     * Drain the last of the audio and emit the utterance still in flight, so a session stopped
     * mid-sentence doesn't lose its final phrase.
     */
    private suspend fun finishUp(whisper: WhisperModel, lastText: String) {
        // recorder.stop() joins the capture thread and releases AudioRecord; it also hands
        // back the tail audio captured since the last drain.
        val tailAudio = runCatching { recorder.stop() }.getOrDefault(FloatArray(0))

        var finalText = lastText
        // The tail may itself contain a complete utterance plus the start of another; feed it
        // through normally first, then force out whatever is left open.
        val pending = runCatching { segmenter.offer(tailAudio) }.getOrDefault(emptyList())
        for (segment in pending) {
            runCatching { transcribeSegment(whisper, segment) }.getOrNull()?.let { finalText = it }
        }
        runCatching { segmenter.flush() }.getOrNull()?.let { tail ->
            runCatching { transcribeSegment(whisper, tail) }.getOrNull()?.let { finalText = it }
        }

        // Closed here rather than in onDestroy so it always happens on the thread that runs
        // inference. transcribe() is a blocking native call that job cancellation cannot
        // interrupt, so destroying the ExecuTorch modules from the main thread could free
        // them mid-forward() — a native use-after-free.
        model?.close()
        model = null

        ListeningStateHolder.finalizeActiveMessage(toolInvoked = false)
        // A read error means capture died on us rather than being stopped — report that
        // instead of a clean result, which would otherwise look like "heard nothing".
        val readError = recorder.readError
        if (readError != null) {
            Log.e(TAG, "capture ended abnormally: $readError")
            fail(getString(R.string.listening_error_mic, readError))
        } else {
            ListeningStateHolder.setState(
                TranscriptionUiState.Result(
                    finalText.ifBlank { getString(R.string.listening_no_speech) },
                ),
            )
        }
        stopSelfAndCleanUp()
    }

    // --- lifecycle helpers -----------------------------------------------------

    private fun fail(message: String) {
        ListeningStateHolder.setState(TranscriptionUiState.Error(message))
    }

    private fun stopSelfAndCleanUp() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        if (recorder.isRecording) runCatching { recorder.stop() }
        // Only safe to close from here if no inference can be in flight. When the loop is
        // still active, cancelling it runs its finally block, which closes the model on the
        // inference thread instead.
        if (streamJob?.isActive != true) {
            model?.close()
            model = null
        }
        releaseWakeLock()
        streamJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * A foreground service keeps the process alive but does not keep the CPU awake once
     * the screen is off, which would stall transcription mid-session.
     *
     * Held without a timeout on purpose: any timeout short enough to satisfy lint would
     * silently stop transcribing partway through a long monitoring session, which is the
     * exact failure this whole change exists to fix. The lock cannot outlive its usefulness
     * — [onDestroy] releases it, and if the process is killed the OS reclaims it.
     */
    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val power = getSystemService(PowerManager::class.java) ?: return
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            // No timeout: a timeout would silently stop listening mid-session. Release is
            // guaranteed by onDestroy instead.
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
    }

    /** One update per tick at most, and only when the text actually changed. */
    private fun updateNotification(text: String) {
        if (text == lastNotificationText) return
        lastNotificationText = text
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            manager.notify(
                ListeningNotifications.NOTIFICATION_ID,
                ListeningNotifications.build(this, text),
            )
        }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun peak(samples: FloatArray): Float {
        var max = 0f
        for (s in samples) {
            val a = if (s < 0f) -s else s
            if (a > max) max = a
        }
        return max
    }

    companion object {
        private const val TAG = "ListeningService"
        private const val WAKE_LOCK_TAG = "SentriAI:listening"

        /**
         * How often the segmenter is fed. This is *not* a transcription cadence — it only sets
         * how quickly the end of an utterance is noticed, so it wants to be well under the
         * hangover window rather than as long as a transcription takes.
         */
        private const val DRAIN_INTERVAL_MS = 150L

        private const val EMERGENCY_LABEL = "Detected"

        const val ACTION_START = "com.example.sentriai.action.START_LISTENING"
        const val ACTION_STOP = "com.example.sentriai.action.STOP_LISTENING"

        /**
         * Start listening. Only legal while the app is visible — see the class docs.
         * Uses [Context.startForegroundService] on API 26+ so the platform expects the
         * startForeground call that follows.
         */
        fun start(context: Context) {
            val intent = Intent(context, ListeningService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Safe to call from anywhere: stopping an already-foreground service is allowed. */
        fun stop(context: Context) {
            val intent = Intent(context, ListeningService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
                .onFailure { Log.w(TAG, "stop dispatch failed", it) }
        }
    }
}
