package com.example.sentriai.reminder

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.sentriai.MainActivity
import com.example.sentriai.R

/**
 * Two notifications with two different jobs, on two channels.
 *
 * **The ongoing one** ([buildOngoing], [SERVICE_CHANNEL_ID], IMPORTANCE_LOW) exists because a
 * foreground service must post something. It says the overlay is up and nothing more; it must not
 * make a sound, because the overlay on screen is already the reminder.
 *
 * **The alert** ([postAlert], [ALERT_CHANNEL_ID], IMPORTANCE_HIGH) is the fallback that carries
 * the reminder when the overlay cannot: the screen is locked, the overlay grant is missing, or
 * nobody acted on the window before it timed out. It is a separate notification with a separate
 * id on purpose — the ongoing one disappears with the service, and the whole point of the
 * fallback is to still be there afterwards.
 */
internal object ReminderNotifications {

    private const val TAG = "ReminderNotifications"

    const val SERVICE_CHANNEL_ID = "sentriai_reminder_service"
    const val ALERT_CHANNEL_ID = "sentriai_reminders_v3"



    const val SERVICE_NOTIFICATION_ID = 1101
    private const val ALERT_NOTIFICATION_ID = 1102

    private const val REQUEST_OPEN_APP = 3001
    private const val REQUEST_SNOOZE = 3002
    private const val REQUEST_FULL_SCREEN = 3003



    /** Idempotent; both channels are created together so either notification can be posted. */
    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        if (manager.getNotificationChannel(SERVICE_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    SERVICE_CHANNEL_ID,
                    context.getString(R.string.reminder_service_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.reminder_service_channel_description)
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                },
            )
        }

        if (manager.getNotificationChannel(ALERT_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    ALERT_CHANNEL_ID,
                    context.getString(R.string.reminder_alert_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.reminder_alert_channel_description)
                    enableVibration(true)
                },
            )
        }
    }

    fun buildOngoing(context: Context): Notification =
        NotificationCompat.Builder(context, SERVICE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(context.getString(R.string.reminder_service_notification_title))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    /**
     * Posts the reminder itself, as a heads-up notification with a Snooze action.
     *
     * `CATEGORY_REMINDER` rather than `CATEGORY_ALARM`: alarm-category notifications can sound
     * through Do Not Disturb, and a meal reminder is not an emergency. The SOS path is what
     * escalates in this app, and this must not borrow its volume.
     */
    // The POST_NOTIFICATIONS guard is the first thing in the body, but it goes through
    // ReminderPermissions so lint cannot see it — it only recognises an inlined
    // checkSelfPermission. Duplicating the check inline to satisfy the tool would leave two
    // copies of the same rule to keep in step, which is the worse trade.
    @SuppressLint("MissingPermission")
    fun postAlert(context: Context, batch: ReminderBatch) {
        if (batch.items.isEmpty()) return
        // Checked rather than attempted: on API 33+ a denied POST_NOTIFICATIONS makes notify() a
        // silent no-op, and a fallback that quietly does nothing is worth a log line.
        if (!ReminderPermissions.hasNotificationPermission(context)) {
            Log.w(TAG, "no notification permission; the reminder has nowhere to go")
            return
        }
        ensureChannels(context)

        val title = context.getString(
            if (batch.items.any { it.kind == ReminderKind.MEDICINE }) {
                R.string.reminder_heading_medicine
            } else {
                R.string.reminder_heading_meal
            },
        )
        val body = batch.items.joinToString("\n") { item ->
            if (item.detail.isBlank()) item.title else "${item.title} — ${item.detail}"
        }

        val openApp = PendingIntent.getActivity(
            context,
            REQUEST_OPEN_APP,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            pendingIntentFlags(),
        )
        val snooze = PendingIntent.getBroadcast(
            context,
            REQUEST_SNOOZE,
            Intent(context, ReminderAlarmReceiver::class.java)
                .setAction(ReminderAlarmReceiver.ACTION_SNOOZE_FROM_NOTIFICATION)
                .putExtra(ReminderAlarmReceiver.EXTRA_BATCH_JSON, batch.toJsonString()),
            pendingIntentFlags(),
        )

        val fullScreenIntent = Intent(context, ReminderActivity::class.java).apply {
            putExtra(ReminderActivity.EXTRA_BATCH_JSON, batch.toJsonString())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_FULL_SCREEN,
            fullScreenIntent,
            pendingIntentFlags(),
        )

        val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openApp)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .addAction(
                0,
                context.getString(R.string.reminder_snooze, ReminderSettings.SNOOZE_MINUTES),
                snooze,
            )
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()



        NotificationManagerCompat.from(context).notify(ALERT_NOTIFICATION_ID, notification)
    }

    fun cancelAlert(context: Context) {
        NotificationManagerCompat.from(context).cancel(ALERT_NOTIFICATION_ID)
    }

    /** Matches [com.example.sentriai.service.ListeningNotifications]; `minSdk` is past API 23. */
    private fun pendingIntentFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
}
