package com.example.sentriai.ui.screens

import com.example.sentriai.agora.Speaker
import com.example.sentriai.agora.TranscriptEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How transcript events become bubbles.
 *
 * The invariant every test here also checks — ids are distinct — is the one whose violation
 * crashed the call screen: `LazyColumn` throws `IllegalArgumentException` from measure when two
 * items share a key, which takes the process down mid-call.
 */
class CallTranscriptTest {

    private var nextId = 0L
    private var lines = emptyList<CallLine>()

    private fun send(
        text: String,
        speaker: Speaker = Speaker.AGENT,
        turnId: Long = 1L,
        isFinal: Boolean = false,
    ) {
        lines = CallTranscript.append(lines, TranscriptEvent(speaker, text, isFinal, turnId), nextId++)
        val ids = lines.map { it.id }
        assertEquals("duplicate line id: $ids", ids.size, ids.distinct().size)
    }

    /**
     * The greeting from a real call, message for message. It used to render as five bubbles, each
     * a prefix of the next.
     */
    @Test
    fun `an accumulating turn grows one bubble`() {
        send("Hello,")
        send("Hello, aa.")
        send("Hello, aa. It's Sentri.")
        send("Hello, aa. It's Sentri. How's your day going?")
        send("Hello, aa. It's Sentri. How's your day going?", isFinal = true)

        assertEquals(1, lines.size)
        assertEquals("Hello, aa. It's Sentri. How's your day going?", lines.single().text)
        assertTrue(lines.single().isFinal)
    }

    /** The bubble keeps its identity as it grows, so Compose does not re-animate it per revision. */
    @Test
    fun `a growing bubble keeps its id`() {
        send("I")
        val first = lines.single().id
        send("I am fine", isFinal = true)

        assertEquals(first, lines.single().id)
    }

    /**
     * The completed text arriving again with `turn_status` flipped from 0 to 1 — which is what
     * every turn ends with. Two bubbles here would mean the agent appeared to say it twice.
     */
    @Test
    fun `a repeated final does not add a second bubble`() {
        send("Good morning.", isFinal = true)
        send("Good morning.", isFinal = true)

        assertEquals(1, lines.size)
    }

    @Test
    fun `each turn gets its own bubble`() {
        send("Hello.", turnId = 1, isFinal = true)
        send("I am fine.", speaker = Speaker.PERSON, turnId = 2, isFinal = true)
        send("Glad to hear it.", turnId = 3, isFinal = true)

        assertEquals(3, lines.size)
        assertEquals(listOf(Speaker.AGENT, Speaker.PERSON, Speaker.AGENT), lines.map { it.speaker })
    }

    /** Both speak in turn 2 of the conversation; they are still two separate bubbles. */
    @Test
    fun `the two speakers do not share a turn's bubble`() {
        send("How are you?", turnId = 2, isFinal = true)
        send("Not too bad.", speaker = Speaker.PERSON, turnId = 2, isFinal = true)

        assertEquals(2, lines.size)
    }

    /**
     * Text under a turn already finished that does *not* continue it. A second bubble is right
     * here — and it is the case that produced the duplicate key, because the old code identified
     * lines by (speaker, turnId, text length).
     */
    @Test
    fun `unrelated text after a final turn starts a new bubble`() {
        send("Ready when you are.", turnId = 1, isFinal = true)
        send("Are you still there?", turnId = 1, isFinal = true)

        assertEquals(2, lines.size)
        assertEquals(2, lines.map { it.id }.distinct().size)
    }

    /**
     * Same speaker, same turn, same length, different text — byte-identical under the old key.
     * This input crashed the app.
     */
    @Test
    fun `same-length text in one turn does not collide`() {
        send("Take the white tablet now", turnId = 7, isFinal = true)
        send("Take the small tablet now", turnId = 7, isFinal = true)

        assertEquals(2, lines.map { it.id }.distinct().size)
    }

    @Test
    fun `the alert marker is never shown`() {
        send("I will get help. [[SENTRI_ALERT:FALL]]", isFinal = true)

        assertEquals("I will get help.", lines.single().text)
    }

    @Test
    fun `a blank event changes nothing`() {
        send("Hello.", isFinal = true)
        val before = lines
        send("   ", turnId = 2)

        assertEquals(before, lines)
    }

    /** A marker-only utterance is addressed to this code alone and leaves no bubble. */
    @Test
    fun `a marker-only event leaves no bubble`() {
        send("[[SENTRI_ALERT:HELP]]", isFinal = true)

        assertTrue(lines.isEmpty())
    }

    @Test
    fun `the oldest bubbles are dropped on a long call`() {
        repeat(CallTranscript.MAX_VISIBLE_LINES + 10) { turn ->
            send("Line $turn", turnId = turn.toLong(), isFinal = true)
        }

        assertEquals(CallTranscript.MAX_VISIBLE_LINES, lines.size)
        assertEquals("Line 69", lines.last().text)
    }
}
