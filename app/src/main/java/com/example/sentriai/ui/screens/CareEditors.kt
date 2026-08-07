package com.example.sentriai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sentriai.R
import com.example.sentriai.care.CareBriefing
import com.example.sentriai.care.CareScheduleStore
import com.example.sentriai.care.DoseTiming
import com.example.sentriai.care.FoodRelation
import com.example.sentriai.care.MealSchedule
import com.example.sentriai.care.MealSlot
import com.example.sentriai.care.Medicine
import com.example.sentriai.care.MedicineFrequency

/**
 * The two things the caregiver actually enters: meal times, and medicine times.
 *
 * Everything else the feature shows or says is derived from these — the companion screen's list,
 * the food-relation chips, and the briefing block the Agora agent is given. There is deliberately
 * no third form.
 */

// --- meal times ------------------------------------------------------------------

/**
 * Breakfast, lunch and dinner are required; snack and bedtime can be cleared.
 *
 * Edited as a whole rather than a field at a time because the times are read together — moving
 * dinner an hour later usually means moving the bedtime dose too, and a per-field editor hides
 * that.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MealTimesSheet(
    meals: MealSchedule,
    onDismiss: () -> Unit,
    onSave: (MealSchedule) -> Unit,
) {
    var breakfast by remember { mutableStateOf(meals.breakfastMinutes) }
    var lunch by remember { mutableStateOf(meals.lunchMinutes) }
    var dinner by remember { mutableStateOf(meals.dinnerMinutes) }
    var snack by remember { mutableStateOf(meals.snackMinutes) }
    var bedtime by remember { mutableStateOf(meals.bedtimeMinutes) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = CardBackground,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            SheetTitle(stringResource(R.string.care_edit_meals_title), onDismiss)

            Text(
                text = stringResource(R.string.care_edit_meals_hint),
                color = MutedText,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
            Spacer(Modifier.height(16.dp))

            TimeRow(stringResource(R.string.care_meal_breakfast), breakfast) { breakfast = it }
            TimeRow(stringResource(R.string.care_meal_lunch), lunch) { lunch = it }
            TimeRow(stringResource(R.string.care_meal_dinner), dinner) { dinner = it }

            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.care_optional_section),
                color = MutedText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))

            OptionalTimeRow(stringResource(R.string.care_meal_snack), snack) { snack = it }
            OptionalTimeRow(stringResource(R.string.care_meal_bedtime), bedtime) { bedtime = it }

            Spacer(Modifier.height(20.dp))
            PrimaryButton(stringResource(R.string.care_save)) {
                onSave(MealSchedule(breakfast, lunch, dinner, snack, bedtime))
            }
        }
    }
}

// --- one medicine ------------------------------------------------------------------

/**
 * Add or edit a dose.
 *
 * The time field is *derived* from the meal and the food rule until the caregiver touches it —
 * see [DoseTiming]. Picking "after breakfast" fills in half an hour past breakfast, and typing a
 * time by hand pins it. That is what keeps this form down to "which medicine, and when", which
 * is all anyone should have to say.
 *
 * @param existing null when adding. Non-null enables the delete action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedicineEditorSheet(
    existing: Medicine?,
    meals: MealSchedule,
    onDismiss: () -> Unit,
    onSave: (Medicine) -> Unit,
    onDelete: (String) -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var dosage by remember { mutableStateOf(existing?.dosage.orEmpty()) }
    var notes by remember { mutableStateOf(existing?.notes.orEmpty()) }
    var relation by remember { mutableStateOf(existing?.foodRelation ?: FoodRelation.AFTER_FOOD) }
    var slot by remember { mutableStateOf(existing?.mealSlot ?: MealSlot.BREAKFAST) }
    var frequency by remember { mutableStateOf(existing?.frequency ?: MedicineFrequency.DAILY) }

    // An existing dose already has a time the caregiver chose or accepted, so editing one starts
    // pinned. A new one tracks the meal until it is touched.
    var timePinned by remember { mutableStateOf(existing != null) }
    var time by remember {
        mutableStateOf(
            existing?.timeMinutes
                ?: DoseTiming.derive(meals, MealSlot.BREAKFAST, FoodRelation.AFTER_FOOD)
                ?: (8 * 60),
        )
    }

    fun retime(newSlot: MealSlot, newRelation: FoodRelation) {
        if (timePinned) return
        DoseTiming.derive(meals, newSlot, newRelation)?.let { time = it }
    }

    var confirmingDelete by remember { mutableStateOf(false) }
    val nameBlank = name.isBlank()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = CardBackground,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            SheetTitle(
                stringResource(
                    if (existing == null) R.string.care_add_medicine else R.string.care_edit_medicine,
                ),
                onDismiss,
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.care_field_name)) },
                singleLine = true,
                isError = nameBlank,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = dosage,
                onValueChange = { dosage = it },
                label = { Text(stringResource(R.string.care_field_dosage)) },
                placeholder = { Text(stringResource(R.string.care_field_dosage_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))

            // Food rule first, then meal, then time — the order somebody reads a label in, and
            // the order that lets the first two fill in the third.
            FieldLabel(stringResource(R.string.care_field_food_relation))
            ChipRow(
                options = FoodRelation.entries,
                selected = relation,
                label = { foodRelationChipLabel(it) },
            ) {
                relation = it
                retime(slot, it)
            }

            Spacer(Modifier.height(14.dp))
            FieldLabel(stringResource(R.string.care_field_meal))
            ChipRow(
                options = MealSlot.entries,
                selected = slot,
                label = { if (it == MealSlot.NONE) stringResource(R.string.care_meal_none) else CareBriefing.mealLabel(it) },
            ) {
                slot = it
                retime(it, relation)
            }

            Spacer(Modifier.height(14.dp))
            TimeRow(
                label = stringResource(R.string.care_field_time),
                minutes = time,
                trailing = if (timePinned) null else stringResource(R.string.care_time_from_meal),
            ) {
                time = it
                timePinned = true
            }

            Spacer(Modifier.height(14.dp))
            FieldLabel(stringResource(R.string.care_field_frequency))
            ChipRow(
                options = MedicineFrequency.entries,
                selected = frequency,
                label = { CareBriefing.frequencyLabel(it) },
            ) { frequency = it }

            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text(stringResource(R.string.care_field_notes)) },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))
            Text(
                // A dose taken three times a day is three entries. Saying so here is cheaper
                // than letting somebody enter one and wonder why the check-in call asks once.
                text = stringResource(R.string.care_one_entry_per_dose),
                color = MutedText,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )

            Spacer(Modifier.height(20.dp))
            PrimaryButton(stringResource(R.string.care_save), enabled = !nameBlank) {
                onSave(
                    Medicine(
                        id = existing?.id ?: CareScheduleStore.newMedicineId(),
                        name = name.trim(),
                        dosage = dosage.trim(),
                        timeMinutes = time,
                        frequency = frequency,
                        foodRelation = relation,
                        mealSlot = slot,
                        // Editing must not silently un-tick a dose already taken today.
                        takenAtMillis = existing?.takenAtMillis,
                        notes = notes.trim().takeIf { it.isNotEmpty() },
                    ),
                )
            }

            if (existing != null) {
                Spacer(Modifier.height(6.dp))
                TextButton(
                    onClick = { confirmingDelete = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = null,
                        tint = StopRed,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.care_delete_medicine),
                        color = StopRed,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }

    if (confirmingDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.care_delete_confirm_title, existing.name)) },
            text = { Text(stringResource(R.string.care_delete_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    onDelete(existing.id)
                }) { Text(stringResource(R.string.care_delete_medicine), color = StopRed) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) {
                    Text(stringResource(R.string.trigger_log_clear_no))
                }
            },
        )
    }
}

// --- shared pieces -------------------------------------------------------------------

/**
 * A labelled time that opens a picker.
 *
 * @param trailing a note shown next to the value — used to say a dose time is still following
 *   its meal rather than having been set by hand.
 */
@Composable
private fun TimeRow(
    label: String,
    minutes: Int,
    trailing: String? = null,
    onChange: (Int) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button) { picking = true }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Schedule,
            contentDescription = null,
            tint = MutedText,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, color = NavyInk, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            trailing?.let {
                Text(text = it, color = MutedText, fontSize = 12.sp)
            }
        }
        Text(
            text = CareBriefing.clock(minutes),
            color = AccentBlue,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
        )
    }

    if (picking) {
        TimePickerDialog(
            initialMinutes = minutes,
            onDismiss = { picking = false },
            onConfirm = {
                picking = false
                onChange(it)
            },
        )
    }
}

/** A time that can be absent — "Add" when unset, and clearable once set. */
@Composable
private fun OptionalTimeRow(label: String, minutes: Int?, onChange: (Int?) -> Unit) {
    if (minutes == null) {
        var picking by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button) { picking = true }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, color = MutedText, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text(
                text = stringResource(R.string.care_add_time),
                color = AccentBlue,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        if (picking) {
            TimePickerDialog(
                // Mid-afternoon is a more useful starting point for a snack than midnight.
                initialMinutes = 16 * 60,
                onDismiss = { picking = false },
                onConfirm = {
                    picking = false
                    onChange(it)
                },
            )
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f)) {
                TimeRow(label = label, minutes = minutes) { onChange(it) }
            }
            TextButton(onClick = { onChange(null) }) {
                Text(stringResource(R.string.care_clear), color = MutedText, fontSize = 13.sp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    initialMinutes: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = initialMinutes / 60,
        initialMinute = initialMinutes % 60,
        // 12-hour, matching how every time in this feature is displayed and spoken.
        is24Hour = false,
    )
    var showInputMode by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) {
                Text(stringResource(R.string.care_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.trigger_log_clear_no)) }
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (showInputMode) {
                        TimeInput(state = state)
                    } else {
                        TimePicker(state = state)
                    }
                }
                
                Spacer(Modifier.height(8.dp))
                
                TextButton(onClick = { showInputMode = !showInputMode }) {
                    Text(
                        text = if (showInputMode) "Use clock dial" else "Use keyboard entry",
                        color = AccentBlue
                    )
                }
            }
        },
    )
}

/**
 * Single-select chips.
 *
 * Wrapped onto lines by hand rather than with a FlowRow: the option sets here are small and
 * fixed, and `FlowRow` is still experimental in this Compose version.
 */
@Composable
private fun <T> ChipRow(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.chunked(CHIPS_PER_ROW).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { option ->
                    val isSelected = option == selected
                    Text(
                        text = label(option),
                        color = if (isSelected) Color.White else NavyInk,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSelected) AccentBlue else SoftBlueContainer)
                            .clickable(role = Role.RadioButton) { onSelect(option) }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun foodRelationChipLabel(relation: FoodRelation): String = stringResource(
    when (relation) {
        FoodRelation.BEFORE_FOOD -> R.string.care_relation_before
        FoodRelation.AFTER_FOOD -> R.string.care_relation_after
        FoodRelation.WITH_FOOD -> R.string.care_relation_with
        FoodRelation.NONE -> R.string.care_relation_none
    },
)

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        color = MutedText,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
        Text(text = text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SheetTitle(title: String, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            color = NavyInk,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = stringResource(R.string.trigger_log_clear_no),
            tint = MutedText,
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(18.dp))
                .clickable(role = Role.Button, onClick = onClose)
                .padding(8.dp),
        )
    }
    Spacer(Modifier.height(10.dp))
}

private const val CHIPS_PER_ROW = 3
