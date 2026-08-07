package com.example.sentriai.engine

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Watches one live conversation and decides when it has become an emergency.
 *
 * Deliberately knows nothing about Android, Agora or SMS. It is fed text and it calls
 * [onEscalate]; the caller wires that to `EmergencyPipeline.dispatch`. That boundary is what
 * makes the escalation rules testable without a device, and it is why the conversational layer
 * cannot accidentally grow its own alerting path.
 *
 * ### Three independent ways in
 *
 * 1. **What the person said.** [ConversationDistress] scans every finalized user utterance.
 *    A [ConversationDistress.Tier.CRITICAL] cue escalates immediately.
 * 2. **What the agent signalled.** The system prompt tells the agent to emit
 *    `[[SENTRI_ALERT:…]]`. Finding one escalates.
 * 3. **Silence after distress.** An [ConversationDistress.Tier.ELEVATED] cue starts
 *    [SILENCE_AFTER_DISTRESS_MS]. If the person has not spoken again by then, that is an
 *    escalation in its own right. Someone who says "I feel dizzy" and then stops answering is
 *    the case a purely keyword-driven system misses entirely, because the informative event is
 *    the *absence* of the next utterance.
 *
 * Plus a fourth, slower one: utterances that trip none of the above are handed to
 * [EmergencyDetector], so the classifier that guards the passive path is also watching here.
 *
 * ### The latch
 *
 * [escalated] is set once and never cleared for the life of the monitor. Every path in and out
 * goes through [raise], so:
 *
 * - the alert fires exactly once per call, however many ways it was detected;
 * - **nothing can retract it.** There is no code path that clears the flag, so no later turn of
 *   conversation — the agent smoothing things over, the person saying "oh I'm fine really", a
 *   summarisation deciding the exchange was benign — can unsend the alert or suppress a second
 *   one. That is a structural property of this class, not a rule someone has to remember.
 *
 * A new call gets a new monitor. The latch is per-session by construction.
 */
class ConversationEscalationMonitor(
    private val scope: CoroutineScope,
    /**
     * Invoked at most once, with the decision and the transcript recorded so far. Suspending so
     * the caller can send the SMS inline rather than fire-and-forget.
     */
    private val onEscalate: suspend (EmergencyDecision, String) -> Unit,
    /**
     * Stage 1 of the passive pipeline, injected so tests can supply a stub instead of loading a
     * 1B classifier. Returns a decision or null, and never dispatches anything itself.
     */
    private val classifierProbe: suspend (String) -> EmergencyDecision? = EmergencyDetector::detect,
) {

    private val escalated = AtomicBoolean(false)

    /** True once this conversation has raised an alert. Never returns to false. */
    val hasEscalated: Boolean get() = escalated.get()

    /**
     * Serialises cue handling. Without it a critical cue and a returning classifier probe can
     * both be mid-[raise] at once, and the transcript passed to the pipeline is whatever the
     * loser happened to see.
     */
    private val gate = Mutex()

    private val transcript = StringBuilder()
    private var silenceJob: Job? = null
    private var probeJob: Job? = null

    /** The cue that armed the silence watchdog, quoted if the watchdog ends up firing. */
    @Volatile
    private var pendingCue: ConversationDistress.Cue? = null

    /**
     * One finalized thing the person said.
     *
     * Interim (non-final) transcript fragments must not come here. The agent's ASR revises them
     * — "I feel" can become "I feel fine" — and acting on a prefix would alert on half a
     * sentence.
     */
    fun onUserSpeech(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        append("Person: $clean")

        // The person spoke, so the silence that was being timed did not happen. Cancelled before
        // anything else: re-evaluating below may arm a fresh one.
        cancelSilenceWatch("person spoke again")

        val cue = ConversationDistress.scan(clean)
        when (cue?.tier) {
            ConversationDistress.Tier.CRITICAL -> {
                Log.w(TAG, "CRITICAL cue in conversation: \"${cue.matched}\" -> ${cue.emergencyType}")
                raiseAsync(
                    EmergencyDecision(
                        emergencyType = cue.emergencyType,
                        triggerPhrase = clean,
                        confidence = CONFIDENCE_CONVERSATION,
                        source = EmergencyDecision.Source.CONVERSATION,
                    ),
                )
            }

            ConversationDistress.Tier.ELEVATED -> {
                Log.w(TAG, "elevated cue in conversation: \"${cue.matched}\" — watching for silence")
                armSilenceWatch(cue)
            }

            // Nothing obvious. Fall back to the on-device classifier, which is the only thing
            // watching for distress phrased in a way no pattern anticipated.
            null -> probeClassifier(clean)
        }
    }

    /**
     * One finalized thing the agent said.
     *
     * Note what this does *not* do: it does not touch the silence watchdog. The agent talking is
     * not evidence the person is alright — during the exact failure this class exists to catch,
     * the agent is talking and the person is not answering.
     */
    fun onAgentSpeech(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return

        val signalled = ConversationDistress.agentSignal(clean)
        append("Companion: ${ConversationDistress.stripAgentSignal(clean)}")

        if (signalled != null) {
            Log.w(TAG, "agent raised a structured signal: $signalled")
            raiseAsync(
                EmergencyDecision(
                    emergencyType = signalled,
                    // The person's own words, not the agent's paraphrase of them — the caregiver
                    // needs to read what was actually said.
                    triggerPhrase = lastUserUtterance() ?: ConversationDistress.stripAgentSignal(clean),
                    confidence = CONFIDENCE_AGENT_SIGNAL,
                    source = EmergencyDecision.Source.AGENT_SIGNAL,
                ),
            )
        }
    }

    /**
     * Escalate from outside the conversation — the SOS button pressed while a call is up.
     *
     * Goes through the same latch so the button and the conversation cannot both send.
     */
    fun onManualTrigger(reason: String) {
        raiseAsync(
            EmergencyDecision(
                emergencyType = EmergencyType.HELP,
                triggerPhrase = reason,
                confidence = CONFIDENCE_MANUAL,
                source = EmergencyDecision.Source.MANUAL_SOS,
            ),
        )
    }

    /** Call ended. Stops the watchdog; the latch is left exactly as it is. */
    fun stop() {
        cancelSilenceWatch("call ended")
        probeJob?.cancel()
        probeJob = null
    }

    /** Everything said so far, oldest first. Recorded in the trigger log on escalation. */
    fun transcriptSoFar(): String = synchronized(transcript) { transcript.toString().trim() }

    // --- internals ---------------------------------------------------------------

    private fun armSilenceWatch(cue: ConversationDistress.Cue) {
        pendingCue = cue
        silenceJob = scope.launch {
            delay(SILENCE_AFTER_DISTRESS_MS)
            Log.w(
                TAG,
                "no reply for ${SILENCE_AFTER_DISTRESS_MS}ms after \"${cue.matched}\" — escalating",
            )
            raise(
                EmergencyDecision(
                    emergencyType = cue.emergencyType,
                    triggerPhrase = "\"${cue.matched}\", then no response for " +
                        "${SILENCE_AFTER_DISTRESS_MS / 1000} seconds",
                    confidence = CONFIDENCE_SILENCE,
                    source = EmergencyDecision.Source.SILENCE_AFTER_DISTRESS,
                ),
            )
        }
    }

    private fun cancelSilenceWatch(why: String) {
        if (silenceJob?.isActive == true) Log.d(TAG, "silence watch cleared: $why")
        silenceJob?.cancel()
        silenceJob = null
        pendingCue = null
    }

    /**
     * Runs [EmergencyDetector] on an utterance no pattern matched.
     *
     * Deliberately conflating: a probe still running when the next utterance arrives is
     * cancelled. The classifier takes on the order of a second, and stacking one inference per
     * utterance behind a live call would put the answers further and further behind the
     * conversation they are about.
     *
     * Very short utterances are skipped — "yes", "okay", "hmm" carry nothing for a classifier
     * to work with, and they are most of what gets said on a companion call.
     */
    private fun probeClassifier(text: String) {
        if (text.split(Regex("\\s+")).size < MIN_WORDS_FOR_CLASSIFIER) return
        if (escalated.get()) return

        probeJob?.cancel()
        probeJob = scope.launch {
            val decision = runCatching { classifierProbe(text) }
                .onFailure { Log.e(TAG, "classifier probe failed: ${it.message}") }
                .getOrNull() ?: return@launch
            Log.w(TAG, "classifier found ${decision.emergencyType} in conversation speech")
            raise(decision.copy(source = EmergencyDecision.Source.CONVERSATION))
        }
    }

    private fun raiseAsync(decision: EmergencyDecision) {
        scope.launch { raise(decision) }
    }

    /**
     * The single gate. Every escalation path ends here, and the compare-and-set is what makes
     * the decision final.
     */
    private suspend fun raise(decision: EmergencyDecision) {
        if (!escalated.compareAndSet(false, true)) {
            Log.i(TAG, "already escalated this call; ignoring ${decision.source}")
            return
        }
        gate.withLock {
            cancelSilenceWatch("escalated")
            Log.w(
                TAG,
                "ESCALATING ${decision.emergencyType} via ${decision.source} " +
                    "phrase=\"${decision.triggerPhrase}\"",
            )
            runCatching { onEscalate(decision, transcriptSoFar()) }
                .onFailure { Log.e(TAG, "escalation handler threw: ${it.message}", it) }
        }
    }

    private fun append(line: String) {
        synchronized(transcript) {
            transcript.append(line).append('\n')
            // A long companion call would otherwise grow an unbounded string that ends up in
            // every trigger-log entry. The tail is the part that matters.
            if (transcript.length > MAX_TRANSCRIPT_CHARS) {
                transcript.delete(0, transcript.length - MAX_TRANSCRIPT_CHARS)
            }
        }
    }

    private fun lastUserUtterance(): String? = synchronized(transcript) {
        transcript.lines().lastOrNull { it.startsWith("Person: ") }?.removePrefix("Person: ")
    }

    companion object {
        private const val TAG = "ConversationEscalation"

        /**
         * How long a person may stay quiet after an elevated cue before that silence is itself
         * treated as the emergency.
         *
         * Twelve seconds is long enough to be a pause for breath and short enough to still be
         * useful. Shorter and a slow speaker triggers it; much longer and the reason for having
         * a live call rather than a passive monitor is gone.
         */
        const val SILENCE_AFTER_DISTRESS_MS = 12_000L

        private const val MIN_WORDS_FOR_CLASSIFIER = 3
        private const val MAX_TRANSCRIPT_CHARS = 4_000

        // Priors, matching the scale EmergencyDetector uses. A named phrase heard in reply to a
        // direct question is more reliable than the same words overheard, hence above the
        // classifier's 0.90; silence is inference rather than testimony, hence below it.
        private const val CONFIDENCE_CONVERSATION = 0.94f
        private const val CONFIDENCE_AGENT_SIGNAL = 0.92f
        private const val CONFIDENCE_SILENCE = 0.85f
        private const val CONFIDENCE_MANUAL = 1.0f
    }
}
