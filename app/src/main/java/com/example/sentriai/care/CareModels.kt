package com.example.sentriai.care

import org.json.JSONObject

/**
 * When a dose is taken relative to eating.
 *
 * This is not cosmetic. Metformin after food and levothyroxine before food are different
 * instructions with different consequences, and "8:00 AM — Metformin" on its own does not carry
 * either of them. Every surface that shows a dose — the companion screen, the check-in agent's
 * briefing, the spoken reminder — renders this alongside the time.
 */
enum class FoodRelation {
    BEFORE_FOOD,
    AFTER_FOOD,
    WITH_FOOD,

    /** No instruction either way — the dose stands on its own clock. */
    NONE,
}

/** The meal a dose is anchored to, when it is anchored to one at all. */
enum class MealSlot {
    BREAKFAST,
    LUNCH,
    DINNER,
    SNACK,
    NONE,
}

/** How often a medicine recurs. Only [DAILY] affects today's list today; the rest are labels. */
enum class MedicineFrequency {
    DAILY,
    TWICE_DAILY,
    THRICE_DAILY,
    WEEKLY,
    AS_NEEDED,
}

/**
 * One scheduled dose.
 *
 * A medicine taken three times a day is three [Medicine] rows, not one row with three times.
 * Adherence is tracked per dose ([takenAtMillis]), and a single row could only record that
 * *something* was taken today — which is the question the check-in call exists to answer
 * precisely.
 */
data class Medicine(
    val id: String,
    val name: String,
    val dosage: String,
    /** Minutes past local midnight. Stored as an int so it survives a timezone change intact. */
    val timeMinutes: Int,
    val frequency: MedicineFrequency = MedicineFrequency.DAILY,
    val foodRelation: FoodRelation = FoodRelation.NONE,
    val mealSlot: MealSlot = MealSlot.NONE,
    /** Epoch millis of the tap that marked this taken, or null. Cleared at each day rollover. */
    val takenAtMillis: Long? = null,
    val notes: String? = null,
) {
    val isTaken: Boolean get() = takenAtMillis != null

    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_ID, id)
        put(KEY_NAME, name)
        put(KEY_DOSAGE, dosage)
        put(KEY_TIME, timeMinutes)
        put(KEY_FREQUENCY, frequency.name)
        put(KEY_FOOD_RELATION, foodRelation.name)
        put(KEY_MEAL_SLOT, mealSlot.name)
        put(KEY_TAKEN_AT, takenAtMillis ?: JSONObject.NULL)
        put(KEY_NOTES, notes ?: JSONObject.NULL)
    }

    companion object {
        private const val KEY_ID = "id"
        private const val KEY_NAME = "name"
        private const val KEY_DOSAGE = "dosage"
        private const val KEY_TIME = "time_minutes"
        private const val KEY_FREQUENCY = "frequency"
        private const val KEY_FOOD_RELATION = "food_relation"
        private const val KEY_MEAL_SLOT = "meal_slot"
        private const val KEY_TAKEN_AT = "taken_at"
        private const val KEY_NOTES = "notes"

        fun fromJson(json: JSONObject): Medicine = Medicine(
            id = json.getString(KEY_ID),
            name = json.getString(KEY_NAME),
            dosage = json.optString(KEY_DOSAGE),
            timeMinutes = json.getInt(KEY_TIME),
            // An unknown enum name is read as the safest neutral value rather than throwing:
            // a schedule written by a newer build must not make the whole list unreadable.
            frequency = json.optString(KEY_FREQUENCY).toEnumOr(MedicineFrequency.DAILY),
            foodRelation = json.optString(KEY_FOOD_RELATION).toEnumOr(FoodRelation.NONE),
            mealSlot = json.optString(KEY_MEAL_SLOT).toEnumOr(MealSlot.NONE),
            takenAtMillis = if (json.isNull(KEY_TAKEN_AT)) null else json.getLong(KEY_TAKEN_AT),
            notes = if (json.isNull(KEY_NOTES)) null else json.optString(KEY_NOTES).ifBlank { null },
        )
    }
}

/** One meal's clock time. [minutes] is minutes past local midnight, as in [Medicine]. */
data class MealTime(val slot: MealSlot, val minutes: Int)

/**
 * The day's eating times. Breakfast, lunch and dinner always exist; snack and bedtime are
 * optional because plenty of people do not have either, and inventing a time for them would
 * put a wrong instruction in the agent's mouth.
 */
data class MealSchedule(
    val breakfastMinutes: Int,
    val lunchMinutes: Int,
    val dinnerMinutes: Int,
    val snackMinutes: Int? = null,
    val bedtimeMinutes: Int? = null,
) {
    /** Every configured meal in clock order, skipping the optional ones that are unset. */
    fun times(): List<MealTime> = buildList {
        add(MealTime(MealSlot.BREAKFAST, breakfastMinutes))
        snackMinutes?.let { add(MealTime(MealSlot.SNACK, it)) }
        add(MealTime(MealSlot.LUNCH, lunchMinutes))
        add(MealTime(MealSlot.DINNER, dinnerMinutes))
    }.sortedBy { it.minutes }

    fun minutesFor(slot: MealSlot): Int? = when (slot) {
        MealSlot.BREAKFAST -> breakfastMinutes
        MealSlot.LUNCH -> lunchMinutes
        MealSlot.DINNER -> dinnerMinutes
        MealSlot.SNACK -> snackMinutes
        MealSlot.NONE -> null
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_BREAKFAST, breakfastMinutes)
        put(KEY_LUNCH, lunchMinutes)
        put(KEY_DINNER, dinnerMinutes)
        put(KEY_SNACK, snackMinutes ?: JSONObject.NULL)
        put(KEY_BEDTIME, bedtimeMinutes ?: JSONObject.NULL)
    }

    companion object {
        private const val KEY_BREAKFAST = "breakfast"
        private const val KEY_LUNCH = "lunch"
        private const val KEY_DINNER = "dinner"
        private const val KEY_SNACK = "snack"
        private const val KEY_BEDTIME = "bedtime"

        /** 8:00 / 13:00 / 20:00 — a starting point the caregiver is expected to correct. */
        val DEFAULT = MealSchedule(
            breakfastMinutes = 8 * 60,
            lunchMinutes = 13 * 60,
            dinnerMinutes = 20 * 60,
        )

        fun fromJson(json: JSONObject): MealSchedule = MealSchedule(
            breakfastMinutes = json.optInt(KEY_BREAKFAST, DEFAULT.breakfastMinutes),
            lunchMinutes = json.optInt(KEY_LUNCH, DEFAULT.lunchMinutes),
            dinnerMinutes = json.optInt(KEY_DINNER, DEFAULT.dinnerMinutes),
            snackMinutes = if (json.isNull(KEY_SNACK)) null else json.optInt(KEY_SNACK),
            bedtimeMinutes = if (json.isNull(KEY_BEDTIME)) null else json.optInt(KEY_BEDTIME),
        )
    }
}

/** Everything the companion screen renders and the check-in agent is briefed with. */
data class CareSchedule(
    val medicines: List<Medicine>,
    val meals: MealSchedule,
) {
    /** Doses in clock order — the order both the screen and the spoken briefing use. */
    fun medicinesByTime(): List<Medicine> = medicines.sortedBy { it.timeMinutes }

    fun pending(): List<Medicine> = medicinesByTime().filterNot { it.isTaken }

    companion object {
        val EMPTY = CareSchedule(emptyList(), MealSchedule.DEFAULT)
    }
}

private inline fun <reified T : Enum<T>> String?.toEnumOr(fallback: T): T =
    this?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: fallback
