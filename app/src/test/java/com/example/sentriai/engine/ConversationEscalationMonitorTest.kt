package com.example.sentriai.engine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the escalation rules that a live call depends on: the silence watchdog, and the latch
 * that makes an emergency decision final.
 *
 * Runs on `runTest`'s virtual clock, so the twelve-second watchdog is exercised without the test
 * taking twelve seconds.
 *
 * The classifier is stubbed. Its real implementation loads a 1B model and belongs in
 * `EmergencyDetectorDeviceTest`; what matters here is that a decision coming back from it is
 * routed through the same latch as everything else.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationEscalationMonitorTest {

    private class Recorder {
        val decisions = mutableListOf<EmergencyDecision>()
        val transcripts = mutableListOf<String>()

        fun handler(): suspend (EmergencyDecision, String) -> Unit = { decision, transcript ->
            decisions += decision
            transcripts += transcript
        }
    }

    @Test
    fun `a critical phrase escalates without waiting for anything`() = runTest {
        val recorder = Recorder()
        val monitor = ConversationEscalationMonitor(
            scope = this,
            onEscalate = recorder.handler(),
            classifierProbe = { null },
        )

        monitor.onUserSpeech("I fell and I can't get up")
        runCurrent()

        assertEquals(1, recorder.decisions.size)
        assertEquals(EmergencyType.FALL, recorder.decisions.single().emergencyType)
        assertEquals(
            EmergencyDecision.Source.CONVERSATION,
            recorder.decisions.single().source,
        )
        assertTrue(monitor.hasEscalated)
        monitor.stop()
    }

    @Test
    fun `silence after an elevated cue escalates`() = runTest {
        val recorder = Recorder()
        val monitor = ConversationEscalationMonitor(
            scope = this,
            onEscalate = recorder.handler(),
            classifierProbe = { null },
        )

        monitor.onUserSpeech("I feel very dizzy")
        runCurrent()
        // Not yet — an elevated cue alone is not an alert.
        assertTrue(recorder.decisions.isEmpty())

        advanceTimeBy(ConversationEscalationMonitor.SILENCE_AFTER_DISTRESS_MS + 100)
        runCurrent()

        assertEquals(1, recorder.decisions.size)
        assertEquals(
            EmergencyDecision.Source.SILENCE_AFTER_DISTRESS,
            recorder.decisions.single().source,
        )
        monitor.stop()
    }

    @Test
    fun `answering after an elevated cue clears the watchdog`() = runTest {
        val recorder = Recorder()
        val monitor = ConversationEscalationMonitor(
            scope = this,
            onEscalate = recorder.handler(),
            classifierProbe = { null },
        )

        monitor.onUserSpeech("I feel a bit dizzy")
        runCurrent()
        advanceTimeBy(4_000)
        monitor.onUserSpeech("It has passed now, I am alright thank you")
        runCurrent()

        advanceTimeBy(ConversationEscalationMonitor.SILENCE_AFTER_DISTRESS_MS * 2)
        runCurrent()

        assertTrue("answering should have cancelled the watchdog", recorder.decisions.isEmpty())
        assertFalse(monitor.hasEscalated)
        monitor.stop()
    }

    @Test
    fun `the agent talking does not count as the person answering`() = runTest {
        val recorder = Recorder()
        val monitor = ConversationEscalationMonitor(
            scope = this,
            onEscalate = recorder.handler(),
            classifierProbe = { null },
        )

        monitor.onUserSpeech("I feel faint")
        runCurrent()
        // The agent keeps checking on them. The person still has not spoken.
        advanceTimeBy(3_000)
        monitor.onAgentSpeech("Are you alright? Can you hear me?")
        advanceTimeBy(3_000)
        monitor.onAgentSpeech("Say something if you can.")
        runCurrent()

        advanceTimeBy(ConversationEscalationMonitor.SILENCE_AFTER_DISTRESS_MS)
        runCurrent()

        assertEquals(
            EmergencyDecision.Source.SILENCE_AFTER_DISTRESS,
            recorder.decisions.singleOrNull()?.source,
        )
        monitor.stop()
    }

    @Test
    fun `the agent's structured signal escalates`() = runTest {
        val recorder = Recorder()
        val monitor = ConversationEscalationMonitor(
            scope = this,
            onEscalate = recorder.handler(),
            classifierProbe = { null },
        )

        // Phrased so the on-device lexicon does not catch it — this proves the agent's own
        // signal is an independent route in, not a duplicate of the pattern scan.
        monitor.onUserSpeech("something is wrong with me, everything has gone strange")
        monitor.onAgentSpeech("I'm getting help for you now. [[SENTRI_ALERT:MEDICAL]]")
        runCurrent()

        assertEquals(EmergencyDecision.Source.AGENT_SIGNAL, recorder.decisions.singleOrNull()?.source)
        assertEquals(EmergencyType.MEDICAL, recorder.decisions.single().emergencyType)
        // The caregiver reads the person's words, not the agent's.
        assertTrue(recorder.decisions.single().triggerPhrase.contains("something is wrong"))
        monitor.stop()
    }

    @Test
    fun `the classifier is another route into the same latch`() = runTest {
        val recorder = Recorder()
        val monitor = ConversationEscalationMonitor(
            scope = this,
            onEscalate = recorder.handler(),
            classifierProbe = {
                EmergencyDecision(
                    emergencyType = EmergencyType.MEDICAL,
                    triggerPhrase = it,
                    confidence = 0.9f,
                    source = EmergencyDecision.Source.CLASSIFIER,
                )
            },
        )

        monitor.onUserSpeech("everything has gone dark and strange all of a sudden")
        runCurrent()

        assertEquals(1, recorder.decisions.size)
        assertEquals(EmergencyDecision.Source.CONVERSATION, recorder.decisions.single().source)
        monitor.stop()
    }

    @Test
    fun `nothing after the first escalation can add a second alert or take one back`() = runTest {
        val recorder = Recorder()
        val monitor = ConversationEscalationMonitor(
            scope = this,
            onEscalate = recorder.handler(),
            classifierProbe = { null },
        )

        monitor.onUserSpeech("I fell")
        runCurrent()
        assertEquals(1, recorder.decisions.size)

        // Everything a later turn of conversation could throw at it: the person walking it back,
        // the agent smoothing it over, another critical phrase, a manual press, more silence.
        monitor.onUserSpeech("Oh never mind, I'm fine really, it was nothing")
        monitor.onAgentSpeech("That's good to hear, everything is alright then.")
        monitor.onUserSpeech("Actually my chest hurts too")
        monitor.onManualTrigger("SOS pressed")
        runCurrent()
        advanceTimeBy(ConversationEscalationMonitor.SILENCE_AFTER_DISTRESS_MS * 3)
        runCurrent()

        assertEquals("the alert must fire exactly once", 1, recorder.decisions.size)
        assertEquals(EmergencyType.FALL, recorder.decisions.single().emergencyType)
        assertTrue("the latch must never clear", monitor.hasEscalated)
        monitor.stop()
    }

    @Test
    fun `the transcript handed to the pipeline holds both sides of the call`() = runTest {
        val recorder = Recorder()
        val monitor = ConversationEscalationMonitor(
            scope = this,
            onEscalate = recorder.handler(),
            classifierProbe = { null },
        )

        monitor.onUserSpeech("Good morning")
        monitor.onAgentSpeech("Good morning. How are you feeling today?")
        monitor.onUserSpeech("I fell in the bathroom")
        runCurrent()

        val transcript = recorder.transcripts.single()
        assertTrue(transcript.contains("Person: Good morning"))
        assertTrue(transcript.contains("Companion: Good morning. How are you feeling today?"))
        assertTrue(transcript.contains("Person: I fell in the bathroom"))
        monitor.stop()
    }

    @Test
    fun `short filler words never reach the classifier`() = runTest {
        var probes = 0
        val monitor = ConversationEscalationMonitor(
            scope = this,
            onEscalate = { _, _ -> },
            classifierProbe = { probes++; null },
        )

        for (filler in listOf("yes", "okay", "mm", "no thank you")) {
            monitor.onUserSpeech(filler)
        }
        runCurrent()

        assertEquals("only the four-word reply is worth an inference", 1, probes)
        monitor.stop()
    }

    @Test
    fun `blank speech is ignored entirely`() = runTest {
        val recorder = Recorder()
        val monitor = ConversationEscalationMonitor(
            scope = this,
            onEscalate = recorder.handler(),
            classifierProbe = { null },
        )

        monitor.onUserSpeech("   ")
        monitor.onAgentSpeech("")
        runCurrent()

        assertTrue(recorder.decisions.isEmpty())
        assertEquals("", monitor.transcriptSoFar())
        assertNull(recorder.transcripts.firstOrNull())
        monitor.stop()
    }
}
