package com.example.sentriai.care

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the dose line and the agent briefing.
 *
 * Both come out of the same functions precisely so the screen and the agent cannot describe a
 * dose differently, and these tests are what hold that together — if the two ever diverge, they
 * diverge here first.
 */
class CareBriefingTest {

    private val metformin = Medicine(
        id = "m1",
        name = "Metformin",
        dosage = "500 mg",
        timeMinutes = 8 * 60,
        frequency = MedicineFrequency.TWICE_DAILY,
        foodRelation = FoodRelation.AFTER_FOOD,
        mealSlot = MealSlot.BREAKFAST,
    )

    private val tabletX = Medicine(
        id = "m2",
        name = "Tablet X",
        dosage = "5 mg",
        timeMinutes = 13 * 60,
        foodRelation = FoodRelation.BEFORE_FOOD,
        mealSlot = MealSlot.LUNCH,
    )

    @Test
    fun `dose lines read exactly as specified`() {
        assertEquals("8:00 AM - Metformin - After breakfast", CareBriefing.doseLine(metformin))
        assertEquals("1:00 PM - Tablet X - Before lunch", CareBriefing.doseLine(tabletX))
    }

    @Test
    fun `a dose with no food rule does not invent one`() {
        val plain = metformin.copy(
            name = "Vitamin D",
            foodRelation = FoodRelation.NONE,
            mealSlot = MealSlot.NONE,
        )
        assertEquals("8:00 AM - Vitamin D", CareBriefing.doseLine(plain))
        assertEquals("", CareBriefing.foodRelationLabel(FoodRelation.NONE, MealSlot.BREAKFAST))
    }

    @Test
    fun `the food rule falls back to food when no meal is linked`() {
        assertEquals("With food", CareBriefing.foodRelationLabel(FoodRelation.WITH_FOOD, MealSlot.NONE))
        assertEquals("After dinner", CareBriefing.foodRelationLabel(FoodRelation.AFTER_FOOD, MealSlot.DINNER))
    }

    @Test
    fun `midnight and noon are not confused`() {
        assertEquals("12:00 AM", CareBriefing.clock(0))
        assertEquals("12:30 PM", CareBriefing.clock(12 * 60 + 30))
        assertEquals("11:59 PM", CareBriefing.clock(23 * 60 + 59))
    }

    @Test
    fun `the agent briefing carries the food rule and the taken state`() {
        val briefing = CareBriefing.agentBriefing(
            CareSchedule(
                medicines = listOf(metformin, tabletX.copy(takenAtMillis = 1L)),
                meals = MealSchedule.DEFAULT,
            ),
        )

        assertTrue(briefing.contains("Metformin, 500 mg, at 8:00 AM, twice a day, after breakfast"))
        assertTrue(briefing.contains("NOT YET TAKEN"))
        assertTrue(briefing.contains("Tablet X"))
        assertTrue(briefing.contains("ALREADY TAKEN"))
        assertTrue(briefing.contains("Breakfast: 8:00 AM"))
        assertTrue(briefing.contains("Dinner: 8:00 PM"))
    }

    @Test
    fun `an empty schedule tells the agent not to make one up`() {
        val briefing = CareBriefing.agentBriefing(CareSchedule.EMPTY)
        assertTrue(briefing.contains("No medicines are on file. Do not invent any."))
    }

    @Test
    fun `optional meals only appear when they are set`() {
        val withoutSnack = CareBriefing.agentBriefing(CareSchedule.EMPTY)
        assertTrue(!withoutSnack.contains("Snack"))

        val withSnack = CareBriefing.agentBriefing(
            CareSchedule.EMPTY.copy(
                meals = MealSchedule.DEFAULT.copy(snackMinutes = 16 * 60, bedtimeMinutes = 22 * 60),
            ),
        )
        assertTrue(withSnack.contains("Snack: 4:00 PM"))
        assertTrue(withSnack.contains("Bedtime: 10:00 PM"))
    }

    @Test
    fun `meal times come back in clock order`() {
        val slots = MealSchedule.DEFAULT.copy(snackMinutes = 16 * 60).times().map { it.slot }
        assertEquals(listOf(MealSlot.BREAKFAST, MealSlot.LUNCH, MealSlot.SNACK, MealSlot.DINNER), slots)
    }
}
