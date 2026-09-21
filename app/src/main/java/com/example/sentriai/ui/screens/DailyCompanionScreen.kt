package com.example.sentriai.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Restaurant
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
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.sentriai.R
import com.example.sentriai.agora.CallState
import com.example.sentriai.agora.ConversationMode
import com.example.sentriai.care.CareBriefing
import com.example.sentriai.care.FoodRelation
import com.example.sentriai.care.MealSchedule
import com.example.sentriai.care.Medicine

/**
 * The everyday care surface: start a call, see what is due, tick off a dose.
 *
 * The food relation is rendered on the same line as the time and the drug name, never in a
 * detail view or behind a tap. "8:00 AM — Metformin" and "8:00 AM — Metformin — After breakfast"
 * are different instructions, and the second one is the one that was prescribed.
 *
 * Starting a call navigates away to [CompanionCallScreen] via [onCallStarted]; this screen does
 * not host the call itself, so the schedule stays readable and the call gets the whole display.
 */
@Composable
fun DailyCompanionScreen(
    onBack: () -> Unit,
    onCallStarted: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CompanionCallViewModel = viewModel(),
) {
    val schedule by viewModel.schedule.collectAsState()
    val callState by viewModel.callState.collectAsState()
    val reminderStatus by viewModel.reminderStatus.collectAsState()

    // Doses may have been ticked off on a previous visit, or by the day rolling over.
    LaunchedEffect(Unit) { viewModel.refreshSchedule() }

    // Agora publishes silence rather than failing when RECORD_AUDIO is missing, so the call
    // would connect and then simply never hear anything. The permission is asked for before
    // the call starts, and the requested mode is held so the grant can carry on into it.
    // Which editor is open. Null for both means none — the schedule is read-only until asked.
    var editingMeals by remember { mutableStateOf(false) }
    var editingMedicine by remember { mutableStateOf<MedicineEditorTarget?>(null) }

    var pendingMode by remember { mutableStateOf<ConversationMode?>(null) }
    var micDenied by remember { mutableStateOf(false) }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        micDenied = !granted
        if (granted) pendingMode?.let(viewModel::startCall)
        pendingMode = null
    }

    fun requestCall(mode: ConversationMode) {
        micDenied = false
        if (viewModel.hasMicPermission()) {
            viewModel.startCall(mode)
        } else {
            pendingMode = mode
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Navigation happens on the state change rather than in the button handler: a call that
    // fails during setup should leave the person here with the error, not on an empty call
    // screen. Connecting is enough to hand over — the call screen renders that state itself.
    LaunchedEffect(callState) {
        if (callState is CallState.Connecting || callState is CallState.Live) onCallStarted()
    }

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
        CompanionTopBar(onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(20.dp))

            viewModel.configurationProblem?.let { problem ->
                ConfigurationNotice(problem)
                Spacer(Modifier.height(16.dp))
            }

            // Said once, at the top, only while there is nothing to say it about. A check-in
            // call with an empty schedule has nothing to check in about.
            if (schedule.medicines.isEmpty()) {
                Text(
                    text = stringResource(R.string.companion_setup_prompt),
                    color = MutedText,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
                Spacer(Modifier.height(16.dp))
            }

            // The companion call is started by hand here.
            CallAction(
                icon = Icons.AutoMirrored.Filled.Chat,
                title = stringResource(R.string.companion_start_companion),
                detail = stringResource(R.string.companion_start_companion_detail),
                accent = CalmGreen,
                container = CalmGreenContainer,
                enabled = viewModel.configurationProblem == null,
                onClick = { requestCall(ConversationMode.COMPANION) },
            )

            if (micDenied) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.ai_assistant_mic_permission_denied),
                    color = ErrorRed,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
            }

            Spacer(Modifier.height(24.dp))

            // Directly above the times it governs, rather than in a settings page: the moment
            // somebody enters their first medicine time is the moment the consents matter, and
            // asking for them later means asking after the first missed dose.
            ReminderSetupCard(
                status = reminderStatus,
                onEnabledChange = viewModel::setRemindersEnabled,
                onRefresh = viewModel::refreshReminderStatus,
            )

            Spacer(Modifier.height(20.dp))

            SectionHeader(
                icon = Icons.Filled.Medication,
                title = stringResource(R.string.companion_medicines_title),
                actionIcon = Icons.Filled.Add,
                actionLabel = stringResource(R.string.companion_add_medicine),
                onAction = { editingMedicine = MedicineEditorTarget(null) },
            )
            Spacer(Modifier.height(10.dp))

            if (schedule.medicines.isEmpty()) {
                EmptyNote(stringResource(R.string.companion_no_medicines))
            } else {
                schedule.medicinesByTime().forEach { medicine ->
                    MedicineRow(
                        medicine = medicine,
                        onToggleTaken = {
                            viewModel.setMedicineTaken(medicine.id, !medicine.isTaken)
                        },
                        onEdit = { editingMedicine = MedicineEditorTarget(medicine) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            Spacer(Modifier.height(20.dp))

            SectionHeader(
                icon = Icons.Filled.Restaurant,
                title = stringResource(R.string.companion_meals_title),
                actionIcon = Icons.Filled.Edit,
                actionLabel = stringResource(R.string.companion_edit),
                onAction = { editingMeals = true },
            )
            Spacer(Modifier.height(10.dp))
            MealCard(schedule.meals)

            Spacer(Modifier.height(28.dp))
        }
    }

    if (editingMeals) {
        MealTimesSheet(
            meals = schedule.meals,
            onDismiss = { editingMeals = false },
            onSave = {
                viewModel.updateMeals(it)
                editingMeals = false
            },
        )
    }

    editingMedicine?.let { target ->
        MedicineEditorSheet(
            existing = target.medicine,
            meals = schedule.meals,
            onDismiss = { editingMedicine = null },
            onSave = {
                viewModel.saveMedicine(it)
                editingMedicine = null
            },
            onDelete = {
                viewModel.deleteMedicine(it)
                editingMedicine = null
            },
        )
    }
}

/**
 * Which medicine the editor is open for. A wrapper rather than a bare `Medicine?` because null
 * already means "add a new one", and the sheet has to tell that apart from "no sheet open".
 */
private data class MedicineEditorTarget(val medicine: Medicine?)

/**
 * One dose. The whole row is the tap target for marking it taken — a small checkbox in a corner
 * is the wrong control for hands that may not be steady.
 */
@Composable
private fun MedicineRow(medicine: Medicine, onToggleTaken: () -> Unit, onEdit: () -> Unit) {
    val taken = medicine.isTaken
    val rowInteraction = remember { MutableInteractionSource() }
    val rowPressed by rowInteraction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (rowPressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "row_press"
    )

    Row(
        modifier = Modifier
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .shadow(
                elevation = if (rowPressed) 1.dp else 4.dp,
                shape = RoundedCornerShape(16.dp),
                clip = false
            )
            .clip(RoundedCornerShape(16.dp))
            .background(CardBackground)
            .border(width = 1.dp, color = CardBorder, shape = RoundedCornerShape(16.dp))
            .clickable(
                role = Role.Checkbox,
                interactionSource = rowInteraction,
                indication = null,
                onClick = onToggleTaken
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (taken) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            contentDescription = stringResource(
                if (taken) R.string.companion_taken else R.string.companion_mark_taken,
            ),
            tint = if (taken) CalmGreen else MutedText,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = CareBriefing.doseLine(medicine),
                color = if (taken) MutedText else NavyInk,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 24.sp,
                textDecoration = if (taken) TextDecoration.LineThrough else null,
            )
            if (medicine.dosage.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${medicine.dosage} · ${CareBriefing.frequencyLabel(medicine.frequency)}",
                    color = MutedText,
                    fontSize = 13.sp,
                )
            }
            if (medicine.foodRelation != FoodRelation.NONE) {
                Spacer(Modifier.height(6.dp))
                FoodRelationChip(medicine)
            }
        }

        val editInteraction = remember { MutableInteractionSource() }
        val editPressed by editInteraction.collectIsPressedAsState()
        val editScale by animateFloatAsState(if (editPressed) 0.82f else 1f, label = "edit_press")

        Icon(
            imageVector = Icons.Filled.Edit,
            contentDescription = stringResource(R.string.companion_edit),
            tint = MutedText,
            modifier = Modifier
                .graphicsLayer(scaleX = editScale, scaleY = editScale)
                .size(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .clickable(
                    role = Role.Button,
                    interactionSource = editInteraction,
                    indication = null,
                    onClick = onEdit
                )
                .padding(10.dp),
        )
    }
}

/**
 * The food rule, repeated as a chip below the line that already contains it.
 *
 * Redundant on purpose. It is the single most consequential field on the row and the easiest to
 * skim past at the end of a sentence, so it also gets a shape and a colour of its own.
 */
@Composable
private fun FoodRelationChip(medicine: Medicine) {
    val label = CareBriefing.foodRelationLabel(medicine.foodRelation, medicine.mealSlot)
    if (label.isEmpty()) return
    Text(
        text = label,
        color = WarmAmber,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(WarmAmberContainer)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun MealCard(meals: MealSchedule) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(elevation = 4.dp, shape = RoundedCornerShape(16.dp), clip = false)
            .clip(RoundedCornerShape(16.dp))
            .background(CardBackground)
            .border(width = 1.dp, color = CardBorder, shape = RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        meals.times().forEach { meal ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = CareBriefing.mealLabel(meal.slot),
                    color = NavyInk,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = CareBriefing.clock(meal.minutes),
                    color = AccentBlue,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        meals.bedtimeMinutes?.let { bedtime ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Bedtime",
                    color = NavyInk,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = CareBriefing.clock(bedtime),
                    color = AccentBlue,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun CallAction(
    icon: ImageVector,
    title: String,
    detail: String,
    accent: Color,
    container: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "call_press"
    )

    Row(
        modifier = Modifier
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
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(if (enabled) container else HaloRing),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) accent else MutedText,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (enabled) NavyInk else MutedText,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(2.dp))
            Text(text = detail, color = MutedText, fontSize = 14.sp, lineHeight = 19.sp)
        }
    }
}

@Composable
private fun ConfigurationNotice(problem: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(WarmAmberContainer)
            .padding(14.dp),
    ) {
        Text(
            text = stringResource(R.string.companion_unavailable, problem),
            color = WarmAmber,
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
    }
}

@Composable
private fun SectionHeader(
    icon: ImageVector,
    title: String,
    actionIcon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(imageVector = icon, contentDescription = null, tint = NavyInk, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text = title, color = NavyInk, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        if (actionIcon != null && actionLabel != null && onAction != null) {
            val actionInteraction = remember { MutableInteractionSource() }
            val actionPressed by actionInteraction.collectIsPressedAsState()
            val actionScale by animateFloatAsState(if (actionPressed) 0.9f else 1f, label = "action_press")

            Row(
                modifier = Modifier
                    .graphicsLayer(scaleX = actionScale, scaleY = actionScale)
                    .clip(RoundedCornerShape(10.dp))
                    .background(SoftBlueContainer)
                    .clickable(
                        interactionSource = actionInteraction,
                        indication = null,
                        onClick = onAction
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = actionIcon,
                    contentDescription = null,
                    tint = AccentBlue,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = actionLabel,
                    color = AccentBlue,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun EmptyNote(text: String) {
    Text(
        text = text,
        color = MutedText,
        fontSize = 14.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CardBackground)
            .padding(16.dp),
    )
}

@Composable
private fun CompanionTopBar(onBack: () -> Unit) {
    val backInteraction = remember { MutableInteractionSource() }
    val backPressed by backInteraction.collectIsPressedAsState()
    val backScale by animateFloatAsState(if (backPressed) 0.88f else 1f, label = "back_press")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Transparent)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer(scaleX = backScale, scaleY = backScale)
                .size(38.dp)
                .clip(CircleShape)
                .background(SoftBlueContainer)
                .clickable(
                    interactionSource = backInteraction,
                    indication = null,
                    onClick = onBack
                ),
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
            text = stringResource(R.string.companion_title),
            color = NavyInk,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
