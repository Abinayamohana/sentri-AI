package com.example.sentriai.engine

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.sentriai.models.ModelCatalog
import com.example.sentriai.models.ModelStore
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/** Emergency categories understood by the whole pipeline. */
object EmergencyType {
    const val FALL = "Fall"
    const val MEDICAL = "Medical Distress"
    const val HELP = "Help Call"
    const val PHRASE = "Distress Phrase"
}

/**
 * The detection stage's verdict on one utterance. Only produced when an emergency is believed
 * to be happening — absence of a decision is the "nothing to do" case.
 */
data class EmergencyDecision(
    val emergencyType: String,
    val triggerPhrase: String,
    val confidence: Float,
    val source: Source,
) {
    /** Which detector fired, for the trigger log and for debugging false positives. */
    enum class Source {
        PHRASE_TRIGGER,
        EXPLICIT_PLEA,
        CLASSIFIER,

        /** Distress heard during a live Agora conversation — see `ConversationDistress`. */
        CONVERSATION,

        /** The agent's own structured `[[SENTRI_ALERT:…]]` signal during a live conversation. */
        AGENT_SIGNAL,

        /** A live conversation went quiet immediately after a distress cue. */
        SILENCE_AFTER_DISTRESS,

        /** The user pressed the SOS button. No detector was involved. */
        MANUAL_SOS,
    }
}

/**
 * Decides *whether* an utterance is an emergency. Nothing downstream re-litigates that call.
 *
 * Three detectors, in order, each short-circuiting the next:
 *
 * 1. **Phrase trigger** — the user deliberately repeating the safe word.
 * 2. **Explicit plea** — the person actually says "help". Deterministic.
 * 3. **Classifier LLM** — a general instruction-tuned Gemma, asked a yes/no question about
 *    whether this is an emergency, then a second question for the category.
 *
 * The plea check is *not* the return of the general keyword scan that was removed. That one
 * guessed at emergencies from words like "fell" and "dizzy", matched "that was really helpful",
 * and found nothing the model had not already found. This matches only utterances that **are**
 * a call for help — the one thing a monitoring device must never miss, and the one thing the
 * classifier measurably fails at: asked about a bare `"Help"`, it answered NO.
 *
 * That failure is structural rather than a tuning accident. The yes/no prompt tells the model
 * to answer NO to fragments and greetings, and a single word with no context reads as exactly
 * that. Widening the prompt to catch it swung the model to answering YES to everything —
 * measured, twice. So the plea stays in code, where it is deterministic and testable.
 *
 * Beyond that, **the classifier is the only judge of unprompted speech**. If its model is not
 * loaded, the safe word and the plea check are all that remain — see [isClassifierReady].
 */
object EmergencyDetector {

    private const val TAG = "EmergencyDetector"

    private const val TRIGGER_PHRASE = "Mimi"
    private const val PHRASE_TRIGGER_THRESHOLD = 3

    // Whisper's mis-transcriptions of "Mimi" swap only the i/e vowel slots (Mime, Memi, Meme) —
    // acoustically close front vowels — so the match is restricted to those four combinations
    // and deliberately excludes other m+vowel+m+vowel words like "Mama" or "Momo".
    private val phraseOccurrenceRegex = Regex("\\bm[ie]m[ie]\\b", RegexOption.IGNORE_CASE)

    // Each transcript is one isolated VAD utterance with no memory of prior calls, so counting
    // repetitions of the safe word across separate utterances has to be tracked here.
    private val phraseOccurrenceCount = AtomicInteger(0)

    /** Clears the safe-word streak. Exists so tests can start from a known state. */
    internal fun resetPhraseStreak() = phraseOccurrenceCount.set(0)

    // Confidence is a per-source prior, not a probability the model reported — neither detector
    // emits a calibrated score. It exists so the caregiver SMS and the trigger log can rank
    // events by how certain the trigger was.
    private const val CONFIDENCE_PHRASE = 0.98f
    private const val CONFIDENCE_PLEA = 0.95f
    private const val CONFIDENCE_CLASSIFIER = 0.90f

    /**
     * A direct call for help, as opposed to a description of one.
     *
     * Word boundaries do the heavy lifting: `\bhelp\b` matches "Help", "help me" and "somebody
     * help", but not "helpful", "helped" or "helping" — which is what made the old keyword scan
     * fire on "that was really helpful, thank you".
     *
     * The list stays this short on purpose. Every addition is a chance to text a caregiver over
     * a television programme, so it holds only words that are themselves a request for
     * assistance.
     */
    private val explicitPleaRegex = Regex(
        """\b(help|sos|ambulance|emergency)\b|\bcall\s+(?:911|999|112|108)\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * @return a HELP decision when [transcript] is a plea, regardless of how short it is.
     *
     * The category is fixed rather than asked of the model: someone calling for help has
     * already said what matters, and spending ~600 ms to refine the label is the wrong
     * trade when the point is to get the SMS out.
     */
    internal fun explicitPlea(transcript: String): EmergencyDecision? =
        if (explicitPleaRegex.containsMatchIn(transcript)) {
            EmergencyDecision(
                emergencyType = EmergencyType.HELP,
                triggerPhrase = transcript,
                confidence = CONFIDENCE_PLEA,
                source = EmergencyDecision.Source.EXPLICIT_PLEA,
            )
        } else {
            null
        }

    @Volatile
    private var classifier: LlmInference? = null

    @Volatile
    var lastError: String? = null
        private set

    /** True when the classifier LLM is loaded. False leaves the safe word as the only detector. */
    fun isClassifierReady(): Boolean = classifier != null

    /**
     * Loads the classifier model.
     *
     * @return true if the classifier is available. False is not fatal — detection still runs on
     *   the phrase trigger — but that is close to the feature being off, so the UI says so.
     */
    suspend fun initialize(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (classifier != null) return@withContext true

        val modelFile = ModelStore.resolve(context, ModelCatalog.CLASSIFIER).getOrElse { e ->
            lastError = "Classifier model unavailable: ${e.message}"
            Log.w(TAG, lastError!!)
            return@withContext false
        }

        try {
            Log.i(TAG, "Loading classifier from ${modelFile.absolutePath}")
            classifier = LlmInference.createFromOptions(
                context,
                LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelFile.absolutePath)
                    .setMaxTokens(1024)
                    .build(),
            )
            lastError = null
            Log.i(TAG, "Classifier ready.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Classifier load failed: ${e.message}", e)
            classifier = null
            lastError = e.message ?: e.javaClass.simpleName
            false
        }
    }

    /**
     * @return a decision when this utterance is an emergency, or null when it is not.
     *
     * Every utterance produces exactly one summary line naming what each of the three detectors
     * thought, whether or not anything fired. Without that, a quiet log is ambiguous — it could
     * mean the classifier ran and said NONE, or that it was never loaded and nothing is
     * watching unprompted speech at all.
     */
    suspend fun detect(transcript: String): EmergencyDecision? = withContext(Dispatchers.Default) {
        if (transcript.isBlank()) return@withContext null

        val phrase = checkPhraseTrigger(transcript)
        val streak = phraseOccurrenceCount.get()

        // The safe word is a deliberate act, so it settles the matter before a model is asked.
        // The classifier is still skipped rather than run-and-ignored: it costs ~2 s.
        if (phrase != null) {
            log(transcript, phrase, "SKIPPED (phrase trigger fired)", streak)
            return@withContext phrase
        }

        // Ahead of the model, not alongside it. Asking the classifier about "Help" wastes
        // ~600 ms and gets NO, and there is nothing it could add: the person has already said
        // the word.
        val plea = explicitPlea(transcript)
        if (plea != null) {
            log(transcript, plea, "SKIPPED (explicit plea)", streak)
            return@withContext plea
        }

        val started = SystemClock.elapsedRealtime()
        val verdict = classify(transcript)
        val elapsedMs = SystemClock.elapsedRealtime() - started

        // Anything other than a clean emergency label — NONE, an unparseable answer, a failed
        // inference, no model at all — is treated as "not an emergency". With nothing behind
        // it, an unusable answer is a miss rather than a deferral, which is exactly why
        // [ClassifierVerdict] keeps those cases distinct in the log.
        val decision = (verdict as? ClassifierVerdict.Emergency)?.let {
            EmergencyDecision(
                emergencyType = it.type,
                triggerPhrase = transcript,
                confidence = CONFIDENCE_CLASSIFIER,
                source = EmergencyDecision.Source.CLASSIFIER,
            )
        }

        log(
            transcript = transcript,
            decision = decision,
            classifier = "${verdict.describe()} in ${elapsedMs}ms",
            streak = streak,
        )
        decision
    }

    /** One line per utterance, at W when something fired so it stands out in a busy log. */
    private fun log(
        transcript: String,
        decision: EmergencyDecision?,
        classifier: String,
        streak: Int,
    ) {
        val outcome = if (decision == null) {
            "NONE"
        } else {
            "EMERGENCY ${decision.emergencyType} via ${decision.source}"
        }
        val line = "verdict \"$transcript\" -> $outcome " +
            "| classifier=$classifier phrase=$streak/$PHRASE_TRIGGER_THRESHOLD"

        if (decision != null) Log.w(TAG, line) else Log.d(TAG, line)
    }

    /**
     * What the classifier LLM concluded, kept distinct from "no decision" so the log can tell
     * a confident NONE apart from a model that never ran. Those look identical in the outcome
     * but mean completely different things when a real emergency is missed.
     */
    private sealed interface ClassifierVerdict {
        /** No model loaded — nothing is watching unprompted speech. */
        data object Unavailable : ClassifierVerdict

        /** Inference threw. */
        data class Failed(val reason: String) : ClassifierVerdict

        /** Ran, but the response held none of the four labels. */
        data class Unparsed(val raw: String) : ClassifierVerdict

        /** Ran and said this is not an emergency. */
        data object None : ClassifierVerdict

        data class Emergency(val type: String) : ClassifierVerdict

        fun describe(): String = when (this) {
            Unavailable -> "UNAVAILABLE (model not loaded)"
            is Failed -> "FAILED ($reason)"
            is Unparsed -> "UNPARSED (\"${raw.take(40).replace('\n', ' ')}\")"
            None -> "NONE"
            is Emergency -> type
        }
    }

    /**
     * Decides in two questions rather than one.
     *
     * A four-way "pick a label" prompt asks a 1B model to weigh four options against a short,
     * often near-empty transcript, and measured on device it labelled ordinary speech an
     * emergency far too readily. A yes/no question is a much easier judgement for a model this
     * size, and the type only has to be settled once the answer is already yes — which is rare,
     * so the second inference costs nothing in the common case.
     */
    private fun classify(transcript: String): ClassifierVerdict {
        if (classifier == null) return ClassifierVerdict.Unavailable

        return try {
            val answer = ask(buildClassifierPrompt(transcript)) ?: return ClassifierVerdict.Failed("no session")
            Log.d(TAG, "Classifier emergency? -> \"${answer.take(60)}\"")

            when {
                Regex("\\bNO\\b").containsMatchIn(answer.uppercase()) -> ClassifierVerdict.None
                !Regex("\\bYES\\b").containsMatchIn(answer.uppercase()) -> ClassifierVerdict.Unparsed(answer)
                else -> {
                    val typed = ask(buildTypePrompt(transcript)).orEmpty()
                    Log.d(TAG, "Classifier type -> \"${typed.take(60)}\"")
                    ClassifierVerdict.Emergency(parseType(typed))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Classifier inference failed: ${e.message}", e)
            ClassifierVerdict.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** One greedy completion in a throwaway session, so no turn conditions the next. */
    private fun ask(prompt: String): String? {
        val inference = classifier ?: return null
        var session: LlmInferenceSession? = null
        return try {
            session = LlmInferenceSession.createFromOptions(
                inference,
                LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    // topK 1 makes decoding greedy, so the same utterance always produces the
                    // same verdict. A safety device that answers differently on a retry is not
                    // something a caregiver can trust or a test can pin down.
                    .setTopK(1)
                    .setTemperature(0.0f)
                    .build(),
            )
            session.addQueryChunk(prompt)
            session.generateResponse()
        } finally {
            runCatching { session?.close() }
        }
    }

    /**
     * Maps the follow-up answer to a category. Defaults to [EmergencyType.HELP] rather than
     * failing: the first question already established that this is an emergency, so an
     * unrecognised label must not be allowed to cancel the alert — only to mislabel it.
     */
    private fun parseType(response: String): String =
        when (Regex("\\b(FALL|MEDICAL|HELP)\\b").find(response.uppercase())?.value) {
            "FALL" -> EmergencyType.FALL
            "MEDICAL" -> EmergencyType.MEDICAL
            else -> EmergencyType.HELP
        }

    /**
     * The `.task` bundle's metadata already wraps this in `<bos><start_of_turn>user ... model`,
     * so the prompt is written as plain text with no control tokens of its own.
     *
     * Two things here are load-bearing, both learned from this model getting it wrong on device:
     *
     * - **The query is not another row of the example list.** The previous version ended with
     *   `Transcript: … / Answer:` in the same shape as the examples, and one example was
     *   `"Hello." -> NONE`. Asked to classify a real "Hello.", the model answered MEDICAL and
     *   sent a live SMS: continuing a list where the query duplicates the last row pushes a
     *   small model toward *not* repeating the answer it just gave. The examples are now a
     *   labelled block and the query is a separate instruction.
     * - **NONE is stated as the default.** Left to infer a prior, this model reaches for an
     *   emergency label on near-empty input.
     *
     * Examples are deliberately not reused as test phrases in `EmergencyDetectorDeviceTest` —
     * scoring a model on its own few-shot examples measures nothing.
     */
    private fun buildClassifierPrompt(transcript: String): String = """
        You are a safety monitor for an elderly person who lives alone.

        This is something they just said:
        "${transcript.replace('"', '\'')}"

        Is this person reporting an emergency happening to them right now — they have fallen and
        cannot get up, they are having a medical crisis, or they are calling for help?

        Answer NO if they are simply talking, greeting someone, telling a story about somebody
        else, or mentioning doctors or medicine in passing.

        Answer YES or NO.
    """.trimIndent()

    /**
     * Asked only after [buildClassifierPrompt] has already answered YES, so the model is
     * choosing a label rather than deciding whether anything happened.
     */
    private fun buildTypePrompt(transcript: String): String = """
        An elderly person who lives alone said this:
        "${transcript.replace('"', '\'')}"

        What kind of emergency is it?
        FALL - they have fallen, slipped or tripped, or cannot get up
        MEDICAL - a medical crisis such as chest pain, trouble breathing, or bleeding
        HELP - they are calling for help

        Answer with one word: FALL, MEDICAL or HELP.
    """.trimIndent()

    /**
     * Deterministically tracks repetitions of the safe word across successive utterances. A
     * single utterance may contain it several times, or the user may repeat it across separate
     * utterances with pauses between — either counts toward the same streak. The streak resets
     * whenever an utterance does not contain the phrase at all.
     */
    internal fun checkPhraseTrigger(transcript: String): EmergencyDecision? {
        val occurrences = phraseOccurrenceRegex.findAll(transcript).count()
        if (occurrences == 0) {
            phraseOccurrenceCount.set(0)
            return null
        }

        val total = phraseOccurrenceCount.addAndGet(occurrences)
        if (total < PHRASE_TRIGGER_THRESHOLD) return null

        phraseOccurrenceCount.set(0)
        return EmergencyDecision(
            emergencyType = EmergencyType.PHRASE,
            triggerPhrase = "\"$TRIGGER_PHRASE\" repeated ${total}x",
            confidence = CONFIDENCE_PHRASE,
            source = EmergencyDecision.Source.PHRASE_TRIGGER,
        )
    }

    fun close() {
        runCatching { classifier?.close() }
            .onFailure { Log.e(TAG, "Error closing classifier: ${it.message}") }
        classifier = null
    }
}
