package com.example.sentriai.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.sentriai.R
import com.example.sentriai.agora.CallState

/**
 * The emergency surface. One button, and nothing on the screen competing with it.
 *
 * Deliberately isolated from the companion features: different background, different palette,
 * no schedule, no chat, no navigation into them. Someone reaching for this has one thing they
 * are trying to do, and every additional control is something to get wrong.
 *
 * **No confirmation dialog.** The button dispatches on the press. A "are you sure?" step is the
 * obvious way to prevent accidental alerts, and it is the wrong trade here: an unnecessary SMS
 * costs a caregiver a phone call, and a missed one costs considerably more. The secondary
 * actions are placed well below the button and are small, which is the accident prevention.
 */
@Composable
fun EmergencyScreen(
    onBack: () -> Unit,
    onViewAlertHistory: () -> Unit,
    onCallStarted: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CompanionCallViewModel = viewModel(),
) {
    val context = LocalContext.current
    val escalation by viewModel.escalation.collectAsState()
    val callState by viewModel.callState.collectAsState()

    // The emergency-mode call is optional — it only happens when Agora is configured and the
    // mic is already granted — but when it does happen the person needs the call screen, which
    // is the only place with an End call button and the running transcript.
    LaunchedEffect(callState) {
        if (callState is CallState.Connecting || callState is CallState.Live) onCallStarted()
    }

    var testAlertSent by remember { mutableStateOf(false) }
    val caregiverName = remember { viewModel.caregiverName() }
    val caregiverNumber = remember { viewModel.caregiverNumber() }

    // Latched by the ViewModel, not by the screen: rotating the phone or backgrounding the app
    // must not take the "help has been called" message away from someone who is waiting on it.
    val alertSent = escalation != null

    // The button is only ever disabled for as long as a send is in flight, and is live again the
    // moment it finishes. It is deliberately *not* latched off once an alert has gone out:
    // someone who presses SOS and finds that nobody has come half an hour later will press it
    // again, and that is the correct thing for them to do. The confirmation card below records
    // that help was called; the button stays a button.
    var dispatching by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(EmergencyBackground)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        EmergencyTopBar(onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(32.dp))

            SosButton(
                enabled = !dispatching,
                onPress = {
                    dispatching = true
                    viewModel.raiseSosAlert(onDispatched = { dispatching = false })
                },
            )

            Spacer(Modifier.height(28.dp))

            if (alertSent) {
                AlertSentCard(
                    caregiverName = caregiverName,
                    companionJoining = callState is CallState.Connecting,
                    companionLive = callState is CallState.Live,
                )
            } else {
                Text(
                    text = stringResource(R.string.emergency_sos_hint, caregiverName),
                    color = MutedText,
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    textAlign = TextAlign.Center,
                )
            }

            if (caregiverNumber == null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.emergency_no_contact),
                    color = ErrorRed,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(40.dp))

            SecondaryAction(
                icon = Icons.Filled.Call,
                label = stringResource(R.string.emergency_call_family),
                enabled = caregiverNumber != null,
                onClick = {
                    caregiverNumber?.let { number ->
                        // ACTION_DIAL, not ACTION_CALL: it opens the dialer with the number
                        // filled in and needs no CALL_PHONE permission, so it can never fail
                        // silently on a permission the user did not grant.
                        context.startActivity(
                            Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)),
                        )
                    }
                },
            )

            Spacer(Modifier.height(12.dp))

            SecondaryAction(
                icon = Icons.Filled.NotificationsActive,
                label = if (testAlertSent) {
                    stringResource(R.string.emergency_test_sent, caregiverName)
                } else {
                    stringResource(R.string.emergency_test_alert)
                },
                enabled = caregiverNumber != null && !testAlertSent,
                onClick = { viewModel.sendTestAlert { testAlertSent = true } },
            )

            Spacer(Modifier.height(12.dp))

            SecondaryAction(
                icon = Icons.Filled.History,
                label = stringResource(R.string.emergency_recent_alerts),
                onClick = onViewAlertHistory,
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

/**
 * The button. Large enough to hit without aiming, and the only red thing on the screen.
 *
 * `clickable` with an explicit [Role.Button] rather than a Material `Button` because none of
 * the Material sizes get anywhere near this, and a 240 dp button assembled from padding on a
 * standard one loses its ripple bounds.
 */
@Composable
private fun SosButton(enabled: Boolean, onPress: () -> Unit) {
    Box(
        modifier = Modifier
            .size(268.dp)
            .clip(CircleShape)
            .background(SosHalo),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(232.dp)
                .clip(CircleShape)
                .background(if (enabled) SosRed else SosRedPressed)
                .clickable(enabled = enabled, role = Role.Button, onClick = onPress),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.emergency_sos),
                    color = Color.White,
                    fontSize = 64.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 4.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.emergency_sos_caption),
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun AlertSentCard(
    caregiverName: String,
    companionJoining: Boolean,
    companionLive: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(CalmGreenContainer)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = CalmGreen,
            modifier = Modifier.size(36.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.emergency_sent_title),
            color = NavyInk,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.emergency_sent_body, caregiverName),
            color = MutedText,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            textAlign = TextAlign.Center,
        )
        // The companion call is an extra on top of the alert, so its progress is a small line
        // here rather than anything that could be mistaken for the alert's own status.
        if (companionJoining || companionLive) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(
                    if (companionLive) R.string.call_live else R.string.call_agent_joining,
                ),
                color = CalmGreen,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun SecondaryAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardBackground)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) SosRed else MutedText,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = label,
            color = if (enabled) NavyInk else MutedText,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun EmergencyTopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TopBarBackground)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(SosHalo)
                .clickable(role = Role.Button, onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.voice_transcript_back),
                tint = NavyInk,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.emergency_title),
            color = NavyInk,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
