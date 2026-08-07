package com.example.sentriai.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.sentriai.R
import com.example.sentriai.agora.CallState
import com.example.sentriai.agora.Speaker

/**
 * The live Agora conversation.
 *
 * Two things share the screen and nothing else does: the transcript, so a person who mishears
 * can read what was said, and one large End call button.
 *
 * The SOS control stays available for the whole call. Someone who is already talking to the
 * companion when something happens should not have to navigate anywhere; the press goes through
 * the same escalation latch as the conversation itself, so the two cannot both fire.
 */
@Composable
fun CompanionCallScreen(
    onCallFinished: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CompanionCallViewModel = viewModel(),
) {
    val state by viewModel.callState.collectAsState()
    val lines by viewModel.lines.collectAsState()
    val muted by viewModel.muted.collectAsState()
    val agentSpeaking by viewModel.agentSpeaking.collectAsState()
    val escalation by viewModel.escalation.collectAsState()

    // The call ending is what leaves this screen — including when it ends by itself, because
    // the agent hit its idle timeout or the network dropped.
    LaunchedEffect(state) {
        if (state is CallState.Idle) onCallFinished()
    }

    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PageBackground)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        CallHeader(state = state, agentSpeaking = agentSpeaking)

        escalation?.let { EmergencyBanner(it, viewModel.caregiverName()) }

        (state as? CallState.Failed)?.let { failed ->
            Text(
                text = stringResource(R.string.call_failed, failed.message),
                color = ErrorRed,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(20.dp),
            )
        }

        if (lines.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.call_transcript_empty),
                    color = MutedText,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(32.dp),
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(lines, key = { it.id }) { line ->
                    TranscriptBubble(line)
                }
            }
        }

        CallControls(
            muted = muted,
            sosEnabled = escalation == null && state is CallState.Live,
            onToggleMute = viewModel::toggleMute,
            onSos = viewModel::triggerSosDuringCall,
            onEnd = viewModel::endCall,
        )
    }
}

@Composable
private fun CallHeader(state: CallState, agentSpeaking: Boolean) {
    val status = when (state) {
        is CallState.Connecting -> state.detail
        is CallState.Live ->
            if (state.agentPresent) stringResource(R.string.call_live)
            else stringResource(R.string.call_agent_joining)
        CallState.Ending -> stringResource(R.string.call_ending)
        CallState.Idle -> stringResource(R.string.call_ending)
        is CallState.Failed -> ""
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(TopBarBackground)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(CircleShape)
                // A visible pulse on the avatar is the only cue that the companion is talking:
                // there is no video and, for a hard-of-hearing user, no reliable audio cue.
                .background(if (agentSpeaking) SoftBlueContainer else HaloRing),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "S",
                color = AccentBlue,
                fontSize = 34.sp,
                fontWeight = FontWeight.ExtraBold,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.call_title),
            color = NavyInk,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state is CallState.Connecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = AccentBlue,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(text = status, color = MutedText, fontSize = 14.sp)
        }
    }
}

/**
 * Shown once an alert has gone out, and never dismissible.
 *
 * There is no close button by design. The banner reflects the escalation latch in
 * [com.example.sentriai.engine.ConversationEscalationMonitor], which nothing can clear for the
 * life of the call — a UI affordance that appeared to undo it would be lying.
 */
@Composable
private fun EmergencyBanner(escalation: CallEscalation, caregiverName: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SosRed)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = stringResource(R.string.call_emergency_banner, caregiverName),
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 20.sp,
            )
            Text(
                text = "${escalation.emergencyType} · ${escalation.source}",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun TranscriptBubble(line: CallLine) {
    val isPerson = line.speaker == Speaker.PERSON
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isPerson) Alignment.End else Alignment.Start,
    ) {
        Text(
            text = stringResource(
                if (isPerson) R.string.call_speaker_you else R.string.call_speaker_agent,
            ),
            color = MutedText,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = line.text,
            color = NavyInk,
            fontSize = 17.sp,
            lineHeight = 24.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(if (isPerson) SoftBlueContainer else CardBackground)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            // An in-flight line is dimmed so a half-transcribed sentence does not read as
            // something the person finished saying.
            fontWeight = if (line.isFinal) FontWeight.Normal else FontWeight.Light,
        )
    }
}

@Composable
private fun CallControls(
    muted: Boolean,
    sosEnabled: Boolean,
    onToggleMute: () -> Unit,
    onSos: () -> Unit,
    onEnd: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TopBarBackground)
            .padding(horizontal = 24.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundControl(
            label = stringResource(if (muted) R.string.call_unmute else R.string.call_mute),
            background = if (muted) HaloRing else SoftBlueContainer,
            onClick = onToggleMute,
        ) {
            Icon(
                imageVector = if (muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                contentDescription = null,
                tint = NavyInk,
                modifier = Modifier.size(26.dp),
            )
        }

        RoundControl(
            label = stringResource(R.string.emergency_sos),
            background = if (sosEnabled) SosHalo else HaloRing,
            onClick = onSos,
            enabled = sosEnabled,
        ) {
            Text(
                text = stringResource(R.string.emergency_sos),
                color = if (sosEnabled) SosRed else MutedText,
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
            )
        }

        RoundControl(
            label = stringResource(R.string.call_end),
            background = SosRed,
            onClick = onEnd,
        ) {
            Icon(
                imageVector = Icons.Filled.CallEnd,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun RoundControl(
    label: String,
    background: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(background)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            color = if (enabled) MutedText else HaloRing,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
