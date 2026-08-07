package com.example.sentriai.agora

import android.util.Base64
import android.util.Log
import org.json.JSONObject

/** Who said it. Derived from the wire message, not guessed from content. */
enum class Speaker { PERSON, AGENT }

/**
 * One transcript update from the conversational agent.
 *
 * [text] is the **whole turn so far**, not the latest fragment: Agora resends the accumulated
 * sentence on every update. So a consumer replaces what it was showing for [turnId] rather than
 * appending, or the screen fills with prefixes of one sentence.
 *
 * @param isFinal false while the turn is still being spoken or revised. Only final events reach
 *   the escalation monitor — see [com.example.sentriai.engine.ConversationEscalationMonitor].
 * @param turnId groups the updates of one utterance with its completed version, so the UI can
 *   replace a growing bubble instead of appending a line per revision.
 */
data class TranscriptEvent(
    val speaker: Speaker,
    val text: String,
    val isFinal: Boolean,
    val turnId: Long,
)

/**
 * Reassembles the chunked transcript messages the Conversational AI Engine sends over the RTC
 * data stream, and reads the JSON inside.
 *
 * ### The wire format
 *
 * Each `onStreamMessage` payload is UTF-8 text in four pipe-separated fields:
 *
 * ```
 * <messageId>|<partIndex>|<totalParts>|<base64 chunk>
 * ```
 *
 * `partIndex` is **1-based**. A message longer than one data-stream frame arrives as several of
 * these sharing a `messageId`; the base64 is only valid once every part has been concatenated
 * in index order, so a chunk cannot be decoded as it arrives. Decoding the reassembled string
 * gives the JSON below.
 *
 * ```json
 * {"object": "assistant.transcription", "text": "Hello. How are you?", "turn_id": 1,
 *  "turn_status": 1, "stream_id": 0, "words": [...]}
 * ```
 *
 * Three fields carry all the meaning, and the first two were verified against a live session
 * rather than taken from the docs:
 *
 * - **`object`** names the kind of message. Several kinds share this stream, and only
 *   `assistant.transcription` and `user.transcription` carry conversation text; `message.state`
 *   reports the agent's idle/listening/thinking/speaking state and has no `text` at all.
 * - **`turn_status`** is the completion flag: `0` while the turn is still being spoken, `1` when
 *   it has ended, `2` when the person interrupted it. There is **no `is_final` field**, which is
 *   what makes this worth spelling out: treating a missing flag as "complete" marks every
 *   accumulating update as a finished utterance, and the UI stacks a bubble per fragment
 *   (`"Hello,"`, `"Hello, aa."`, `"Hello, aa. It's Sentri."` …) instead of growing one. The
 *   `is_final`/`final` booleans are still read as a fallback for versions that send them.
 * - **`stream_id`** is the speaker where `object` is absent: **0 is the agent**, anything else is
 *   the RTC uid of the person who spoke.
 *
 * ### Why this is hand-rolled
 *
 * Agora publishes an Android "Conversational AI toolkit" that does this, but it ships as source
 * to vendor into your project rather than as a Maven artifact, and its newer path moves
 * transcripts onto the Signaling channel — which would add the RTM SDK as a second dependency
 * for one callback. This is the same protocol, verified against Agora's own reference
 * implementation, in one file with no extra dependency.
 *
 * Not thread-safe: [parse] is only ever called from the RTC callback thread.
 */
class ConversationMessageParser {

    /** messageId -> (partIndex -> base64 chunk). */
    private val pending = mutableMapOf<String, MutableMap<Int, String>>()

    /** messageId -> when it was last touched, for evicting messages whose parts never arrive. */
    private val lastSeen = mutableMapOf<String, Long>()

    /**
     * @return the event, or null when this was a chunk of a message that is not complete yet —
     *   which is the common case for anything longer than a short sentence.
     */
    fun parse(payload: ByteArray): TranscriptEvent? {
        evictStale()

        val raw = runCatching { String(payload, Charsets.UTF_8) }.getOrNull() ?: return null
        // limit=4 so a base64 chunk containing '|' cannot be split further. It should not, but
        // the alternative on a malformed frame is silently dropping a whole utterance.
        val parts = raw.split("|", limit = 4)
        if (parts.size != 4) {
            Log.w(TAG, "unexpected data-stream frame with ${parts.size} fields, ignoring")
            return null
        }

        val messageId = parts[0]
        val partIndex = parts[1].toIntOrNull() ?: return null
        val totalParts = parts[2].toIntOrNull() ?: return null
        val chunk = parts[3]

        if (partIndex < 1 || partIndex > totalParts) {
            Log.w(TAG, "part $partIndex of $totalParts is out of range, ignoring")
            return null
        }

        lastSeen[messageId] = System.currentTimeMillis()
        val chunks = pending.getOrPut(messageId) { mutableMapOf() }
        chunks[partIndex] = chunk
        if (chunks.size < totalParts) return null

        pending.remove(messageId)
        lastSeen.remove(messageId)

        // Every index 1..totalParts must be present. `chunks.size == totalParts` above only
        // proves the count, and a duplicated part index would satisfy it with a hole left over.
        val ordered = (1..totalParts).map { chunks[it] ?: return null }
        return decode(ordered.joinToString(""))
    }

    /** Drop half-assembled state. Called when a call ends so the next one starts clean. */
    fun reset() {
        pending.clear()
        lastSeen.clear()
    }

    private fun decode(base64: String): TranscriptEvent? {
        val json = runCatching {
            JSONObject(String(Base64.decode(base64, Base64.DEFAULT), Charsets.UTF_8))
        }.onFailure {
            Log.w(TAG, "undecodable transcript message: ${it.message}")
        }.getOrNull() ?: return null

        return eventFrom(json)
    }

    /**
     * The JSON half of [parse], split out so the wire-format rules can be tested off-device:
     * reassembly needs `android.util.Base64`, which is a stub that returns null in the JVM test
     * runtime, and these rules are the part worth pinning down.
     */
    internal fun eventFrom(json: JSONObject): TranscriptEvent? {
        val speaker = speakerOf(json) ?: return null

        val text = repairSpacing(json.optString("text").trim())
        if (text.isEmpty()) return null

        return TranscriptEvent(
            speaker = speaker,
            text = text,
            isFinal = isFinalOf(json),
            turnId = json.optLong("turn_id", 0L),
        )
    }

    /** @return null for the messages on this stream that are not conversation text. */
    private fun speakerOf(json: JSONObject): Speaker? {
        val kind = json.optString("object")
        return when {
            kind == OBJECT_AGENT -> Speaker.AGENT
            kind == OBJECT_PERSON -> Speaker.PERSON
            // A named kind this code does not know is not a transcript. Guessing from stream_id
            // here would turn every `message.state` into an empty utterance.
            kind.isNotEmpty() -> null
            else -> if (json.optLong("stream_id", 0L) == 0L) Speaker.AGENT else Speaker.PERSON
        }
    }

    private fun isFinalOf(json: JSONObject): Boolean = when {
        // 1 = ended, 2 = interrupted by the person. Both mean the text will not be revised
        // again, which is all "final" claims.
        json.has("turn_status") -> json.optInt("turn_status") != TURN_IN_PROGRESS
        // Fallbacks for versions that send a boolean instead. Agora has shipped both spellings.
        json.has("is_final") -> json.optBoolean("is_final")
        json.has("final") -> json.optBoolean("final")
        // No flag of any kind: treat the message as complete. Dropping it instead would
        // silently discard the utterance.
        else -> true
    }

    /**
     * Puts back the space after a full stop or comma that Agora's pipeline drops.
     *
     * `text` is assembled from the TTS engine's word timings, and those carry no leading
     * whitespace at a sentence start, so what arrives is `"Hello,aa.It's Sentri."`. This is
     * cosmetic for the reader but not only cosmetic: the distress patterns match on word
     * boundaries, and a glued `"I fell.Help me"` hides one.
     *
     * Two word characters are required before the punctuation so initialisms and clock times
     * survive: `"3 p.m."` must not become `"3 p. m."`. Colons are left alone — the agent's alert
     * marker contains one.
     */
    private fun repairSpacing(text: String): String =
        text.replace(GLUED_SENTENCE) { "${it.groupValues[1]} " }

    /**
     * A message whose remaining parts never arrive would otherwise sit in the map for the life
     * of the process. Frames are lost on a bad connection, which is exactly when this happens.
     */
    private fun evictStale() {
        if (lastSeen.isEmpty()) return
        val cutoff = System.currentTimeMillis() - STALE_AFTER_MS
        val dead = lastSeen.filterValues { it < cutoff }.keys
        for (id in dead) {
            pending.remove(id)
            lastSeen.remove(id)
        }
        if (dead.isNotEmpty()) Log.d(TAG, "evicted ${dead.size} incomplete transcript message(s)")
    }

    private companion object {
        const val TAG = "ConvMessageParser"
        const val STALE_AFTER_MS = 60_000L

        const val OBJECT_AGENT = "assistant.transcription"
        const val OBJECT_PERSON = "user.transcription"

        /** `turn_status` while the turn is still being spoken. */
        const val TURN_IN_PROGRESS = 0

        val GLUED_SENTENCE = Regex("""(?<=\w\w)([.,!?])(?=[A-Za-z])""")
    }
}
