package com.example.sentriai.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.border
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.sentriai.R
import com.example.sentriai.reminder.ReminderPermissions
import com.example.sentriai.reminder.ReminderStatus

/**
 * The reminder switch, and the consents it needs to mean anything.
 *
 * Placed near the top of the Daily Companion screen rather than in a settings page, because this
 * is where a caregiver is standing when they enter their first medicine time — and a permission
 * prompt that arrives later, somewhere else, is one that gets granted after the first missed dose
 * instead of before it. Once everything is allowed the card collapses to a single line, so it
 * stops competing with the schedule it is about.
 *
 * Each row opens a system screen; none of these can be granted in-app. The notification row is
 * the exception — it is a runtime permission, so it is a real request the first time and a trip to
 * Settings only after a denial has been remembered.
 */
@Composable
internal fun ReminderSetupCard(
    status: ReminderStatus,
    onEnabledChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // Every grant here happens outside the app, so the card is only ever correct just after
    // coming back to it. ON_RESUME also covers the first composition.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onRefresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        onRefresh()
        if (granted) {
            if (!status.canDrawOverlay) {
                context.startSafely(ReminderPermissions.overlaySettingsIntent(context))
            } else if (status.exactAlarmSettingExists && !status.canScheduleExactAlarms) {
                ReminderPermissions.exactAlarmSettingsIntent(context)
                    ?.let { context.startSafely(it) }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(elevation = 4.dp, shape = RoundedCornerShape(18.dp), clip = false)
            .clip(RoundedCornerShape(18.dp))
            .background(CardBackground)
            .border(width = 1.dp, color = CardBorder, shape = RoundedCornerShape(18.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.NotificationsActive,
                contentDescription = null,
                tint = if (status.enabled) AccentBlue else MutedText,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.reminder_setup_title),
                    color = NavyInk,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(
                        if (status.enabled) {
                            R.string.reminder_setup_enabled
                        } else {
                            R.string.reminder_setup_disabled
                        },
                    ),
                    color = MutedText,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            }
            Switch(
                checked = status.enabled,
                onCheckedChange = { checked ->
                    onEnabledChange(checked)
                    if (checked) {
                        if (status.notificationPermissionExists && !status.hasNotificationPermission) {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else if (!status.canDrawOverlay) {
                            context.startSafely(ReminderPermissions.overlaySettingsIntent(context))
                        } else if (status.exactAlarmSettingExists && !status.canScheduleExactAlarms) {
                            ReminderPermissions.exactAlarmSettingsIntent(context)
                                ?.let { context.startSafely(it) }
                        }
                    }
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = CardBackground,
                    checkedTrackColor = AccentBlue,
                ),
            )
        }

        if (!status.loaded) return@Column
    }
}

/**
 * Some of these screens do not exist on every build — `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` and
 * the battery-optimisation list are both missing from OEM images in the wild. A dead button is a
 * poor outcome; a crash from the care screen is a worse one.
 */
private fun Context.startSafely(intent: Intent) {
    runCatching { startActivity(intent) }
}
