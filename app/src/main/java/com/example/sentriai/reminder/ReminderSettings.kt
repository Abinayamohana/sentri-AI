package com.example.sentriai.reminder

import android.content.Context
import android.content.SharedPreferences

/**
 * The small amount of state the reminder chain needs that is not derivable from the care
 * schedule: whether reminders are on, and the one snooze that is currently pending.
 *
 * `SharedPreferences` rather than a file, mirroring [com.example.sentriai.data.ProfileStore] —
 * two keys and a JSON blob do not want a document format.
 */
object ReminderSettings {

    private const val PREFS = "sentriai_reminders"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SNOOZE_BATCH = "snooze_batch"
    private const val KEY_SNOOZE_AT = "snooze_at"

    /** How long Snooze pushes a reminder out by. */
    const val SNOOZE_MINUTES = 10

    /**
     * Defaults to off so that users are prompted for necessary consents (notification, overlay, exact alarms)
     * when they explicitly toggle the switch on.
     */
    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /**
     * Records the snoozed batch and when it is due back.
     *
     * A single slot, deliberately: snoozing twice replaces the pending snooze instead of stacking
     * a second one, which is the same guarantee the alarm itself gets from reusing one
     * `PendingIntent` request code. The stored copy exists so [ReminderBootReceiver] can put a
     * snooze back after a restart, since the OS drops pending alarms on reboot.
     */
    fun putSnooze(context: Context, batch: ReminderBatch, dueAtMillis: Long) {
        prefs(context).edit()
            .putString(KEY_SNOOZE_BATCH, batch.toJsonString())
            .putLong(KEY_SNOOZE_AT, dueAtMillis)
            .apply()
    }

    fun clearSnooze(context: Context) {
        prefs(context).edit().remove(KEY_SNOOZE_BATCH).remove(KEY_SNOOZE_AT).apply()
    }

    fun snoozedBatch(context: Context): ReminderBatch? =
        ReminderBatch.fromJsonStringOrNull(prefs(context).getString(KEY_SNOOZE_BATCH, null))

    /** 0 when nothing is snoozed. */
    fun snoozeDueAtMillis(context: Context): Long = prefs(context).getLong(KEY_SNOOZE_AT, 0L)

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
