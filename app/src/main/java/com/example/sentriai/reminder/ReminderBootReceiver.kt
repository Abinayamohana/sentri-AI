package com.example.sentriai.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Re-arms the reminder chain after the events that silently empty it.
 *
 * The platform drops every pending alarm on reboot, and an app that only schedules from its own
 * UI would go quiet until the next time somebody opened it — which for a reminder app is the one
 * failure nobody notices until a dose has been missed.
 *
 * The other three actions are not padding:
 *
 *  - `MY_PACKAGE_REPLACED` — an app update clears alarms in the same way a reboot does.
 *  - `TIME_SET` — the clock moved. Reminders are stored as minutes past local midnight, so the
 *    alarm's absolute instant is now wrong and has to be recomputed.
 *  - `TIMEZONE_CHANGED` — same reason. An 8:00 AM dose means 8:00 AM where the person now is.
 */
class ReminderBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            -> {
                Log.i(TAG, "re-arming reminders after ${intent.action}")
                ReminderScheduler.sync(context)
                ReminderScheduler.restoreSnoozeIfPending(context)
            }

            else -> Log.w(TAG, "unexpected action ${intent.action}, ignoring")
        }
    }

    private companion object {
        const val TAG = "ReminderBootReceiver"
    }
}
