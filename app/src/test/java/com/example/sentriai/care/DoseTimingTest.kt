package com.example.sentriai.care

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers deriving a dose time from a meal time.
 *
 * This is what lets the caregiver enter only two things — when meals are, and what medicine is
 * taken around them — instead of also computing a clock time for every dose.
 */
class DoseTimingTest {

    private val meals = MealSchedule(
        breakfastMinutes = 8 * 60,
        lunchMinutes = 13 * 60,
        dinnerMinutes = 20 * 60,
    )

    @Test
    fun `before and after sit either side of the meal`() {
        assertEquals(
            7 * 60 + 30,
            DoseTiming.derive(meals, MealSlot.BREAKFAST, FoodRelation.BEFORE_FOOD),
        )
        assertEquals(
            8 * 60 + 30,
            DoseTiming.derive(meals, MealSlot.BREAKFAST, FoodRelation.AFTER_FOOD),
        )
        assertEquals(
            8 * 60,
            DoseTiming.derive(meals, MealSlot.BREAKFAST, FoodRelation.WITH_FOOD),
        )
    }

    @Test
    fun `a meal with no food rule still gives a time`() {
        // "at lunchtime" is a legitimate instruction with no before/after to it.
        assertEquals(13 * 60, DoseTiming.derive(meals, MealSlot.LUNCH, FoodRelation.NONE))
    }

    @Test
    fun `nothing to derive from gives null rather than a guess`() {
        assertNull(DoseTiming.derive(meals, MealSlot.NONE, FoodRelation.AFTER_FOOD))
        // Snack is optional and unset here, so a dose tied to it has no anchor.
        assertNull(DoseTiming.derive(meals, MealSlot.SNACK, FoodRelation.WITH_FOOD))
    }

    @Test
    fun `an optional meal that is set can be derived from`() {
        val withSnack = meals.copy(snackMinutes = 16 * 60)
        assertEquals(
            16 * 60 + 30,
            DoseTiming.derive(withSnack, MealSlot.SNACK, FoodRelation.AFTER_FOOD),
        )
    }

    @Test
    fun `an early breakfast does not push a before-food dose into yesterday`() {
        // Clamped, not wrapped: 00:15 minus half an hour must not become 11:45 PM.
        val earlyRiser = meals.copy(breakfastMinutes = 15)
        assertEquals(
            0,
            DoseTiming.derive(earlyRiser, MealSlot.BREAKFAST, FoodRelation.BEFORE_FOOD),
        )
    }

    @Test
    fun `a late dinner does not push an after-food dose into tomorrow`() {
        val lateDinner = meals.copy(dinnerMinutes = 23 * 60 + 45)
        assertEquals(
            24 * 60 - 1,
            DoseTiming.derive(lateDinner, MealSlot.DINNER, FoodRelation.AFTER_FOOD),
        )
    }

    @Test
    fun `a derived dose reads back the way it was described`() {
        // The round trip that matters: pick "before lunch", and both the screen line and the
        // agent briefing must agree with the time that was derived.
        val time = DoseTiming.derive(meals, MealSlot.LUNCH, FoodRelation.BEFORE_FOOD)!!
        val medicine = Medicine(
            id = "m",
            name = "Tablet X",
            dosage = "5 mg",
            timeMinutes = time,
            foodRelation = FoodRelation.BEFORE_FOOD,
            mealSlot = MealSlot.LUNCH,
        )
        assertEquals("12:30 PM - Tablet X - Before lunch", CareBriefing.doseLine(medicine))
    }
}
