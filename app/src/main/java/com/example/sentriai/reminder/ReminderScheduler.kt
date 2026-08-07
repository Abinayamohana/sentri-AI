package com.example.sentriai.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.sentriai.care.CareScheduleStore
import java.util.Calendar

/**
 * The only place that talks to [AlarmManager].
 *
 * Everything about *which* reminder is next lives in [ReminderPlan]; this translates the answer
 * into an alarm and holds the two `PendingIntent`s the chain uses.
 *
 * ### Why two request codes and no more
 *
 * [REQUEST_DAILY] carries the rolling schedule and [REQUEST_SNOOZE] carries at most one snooze.
 * Reusing a request code is what makes both idempotent: scheduling again replaces the pending
 * alarm rather than adding one, so [sync] can be called freely — on app start, on every schedule
 * edit, on boot — without ever accumulating duplicate alarms. It is also what gives Snooze the
 * behaviour it needs, where snoozing three times leaves one pending alarm and not three.
 *
 * ### Why `setAlarmClock`
 *
 * A missed dose is the failure this feature exists to prevent, and `setAlarmClock` is the only
 * exact-alarm API the platform treats as unmissable: it is not deferred by Doze and not batched.
 * The cost is an alarm icon in the status bar, which for a medication reminder is honest rather
 * than unwanted. `setExactAndAllowWhileIdle` is the fallback, and it is a real downgrade — in
 * deep Doze the platform only lets one through every ~15 minutes per app, so two reminders close
 * together can be delayed. Both need the "Alarms & reminders" grant on API 31+; without it the
 * inexact `set` is all that remains and the reminder becomes approximate.
 */
object ReminderScheduler {

    private const val TAG = "ReminderScheduler"

    const val ACTION_REMINDER_DUE = "com.example.sentriai.action.REMINDER_DUE"
    const val ACTION_SNOOZED_REMINDER_DUE = "com.example.sentriai.action.SNOOZED_REMINDER_DUE"

    /** The minute of the day the alarm was set for, so the receiver need not re-derive it. */
    const val EXTRA_MINUTES = "minutes"

    private const val REQUEST_DAILY = 2001
    private const val REQUEST_SNOOZE = 2002

    /**
     * Brings the alarm state in line with the stored schedule: schedules the next batch, or
     * cancels everything when reminders are off or the schedule implies none.
     *
     * Safe to call repeatedly and from any thread. Reads the schedule from disk, so callers on
     * the main thread should be doing it at a moment where a small file read is acceptable —
     * app start and a save from the editor both are.
     *
     * @param afterMinutes the minute to search forward from. Defaults to now; the receiver passes
     *   the minute it has just handled, so the alarm it just consumed cannot be picked again.
     */
    fun sync(context: Context, afterMinutes: Int? = null) {
        val appContext = context.applicationContext
        if (!ReminderSettings.isEnabled(appContext)) {
            Log.i(TAG, "reminders disabled, cancelling all alarms")
            cancelAll(appContext)
            return
        }

        // Nothing saved yet means the meal times on screen are the placeholder defaults, not
        // anybody's actual schedule. Reminding at 8:00 for a breakfast nobody entered would be
        // inventing an instruction, so the chain stays disarmed until there is a schedule.
        if (!CareScheduleStore.hasStoredSchedule(appContext)) {
            Log.i(TAG, "no schedule saved yet, nothing to arm")
            cancel(appContext, REQUEST_DAILY, ACTION_REMINDER_DUE)
            return
        }

        val now = Calendar.getInstance()
        val schedule = CareScheduleStore.load(appContext)
        val batch = ReminderPlan.nextBatch(schedule, afterMinutes ?: ReminderPlan.minutesOfDay(now))
        if (batch == null) {
            Log.i(TAG, "nothing to remind about, cancelling daily alarm")
            cancel(appContext, REQUEST_DAILY, ACTION_REMINDER_DUE)
            return
        }

        val triggerAt = ReminderPlan.triggerAtMillis(batch, now)
        schedule(
            context = appContext,
            requestCode = REQUEST_DAILY,
            action = ACTION_REMINDER_DUE,
            minutes = batch.minutes,
            triggerAtMillis = triggerAt,
        )
        Log.i(
            TAG,
            "next reminder in ${(triggerAt - now.timeInMillis) / 60_000} min " +
                "(${batch.items.size} item(s) at ${batch.minutes} min of day)",
        )
    }

    /**
     * Pushes [batch] out by [ReminderSettings.SNOOZE_MINUTES], replacing any pending snooze.
     *
     * The daily alarm is left completely alone — a snooze is a one-off, and tomorrow's dose is
     * still tomorrow's dose. Snoozing at 23:55 therefore fires at 00:05 the next day, which is
     * the same reminder arriving late rather than a schedule that has drifted.
     */
    fun snooze(context: Context, batch: ReminderBatch) {
        val appContext = context.applicationContext
        val dueAt = System.currentTimeMillis() + ReminderSettings.SNOOZE_MINUTES * 60_000L
        // Stored before the alarm is set so a reboot in between loses the alarm, not the batch —
        // ReminderBootReceiver can then put it back.
        ReminderSettings.putSnooze(appContext, batch, dueAt)
        schedule(
            context = appContext,
            requestCode = REQUEST_SNOOZE,
            action = ACTION_SNOOZED_REMINDER_DUE,
            minutes = batch.minutes,
            triggerAtMillis = dueAt,
        )
        Log.i(TAG, "snoozed ${batch.items.size} item(s) for ${ReminderSettings.SNOOZE_MINUTES} min")
    }

    /**
     * Restores a snooze that a restart dropped, or forgets it if its moment has passed.
     *
     * A snooze whose time went by while the phone was off is not re-fired: the person would get a
     * reminder for a dose that is now hours old, with no indication of that. Dropping it is the
     * quieter wrong answer, and the day's next regular reminder is unaffected either way.
     */
    fun restoreSnoozeIfPending(context: Context) {
        val appContext = context.applicationContext
        val batch = ReminderSettings.snoozedBatch(appContext) ?: return
        val dueAt = ReminderSettings.snoozeDueAtMillis(appContext)
        if (dueAt <= System.currentTimeMillis()) {
            Log.i(TAG, "pending snooze already expired, dropping it")
            ReminderSettings.clearSnooze(appContext)
            return
        }
        schedule(
            context = appContext,
            requestCode = REQUEST_SNOOZE,
            action = ACTION_SNOOZED_REMINDER_DUE,
            minutes = batch.minutes,
            triggerAtMillis = dueAt,
        )
        Log.i(TAG, "restored pending snooze")
    }

    /** Drops a pending snooze. Stop uses this so a dismissed reminder cannot come back. */
    fun cancelSnooze(context: Context) {
        val appContext = context.applicationContext
        ReminderSettings.clearSnooze(appContext)
        cancel(appContext, REQUEST_SNOOZE, ACTION_SNOOZED_REMINDER_DUE)
    }

    fun cancelAll(context: Context) {
        cancel(context, REQUEST_DAILY, ACTION_REMINDER_DUE)
        cancelSnooze(context)
    }

    // ---- internals ----------------------------------------------------------------

    private fun schedule(
        context: Context,
        requestCode: Int,
        action: String,
        minutes: Int,
        triggerAtMillis: Long,
    ) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: run {
            Log.e(TAG, "no AlarmManager, cannot schedule")
            return
        }
        val pendingIntent = pendingIntent(context, requestCode, action, minutes)

        // Each branch is a real downgrade from the one above it; see the class comment.
        runCatching {
            when {
                ReminderPermissions.canScheduleExactAlarms(context) ->
                    manager.setAlarmClock(
                        AlarmManager.AlarmClockInfo(triggerAtMillis, pendingIntent),
                        pendingIntent,
                    )

                else -> {
                    Log.w(TAG, "no exact-alarm grant, falling back to an inexact alarm")
                    manager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                }
            }
        }.onFailure {
            // canScheduleExactAlarms() and the actual call are not atomic — the grant can be
            // revoked in between, and the platform throws SecurityException rather than
            // downgrading. An approximate reminder beats a crash in a receiver or on a save.
            Log.w(TAG, "exact alarm refused, falling back to an inexact alarm: ${it.message}")
            runCatching { manager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent) }
                .onFailure { failure -> Log.e(TAG, "could not schedule any alarm", failure) }
        }
    }

    /**
     * `FLAG_NO_CREATE`, so cancelling never builds a `PendingIntent` and never touches the extras
     * of the one already out there.
     *
     * With `FLAG_UPDATE_CURRENT` — the flag [pendingIntent] uses for scheduling — this would
     * rewrite the live alarm's minute to whatever placeholder was passed in on its way to
     * cancelling it. Harmless while cancel is always followed by a schedule, and a corrupted alarm
     * the first time it is not.
     */
    private fun cancel(context: Context, requestCode: Int, action: String) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, ReminderAlarmReceiver::class.java).setAction(action)
        val existing = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return // nothing pending
        manager.cancel(existing)
        existing.cancel()
    }

    /**
     * `FLAG_UPDATE_CURRENT` is what makes reusing a request code work: the extras of the existing
     * `PendingIntent` are overwritten with the new minute instead of the stale one being kept.
     */
    private fun pendingIntent(
        context: Context,
        requestCode: Int,
        action: String,
        minutes: Int,
    ): PendingIntent {
        val intent = Intent(context, ReminderAlarmReceiver::class.java)
            .setAction(action)
            .putExtra(EXTRA_MINUTES, minutes)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
