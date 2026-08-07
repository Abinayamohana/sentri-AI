package com.example.sentriai.ui.screens

import com.example.sentriai.agora.Speaker
import com.example.sentriai.agora.TranscriptEvent
import com.example.sentriai.engine.ConversationDistress

/**
 * One line in the on-screen conversation.
 *
 * @param id unique for the life of the call and stable across the revisions of one bubble. It is
 *   what the transcript list is keyed on: (speaker, turnId) is *not* unique, because a turn that
 *   has ended can be followed by more text under the same `turn_id`, and a duplicate key crashes
 *   LazyColumn during measure.
 */
data class CallLine(
    val id: Long,
    val speaker: Speaker,
    val text: String,
    val turnId: Long,
    val isFinal: Boolean,
)

/**
 * Folds [TranscriptEvent]s into the list of bubbles the call screen shows.
 *
 * Separate from `CompanionCallViewModel` only so it can be tested: it is the whole reason the
 * screen either reads as a conversation or as a staircase, and the rules it encodes come from the
 * wire format rather than from anything a reader would guess. See [ConversationMessageParser]
 * for that format.
 */
internal object CallTranscript {

    /** Beyond this the oldest bubbles are dropped; a long call must not grow without bound. */
    const val MAX_VISIBLE_LINES = 60

    /**
     * @param nextId id for a line if one is started. Ignored when the event updates a line that
     *   is already there, which is the common case — the existing id is kept so Compose treats
     *   the bubble as the same item and does not animate it in again.
     * @return the new list, or [current] unchanged when the event carries nothing to show.
     */
    fun append(current: List<CallLine>, event: TranscriptEvent, nextId: Long): List<CallLine> {
        // The alert marker is addressed to this app, not to the person. Never show it.
        val text = if (event.speaker == Speaker.AGENT) {
            ConversationDistress.stripAgentSignal(event.text)
        } else {
            event.text
        }
        if (text.isBlank()) return current

        // Agora resends the whole turn on every update, so an event normally replaces the bubble
        // for its turn rather than adding one. Replacing continues after the turn was marked
        // final, because the completed text arrives once more with `turn_status` flipped, and that
        // repeat is the same sentence rather than a second one.
        val existing = current.indexOfLast { it.speaker == event.speaker && it.turnId == event.turnId }
        val updates = existing >= 0 &&
            (!current[existing].isFinal || text.startsWith(current[existing].text))

        return if (updates) {
            current.toMutableList().apply {
                this[existing] = current[existing].copy(text = text, isFinal = event.isFinal)
            }
        } else {
            val line = CallLine(
                id = nextId,
                speaker = event.speaker,
                text = text,
                turnId = event.turnId,
                isFinal = event.isFinal,
            )
            (current + line).takeLast(MAX_VISIBLE_LINES)
        }
    }
}
