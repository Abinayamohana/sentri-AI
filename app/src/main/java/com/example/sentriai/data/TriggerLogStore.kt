package com.example.sentriai.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Append-only log store backed by a single JSON file in internal storage.
 *
 * Mirrors the [ProfileStore] singleton pattern already used in this codebase.
 * Thread-safe: every public function synchronises on [LOCK] before touching
 * the file.
 *
 * **Integration point for Emergency Pipeline + SMS:**
 * Call [logEvent] at the point where the SMS send attempt completes —
 * pass in whether it succeeded and any failure reason.
 */
object TriggerLogStore {

    private const val FILE_NAME = "trigger_log.json"
    private val LOCK = Any()

    /**
     * Append a new trigger event to the log.
     *
     * This is the **single call-site** that the SMS sender should invoke once
     * an SMS delivery attempt finishes.
     *
     * ```
     * // Wire from SMS sender
     * TriggerLogStore.logEvent(
     *     context       = appContext,
     *     triggerPhrase  = "Help, I've fallen",
     *     emergencyType  = "Fall",
     *     fullTranscript = whisperTranscript,
     *     confidenceScore = 0.94f,
     *     smsSuccess     = result.isSuccess,
     *     handlerNumber  = "+1 (555) 999-9999",
     *     failureReason  = result.errorMessage,
     * )
     * ```
     */
    fun logEvent(
        context: Context,
        triggerPhrase: String,
        emergencyType: String,
        fullTranscript: String,
        confidenceScore: Float,
        smsSuccess: Boolean,
        handlerNumber: String,
        failureReason: String? = null,
    ) {
        val entry = TriggerLogEntry(
            triggerPhrase = triggerPhrase,
            emergencyType = emergencyType,
            fullTranscript = fullTranscript,
            confidenceScore = confidenceScore,
            smsSuccess = smsSuccess,
            handlerNumber = handlerNumber,
            failureReason = failureReason,
        )
        synchronized(LOCK) {
            val entries = readArray(context)
            entries.put(entry.toJson())
            writeArray(context, entries)
        }
    }

    /** Read every entry, newest first. Returns an empty list when the file doesn't exist. */
    fun loadEntries(context: Context): List<TriggerLogEntry> {
        val array = synchronized(LOCK) { readArray(context) }
        val list = mutableListOf<TriggerLogEntry>()
        for (i in 0 until array.length()) {
            try {
                list += TriggerLogEntry.fromJson(array.getJSONObject(i))
            } catch (_: Exception) {
                // Skip malformed entries silently — never crash the safety UI.
            }
        }
        // Newest first.
        return list.sortedByDescending { it.timestampMillis }
    }

    /** Quick count without fully deserialising every entry. */
    fun entryCount(context: Context): Int =
        synchronized(LOCK) { readArray(context).length() }

    /** Delete every entry. Intended for testing / developer reset only. */
    fun clearAll(context: Context) {
        synchronized(LOCK) { logFile(context).delete() }
    }

    // ---- internal helpers -------------------------------------------------------

    private fun logFile(context: Context): File =
        File(context.filesDir, FILE_NAME)

    private fun readArray(context: Context): JSONArray {
        val file = logFile(context)
        if (!file.exists()) return JSONArray()
        return try {
            JSONArray(file.readText())
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private fun writeArray(context: Context, array: JSONArray) {
        logFile(context).writeText(array.toString(/* indentSpaces = */ 2))
    }
}
