package com.example.sentriai.care

import java.util.Locale

/**
 * Turns a [CareSchedule] into the two text forms the rest of the app needs: the line the
 * companion screen shows, and the briefing block the Agora agent is given.
 *
 * Both come from here rather than from their respective call sites so they cannot disagree. If
 * the screen says "after breakfast" and the agent has been told "before breakfast", the person
 * on the call gets contradicted by their own phone — and has no way to tell which is right.
 *
 * The strings are English literals rather than resources on purpose: the agent briefing is a
 * prompt, not UI, and localising it would change what the model is told when the phone's
 * language changes. The screen composes its headings from resources and its dose lines from
 * here, so the localised chrome and the shared clinical text stay separate.
 */
object CareBriefing {

    /** `"8:00 AM"`. Fixed en-US so a dose time reads the same in the prompt and on screen. */
    fun clock(minutes: Int): String {
        val normalized = ((minutes % 1440) + 1440) % 1440
        val hour24 = normalized / 60
        val minute = normalized % 60
        val hour12 = when (hour24 % 12) {
            0 -> 12
            else -> hour24 % 12
        }
        val suffix = if (hour24 < 12) "AM" else "PM"
        return String.format(Locale.US, "%d:%02d %s", hour12, minute, suffix)
    }

    /**
     * `"After breakfast"`, `"Before food"`, or `""` when there is no instruction.
     *
     * The meal name is folded in when there is one, because "after breakfast" is a time a
     * person can act on and "after food" is a rule they have to apply.
     */
    fun foodRelationLabel(relation: FoodRelation, slot: MealSlot): String {
        val anchor = when (slot) {
            MealSlot.BREAKFAST -> "breakfast"
            MealSlot.LUNCH -> "lunch"
            MealSlot.DINNER -> "dinner"
            MealSlot.SNACK -> "a snack"
            MealSlot.NONE -> "food"
        }
        return when (relation) {
            FoodRelation.BEFORE_FOOD -> "Before $anchor"
            FoodRelation.AFTER_FOOD -> "After $anchor"
            FoodRelation.WITH_FOOD -> "With $anchor"
            // With no food rule, naming the meal would imply one. Say nothing instead.
            FoodRelation.NONE -> ""
        }
    }

    /** `"8:00 AM - Metformin - After breakfast"` — the line the companion screen shows. */
    fun doseLine(medicine: Medicine): String = buildString {
        append(clock(medicine.timeMinutes))
        append(" - ")
        append(medicine.name)
        val relation = foodRelationLabel(medicine.foodRelation, medicine.mealSlot)
        if (relation.isNotEmpty()) {
            append(" - ")
            append(relation)
        }
    }

    fun mealLabel(slot: MealSlot): String = when (slot) {
        MealSlot.BREAKFAST -> "Breakfast"
        MealSlot.LUNCH -> "Lunch"
        MealSlot.DINNER -> "Dinner"
        MealSlot.SNACK -> "Snack"
        MealSlot.NONE -> ""
    }

    fun frequencyLabel(frequency: MedicineFrequency): String = when (frequency) {
        MedicineFrequency.DAILY -> "once a day"
        MedicineFrequency.TWICE_DAILY -> "twice a day"
        MedicineFrequency.THRICE_DAILY -> "three times a day"
        MedicineFrequency.WEEKLY -> "once a week"
        MedicineFrequency.AS_NEEDED -> "as needed"
    }

    /**
     * The block injected into the agent's system prompt.
     *
     * Written as a labelled list rather than prose because the agent is asked to *read values
     * back*, not to summarise them — "did you take the Metformin after breakfast" has to name
     * the drug and the food rule exactly, and a paragraph invites the model to paraphrase a
     * dosage.
     *
     * Taken/not-taken is included so a check-in call opens knowing what is outstanding instead
     * of asking about doses the person already ticked off an hour ago.
     */
    fun agentBriefing(schedule: CareSchedule): String {
        val meals = schedule.meals
        val mealLines = buildList {
            add("- Breakfast: ${clock(meals.breakfastMinutes)}")
            add("- Lunch: ${clock(meals.lunchMinutes)}")
            add("- Dinner: ${clock(meals.dinnerMinutes)}")
            meals.snackMinutes?.let { add("- Snack: ${clock(it)}") }
            meals.bedtimeMinutes?.let { add("- Bedtime: ${clock(it)}") }
        }

        val medicineLines = schedule.medicinesByTime().map { medicine ->
            buildString {
                append("- ${medicine.name}")
                if (medicine.dosage.isNotBlank()) append(", ${medicine.dosage}")
                append(", at ${clock(medicine.timeMinutes)}")
                append(", ${frequencyLabel(medicine.frequency)}")
                val relation = foodRelationLabel(medicine.foodRelation, medicine.mealSlot)
                if (relation.isNotEmpty()) append(", ${relation.lowercase(Locale.US)}")
                append(if (medicine.isTaken) ", ALREADY TAKEN today" else ", NOT YET TAKEN today")
                medicine.notes?.takeIf { it.isNotBlank() }?.let { append(". Note: $it") }
            }
        }

        return buildString {
            appendLine("MEAL TIMES")
            mealLines.forEach { appendLine(it) }
            appendLine()
            appendLine("MEDICINES")
            if (medicineLines.isEmpty()) {
                appendLine("- No medicines are on file. Do not invent any.")
            } else {
                medicineLines.forEach { appendLine(it) }
            }
        }.trim()
    }
}
