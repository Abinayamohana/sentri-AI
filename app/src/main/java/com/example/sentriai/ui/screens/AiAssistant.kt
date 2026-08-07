package com.example.sentriai.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.sentriai.R
import com.example.sentriai.model_inference.speech_to_text.TranscriptionUiState
import com.example.sentriai.model_inference.speech_to_text.TranscriptionViewModel
import com.example.sentriai.ui.theme.SentriAITheme
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material.icons.automirrored.filled.ArrowForward

/**
 * Guardian AI activation screen, wired to on-device speech-to-text.
 *
 * The power pill drives [TranscriptionViewModel]'s live streaming loop: powering on
 * starts mic capture (after RECORD_AUDIO is granted) and, once the model has finished
 * loading, hands off to the Voice Transcript screen via [onTranscriptionStarted].
 * Transcript text itself lives on that screen, not here.
 *
 * @param onProfileClick invoked when the profile chip in the top bar is tapped.
 * @param onTranscriptionStarted invoked when the model is loaded and the stream is live.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiAssistantActivateScreen(
    onProfileClick: () -> Unit,
    onTranscriptionStarted: () -> Unit,
    onAlertHistoryClick: () -> Unit,
    onEmergencyClick: () -> Unit,
    onDailyCompanionClick: () -> Unit,
    viewModel: TranscriptionViewModel,
    modifier: Modifier = Modifier,
    triggerLogViewModel: TriggerLogViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    var permissionDenied by rememberSaveable { mutableStateOf(false) }
    // Only a power tap made *here* should push the transcript screen. Without this the
    // effect below would re-navigate every time this screen is recomposed while the
    // stream is still live — i.e. immediately after the user backs out of the transcript.
    var awaitingStart by rememberSaveable { mutableStateOf(false) }

    val logEntries by triggerLogViewModel.logEntries.collectAsState()
    val entryCount by triggerLogViewModel.entryCount.collectAsState()
    val engineState by triggerLogViewModel.engineState.collectAsState()
    val engineErrorMessage by triggerLogViewModel.engineErrorMessage.collectAsState()



    LaunchedEffect(state, awaitingStart) {
        if (!awaitingStart) return@LaunchedEffect
        when (state) {
            is TranscriptionUiState.Listening -> {
                awaitingStart = false
                onTranscriptionStarted()
            }
            // Mic or model failure — the handoff is off, the error shows here instead.
            is TranscriptionUiState.Error -> awaitingStart = false
            else -> Unit
        }
    }

    // RECORD_AUDIO plus, on API 33+, POST_NOTIFICATIONS: listening runs in a foreground
    // service whose ongoing notification is how the user sees and stops it. Both are asked
    // for together so there is only one prompt sequence, but only the mic result gates the
    // start — a denied notification permission hides the status entry without stopping the
    // service from running legally.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val micGranted = results[Manifest.permission.RECORD_AUDIO] == true
        permissionDenied = !micGranted
        if (micGranted) {
            awaitingStart = true
            viewModel.startStreaming()
        }
    }
    val startPermissions = remember {
        buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    AiAssistantActivateContent(
        state = state,
        permissionDenied = permissionDenied,
        onPowerClick = {
            when {
                // Reachable when the user backed out of the transcript screen without
                // stopping: the assistant is still armed, so the pill turns it off.
                state is TranscriptionUiState.Listening -> viewModel.stopStreaming()
                context.hasMicPermission() -> {
                    permissionDenied = false
                    awaitingStart = true
                    viewModel.startStreaming()
                }
                // First tap without permission — and every later tap while the user has
                // only soft-denied. Once they pick "Don't allow" permanently the system
                // returns the denial immediately, which surfaces the Settings shortcut.
                else -> permissionLauncher.launch(startPermissions)
            }
        },
        onOpenSettingsClick = { context.openAppSettings() },
        onProfileClick = onProfileClick,
        logBadgeCount = entryCount,
        onLogClick = {
            triggerLogViewModel.refresh()
            onAlertHistoryClick()
        },
        engineState = engineState,
        engineErrorMessage = engineErrorMessage,
        onEmergencyClick = onEmergencyClick,
        onDailyCompanionClick = onDailyCompanionClick,
        onDownloadDone = { triggerLogViewModel.initEngine() },
        modifier = modifier,
    )
}

private fun Context.hasMicPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

private fun Context.openAppSettings() {
    startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        ),
    )
}

/**
 * Stateless activation surface: assistant portrait, the power toggle and the live
 * monitoring status tiles.
 */
@Composable
private fun AiAssistantActivateContent(
    state: TranscriptionUiState,
    permissionDenied: Boolean,
    onPowerClick: () -> Unit,
    onOpenSettingsClick: () -> Unit,
    onProfileClick: () -> Unit,
    logBadgeCount: Int,
    onLogClick: () -> Unit,
    engineState: EngineState,
    engineErrorMessage: String?,
    onEmergencyClick: () -> Unit,
    onDailyCompanionClick: () -> Unit,
    onDownloadDone: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val isActive = state is TranscriptionUiState.Listening
    // Both are transitional: the mic is on but the pill must not accept another tap.
    val isPreparing = state is TranscriptionUiState.Preparing
    val isStopping = state is TranscriptionUiState.Finishing

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFFE8EFFF),
                        PageBackground
                    )
                )
            )
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        GuardianTopBar(
            onProfileClick = onProfileClick,
            logBadgeCount = logBadgeCount,
            onLogClick = onLogClick,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))

            AssistantPortrait(isActive = isActive)

            Spacer(Modifier.height(28.dp))

            Text(
                text = stringResource(
                    if (isActive) R.string.ai_assistant_status_active
                    else R.string.ai_assistant_status_ready,
                ),
                color = NavyInk,
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(10.dp))

            Text(
                text = stringResource(
                    if (isActive) R.string.ai_assistant_status_active_detail
                    else R.string.ai_assistant_status_ready_detail,
                ),
                color = MutedText,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp),
            )

            Spacer(Modifier.height(28.dp))

            PowerButton(
                isActive = isActive,
                isPreparing = isPreparing,
                isStopping = isStopping,
                onClick = onPowerClick,
            )

            if (permissionDenied) {
                Spacer(Modifier.height(12.dp))
                MicPermissionNotice(onOpenSettingsClick = onOpenSettingsClick)
            }

            Spacer(Modifier.height(16.dp))

            EngineStatusBadge(engineState = engineState, errorMessage = engineErrorMessage)

            // Sits directly under the badge on purpose: the badge is what tells you a model is
            // missing, so the way to fetch it belongs next to it. Debug builds only.
            HuggingFaceDownloadCard(
                modifier = Modifier.padding(top = 12.dp),
                onDownloadDone = onDownloadDone
            )

            Spacer(Modifier.height(16.dp))

            // The two care surfaces. Only entry points live here — everything about them is on
            // their own screens, so the passive monitoring flow above is unchanged.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CareEntryTile(
                    title = stringResource(R.string.home_emergency_entry),
                    detail = stringResource(R.string.home_emergency_entry_detail),
                    accent = SosRed,
                    container = SosHalo,
                    onClick = onEmergencyClick,
                    modifier = Modifier.weight(1f),
                )
                CareEntryTile(
                    title = stringResource(R.string.home_companion_entry),
                    detail = stringResource(R.string.home_companion_entry_detail),
                    accent = AccentBlue,
                    container = SoftBlueContainer,
                    onClick = onDailyCompanionClick,
                    modifier = Modifier.weight(1f),
                )
            }

            // A failed mic start or model load never reaches the transcript screen, so it
            // is reported here.
            (state as? TranscriptionUiState.Error)?.let { error ->
                Spacer(Modifier.height(20.dp))
                Text(
                    text = error.message,
                    color = ErrorRed,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Entry tile into one of the care screens.
 *
 * Kept visually quiet: these are doors, and the Emergency screen behind the red one has the
 * actual SOS button on it. A second full-size SOS control on the home screen would be two
 * places to press with different behaviour.
 */
@Composable
private fun CareEntryTile(
    title: String,
    detail: String,
    accent: Color,
    container: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "tile_press"
    )

    Column(
        modifier = modifier
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .shadow(
                elevation = if (isPressed) 2.dp else 6.dp,
                shape = RoundedCornerShape(20.dp),
                clip = false
            )
            .clip(RoundedCornerShape(20.dp))
            .background(CardBackground)
            .border(width = 1.dp, color = CardBorder, shape = RoundedCornerShape(20.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(container),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = accent.copy(alpha = 0.6f),
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = title,
            color = accent,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.3).sp
        )
        Spacer(Modifier.height(4.dp))
        Text(text = detail, color = MutedText, fontSize = 12.sp, lineHeight = 16.sp)
    }
}

/** Shown once the user has denied the mic permission — the only way back is Settings. */
@Composable
private fun MicPermissionNotice(onOpenSettingsClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.ai_assistant_mic_permission_denied),
            color = ErrorRed,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = onOpenSettingsClick) {
            Text(
                text = stringResource(R.string.ai_assistant_open_settings),
                color = AccentBlue,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun GuardianTopBar(
    onProfileClick: () -> Unit,
    logBadgeCount: Int = 0,
    onLogClick: () -> Unit = {},
) {
    val logInteraction = remember { MutableInteractionSource() }
    val logPressed by logInteraction.collectIsPressedAsState()
    val logScale by animateFloatAsState(if (logPressed) 0.88f else 1f, label = "log_press")

    val profileInteraction = remember { MutableInteractionSource() }
    val profilePressed by profileInteraction.collectIsPressedAsState()
    val profileScale by animateFloatAsState(if (profilePressed) 0.88f else 1f, label = "profile_press")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Transparent)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_shield),
            contentDescription = null,
            tint = NavyInk,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = stringResource(R.string.app_name),
            color = NavyInk,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.weight(1f))

        // ── Trigger-log badge icon ──────────────────────────────────
        Box(contentAlignment = Alignment.TopEnd) {
            Box(
                modifier = Modifier
                    .graphicsLayer(scaleX = logScale, scaleY = logScale)
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(SoftBlueContainer)
                    .clickable(
                        interactionSource = logInteraction,
                        indication = null,
                        onClick = onLogClick
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_trigger_log),
                    contentDescription = stringResource(R.string.trigger_log_icon),
                    tint = NavyInk,
                    modifier = Modifier.size(20.dp),
                )
            }
            if (logBadgeCount > 0) {
                Badge(
                    containerColor = Color(0xFFC62828),
                    contentColor = Color.White,
                    modifier = Modifier
                        .offset(x = 4.dp, y = (-4).dp),
                ) {
                    Text(
                        text = logBadgeCount.toString(),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        Spacer(Modifier.width(10.dp))

        // ── Profile avatar ──────────────────────────────────────────
        Box(
            modifier = Modifier
                .graphicsLayer(scaleX = profileScale, scaleY = profileScale)
                .size(38.dp)
                .clip(CircleShape)
                .background(SoftBlueContainer)
                .clickable(
                    interactionSource = profileInteraction,
                    indication = null,
                    onClick = onProfileClick
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = stringResource(R.string.profile),
                tint = Color(0xFF4A6FA5),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Assistant avatar framed by concentric halo rings, with a verified shield badge. */
@Composable
private fun AssistantPortrait(isActive: Boolean = false) {
    val infiniteTransition = rememberInfiniteTransition(label = "avatar_pulse")
    
    val pulseScale1 by if (isActive) {
        infiniteTransition.animateFloat(
            initialValue = 0.86f,
            targetValue = 1.25f,
            animationSpec = infiniteRepeatable(
                animation = tween(2000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "pulse1"
        )
    } else {
        remember { mutableStateOf(0.86f) }
    }
    
    val pulseAlpha1 by if (isActive) {
        infiniteTransition.animateFloat(
            initialValue = 0.6f,
            targetValue = 0.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(2000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "alpha1"
        )
    } else {
        remember { mutableStateOf(0.0f) }
    }

    val pulseScale2 by if (isActive) {
        infiniteTransition.animateFloat(
            initialValue = 0.86f,
            targetValue = 1.45f,
            animationSpec = infiniteRepeatable(
                animation = tween(2800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "pulse2"
        )
    } else {
        remember { mutableStateOf(0.86f) }
    }
    
    val pulseAlpha2 by if (isActive) {
        infiniteTransition.animateFloat(
            initialValue = 0.4f,
            targetValue = 0.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(2800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "alpha2"
        )
    } else {
        remember { mutableStateOf(0.0f) }
    }

    Box(
        modifier = Modifier.size(268.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = size.minDimension / 2f
            
            // Draw active pulse rings
            if (isActive) {
                drawCircle(
                    color = AccentBlue.copy(alpha = pulseAlpha1),
                    radius = center * pulseScale1,
                    style = Stroke(width = 2.dp.toPx()),
                )
                drawCircle(
                    color = AccentBlue.copy(alpha = pulseAlpha2),
                    radius = center * pulseScale2,
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
            
            listOf(0.99f, 0.86f).forEach { fraction ->
                drawCircle(
                    color = HaloRing,
                    radius = center * fraction,
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
        }

        Image(
            painter = painterResource(R.drawable.ai_assistant),
            contentDescription = stringResource(R.string.ai_assistant_avatar),
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(206.dp)
                .shadow(elevation = 16.dp, shape = CircleShape, clip = false)
                .clip(CircleShape)
                .background(Color.White)
                .border(width = 5.dp, color = Color.White, shape = CircleShape),
        )

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 22.dp, end = 18.dp)
                .size(34.dp)
                .shadow(elevation = 6.dp, shape = CircleShape)
                .clip(CircleShape)
                .background(CardBackground),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_shield),
                contentDescription = stringResource(R.string.ai_assistant_verified_badge),
                tint = AccentBlue,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

/**
 * Full-width power button. Grey while the assistant is off, green once it is on;
 * the label and trailing knob track the same state.
 *
 * @param isPreparing the mic is on but the model is still loading — the handoff to the
 *   transcript screen happens on its own once loading finishes.
 * @param isStopping the streaming loop is winding down — taps are ignored until it has
 *   emitted its final transcript, so a new capture can't race the one still finishing.
 */
@Composable
private fun PowerButton(
    isActive: Boolean,
    isPreparing: Boolean,
    isStopping: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "power_press"
    )

    val containerColor = if (isActive || isPreparing) PowerOnGreen else PowerOffGrey
    val buttonGradient = remember(isActive, isPreparing) {
        if (isActive || isPreparing) {
            Brush.linearGradient(
                colors = listOf(Color(0xFF10B981), Color(0xFF047857))
            )
        } else {
            Brush.linearGradient(
                colors = listOf(Color(0xFF64748B), Color(0xFF334155))
            )
        }
    }

    val shadowColor = if (isActive || isPreparing) Color(0xFF10B981) else Color(0xFF64748B)
    val shadowOpacity by animateFloatAsState(
        targetValue = if (isActive) 0.4f else 0.2f,
        label = "shadow_opacity"
    )

    Box(
        modifier = Modifier
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .shadow(
                elevation = 12.dp,
                shape = RoundedCornerShape(28.dp),
                ambientColor = shadowColor.copy(alpha = shadowOpacity),
                spotColor = shadowColor.copy(alpha = shadowOpacity),
                clip = false
            )
            .clip(RoundedCornerShape(28.dp))
            .background(buttonGradient)
            .clickable(
                enabled = !isStopping && !isPreparing,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .width(220.dp)
            .height(58.dp)
            .padding(start = 20.dp, end = 8.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(
                    when {
                        isPreparing -> R.string.ai_assistant_power_starting
                        isStopping -> R.string.ai_assistant_power_stopping
                        isActive -> R.string.ai_assistant_power_on
                        else -> R.string.ai_assistant_power_off
                    },
                ).uppercase(),
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.sp,
            )
            Spacer(Modifier.weight(1f))

            val infiniteTransition = rememberInfiniteTransition(label = "power_knob")
            val rotation by if (isPreparing || isStopping) {
                infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1500, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "knob_rotate"
                )
            } else {
                remember { mutableStateOf(0f) }
            }

            Box(
                modifier = Modifier
                    .graphicsLayer(rotationZ = rotation)
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_power),
                    contentDescription = stringResource(
                        if (isActive) R.string.ai_assistant_deactivate
                        else R.string.ai_assistant_activate,
                    ),
                    tint = containerColor,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}


@Composable
private fun EngineStatusBadge(engineState: EngineState, errorMessage: String? = null) {
    val (statusText, statusColor, statusBg) = when (engineState) {
        EngineState.LOADING -> Triple(
            stringResource(R.string.engine_status_loading),
            AccentBlue,
            SoftBlueContainer
        )
        EngineState.READY -> Triple(
            stringResource(R.string.engine_status_ready),
            PowerOnGreen,
            Color(0xFFDCFCE7)
        )
        EngineState.DEGRADED -> Triple(
            errorMessage ?: stringResource(R.string.engine_status_degraded),
            Color(0xFFD97706), // Amber
            Color(0xFFFEF3C7)
        )
        EngineState.UNAVAILABLE -> Triple(
            errorMessage ?: stringResource(R.string.engine_status_unavailable),
            Color(0xFFD97706), // Amber
            Color(0xFFFEF3C7)
        )
        EngineState.ERROR -> Triple(
            errorMessage ?: stringResource(R.string.engine_status_error),
            Color(0xFFC62828),
            Color(0xFFFEE2E2)
        )
    }

    val infiniteTransition = rememberInfiniteTransition(label = "badge_pulse")
    val dotAlpha by if (engineState == EngineState.LOADING) {
        infiniteTransition.animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(800, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "dot_alpha"
        )
    } else {
        remember { mutableStateOf(1f) }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(statusBg)
            .border(width = 1.dp, color = statusColor.copy(alpha = 0.15f), shape = RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer(alpha = dotAlpha)
                .size(8.dp)
                .clip(CircleShape)
                .background(statusColor)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = statusText,
            color = statusColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.2.sp
        )
    }
}

@Preview(showBackground = true, widthDp = 335, heightDp = 690)
@Composable
private fun AiAssistantActivateScreenPreview() {
    SentriAITheme(dynamicColor = false) {
        AiAssistantActivateContent(
            state = TranscriptionUiState.Idle,
            permissionDenied = false,
            onPowerClick = {},
            onOpenSettingsClick = {},
            onProfileClick = {},
            logBadgeCount = 0,
            onLogClick = {},
            engineState = EngineState.READY,
            engineErrorMessage = null,
            onEmergencyClick = {},
            onDailyCompanionClick = {},
        )
    }
}

@Preview(name = "Active", showBackground = true, widthDp = 335,)
@Composable
private fun AiAssistantListeningPreview() {
    SentriAITheme(dynamicColor = false) {
        AiAssistantActivateContent(
            state = TranscriptionUiState.Listening(""),
            permissionDenied = false,
            onPowerClick = {},
            onOpenSettingsClick = {},
            onProfileClick = {},
            logBadgeCount = 2,
            onLogClick = {},
            engineState = EngineState.READY,
            engineErrorMessage = null,
            onEmergencyClick = {},
            onDailyCompanionClick = {},
        )
    }
}