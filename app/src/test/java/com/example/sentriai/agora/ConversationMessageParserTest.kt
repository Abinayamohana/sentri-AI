package com.example.sentriai.agora

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire-format rules in [ConversationMessageParser].
 *
 * Every payload here is a real message captured from a live Conversational AI session, trimmed of
 * the `words` and `metadata` blocks the parser does not read. That matters: the format Agora
 * actually sends differs from its documentation in the two ways these tests cover — there is no
 * `is_final`, and `text` accumulates.
 *
 * The chunk-reassembly half of the parser is not covered, because it needs `android.util.Base64`.
 */
class ConversationMessageParserTest {

    private val parser = ConversationMessageParser()

    private fun event(json: String) = parser.eventFrom(JSONObject(json))

    @Test
    fun `agent transcription is attributed to the agent`() {
        val event = event(
            """{"object":"assistant.transcription","text":"Hello.","turn_id":1,
               "turn_status":1,"stream_id":0}""",
        )

        assertEquals(Speaker.AGENT, event?.speaker)
        assertEquals(1L, event?.turnId)
    }

    @Test
    fun `person transcription is attributed to the person`() {
        val event = event(
            """{"object":"user.transcription","text":"I am fine.","turn_id":2,
               "turn_status":1,"stream_id":452600}""",
        )

        assertEquals(Speaker.PERSON, event?.speaker)
    }

    /**
     * The bug this whole file exists for. `turn_status` 0 means the turn is still being spoken;
     * read as final, every accumulating update becomes a finished utterance and the screen fills
     * with prefixes of one sentence.
     */
    @Test
    fun `turn_status marks completion, and zero is not final`() {
        val speaking = event(
            """{"object":"assistant.transcription","text":"Hello,","turn_id":1,"turn_status":0}""",
        )
        val ended = event(
            """{"object":"assistant.transcription","text":"Hello.","turn_id":1,"turn_status":1}""",
        )

        assertFalse(speaking!!.isFinal)
        assertTrue(ended!!.isFinal)
    }

    /** 2 is "the person interrupted". The text will not be revised again, so it is final. */
    @Test
    fun `an interrupted turn is final`() {
        val event = event(
            """{"object":"assistant.transcription","text":"As I was say","turn_id":3,
               "turn_status":2}""",
        )

        assertTrue(event!!.isFinal)
    }

    @Test
    fun `is_final is honoured when there is no turn_status`() {
        val interim = event("""{"text":"I think I","stream_id":9,"is_final":false,"turn_id":4}""")
        val final = event("""{"text":"I think I fell","stream_id":9,"final":true,"turn_id":4}""")

        assertFalse(interim!!.isFinal)
        assertTrue(final!!.isFinal)
    }

    /** With no completion flag at all the message is complete; dropping it would lose speech. */
    @Test
    fun `a message with no completion flag is final`() {
        assertTrue(event("""{"text":"Help me","stream_id":9,"turn_id":5}""")!!.isFinal)
    }

    /**
     * `message.state` (idle/listening/thinking/speaking/silent) shares this stream and carries no
     * text. It must not become an utterance — and must not be read as the agent just because its
     * `stream_id` is absent.
     */
    @Test
    fun `agent state messages are not transcripts`() {
        assertNull(
            event("""{"object":"message.state","state":"listening","turn_id":2,"ts_ms":1785742895206}"""),
        )
    }

    @Test
    fun `speaker falls back to stream_id when there is no object field`() {
        assertEquals(Speaker.AGENT, event("""{"text":"Hello","stream_id":0}""")?.speaker)
        assertEquals(Speaker.PERSON, event("""{"text":"Hello","stream_id":452600}""")?.speaker)
    }

    @Test
    fun `empty text is dropped`() {
        assertNull(event("""{"object":"assistant.transcription","text":"   ","turn_id":1}"""))
    }

    // --- spacing repair ---------------------------------------------------------------

    /**
     * Verbatim from a live greeting. `text` is assembled from TTS word timings, which carry no
     * leading whitespace, so sentences arrive glued together.
     */
    @Test
    fun `glued sentences get their spaces back`() {
        val event = event(
            """{"object":"assistant.transcription",
               "text":"Hello,aa.It's Sentri.I've got time if you'd like to talk.How's your day going?",
               "turn_id":1,"turn_status":1,"stream_id":0}""",
        )

        assertEquals(
            "Hello, aa. It's Sentri. I've got time if you'd like to talk. How's your day going?",
            event?.text,
        )
    }

    /** A glued full stop hides a distress phrase from patterns that match on word boundaries. */
    @Test
    fun `repair exposes a phrase that was glued to the previous sentence`() {
        val event = event("""{"text":"I slipped.Help me please","stream_id":9,"turn_id":1}""")

        assertEquals("I slipped. Help me please", event?.text)
    }

    /**
     * The reason the repair requires two word characters before the punctuation. Medicine times
     * are spoken on these calls, and `"3 p. m."` would be worse than the glued original.
     */
    @Test
    fun `clock times and decimals are left alone`() {
        assertEquals(
            "Take it at 8 a.m.and again at 2.5 hours later",
            event("""{"text":"Take it at 8 a.m.and again at 2.5 hours later","stream_id":0}""")?.text,
        )
    }

    @Test
    fun `text that is already spaced is unchanged`() {
        assertEquals(
            "Good morning. How are you today?",
            event("""{"text":"Good morning. How are you today?","stream_id":0}""")?.text,
        )
    }
}
