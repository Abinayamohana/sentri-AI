package com.example.sentriai.engine

import android.content.Context
import android.util.Log
import com.example.sentriai.data.TriggerLogStore
import com.example.sentriai.sms.EmergencySmsSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** How much of the pipeline is actually operational, for the status badge in the UI. */
enum class PipelineReadiness {
    /** The classifier model loaded successfully. */
    FULL,

    /**
     * The classifier LLM is not loaded, so only the safe word can raise an alert. Unprompted
     * speech is not monitored at all in this state — it is close to the feature being off.
     */
    DEGRADED,
}

/**
 * End-to-end processing for one speech transcript.
 *
 * **Stage 1 — decide.** [EmergencyDetector] answers whether this utterance is an emergency.
 * **Stage 2 — act.** Only once the answer is yes, the decision is dispatched by [EmergencySmsSender]
 * and recorded by [TriggerLogStore].
 */
object EmergencyPipeline {

    private const val TAG = "EmergencyPipeline"

    @Volatile
    var readiness: PipelineReadiness = PipelineReadiness.DEGRADED
        private set

    /**
     * Loads the classifier model.
     *
     * @return true when the classifier is available. False means detection has fallen back to
     *   the phrase trigger alone, which the UI must surface.
     */
    suspend fun initialize(context: Context): Boolean {
        val classifierReady = EmergencyDetector.initialize(context)
        readiness = if (classifierReady) PipelineReadiness.FULL else PipelineReadiness.DEGRADED
        Log.i(TAG, "Pipeline ready: classifier=$classifierReady")
        return classifierReady
    }

    /** The reason the pipeline is degraded, or null when it is fully loaded. */
    fun statusMessage(): String? =
        if (readiness == PipelineReadiness.FULL) null
        else EmergencyDetector.lastError ?: "Classifier model unavailable — safe word only"

    /**
     * @return true if an emergency alert was dispatched.
     */
    suspend fun processTranscript(context: Context, transcript: String): Boolean =
        withContext(Dispatchers.Default) {
            if (transcript.isBlank()) return@withContext false

            Log.d(TAG, "Processing transcript: \"$transcript\"")

            val decision = EmergencyDetector.detect(transcript)
            if (decision == null) {
                // The detector has already logged which detectors ran and what each concluded.
                return@withContext false
            }

            dispatch(context, decision, transcript)
        }

    /**
     * Stage 2 on its own: act on a decision that has already been made.
     *
     * [processTranscript] is stage 1 followed by this. Callers that reached a decision some
     * other way — the SOS button, or distress heard during a live Agora conversation — come
     * straight here, because re-running the detector on their behalf could only produce one new
     * outcome: a NO that discards an emergency somebody has already established. The detector
     * is tuned for unprompted speech in an empty room and answers NO to a bare "Help"; letting
     * it re-litigate a pressed SOS button would be a bug with a body count.
     *
     * Everything downstream — the SMS, the trigger log — is the same
     * code the passive path runs, so an alert raised in a conversation is indistinguishable
     * from one raised by the on-device pipeline by the time it reaches the caregiver.
     *
     * @param transcript the surrounding context recorded in the log. May be longer than
     *   [EmergencyDecision.triggerPhrase] — for a conversation it is the whole exchange.
     * @return true, always: the alert attempt happened. SMS success is recorded in the log,
     *   not returned, because a failed send is still an event the caregiver must be able to see.
     */
    suspend fun dispatch(
        context: Context,
        decision: EmergencyDecision,
        transcript: String = decision.triggerPhrase,
    ): Boolean =
        withContext(Dispatchers.Default) {
            Log.w(
                TAG,
                "DISPATCH ${decision.emergencyType} detectedBy=${decision.source} " +
                    "confidence=${"%.2f".format(decision.confidence)} phrase=\"${decision.triggerPhrase}\"",
            )

            val smsResult = EmergencySmsSender.sendEmergencyAlert(
                context = context,
                emergencyType = decision.emergencyType,
                triggerPhrase = decision.triggerPhrase,
                confidence = decision.confidence,
            )

            if (smsResult.success) {
                Log.w(TAG, "SMS sent to ${smsResult.handlerNumber}")
            } else {
                Log.e(TAG, "SMS FAILED to ${smsResult.handlerNumber}: ${smsResult.failureReason}")
            }

            TriggerLogStore.logEvent(
                context = context,
                triggerPhrase = decision.triggerPhrase,
                emergencyType = decision.emergencyType,
                fullTranscript = transcript,
                confidenceScore = decision.confidence,
                smsSuccess = smsResult.success,
                handlerNumber = smsResult.handlerNumber,
                failureReason = smsResult.failureReason,
            )

            true
        }
}
