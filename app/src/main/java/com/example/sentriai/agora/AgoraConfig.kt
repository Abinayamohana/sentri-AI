package com.example.sentriai.agora

import com.example.sentriai.BuildConfig
import kotlin.random.Random

/**
 * Where the Agora credentials come from, and what is missing when the call cannot start.
 *
 * Set these in `local.properties` (gitignored) or `gradle.properties`:
 *
 * ```properties
 * agoraAppId=<your Agora project App ID>
 * agoraBackendUrl=https://your-service.example.com
 * # debug builds only — see app/build.gradle.kts
 * agoraCustomerId=<Agora RESTful API Customer ID>
 * agoraCustomerSecret=<Agora RESTful API Customer Secret>
 * ```
 *
 * ### Why a backend is the real answer
 *
 * Two of the four things a conversational session needs cannot legitimately live in an APK:
 *
 * - **The RTC token** is signed with the project's App Certificate. Shipping the certificate
 *   would let anyone mint tokens on the account.
 * - **Starting the agent** authenticates with the Agora RESTful Customer ID/Secret, which can
 *   start billable agents on the account and are not scoped to a channel.
 *
 * So the supported path is [BACKEND_URL] pointing at a service you own, exposing the two
 * endpoints named in [AgoraSessionRepository]. The direct-to-Agora path exists only so the flow
 * can be exercised in a debug build before that service is written, and
 * [AgoraSessionRepository] refuses to take it in a release build regardless of configuration.
 */
object AgoraConfig {

    /** Public by design — it identifies the project and the client cannot start without it. */
    val APP_ID: String = BuildConfig.AGORA_APP_ID

    /** Base URL of your own service. Trailing slash is tolerated. */
    val BACKEND_URL: String = BuildConfig.AGORA_BACKEND_URL.trimEnd('/')

    /**
     * The published Agent Studio configuration to build each call on, from the console's
     * "Embed Agent" snippet. Also public — it names a configuration, it does not authorise
     * anything.
     *
     * With this set, the ASR/LLM/TTS choice, the voice and the turn-detection tuning all live in
     * the console and can be changed without an app release. The app still overrides the system
     * prompt and greeting per call, because those carry the medicine schedule and the
     * conversation mode. Optional: without it the app sends its own vendor configuration.
     */
    val PIPELINE_ID: String = BuildConfig.AGORA_PIPELINE_ID

    val hasPipelineId: Boolean get() = PIPELINE_ID.isNotBlank()

    /**
     * Which wire shape the configured LLM expects for `system_messages`.
     *
     * Not cosmetic, and the reason it is configurable at all: Agora passes the messages through
     * to the vendor more or less as given, and the two families disagree about the field names.
     *
     * ```
     * openai:  {"role": "system", "content": "…"}
     * gemini:  {"role": "user", "parts": [{"text": "…"}]}
     * ```
     *
     * Send the wrong one and nothing errors. The agent starts, answers the phone, and behaves
     * like a generic assistant, because the prompt carrying the medicine schedule and every
     * safety rule was silently dropped. That is a far worse failure than a 400 would be, so the
     * shape is an explicit setting rather than something inferred and hoped for.
     *
     * Set `agoraLlmStyle=gemini` when the agent's LLM is Gemini or Vertex AI. Default is OpenAI
     * shape, which also covers Azure OpenAI, Groq, DeepSeek and most OpenAI-compatible proxies.
     */
    val LLM_STYLE: String = BuildConfig.AGORA_LLM_STYLE.lowercase().ifBlank { STYLE_OPENAI }

    val usesGeminiStyle: Boolean get() = LLM_STYLE == STYLE_GEMINI

    const val STYLE_OPENAI = "openai"
    const val STYLE_GEMINI = "gemini"

    /** Empty outside debug builds. See the class docs. */
    val CUSTOMER_ID: String = BuildConfig.AGORA_CUSTOMER_ID
    val CUSTOMER_SECRET: String = BuildConfig.AGORA_CUSTOMER_SECRET

    val hasAppId: Boolean get() = APP_ID.isNotBlank()
    val hasBackend: Boolean get() = BACKEND_URL.isNotBlank()

    /**
     * True when the app may call the Agora RESTful API itself. Requires both credentials **and**
     * a debug build — a release APK with a stray `agoraCustomerId` in `gradle.properties` still
     * gets false here.
     */
    val canCallAgoraDirectly: Boolean
        get() = BuildConfig.DEBUG && CUSTOMER_ID.isNotBlank() && CUSTOMER_SECRET.isNotBlank()

    /**
     * @return a sentence naming the missing piece, or null when a session can be started.
     *
     * Surfaced verbatim on the companion screen. A call that silently does nothing because a
     * Gradle property is unset is the single most confusing failure in this feature, so the
     * screen says which property.
     */
    fun missingRequirement(): String? = when {
        !hasAppId ->
            "Agora App ID is not set. Add `agoraAppId=…` to local.properties and rebuild."

        !hasBackend && !canCallAgoraDirectly ->
            "No way to start the conversational agent. Set `agoraBackendUrl=…` to your token/agent " +
                "service, or (debug builds only) `agoraCustomerId=…` and `agoraCustomerSecret=…`."

        else -> null
    }

    /**
     * A channel name for one session.
     *
     * Unique per call rather than per user: a reused name lets a stale agent from a previous
     * session — one whose `leave` never landed because the process died — still be in the
     * channel when the next call starts, and the person gets answered by two voices.
     *
     * Agora allows up to 64 bytes of a restricted ASCII set; this stays well inside it.
     */
    fun newChannelName(): String = "sentri-${System.currentTimeMillis()}-${Random.nextInt(1000, 9999)}"

    /**
     * The local user's RTC uid.
     *
     * Kept away from 0 — 0 asks Agora to assign one, and the assigned value is not known until
     * `onJoinChannelSuccess`, which is after the point where the agent has to be told which uid
     * to subscribe to. Also kept out of the low numbers reserved for the agent itself.
     */
    fun newLocalUid(): Int = Random.nextInt(100_000, 999_999)

    /** The uid the conversational agent joins as. Fixed so the client can recognise it. */
    const val AGENT_UID = 1001
}
