package com.example.sentriai.reminder

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * The three consents the reminder overlay needs, and the system screens that grant them.
 *
 * All three are asked for together, because two out of three is not a working feature and the
 * failure is silent in each direction:
 *
 *  - **Display over other apps** — without it the overlay window cannot be added at all. The
 *    reminder degrades to a notification, which is exactly the thing the overlay exists to be
 *    louder than.
 *  - **Alarms & reminders** (API 31+) — without it an exact alarm is downgraded to an inexact one
 *    and a dose reminder arrives at whatever time the platform finds convenient, which for
 *    medication is the difference between a reminder and a note.
 *  - **Notifications** (API 33+) — the overlay is drawn by a foreground service, and a foreground
 *    service must post a notification. Denied, the service still runs, but the fallback path (a
 *    locked screen, a revoked overlay grant) has nowhere to put the reminder.
 *
 * None of these can be granted from an in-app dialog: the first two are Settings screens reached
 * by intent, and only the notification one is a runtime permission request.
 */
object ReminderPermissions {

    /** True when the overlay window can actually be added. */
    fun canDrawOverlay(context: Context): Boolean = Settings.canDrawOverlays(context)

    /**
     * True when [AlarmManager] will honour an exact alarm.
     *
     * Below API 31 there is nothing to grant. From 31 the app-level toggle exists and starts off
     * granted for an app that declares `SCHEDULE_EXACT_ALARM`, but the user can revoke it — and
     * from API 34 a fresh install of an app that only declares `SCHEDULE_EXACT_ALARM` starts
     * revoked, so this is checked rather than assumed.
     */
    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val manager = context.getSystemService(AlarmManager::class.java) ?: return false
        return manager.canScheduleExactAlarms()
    }

    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** Everything granted — the state in which a reminder behaves as designed. */
    fun allGranted(context: Context): Boolean =
        canDrawOverlay(context) && canScheduleExactAlarms(context) && hasNotificationPermission(context)

    /**
     * The Settings screen for the overlay grant, scoped to this app.
     *
     * `FLAG_ACTIVITY_NEW_TASK` is set so this is safe to start from a non-activity context; the
     * caller is normally an activity, but the setup card is reachable from places that are not.
     */
    fun overlaySettingsIntent(context: Context): Intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.fromParts("package", context.packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * The "Alarms & reminders" screen.
     *
     * Null below API 31, where the setting does not exist. Some OEM builds ship without the
     * activity despite being API 31+, so callers resolve it before starting it.
     */
    fun exactAlarmSettingsIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return Intent(
            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * The battery-optimisation exemption screen, for the OEM builds that kill a foreground
     * service anyway.
     *
     * Never opened automatically and never presented as required — on Xiaomi, Oppo and Samsung
     * builds an app outside the exemption list can have its service stopped between the alarm
     * firing and the overlay appearing, and there is no API that reports this having happened.
     * Offered as a last resort on the setup card when reminders are configured but not arriving.
     */
    fun batteryOptimisationSettingsIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
