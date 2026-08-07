package com.example.sentriai.reminder

import com.example.sentriai.care.CareSchedule
import com.example.sentriai.care.FoodRelation
import com.example.sentriai.care.MealSchedule
import com.example.sentriai.care.MealSlot
import com.example.sentriai.care.Medicine
import com.example.sentriai.care.MedicineFrequency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * The reminder chain's decisions, tested off device.
 *
 * Everything awkward about reminders is arithmetic — which minute is next, what happens after the
 * last one of the day, what a snooze at 23:55 means — and [ReminderPlan] exists so that arithmetic
 * can be checked here rather than by waiting for 8:00 AM on a handset.
 */
class ReminderPlanTest {

    private fun medicine(
        id: String,
        name: String,
        minutes: Int,
        frequency: MedicineFrequency = MedicineFrequency.DAILY,
        dosage: String = "",
        relation: FoodRelation = FoodRelation.NONE,
        slot: MealSlot = MealSlot.NONE,
        takenAtMillis: Long? = null,
    ) = Medicine(
        id = id,
        name = name,
        dosage = dosage,
        timeMinutes = minutes,
        frequency = frequency,
        foodRelation = relation,
        mealSlot = slot,
        takenAtMillis = takenAtMillis,
    )

    /** Meals only, at 8:00 / 13:00 / 20:00. */
    private val mealsOnly = CareSchedule(emptyList(), MealSchedule.DEFAULT)

    // ---- what becomes a reminder --------------------------------------------------

    @Test
    fun `every configured meal becomes a reminder`() {
        val items = ReminderPlan.items(mealsOnly)

        assertEquals(listOf(8 * 60, 13 * 60, 20 * 60), items.map { it.minutes })
        assertTrue(items.all { it.kind == ReminderKind.MEAL })
        assertEquals(listOf("Breakfast", "Lunch", "Dinner"), items.map { it.title })
    }

    @Test
    fun `an optional meal that is set gets a reminder and one that is not does not`() {
        val withSnack = CareSchedule(
            emptyList(),
            MealSchedule.DEFAULT.copy(snackMinutes = 16 * 60, bedtimeMinutes = 22 * 60),
        )

        val keys = ReminderPlan.items(withSnack).map { it.key }

        assertTrue(keys.contains("meal:SNACK"))
        // Bedtime is not a meal and has no reminder of its own; it is a label on the schedule.
        assertFalse(keys.any { it.contains("BEDTIME") })
    }

    @Test
    fun `a dose carries its dosage and food rule into the reminder`() {
        val schedule = CareSchedule(
            listOf(
                medicine(
                    id = "m1",
                    name = "Metformin",
                    minutes = 8 * 60 + 30,
                    dosage = "500 mg",
                    relation = FoodRelation.AFTER_FOOD,
                    slot = MealSlot.BREAKFAST,
                ),
            ),
            MealSchedule.DEFAULT,
        )

        val item = ReminderPlan.items(schedule).first { it.kind == ReminderKind.MEDICINE }

        assertEquals("Metformin", item.title)
        // The food rule is the field most likely to be acted on without reading anything else,
        // so it has to survive the trip onto the overlay.
        assertEquals("500 mg · After breakfast", item.detail)
    }

    @Test
    fun `weekly and as-needed doses raise no reminder`() {
        val schedule = CareSchedule(
            listOf(
                medicine("m1", "Alendronate", 7 * 60, frequency = MedicineFrequency.WEEKLY),
                medicine("m2", "Paracetamol", 9 * 60, frequency = MedicineFrequency.AS_NEEDED),
                medicine("m3", "Metformin", 10 * 60, frequency = MedicineFrequency.DAILY),
            ),
            MealSchedule.DEFAULT,
        )

        val medicines = ReminderPlan.items(schedule).filter { it.kind == ReminderKind.MEDICINE }

        // Weekly stores no day of week, so a daily reminder would be wrong six days in seven.
        assertEquals(listOf("Metformin"), medicines.map { it.title })
    }

    @Test
    fun `a taken dose is still scheduled but is not fired`() {
        val schedule = CareSchedule(
            listOf(medicine("m1", "Metformin", 9 * 60, takenAtMillis = 1_000L)),
            MealSchedule.DEFAULT,
        )

        // Tomorrow's alarm must still be set for a dose taken this morning...
        assertTrue(ReminderPlan.items(schedule).any { it.title == "Metformin" })
        // ...but the person must not be interrupted about one they already ticked off.
        assertFalse(
            ReminderPlan.items(schedule, includeCompleted = false).any { it.title == "Metformin" },
        )
    }

    // ---- which one is next --------------------------------------------------------

    @Test
    fun `the next batch is the earliest time strictly after the one given`() {
        val batch = ReminderPlan.nextBatch(mealsOnly, afterMinutes = 9 * 60)

        assertNotNull(batch)
        assertEquals(13 * 60, batch!!.minutes)
        assertFalse(batch.nextDay)
    }

    @Test
    fun `a time is never picked as its own successor`() {
        // The guard against an alarm that fires a moment early rescheduling itself into a loop.
        val batch = ReminderPlan.nextBatch(mealsOnly, afterMinutes = 13 * 60)

        assertEquals(20 * 60, batch!!.minutes)
    }

    @Test
    fun `after the last reminder of the day the next one is tomorrow's first`() {
        val batch = ReminderPlan.nextBatch(mealsOnly, afterMinutes = 23 * 60)

        assertEquals(8 * 60, batch!!.minutes)
        assertTrue(batch.nextDay)
    }

    @Test
    fun `things due at the same minute arrive as one batch`() {
        val schedule = CareSchedule(
            listOf(
                medicine("m1", "Metformin", 13 * 60),
                medicine("m2", "Ramipril", 13 * 60),
            ),
            MealSchedule.DEFAULT,
        )

        val batch = ReminderPlan.nextBatch(schedule, afterMinutes = 9 * 60)!!

        // Two doses and lunch are one interruption at 13:00, not three.
        assertEquals(13 * 60, batch.minutes)
        assertEquals(
            listOf("Metformin", "Ramipril", "Lunch"),
            batch.items.map { it.title }.sortedBy { if (it == "Lunch") 1 else 0 },
        )
        assertEquals(3, batch.items.size)
    }

    @Test
    fun `a schedule with no medicines still has meal reminders`() {
        // Worth pinning down, because it is the reason ReminderScheduler checks
        // CareScheduleStore.hasStoredSchedule() before arming anything: a CareSchedule always
        // carries meal times, including the placeholder 8:00 / 13:00 / 20:00 that nobody entered.
        // "Nothing configured" is not a state this class can see, so the gate cannot live here.
        val batch = ReminderPlan.nextBatch(mealsOnly, afterMinutes = 0)

        assertNotNull(batch)
        assertTrue(batch!!.items.all { it.kind == ReminderKind.MEAL })
    }

    @Test
    fun `what is due at a minute is recomputed against the current schedule`() {
        val schedule = CareSchedule(
            listOf(medicine("m1", "Metformin", 8 * 60)),
            MealSchedule.DEFAULT,
        )

        // 8:00 is both breakfast and the dose.
        assertEquals(2, ReminderPlan.batchAt(schedule, 8 * 60).items.size)
        // A minute nothing is due at yields an empty batch rather than a wrong reminder — the
        // case where the caregiver moved a dose after the alarm was already set.
        assertTrue(ReminderPlan.batchAt(schedule, 9 * 60).items.isEmpty())
    }

    // ---- turning a minute into an instant -----------------------------------------

    private fun calendarAt(hour: Int, minute: Int): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2026, Calendar.AUGUST, 4, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }

    @Test
    fun `a time later today resolves to today`() {
        val now = calendarAt(9, 0)
        val batch = ReminderBatch(minutes = 13 * 60, nextDay = false, items = emptyList())

        val triggerAt = ReminderPlan.triggerAtMillis(batch, now)

        assertEquals(4 * 60 * 60 * 1000L, triggerAt - now.timeInMillis)
    }

    @Test
    fun `a next-day time resolves to tomorrow`() {
        val now = calendarAt(23, 0)
        val batch = ReminderBatch(minutes = 8 * 60, nextDay = true, items = emptyList())

        val triggerAt = ReminderPlan.triggerAtMillis(batch, now)

        assertEquals(9 * 60 * 60 * 1000L, triggerAt - now.timeInMillis)
    }

    @Test
    fun `a time that has already passed is pushed to tomorrow rather than fired at once`() {
        val now = calendarAt(14, 0)
        // nextDay is false, but 13:00 today is behind us — the shape a re-plan arriving a moment
        // late takes. Handing this to AlarmManager as-is would fire it immediately.
        val batch = ReminderBatch(minutes = 13 * 60, nextDay = false, items = emptyList())

        val triggerAt = ReminderPlan.triggerAtMillis(batch, now)

        assertTrue(triggerAt > now.timeInMillis)
        assertEquals(23 * 60 * 60 * 1000L, triggerAt - now.timeInMillis)
    }

    @Test
    fun `minutesOfDay reads the wall clock`() {
        assertEquals(14 * 60 + 37, ReminderPlan.minutesOfDay(calendarAt(14, 37)))
    }

    // ---- the round trip through JSON ----------------------------------------------

    @Test
    fun `a batch survives being written to JSON and read back`() {
        val original = ReminderBatch(
            minutes = 8 * 60 + 30,
            nextDay = true,
            items = listOf(
                ReminderItem("medicine:m1", ReminderKind.MEDICINE, 510, "Metformin", "500 mg · After breakfast"),
                ReminderItem("meal:BREAKFAST", ReminderKind.MEAL, 510, "Breakfast", "Time for breakfast"),
            ),
        )

        // The path a snooze takes: overlay → prefs → alarm → receiver.
        val restored = ReminderBatch.fromJsonStringOrNull(original.toJsonString())

        assertEquals(original, restored)
    }

    @Test
    fun `unreadable stored state yields null rather than throwing`() {
        // Read inside a BroadcastReceiver, where a throw would take the process with it.
        assertNull(ReminderBatch.fromJsonStringOrNull(null))
        assertNull(ReminderBatch.fromJsonStringOrNull(""))
        assertNull(ReminderBatch.fromJsonStringOrNull("{not json"))
    }
}
