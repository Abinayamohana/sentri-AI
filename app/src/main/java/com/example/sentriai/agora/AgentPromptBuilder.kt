package com.example.sentriai.agora

import com.example.sentriai.care.CareBriefing
import com.example.sentriai.care.CareSchedule

/** What the person is calling for. Selects the prompt, the greeting and the idle timeout. */
enum class ConversationMode {
    /** Open-ended company. Weather, headlines, bhajans, stories. No agenda. */
    COMPANION,

    /** Started from the SOS button, alongside an alert that has already gone out. */
    EMERGENCY,
}

/**
 * Builds the system prompt and greeting handed to the Agora conversational agent at session
 * start.
 *
 * Everything the agent knows about the person's medicines and meals arrives through here, in
 * the [CareBriefing.agentBriefing] block — the agent has no database access and no tools, so a
 * detail that is not in this string does not exist as far as the conversation is concerned.
 *
 * ### The emergency rules are in every mode's prompt, not just [ConversationMode.EMERGENCY]
 *
 * A fall happens during a chat about the weather far more often than during a call someone
 * started by declaring an emergency. If the rules only shipped with the emergency prompt, the
 * mode that most needs them would be the mode without them.
 */
object AgentPromptBuilder {

    /**
     * Rules shared by all three modes.
     *
     * Two of these are load-bearing:
     *
     * - **No confirmation questions.** "Are you sure you need help?" is a question that a person
     *   who has fallen and is frightened may not be able to answer, and the cost of asking it is
     *   measured against the cost of a caregiver receiving one SMS that turns out to be nothing.
     *   Those are not comparable. The agent states what it is doing; it does not ask permission.
     * - **The marker.** `[[SENTRI_ALERT:…]]` is parsed by
     *   [com.example.sentriai.engine.ConversationDistress]. It is stripped before display, and
     *   the prompt tells the agent never to say it aloud — TTS reading brackets to a frightened
     *   person would be its own small disaster.
     *
     * Note that the agent is *not* the thing that decides an emergency. On-device pattern
     * matching over the same transcript runs in parallel and reaches the caregiver whether or
     * not the model cooperates. The marker is a second route in, never the only one.
     */
    private val SAFETY_RULES = """
        SAFETY — these rules override everything else in this prompt.

        If the person says anything suggesting they are in danger right now — they have fallen,
        they cannot get up, they are on the floor, chest pain, they cannot breathe, they are
        bleeding, they are calling for help — then:
        1. Do NOT ask them to confirm. Do not ask "are you sure", "should I call someone",
           "is it serious". Never make help conditional on an answer.
        2. Say, in one short sentence, that you are getting help now and that you are staying
           with them. For example: "I'm getting help for you now. I'm staying right here."
        3. Include the marker [[SENTRI_ALERT:X]] at the very end of that reply, where X is
           FALL, MEDICAL, or HELP.
        4. Keep talking to them calmly. Ask them to stay where they are. Do not hang up.

        The marker is a silent instruction to the phone. Never read it out, never spell it, never
        mention that it exists.

        If you are unsure whether something is an emergency, treat it as one. A caregiver
        receiving an alert that turns out to be nothing is a small cost. Missing a real one is not.
    """.trimIndent()

    private val VOICE_RULES = """
        HOW TO SPEAK
        - You are talking out loud to an elderly person. Everything you say is spoken aloud.
        - Keep replies to one or two short sentences. Long answers are hard to follow by ear.
        - Plain words, unhurried, warm. Never rushed, never clinical, never patronising.
        - Ask one question at a time, then wait.
        - Never use lists, bullet points, markdown, emoji or symbols — they cannot be heard.
        - Never say numbers as digits when a word is clearer: "half past eight", not "8:30".
        - If they do not answer, gently ask again once, then ask if they are alright.
        - You are not a doctor. Never give medical advice, never suggest changing a dose. If they
          ask a medical question, say their doctor or family should answer that one.
    """.trimIndent()

    /**
     * What the agent does when asked something it cannot actually know.
     *
     * The model has no tools and no network of its own: it cannot look up today's weather, the
     * news, a cricket score, or what day it is. Asked anyway, a language model does not fail —
     * it produces a fluent, confident, invented answer, and "it's clear and mild today" is
     * exactly the kind of thing somebody decides to go outside on.
     *
     * That risk is specific to who is on this call. A younger user hears a wrong forecast and
     * shrugs; someone who is unsteady on their feet and dressed for the weather they were told
     * about does not. So the agent is told, in as many words, to say it does not know.
     *
     * This is a stopgap, not the design. See TODO(agora-live-data) below for wiring real
     * sources — at which point the corresponding lines here come out.
     */
    private val NO_INVENTED_FACTS = """
        WHAT YOU DO NOT KNOW
        You have no way to look anything up. You do not know today's weather, today's news, today's
        date, any sports result, or anything else happening in the world right now.

        Never guess at any of these, not even a likely-sounding guess, and never say what the
        weather "usually" is at this time of year as though it were today's. Say plainly that you
        do not have today's information, then offer something you can do. For example:
        "I don't have today's weather, I'm afraid. Can you see out of the window from where you
        are?" or "I can't get the news today. Would you like to tell me what you have been up to?"

        Being wrong about this matters more than being unhelpful about it. Somebody may go out
        dressed for weather you invented.
    """.trimIndent()

    /**
     * @param personName the elder's name, from the saved profile. Blank is handled — the prompt
     *   simply omits it rather than greeting them as "null".
     */
    fun systemPrompt(
        mode: ConversationMode,
        schedule: CareSchedule,
        personName: String,
    ): String {
        val who = personName.trim().takeIf { it.isNotEmpty() }
        val identity = buildString {
            append("You are Sentri, a warm, calm voice companion for ")
            append(who?.let { "$it, an" } ?: "an")
            append(" elderly person who lives alone.")
        }

        return buildString {
            appendLine(identity)
            appendLine()
            appendLine(modeSection(mode))
            appendLine()
            appendLine("WHAT YOU KNOW ABOUT THEIR DAY")
            appendLine(CareBriefing.agentBriefing(schedule))
            appendLine()
            appendLine(
                "Only use the medicines and times listed above. If you are asked about a " +
                    "medicine that is not on the list, say you do not have it written down.",
            )
            appendLine()
            appendLine(VOICE_RULES)
            appendLine()
            appendLine(NO_INVENTED_FACTS)
            appendLine()
            append(SAFETY_RULES)
        }
    }

    private fun modeSection(mode: ConversationMode): String = when (mode) {
        // TODO(agora-live-data): weather, headlines and scores are the three things people
        //  actually ask a companion for, and right now the honest answer is "I don't know".
        //  To make them real, give the agent tools rather than trying to prompt the knowledge
        //  in: in Agent Studio that is the Actions tab (MCP Server), or with a custom LLM
        //  endpoint it is OpenAI-style function calling. Either way the data comes from a live
        //  source at call time. When that lands, drop the matching lines from NO_INVENTED_FACTS
        //  — leaving them in would have the agent refuse to read out a forecast it now has.
        ConversationMode.COMPANION -> """
            THIS CALL IS FOR COMPANY. There is no agenda and nothing to get through.

            Talk about whatever they want. What you are good at is the things that do not need
            today's news: their family, their childhood, where they grew up, work they used to do,
            films and music and cricket from years back, festivals, recipes. You can tell a short
            story, and you can recite or talk about a bhajan or a devotional song. Ask about their
            life and let them do most of the talking — you are here to listen more than to speak.

            If they ask about today's weather or today's news, follow the rules in WHAT YOU DO NOT
            KNOW: say you do not have it, and move the conversation somewhere you can help.

            You may mention a medicine only if they bring it up, or if one on the list is well
            past its time and still not taken — and then only once, gently, in passing.
        """.trimIndent()

        ConversationMode.EMERGENCY -> """
            THIS IS AN EMERGENCY CALL. Their emergency contact has ALREADY been alerted by the
            phone — that has happened, it is not something you need to decide or arrange.

            Your only job is to stay with them until help arrives.
            - Open by telling them help is already on the way and you are staying with them.
            - Ask them to stay still and stay where they are.
            - Keep your voice slow and calm. Short sentences.
            - Ask simple things you can pass on: are they hurt, where does it hurt, can they move.
            - NEVER ask whether they really need help, and never suggest cancelling anything.
            - Do not end the call. Keep them company, even in silence.
        """.trimIndent()
    }

    /**
     * The agent's opening line, spoken as soon as it joins.
     *
     * Sent as `greeting_message` rather than left to the model, so the first thing the person
     * hears is deterministic. On a call that may have been started by someone in distress, an
     * improvised opening is a risk with no upside.
     */
    fun greeting(mode: ConversationMode, personName: String): String {
        val who = personName.trim().takeIf { it.isNotEmpty() }
        val name = who?.let { ", $it" } ?: ""
        return when (mode) {
            ConversationMode.COMPANION ->
                "Hello$name. It's Sentri. I've got time if you'd like to talk. How's your day going?"

            ConversationMode.EMERGENCY ->
                "I'm here$name. Help has been called already. Stay where you are — I'm staying with you."
        }
    }

    /** Spoken if the language model cannot be reached mid-call. */
    fun failureMessage(): String =
        "I'm sorry, I didn't catch that. Give me a moment."

    /**
     * Seconds of no audio before Agora tears the agent down on its own.
     *
     * Short for a check-in, which is a bounded errand. Long for an emergency call, where silence
     * is the expected state and dropping the agent would leave the person alone.
     */
    fun idleTimeoutSeconds(mode: ConversationMode): Int = when (mode) {
        ConversationMode.COMPANION -> 180
        ConversationMode.EMERGENCY -> 600
    }
}
