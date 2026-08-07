package com.example.sentriai.care

/**
 * Works out when a dose is due from the meal it is tied to.
 *
 * The caregiver enters two things: when meals happen, and when medicines happen. Those are not
 * independent — "after breakfast" already says when, once breakfast has a time. Making somebody
 * set a clock time for a dose they have just described as "after breakfast" is asking the same
 * question twice, and it invites the two answers to disagree.
 *
 * So the editor derives the time and lets it be overridden, rather than demanding it. The
 * override matters: a dose can legitimately be "after breakfast" at a time that is not
 * [OFFSET_MINUTES] past breakfast, and a schedule that refuses to express that is worse than one
 * that guesses.
 */
object DoseTiming {

    /**
     * How far from the meal a before/after dose sits.
     *
     * Half an hour is the usual instruction on a label for both, and it is a starting point the
     * caregiver can move — not a clinical claim. Nothing downstream depends on the exact value;
     * the food relation is what the agent reads out and what the screen shows.
     */
    const val OFFSET_MINUTES = 30

    /**
     * @return the derived time in minutes past midnight, or null when there is nothing to derive
     *   it from — no meal linked, or the linked optional meal is not configured.
     */
    fun derive(meals: MealSchedule, slot: MealSlot, relation: FoodRelation): Int? {
        val mealAt = meals.minutesFor(slot) ?: return null
        val shifted = when (relation) {
            FoodRelation.BEFORE_FOOD -> mealAt - OFFSET_MINUTES
            FoodRelation.AFTER_FOOD -> mealAt + OFFSET_MINUTES
            FoodRelation.WITH_FOOD -> mealAt
            // No food rule means the meal is only a label for when, so use the meal time itself.
            FoodRelation.NONE -> mealAt
        }
        // A dose derived as before-breakfast at 07:30 stays on the same day; clamping rather
        // than wrapping keeps "before breakfast" from landing at 11:30 PM the night before.
        return shifted.coerceIn(0, 24 * 60 - 1)
    }
}
