package com.example.sentriai.reminder

import java.util.Locale

/**
 * What the character says out loud.
 *
 * A separate composition from the card's text, not a reuse of it, because the two are read by
 * different things. `"500 mg · After breakfast"` is a good line to *look* at and a bad one to
 * hear: the separator is either read out or dropped, "mg" comes out as two letters, and the
 * capitalised fragment has no sentence around it. So the card keeps its labels and this builds
 * sentences.
 *
 * Pure, for the same reason as [ReminderPlan] — the awkward parts here are string assembly and
 * unit expansion, and both are worth checking without a device that can talk.
 */
object ReminderSpeech {

    /**
     * The whole utterance: a greeting by first name when there is one, then what is due.
     *
     * Deliberately two short sentences at most. This is heard up to several times a day, every
     * day, and anything longer than the instruction itself becomes something to sit through
     * rather than listen to.
     */
    fun line(batch: ReminderBatch, personName: String = ""): String {
        if (batch.items.isEmpty()) return ""

        val parts = batch.items.map { spokenPart(it) }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return ""

        // "your Metformin, 500 milligrams, after breakfast, and for lunch" — one "it's time for"
        // carries the whole list, so a batch does not turn into a paragraph.
        val body = "It's time for " + parts.joinToString(", and for ") + "."

        val greeting = firstName(personName)
        return if (greeting.isEmpty()) body else "Hello $greeting. $body"
    }

    /**
     * One item as a fragment that slots in after "it's time for".
     *
     * Medicines keep the possessive ("your Metformin") because the alternative reads as an
     * announcement about a drug rather than something being asked of the listener. Meals do not,
     * since "your lunch" and "lunch" mean the same thing and the shorter one is less fussy.
     */
    private fun spokenPart(item: ReminderItem): String = when (item.kind) {
        ReminderKind.MEDICINE -> buildString {
            append("your ")
            append(item.title.trim())
            // The food rule is spoken for the same reason it is never dropped from the card: it
            // is half the instruction. "After breakfast" is what makes the dose right or wrong.
            item.spokenDetails().forEach { append(", ").append(it) }
        }

        ReminderKind.MEAL -> item.title.trim().lowercase(Locale.US)
    }

    /**
     * The dosage and food rule as speakable fragments, or nothing when the dose carries neither.
     *
     * Split back out of [ReminderItem.detail] rather than re-derived, so the spoken and visible
     * versions cannot drift apart — a food rule added to one is a food rule in both.
     */
    private fun ReminderItem.spokenDetails(): List<String> = detail
        .split(DETAIL_SEPARATOR)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { expandUnits(it).lowercase(Locale.US) }

    /**
     * `"500 mg"` → `"500 milligrams"`.
     *
     * Left as-is, most engines read `mg` as the two letters, which for a dosage is the one place
     * a listener cannot afford to guess. Only the unambiguous units are expanded: `g` is left
     * alone because a bare "g" in free text is as likely to be part of something else as it is to
     * mean grams, and guessing wrong on a dose is worse than spelling a letter.
     */
    private fun expandUnits(text: String): String {
        val withUnits = UNIT_PATTERN.replace(text) { match ->
            val amount = match.groupValues[1]
            val unit = match.groupValues[2].lowercase(Locale.US)
            val word = UNITS[unit] ?: return@replace match.value
            // "1 milligrams" is the kind of small wrongness that makes a voice sound synthetic.
            val spoken = if (amount == "1") word.singular else word.plural
            "$amount $spoken"
        }
        return INTERNATIONAL_UNIT_PATTERN.replace(withUnits, "units")
    }

    /**
     * The first word of the stored name.
     *
     * The profile holds a full name, and being greeted with both barrels several times a day
     * reads as a form letter rather than a companion.
     */
    private fun firstName(personName: String): String =
        personName.trim().substringBefore(' ').trim()

    /** The separator [ReminderPlan] joins card details with. */
    private const val DETAIL_SEPARATOR = "·"

    private data class Unit(val singular: String, val plural: String)

    private val UNITS = mapOf(
        "mg" to Unit("milligram", "milligrams"),
        "mcg" to Unit("microgram", "micrograms"),
        "ml" to Unit("millilitre", "millilitres"),
    )

    /** A number, optional space, then a unit as a whole word — `"500mg"` and `"500 mg"` both. */
    private val UNIT_PATTERN = Regex("""(\d+(?:\.\d+)?)\s*(mg|mcg|ml)\b""", RegexOption.IGNORE_CASE)

    /** `IU` is always international units and never part of another word in a dosage. */
    private val INTERNATIONAL_UNIT_PATTERN = Regex("""\bIU\b""")
}
