package com.example.sentriai.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the conversational distress lexicon.
 *
 * This is the one part of the Agora path that decides whether help gets called, and it is pure
 * Kotlin, so unlike the classifier it can be pinned down exactly. Both directions matter: the
 * phrases the product promises to catch, and the ordinary conversation that must not raise an
 * alarm — most of what an elderly person says on a companion call is a story about somebody
 * else, and a lexicon that fires on those is worse than none.
 */
class ConversationDistressTest {

    // --- the phrases the spec names ---------------------------------------------

    @Test
    fun `falling escalates immediately`() {
        for (phrase in listOf(
            "I fell",
            "I fell in the kitchen just now",
            "I've fallen and I need you",
            "I can't get up",
            "I cannot get back up",
            "I'm on the floor",
        )) {
            val cue = ConversationDistress.scan(phrase)
            assertEquals("\"$phrase\" should be critical", ConversationDistress.Tier.CRITICAL, cue?.tier)
            assertEquals("\"$phrase\" should be a fall", EmergencyType.FALL, cue?.emergencyType)
        }
    }

    @Test
    fun `medical crises escalate immediately`() {
        for (phrase in listOf(
            "I have chest pain",
            "my chest hurts",
            "I can't breathe",
            "I cannot breathe properly",
            "I'm having trouble breathing",
            "I think it's a heart attack",
        )) {
            val cue = ConversationDistress.scan(phrase)
            assertEquals("\"$phrase\" should be critical", ConversationDistress.Tier.CRITICAL, cue?.tier)
            assertEquals("\"$phrase\" should be medical", EmergencyType.MEDICAL, cue?.emergencyType)
        }
    }

    @Test
    fun `calls for help escalate immediately`() {
        for (phrase in listOf("Help me", "Somebody help", "Please help", "Call an ambulance")) {
            val cue = ConversationDistress.scan(phrase)
            assertEquals("\"$phrase\" should be critical", ConversationDistress.Tier.CRITICAL, cue?.tier)
            assertEquals(EmergencyType.HELP, cue?.emergencyType)
        }
    }

    // --- the tier that only arms the silence watchdog ----------------------------

    @Test
    fun `feeling unwell is elevated but not an alert on its own`() {
        for (phrase in listOf(
            "I feel very dizzy",
            "I'm light headed",
            "I feel weak today",
            "I'm short of breath",
            "I hit my head",
        )) {
            val cue = ConversationDistress.scan(phrase)
            assertEquals("\"$phrase\" should be elevated", ConversationDistress.Tier.ELEVATED, cue?.tier)
        }
    }

    @Test
    fun `bleeding a lot outranks bleeding`() {
        assertEquals(
            ConversationDistress.Tier.ELEVATED,
            ConversationDistress.scan("I'm bleeding a little from the scratch")?.tier,
        )
        assertEquals(
            ConversationDistress.Tier.CRITICAL,
            ConversationDistress.scan("I'm bleeding badly")?.tier,
        )
    }

    @Test
    fun `the most severe cue in a sentence is the one reported`() {
        val cue = ConversationDistress.scan("I felt dizzy and then I fell")
        assertEquals(ConversationDistress.Tier.CRITICAL, cue?.tier)
        assertEquals(EmergencyType.FALL, cue?.emergencyType)
    }

    // --- what must stay quiet -----------------------------------------------------

    @Test
    fun `ordinary companion conversation raises nothing`() {
        for (phrase in listOf(
            "It's a lovely day, the sun is out",
            "My granddaughter is coming on Sunday",
            "I watched the cricket last night",
            "I had my breakfast at eight and took the tablet after",
            "Could you tell me a story",
            "That was very helpful, thank you",
            "The doctor said my blood pressure is fine now",
        )) {
            assertNull("\"$phrase\" must not raise a cue", ConversationDistress.scan(phrase))
        }
    }

    @Test
    fun `a story about somebody else is not the caller's emergency`() {
        // First-person anchoring is what does this — the patterns require "I", not just the verb.
        assertNull(ConversationDistress.scan("My neighbour fell last winter and broke her hip"))
        assertNull(ConversationDistress.scan("He had chest pain and they took him in"))
        assertNull(ConversationDistress.scan("She could not get up for an hour, poor thing"))
    }

    @Test
    fun `denials and near misses do not fire`() {
        assertNull(ConversationDistress.scan("I didn't fall, I just sat down for a minute"))
        assertNull(ConversationDistress.scan("I nearly fell but I caught the rail"))
        assertNull(ConversationDistress.scan("I almost fell over the cat"))
        assertNull(ConversationDistress.scan("I have not fallen since the summer"))
    }

    @Test
    fun `contractions are matched`() {
        // Regression: the patterns used to spell this `i\s+'m`, which requires a space before
        // the apostrophe and so never matched the way anybody actually speaks. A missed cue is
        // the failure direction that matters, and nothing else in the system catches it.
        assertNotNull(ConversationDistress.scan("I'm light headed"))
        assertNotNull(ConversationDistress.scan("I'm feeling weak"))
        assertNotNull(ConversationDistress.scan("I've fallen"))
        assertNotNull(ConversationDistress.scan("I can't breathe"))
        assertNotNull(ConversationDistress.scan("I cant get up"))
    }

    @Test
    fun `a pronoun in an earlier sentence does not suppress the caller's own symptom`() {
        // The subject check is scoped to the current sentence for exactly this case.
        val cue = ConversationDistress.scan("She rang me earlier. Chest pain again, quite bad")
        assertEquals(ConversationDistress.Tier.CRITICAL, cue?.tier)
    }

    @Test
    fun `naming a relative does not suppress a plea`() {
        // Regression: guarding on "my son" / "my neighbour" suppressed this. A plea is addressed
        // to the listener whoever else is mentioned around it.
        assertEquals(
            ConversationDistress.Tier.CRITICAL,
            ConversationDistress.scan("My son isn't here, help me")?.tier,
        )
        assertEquals(
            ConversationDistress.Tier.CRITICAL,
            ConversationDistress.scan("My daughter came yesterday and now I fell")?.tier,
        )
    }

    @Test
    fun `blank input raises nothing`() {
        assertNull(ConversationDistress.scan(""))
        assertNull(ConversationDistress.scan("   "))
    }

    // --- the agent's structured signal ----------------------------------------------

    @Test
    fun `agent signal is parsed and mapped`() {
        assertEquals(
            EmergencyType.FALL,
            ConversationDistress.agentSignal("I'm getting help now. [[SENTRI_ALERT:FALL]]"),
        )
        assertEquals(
            EmergencyType.MEDICAL,
            ConversationDistress.agentSignal("[[SENTRI_ALERT:MEDICAL]]"),
        )
        assertEquals(
            EmergencyType.HELP,
            ConversationDistress.agentSignal("Staying with you. [[sentri_alert: help ]]"),
        )
    }

    @Test
    fun `an agent reply with no signal raises nothing`() {
        assertNull(ConversationDistress.agentSignal("How are you feeling today?"))
        // A malformed marker is not a signal — the on-device scan is the backstop for that case.
        assertNull(ConversationDistress.agentSignal("[[SENTRI_ALERT:MAYBE]]"))
    }

    @Test
    fun `the marker never reaches the screen`() {
        val stripped = ConversationDistress.stripAgentSignal(
            "I'm getting help for you now. [[SENTRI_ALERT:FALL]]",
        )
        assertEquals("I'm getting help for you now.", stripped)
        assertNotNull(stripped)
    }
}
