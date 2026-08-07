package com.example.sentriai.reminder

import com.example.sentriai.care.CareBriefing
import com.example.sentriai.care.CareSchedule
import com.example.sentriai.care.FoodRelation
import com.example.sentriai.care.MealSlot
import com.example.sentriai.care.MedicineFrequency
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** What a reminder is about. Drives the heading and the icon on the overlay card. */
enum class ReminderKind {
    MEDICINE,
    MEAL,
}

/**
 * One thing to be reminded of at one time of day.
 *
 * [title] and [detail] are composed here rather than in the overlay so the reminder cannot
 * describe a dose differently from the companion screen — both go through [CareBriefing]. Like
 * that class, the clinical text is English literals: it names a drug and a food rule, and the
 * overlay's own chrome (headings, buttons) is what comes from string resources.
 */
data class ReminderItem(
    /** Stable across a reschedule, so a batch can be recognised after a round trip through JSON. */
    val key: String,
    val kind: ReminderKind,
    /** Minutes past local midnight, matching [com.example.sentriai.care.Medicine.timeMinutes]. */
    val minutes: Int,
    val title: String,
    val detail: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_KEY, key)
        put(KEY_KIND, kind.name)
        put(KEY_MINUTES, minutes)
        put(KEY_TITLE, title)
        put(KEY_DETAIL, detail)
    }

    companion object {
        private const val KEY_KEY = "key"
        private const val KEY_KIND = "kind"
        private const val KEY_MINUTES = "minutes"
        private const val KEY_TITLE = "title"
        private const val KEY_DETAIL = "detail"

        fun fromJson(json: JSONObject): ReminderItem = ReminderItem(
            key = json.getString(KEY_KEY),
            kind = if (json.optString(KEY_KIND) == ReminderKind.MEAL.name) {
                ReminderKind.MEAL
            } else {
                ReminderKind.MEDICINE
            },
            minutes = json.getInt(KEY_MINUTES),
            title = json.getString(KEY_TITLE),
            detail = json.optString(KEY_DETAIL),
        )
    }
}

/**
 * Everything due at one minute of the day, and whether that minute is today or tomorrow.
 *
 * A batch rather than a single reminder because two things due at 8:00 AM are one interruption,
 * not two. The overlay renders every [items] entry on one card with one Stop and one Snooze —
 * queueing them would make the person dismiss two windows for a moment that happened once.
 */
data class ReminderBatch(
    val minutes: Int,
    val nextDay: Boolean,
    val items: List<ReminderItem>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_MINUTES, minutes)
        put(KEY_NEXT_DAY, nextDay)
        put(KEY_ITEMS, JSONArray().apply { items.forEach { put(it.toJson()) } })
    }

    fun toJsonString(): String = toJson().toString()

    companion object {
        private const val KEY_MINUTES = "minutes"
        private const val KEY_NEXT_DAY = "next_day"
        private const val KEY_ITEMS = "items"

        fun fromJson(json: JSONObject): ReminderBatch {
            val array = json.optJSONArray(KEY_ITEMS) ?: JSONArray()
            return ReminderBatch(
                minutes = json.getInt(KEY_MINUTES),
                nextDay = json.optBoolean(KEY_NEXT_DAY, false),
                items = buildList {
                    for (i in 0 until array.length()) {
                        runCatching { ReminderItem.fromJson(array.getJSONObject(i)) }
                            .getOrNull()
                            ?.let { add(it) }
                    }
                },
            )
        }

        /** Null when [raw] is absent or unreadable — a dropped reminder beats a crash in a receiver. */
        fun fromJsonStringOrNull(raw: String?): ReminderBatch? {
            if (raw.isNullOrBlank()) return null
            return runCatching { fromJson(JSONObject(raw)) }.getOrNull()
        }
    }
}

/**
 * Turns the care schedule into the reminders it implies, and works out which one is next.
 *
 * Pure by design — every function here takes the schedule and a clock value and returns a value,
 * with no `Context` and no `AlarmManager` in sight. That is what lets the awkward parts (the
 * wrap past midnight, the day rollover, the "what is due at 8:00 AM" lookup) be unit-tested off
 * device, leaving [ReminderScheduler] as a thin translation into `AlarmManager` calls.
 *
 * ### One alarm, not one per reminder
 *
 * Only the *next* batch is ever scheduled; when it fires, the receiver schedules the one after
 * it. The alternative — an alarm per dose — needs a distinct `PendingIntent` request code per
 * reminder, and the only stable source for one is a hash of the reminder key. Two reminders whose
 * keys collide would then silently share an alarm, and one of them would simply never fire. A
 * single rolling alarm has one request code, no collisions, and it makes simultaneous reminders
 * fall out for free: everything at that minute is in the batch.
 */
object ReminderPlan {

    /**
     * @param includeCompleted false drops doses already ticked off today. Scheduling passes true
     *   (tomorrow's alarm must still be set for a dose taken this morning); firing passes false,
     *   so a dose the person already marked taken does not interrupt them about it.
     */
    fun items(schedule: CareSchedule, includeCompleted: Boolean = true): List<ReminderItem> {
        val medicines = schedule.medicines
            .filter { it.frequency.remindable() }
            .filter { includeCompleted || !it.isTaken }
            .map { medicine ->
                ReminderItem(
                    key = "medicine:${medicine.id}",
                    kind = ReminderKind.MEDICINE,
                    minutes = medicine.timeMinutes,
                    title = medicine.name,
                    detail = medicineDetail(medicine.dosage, medicine.foodRelation, medicine.mealSlot),
                )
            }

        val meals = schedule.meals.times().map { meal ->
            ReminderItem(
                key = "meal:${meal.slot.name}",
                kind = ReminderKind.MEAL,
                minutes = meal.minutes,
                title = CareBriefing.mealLabel(meal.slot),
                detail = "Time for ${CareBriefing.mealLabel(meal.slot).lowercase()}",
            )
        }

        return (medicines + meals).sortedBy { it.minutes }
    }

    /**
     * The next batch due strictly after [afterMinutes], wrapping to tomorrow when the day has no
     * later reminder in it.
     *
     * Strictly after, and driven by the minute that was *scheduled* rather than by the clock: an
     * alarm that fires a few hundred milliseconds early would otherwise still be inside its own
     * minute, and rescheduling would pick that same minute again and fire in a loop.
     *
     * @return null when the schedule implies no reminders at all, which is a real state — an
     *   empty medicine list with meal times the caregiver has not looked at yet.
     */
    fun nextBatch(schedule: CareSchedule, afterMinutes: Int): ReminderBatch? {
        val all = items(schedule)
        if (all.isEmpty()) return null

        val times = all.map { it.minutes }.distinct().sorted()
        val laterToday = times.firstOrNull { it > afterMinutes }
        val minutes = laterToday ?: times.first()

        return ReminderBatch(
            minutes = minutes,
            nextDay = laterToday == null,
            items = all.filter { it.minutes == minutes },
        )
    }

    /**
     * What is actually due at [minutes], recomputed against the schedule as it stands now.
     *
     * The alarm was set from the schedule as it was then. Between the two the caregiver may have
     * moved a dose, deleted it, or ticked it off, so the batch is rebuilt at fire time rather
     * than carried in the alarm's extras. An empty result means the reminder no longer applies
     * and nothing should be shown.
     */
    fun batchAt(schedule: CareSchedule, minutes: Int): ReminderBatch = ReminderBatch(
        minutes = minutes,
        nextDay = false,
        items = items(schedule, includeCompleted = false).filter { it.minutes == minutes },
    )

    /** Minutes past local midnight for [calendar]'s wall-clock time. */
    fun minutesOfDay(calendar: Calendar): Int =
        calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)

    /**
     * The wall-clock instant [batch] should fire at.
     *
     * Set on a copy of [now] rather than computed from midnight + minutes so a DST transition
     * lands wherever the platform's own calendar arithmetic puts it. The trailing guard covers
     * the case that leaves: a time that has already passed by the time it is resolved (a
     * spring-forward gap, or an alarm being re-planned a moment too late) is pushed to the next
     * day rather than handed to `AlarmManager` in the past, where it would fire immediately.
     */
    fun triggerAtMillis(batch: ReminderBatch, now: Calendar): Long {
        val target = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, batch.minutes / 60)
            set(Calendar.MINUTE, batch.minutes % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (batch.nextDay) add(Calendar.DAY_OF_YEAR, 1)
        }
        if (target.timeInMillis <= now.timeInMillis) target.add(Calendar.DAY_OF_YEAR, 1)
        return target.timeInMillis
    }

    /**
     * `"500 mg · After breakfast"` — dosage and food rule, whichever of them exist.
     *
     * The food relation is never dropped when it is present. "Metformin" on its own is a
     * different instruction from "Metformin, after breakfast", and the reminder is the surface
     * most likely to be acted on without reading anything else.
     */
    private fun medicineDetail(dosage: String, relation: FoodRelation, slot: MealSlot): String {
        val food = CareBriefing.foodRelationLabel(relation, slot)
        return listOf(dosage.trim(), food).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    /**
     * Whether a frequency describes a dose that recurs at the same time every day.
     *
     * `WEEKLY` is excluded because the schedule stores no day of week for it — reminding daily
     * would be a wrong instruction six days out of seven, and picking a day would be inventing
     * one. `AS_NEEDED` has no schedule to remind from at all. Both still appear on the companion
     * screen and in the agent's briefing; they just do not raise an alarm.
     */
    private fun MedicineFrequency.remindable(): Boolean = when (this) {
        MedicineFrequency.DAILY,
        MedicineFrequency.TWICE_DAILY,
        MedicineFrequency.THRICE_DAILY,
        -> true

        MedicineFrequency.WEEKLY,
        MedicineFrequency.AS_NEEDED,
        -> false
    }
}
