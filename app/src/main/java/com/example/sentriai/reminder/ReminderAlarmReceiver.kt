package com.example.sentriai.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.sentriai.care.CareScheduleStore

/**
 * The middle link in the chain: alarm fires → this wakes up → the overlay service starts.
 *
 * ```
 *   AlarmManager ──▶ ReminderAlarmReceiver ──▶ ReminderOverlayService ──▶ overlay window
 *                             │
 *                             └──▶ ReminderScheduler.sync()   (arms the following reminder)
 * ```
 *
 * A receiver cannot do the UI work itself — its process is killable the moment [onReceive]
 * returns, and the overlay has to outlive that by minutes — so its whole job is to decide what is
 * due and hand it to the service.
 *
 * ### Starting a foreground service from here is legal
 *
 * API 31+ blocks background foreground-service starts, and an alarm receiver is background. The
 * exemption that applies is the one for exact alarms: delivering `setAlarmClock` /
 * `setExact*` puts the app on a short temporary allowlist, and that window is what [onReceive]
 * spends. This is also why the service is started synchronously rather than from a coroutine —
 * the allowlist is not held open for work that starts after the receiver has returned.
 *
 * The schedule is read from disk inline for the same reason. It is one small JSON document, well
 * inside the time a receiver is given, and moving it off-thread would move the service start
 * outside the window that permits it.
 */
class ReminderAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ReminderScheduler.ACTION_REMINDER_DUE -> onScheduledReminder(context, intent)
            ReminderScheduler.ACTION_SNOOZED_REMINDER_DUE -> onSnoozedReminder(context)
            ACTION_SNOOZE_FROM_NOTIFICATION -> onSnoozeFromNotification(context, intent)
            else -> Log.w(TAG, "unexpected action ${intent.action}, ignoring")
        }
    }

    private fun onScheduledReminder(context: Context, intent: Intent) {
        val minutes = intent.getIntExtra(ReminderScheduler.EXTRA_MINUTES, -1)
        if (minutes < 0) {
            Log.w(TAG, "alarm without a minute, cannot tell what is due")
            ReminderScheduler.sync(context)
            return
        }

        // Rebuilt from the schedule as it stands now, not from the alarm's extras: the dose may
        // have been moved, deleted or ticked off since this alarm was set.
        val batch = ReminderPlan.batchAt(CareScheduleStore.load(context), minutes)
        if (batch.items.isEmpty()) {
            Log.i(TAG, "nothing due at $minutes min any more, only rescheduling")
        } else {
            ReminderOverlayService.show(context, batch)
        }

        // Always last, and always run: the chain only continues because each fired alarm arms the
        // next one. Passing the minute just handled keeps nextBatch() from picking it again.
        ReminderScheduler.sync(context, afterMinutes = minutes)
    }

    private fun onSnoozedReminder(context: Context) {
        val snoozed = ReminderSettings.snoozedBatch(context)
        // Cleared before anything else can fail: a snooze that has fired is spent, and leaving it
        // stored would let a later reboot restore an alarm for a moment already past.
        ReminderSettings.clearSnooze(context)
        if (snoozed == null) {
            Log.w(TAG, "snooze fired with no stored batch, nothing to show")
            return
        }

        // A dose ticked off during the snooze should not come back. Intersecting against what is
        // currently outstanding also drops a medicine deleted in the meantime.
        val outstanding = ReminderPlan.items(CareScheduleStore.load(context), includeCompleted = false)
            .map { it.key }
            .toSet()
        val items = snoozed.items.filter { it.key in outstanding }
        if (items.isEmpty()) {
            Log.i(TAG, "snoozed reminder no longer outstanding, nothing to show")
            return
        }

        ReminderOverlayService.show(context, snoozed.copy(items = items))
    }

    private fun onSnoozeFromNotification(context: Context, intent: Intent) {
        val batch = ReminderBatch.fromJsonStringOrNull(intent.getStringExtra(EXTRA_BATCH_JSON))
        if (batch == null) {
            Log.w(TAG, "snooze action without a batch, ignoring")
            return
        }
        ReminderNotifications.cancelAlert(context)
        ReminderScheduler.snooze(context, batch)
    }

    companion object {
        private const val TAG = "ReminderAlarmReceiver"

        /**
         * Snooze tapped on the fallback notification rather than on the overlay — the path taken
         * when the screen was locked or the overlay grant is missing.
         */
        const val ACTION_SNOOZE_FROM_NOTIFICATION = "com.example.sentriai.action.SNOOZE_FROM_NOTIFICATION"

        const val EXTRA_BATCH_JSON = "batch_json"
    }
}
