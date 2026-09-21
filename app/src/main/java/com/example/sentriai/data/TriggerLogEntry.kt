package com.example.sentriai.data

import org.json.JSONObject

/**
 * A single recorded trigger event — one row in the alert history log.
 *
 * Serialised to/from JSON via [toJson] / [fromJson] so we can persist the
 * entire log as a plain JSON array file with no external dependencies.
 */
data class TriggerLogEntry(
    /** Unique identifier (epoch millis at creation time). */
    val id: Long = System.currentTimeMillis(),
    /** When the event was detected. */
    val timestampMillis: Long = System.currentTimeMillis(),
    /** The short phrase that triggered the detection (e.g. "Help me, I fell"). */
    val triggerPhrase: String,
    /** Category of emergency: "Fall", "Medical Distress", "Help Call", etc. */
    val emergencyType: String,
    /** Full Whisper transcript that was evaluated. */
    val fullTranscript: String,
    /** Detection confidence score, 0.0–1.0. */
    val confidenceScore: Float,
    /** Whether the SMS alert was delivered successfully. */
    val smsSuccess: Boolean,
    /** Phone number the alert was sent to. */
    val handlerNumber: String,
    /** Human-readable failure reason; null when [smsSuccess] is true. */
    val failureReason: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_ID, id)
        put(KEY_TIMESTAMP, timestampMillis)
        put(KEY_TRIGGER_PHRASE, triggerPhrase)
        put(KEY_EMERGENCY_TYPE, emergencyType)
        put(KEY_FULL_TRANSCRIPT, fullTranscript)
        put(KEY_CONFIDENCE, confidenceScore.toDouble())
        put(KEY_SMS_SUCCESS, smsSuccess)
        put(KEY_HANDLER_NUMBER, handlerNumber)
        put(KEY_FAILURE_REASON, failureReason ?: JSONObject.NULL)
    }

    companion object {
        private const val KEY_ID = "id"
        private const val KEY_TIMESTAMP = "timestamp"
        private const val KEY_TRIGGER_PHRASE = "trigger_phrase"
        private const val KEY_EMERGENCY_TYPE = "emergency_type"
        private const val KEY_FULL_TRANSCRIPT = "full_transcript"
        private const val KEY_CONFIDENCE = "confidence"
        private const val KEY_SMS_SUCCESS = "sms_success"
        private const val KEY_HANDLER_NUMBER = "handler_number"
        private const val KEY_FAILURE_REASON = "failure_reason"

        fun fromJson(json: JSONObject): TriggerLogEntry = TriggerLogEntry(
            id = json.getLong(KEY_ID),
            timestampMillis = json.getLong(KEY_TIMESTAMP),
            triggerPhrase = json.getString(KEY_TRIGGER_PHRASE),
            emergencyType = json.getString(KEY_EMERGENCY_TYPE),
            fullTranscript = json.getString(KEY_FULL_TRANSCRIPT),
            confidenceScore = json.getDouble(KEY_CONFIDENCE).toFloat(),
            smsSuccess = json.getBoolean(KEY_SMS_SUCCESS),
            handlerNumber = json.getString(KEY_HANDLER_NUMBER),
            failureReason = json.optString(KEY_FAILURE_REASON).takeIf { it != "null" && it.isNotBlank() },
        )
    }
}
