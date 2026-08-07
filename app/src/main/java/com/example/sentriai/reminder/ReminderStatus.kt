package com.example.sentriai.reminder

import android.content.Context
import android.os.Build

/**
 * A snapshot of whether reminders will actually work, for the setup card to render.
 *
 * Read fresh each time the screen resumes rather than held as state: all three consents are
 * granted on a system screen the app does not control, so the only reliable moment to know is
 * after coming back from one.
 */
data class ReminderStatus(
    val enabled: Boolean,
    val canDrawOverlay: Boolean,
    val canScheduleExactAlarms: Boolean,
    val hasNotificationPermission: Boolean,
    /**
     * False until the first read has come back.
     *
     * Without it the card would render its "two more taps" block for a frame or two on every
     * visit, including for someone who granted everything weeks ago — the read is off the main
     * thread, so the pessimistic starting values are on screen briefly first.
     */
    val loaded: Boolean = true,
) {
    /** True when nothing is left to grant, and the card can collapse to its one-line state. */
    val allGranted: Boolean
        get() = canDrawOverlay && canScheduleExactAlarms && hasNotificationPermission

    /**
     * Whether the "Alarms & reminders" row is worth showing at all — it is a setting that does
     * not exist below API 31, where an exact alarm needs no permission.
     */
    val exactAlarmSettingExists: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** Same question for the notification runtime permission, which arrived in API 33. */
    val notificationPermissionExists: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    companion object {
        /**
         * The state before anything has been read: optimistic on [enabled], so the card never
         * flashes "off" for someone who has it on, and [loaded] false so the part that depends on
         * the grants stays hidden until they are actually known.
         */
        val UNKNOWN = ReminderStatus(
            enabled = true,
            canDrawOverlay = false,
            canScheduleExactAlarms = false,
            hasNotificationPermission = false,
            loaded = false,
        )

        fun read(context: Context): ReminderStatus = ReminderStatus(
            enabled = ReminderSettings.isEnabled(context),
            canDrawOverlay = ReminderPermissions.canDrawOverlay(context),
            canScheduleExactAlarms = ReminderPermissions.canScheduleExactAlarms(context),
            hasNotificationPermission = ReminderPermissions.hasNotificationPermission(context),
            loaded = true,
        )
    }
}
