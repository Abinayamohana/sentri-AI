package com.example.sentriai.reminder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the character actually says.
 *
 * Worth testing rather than listening to, because the failures here are silent in the literal
 * sense: a dosage spoken as two letters, or a food rule dropped from the sentence, still sounds
 * like a working reminder to anyone who already knows what it was meant to say.
 */
class ReminderSpeechTest {

    private fun medicine(title: String, detail: String) =
        ReminderItem("medicine:$title", ReminderKind.MEDICINE, 8 * 60, title, detail)

    private fun meal(title: String) =
        ReminderItem("meal:$title", ReminderKind.MEAL, 13 * 60, title, "Time for ${title.lowercase()}")

    private fun batch(vararg items: ReminderItem) =
        ReminderBatch(minutes = 8 * 60, nextDay = false, items = items.toList())

    @Test
    fun `a dose is spoken with its dosage and food rule`() {
        val line = ReminderSpeech.line(batch(medicine("Metformin", "500 mg · After breakfast")))

        assertEquals("It's time for your Metformin, 500 milligrams, after breakfast.", line)
    }

    @Test
    fun `a first name is used and a surname is not`() {
        val line = ReminderSpeech.line(
            batch(medicine("Metformin", "")),
            personName = "Naveen Kumar",
        )

        // Being greeted with a full name several times a day reads as a form letter.
        assertTrue(line.startsWith("Hello Naveen."))
        assertFalse(line.contains("Kumar"))
    }

    @Test
    fun `no name means no greeting rather than an empty one`() {
        val line = ReminderSpeech.line(batch(medicine("Metformin", "")), personName = "   ")

        assertEquals("It's time for your Metformin.", line)
    }

    @Test
    fun `a meal is spoken without the possessive`() {
        assertEquals("It's time for lunch.", ReminderSpeech.line(batch(meal("Lunch"))))
    }

    @Test
    fun `everything due at once is one sentence`() {
        val line = ReminderSpeech.line(
            batch(medicine("Metformin", "500 mg · After breakfast"), meal("Breakfast")),
        )

        assertEquals(
            "It's time for your Metformin, 500 milligrams, after breakfast, and for breakfast.",
            line,
        )
    }

    @Test
    fun `a dose with no dosage and no food rule is still a sentence`() {
        assertEquals("It's time for your Ramipril.", ReminderSpeech.line(batch(medicine("Ramipril", ""))))
    }

    @Test
    fun `nothing due says nothing`() {
        assertEquals("", ReminderSpeech.line(batch()))
    }

    // ---- unit expansion -----------------------------------------------------------

    @Test
    fun `dosage units are expanded into words`() {
        // "mg" left alone comes out of most engines as two letters, which for a dose is the one
        // place a listener cannot afford to guess.
        assertTrue(
            ReminderSpeech.line(batch(medicine("A", "250 mcg"))).contains("250 micrograms"),
        )
        assertTrue(ReminderSpeech.line(batch(medicine("A", "5 ml"))).contains("5 millilitres"))
        assertTrue(ReminderSpeech.line(batch(medicine("A", "10 IU"))).contains("10 units"))
    }

    @Test
    fun `a unit with no space is still expanded`() {
        assertTrue(ReminderSpeech.line(batch(medicine("A", "500mg"))).contains("500 milligrams"))
    }

    @Test
    fun `a single unit is spoken in the singular`() {
        // "1 milligrams" is the kind of small wrongness that makes a voice sound synthetic. The
        // whole sentence is asserted because "1 milligram" is a substring of the plural.
        assertEquals(
            "It's time for your A, 1 milligram.",
            ReminderSpeech.line(batch(medicine("A", "1 mg"))),
        )
    }

    @Test
    fun `a decimal dose keeps its number intact`() {
        assertTrue(ReminderSpeech.line(batch(medicine("A", "2.5 mg"))).contains("2.5 milligrams"))
    }

    @Test
    fun `free-text dosages are left alone`() {
        // "1 tablet" needs no help, and a unit expander that rewrote it would be a liability.
        assertEquals(
            "It's time for your Warfarin, 1 tablet.",
            ReminderSpeech.line(batch(medicine("Warfarin", "1 tablet"))),
        )
    }

    @Test
    fun `grams are deliberately not expanded`() {
        // A bare "g" in free text is as likely to belong to a word as to mean grams, and guessing
        // wrong on a dose is worse than spelling out a letter.
        val line = ReminderSpeech.line(batch(medicine("A", "1 g")))

        assertFalse(line.contains("gram"))
        assertTrue(line.contains("1 g"))
    }
}
