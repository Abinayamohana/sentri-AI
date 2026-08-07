package com.example.sentriai.engine

/**
 * Deterministic distress reading for text spoken during a **live conversation**.
 *
 * This is not a second [EmergencyDetector]. The two see different material and answer different
 * questions:
 *
 * - [EmergencyDetector] judges *unprompted* speech in an empty room. It is deliberately
 *   conservative, because everything it hears is ambient — a television, a phone call to a
 *   friend, someone reading aloud — and a false positive texts a caregiver over a soap opera.
 * - This judges an answer given *to a question the app just asked*. "I fell" arriving here is a
 *   reply to "how are you feeling today", not a line of dialogue overheard from the next room.
 *   The prior is completely different, so the threshold is too.
 *
 * Two tiers, because not every worrying sentence is an emergency:
 *
 * - [Tier.CRITICAL] escalates on its own. These are the phrases the product spec names —
 *   falling, chest pain, not breathing, not being able to get up, calling for help.
 * - [Tier.ELEVATED] does not. It arms the silence watchdog in [ConversationEscalationMonitor]:
 *   "I feel dizzy" followed by conversation is a thing to mention to the caregiver later, and
 *   "I feel dizzy" followed by twelve seconds of nothing is someone who has stopped answering.
 *
 * Everything is first-person-anchored. `"i fell"` matches; `"she fell last winter"` does not,
 * and neither does a story about somebody else — which is most of what an elderly person
 * actually talks about on a companion call.
 */
object ConversationDistress {

    /** How urgently a cue should be acted on. */
    enum class Tier {
        /** Dispatch now, no confirmation, no second opinion. */
        CRITICAL,

        /** Not an alert by itself. Arms the silence watchdog. */
        ELEVATED,
    }

    /**
     * @param tier what to do about it.
     * @param emergencyType one of [EmergencyType]'s categories.
     * @param matched the substring that fired, quoted into the caregiver's SMS.
     */
    data class Cue(val tier: Tier, val emergencyType: String, val matched: String)

    /**
     * Guards against a match inside a denial or a hypothetical.
     *
     * "I didn't fall, I just sat down" and "I nearly fell" both contain "fall", and both mean
     * the opposite of the alert they would otherwise raise. Only the words immediately before a
     * match are considered — a negation five clauses back is not negating this one.
     */
    private val negationRegex = Regex(
        """\b(didn't|did\s+not|do\s+not|don't|never|nearly|almost|haven't|have\s+not|won't|wouldn't|if\s+i)\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Guards a symptom named without a subject — "chest pain", "trouble breathing" — against
     * being read as the caller's own when they are describing somebody else's.
     *
     * Subject pronouns only. An earlier version also listed "my husband", "my neighbour" and so
     * on, which suppressed *"My son isn't here, help me"* — a false negative, and the one kind
     * of mistake this file cannot afford. Naming a relative in the run-up to a symptom is not
     * evidence the symptom is theirs; "he" and "she" immediately before one is.
     */
    private val thirdPersonRegex = Regex("""\b(he|she|they|him|her|his|them|their)\b""", RegexOption.IGNORE_CASE)

    /** How far back from a match the guards look, in characters. */
    private const val LOOKBEHIND = 24

    /**
     * `I'm` / `I am` / `I feel`.
     *
     * Written once because writing it per pattern is how `i\s+'m` — which requires a space
     * before the apostrophe and therefore never matches "I'm" — got into two of them. The unit
     * tests caught it; the shared fragment stops it coming back.
     */
    private const val I_AM = """\bi(?:'m|\s+am|\s+feel)\s+(?:feeling\s+)?"""

    private data class Pattern(
        val regex: Regex,
        val tier: Tier,
        val emergencyType: String,
        /** True for patterns that name a symptom without saying whose it is. */
        val needsSubjectCheck: Boolean = false,
    )

    private fun critical(type: String, pattern: String, subjectCheck: Boolean = false) =
        Pattern(Regex(pattern, RegexOption.IGNORE_CASE), Tier.CRITICAL, type, subjectCheck)

    private fun elevated(type: String, pattern: String, subjectCheck: Boolean = false) =
        Pattern(Regex(pattern, RegexOption.IGNORE_CASE), Tier.ELEVATED, type, subjectCheck)

    /**
     * Ordered most severe first — [scan] returns the first hit, so a sentence containing both
     * "I fell" and "I feel a bit dizzy" is reported as the fall.
     */
    private val patterns: List<Pattern> = listOf(
        // --- falls -----------------------------------------------------------------
        critical(EmergencyType.FALL, """\bi\s+(?:have\s+)?fell\b"""),
        critical(EmergencyType.FALL, """\bi(?:'ve|\s+have)\s+fallen\b"""),
        critical(EmergencyType.FALL, """\bi\s+fall(?:en)?\s+down\b"""),
        // "can't get up" / "cannot stand up" / "can't get back up".
        critical(EmergencyType.FALL, """\bi\s+(?:can'?t|cannot|can\s+not)\s+(?:get|stand|move)\s+(?:back\s+)?up\b"""),
        critical(EmergencyType.FALL, """\bi(?:'m|\s+am)\s+(?:on|lying\s+on|down\s+on)\s+the\s+(?:floor|ground)\b"""),
        critical(EmergencyType.FALL, """\bi\s+slipped\s+and\b"""),

        // --- medical ---------------------------------------------------------------
        // "my chest hurts" says whose chest it is; a bare "chest pain" does not, so only the
        // second needs the subject check.
        critical(EmergencyType.MEDICAL, """\b(?:pain\s+in\s+my\s+chest|my\s+chest\s+(?:hurts|is\s+hurting|is\s+tight))\b"""),
        critical(EmergencyType.MEDICAL, """\bchest\s+pain\b""", subjectCheck = true),
        critical(EmergencyType.MEDICAL, """\bi\s+(?:can'?t|cannot|can\s+not)\s+breathe?\b"""),
        critical(EmergencyType.MEDICAL, """\b(?:trouble|difficulty|struggling)\s+breathing\b""", subjectCheck = true),
        critical(EmergencyType.MEDICAL, """\b(?:heart\s+attack|having\s+a\s+stroke)\b""", subjectCheck = true),
        critical(EmergencyType.MEDICAL, """\bi(?:'m|\s+am)\s+bleeding\s+(?:a\s+lot|badly|heavily)\b"""),
        critical(EmergencyType.MEDICAL, """\bi\s+(?:can'?t|cannot)\s+(?:see|speak|talk|move\s+my)\b"""),

        // --- direct pleas ------------------------------------------------------------
        // A bare "help" is left to EmergencyDetector.explicitPlea, which the monitor also runs.
        // What is added here is the conversational shape of it.
        //
        // None of these carry the subject check. A plea is addressed to the listener by
        // definition, and the cost of suppressing one because a pronoun happened to appear in
        // the clause before it is not a cost this feature can pay.
        critical(EmergencyType.HELP, """\bhelp\s+me\b"""),
        critical(EmergencyType.HELP, """\b(?:somebody|someone|please)\s+help\b"""),
        critical(EmergencyType.HELP, """\bcall\s+(?:an\s+)?(?:ambulance|doctor|911|999|112|108)\b""", subjectCheck = true),
        critical(EmergencyType.HELP, """\bi\s+need\s+help\s+(?:now|right\s+now|urgently)\b"""),

        // --- elevated: worth watching, not worth alerting on alone ---------------------
        elevated(EmergencyType.MEDICAL, """$I_AM(?:very\s+|really\s+|so\s+)?(?:dizzy|faint|light[\s-]?headed|nauseous|numb)\b"""),
        elevated(EmergencyType.MEDICAL, """$I_AM(?:very\s+|really\s+)?(?:weak|unwell|breathless)\b"""),
        elevated(EmergencyType.MEDICAL, """\b(?:short\s+of\s+breath|out\s+of\s+breath)\b""", subjectCheck = true),
        elevated(EmergencyType.MEDICAL, """\bi(?:'m|\s+am)\s+bleeding\b"""),
        elevated(EmergencyType.MEDICAL, """\bi\s+(?:hit|banged)\s+my\s+head\b"""),
        elevated(EmergencyType.MEDICAL, """\bi\s+(?:can'?t|cannot)\s+(?:stand|walk)\b"""),
        elevated(EmergencyType.HELP, """\bi(?:'m|\s+am)\s+(?:scared|frightened|in\s+pain)\b"""),
    )

    /**
     * @return the most severe cue in [text], or null when there is none.
     */
    fun scan(text: String): Cue? {
        if (text.isBlank()) return null
        // Critical patterns are all listed before elevated ones, so first-match is
        // severity-ordered without a second pass.
        for (pattern in patterns) {
            val match = pattern.regex.find(text) ?: continue
            val before = contextBefore(text, match.range.first)
            if (negationRegex.containsMatchIn(before)) continue
            if (pattern.needsSubjectCheck && thirdPersonRegex.containsMatchIn(before)) continue
            return Cue(pattern.tier, pattern.emergencyType, match.value.trim())
        }
        return null
    }

    /**
     * The words immediately before a match, which both guards read.
     *
     * Bounded twice. It stops at the previous sentence boundary, so *"She rang earlier. Chest
     * pain again"* is not suppressed by a "she" belonging to a different sentence; and it is
     * capped at [LOOKBEHIND] characters, so a qualifier at the start of a long sentence does not
     * reach across the whole thing.
     */
    private fun contextBefore(text: String, matchStart: Int): String {
        val prefix = text.substring(0, matchStart)
        val sentenceStart = prefix.indexOfLast { it == '.' || it == '?' || it == '!' } + 1
        val from = maxOf(sentenceStart, prefix.length - LOOKBEHIND)
        return prefix.substring(from)
    }

    // --- the agent's own structured signal ------------------------------------------

    /**
     * The marker the agent is instructed to emit — see `AgentPromptBuilder`.
     *
     * A marker rather than free prose because the alternative is asking a model to *describe*
     * an emergency and then reading the description, which puts a paraphrase between the person
     * and the alert. `[[SENTRI_ALERT:FALL]]` either appears or it does not.
     *
     * It is stripped from the transcript before display: it is addressed to this code, not to
     * the person, and showing it on screen would leak the mechanism.
     */
    private val agentSignalRegex = Regex(
        """\[\[\s*SENTRI_ALERT\s*:\s*(FALL|MEDICAL|HELP)\s*]]""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * @return the emergency category the agent signalled, or null.
     *
     * Note there is no "all clear" counterpart, deliberately. The agent can raise an alarm and
     * cannot lower one — see [ConversationEscalationMonitor]'s latch.
     */
    fun agentSignal(text: String): String? =
        agentSignalRegex.find(text)?.groupValues?.get(1)?.uppercase()?.let {
            when (it) {
                "FALL" -> EmergencyType.FALL
                "MEDICAL" -> EmergencyType.MEDICAL
                else -> EmergencyType.HELP
            }
        }

    /** [text] with any signal marker removed, for display. */
    fun stripAgentSignal(text: String): String =
        agentSignalRegex.replace(text, "").replace(Regex("\\s{2,}"), " ").trim()
}
