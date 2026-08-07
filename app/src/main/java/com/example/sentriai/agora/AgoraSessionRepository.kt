package com.example.sentriai.agora

import android.util.Base64
import android.util.Log
import com.example.sentriai.care.CareSchedule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * A channel the client may join, before any agent exists in it.
 *
 * Separate from [AgoraSession] because the two are acquired at different moments: the client
 * joins on a reservation, and only once it is actually in the channel is the agent asked to
 * join it. Starting the agent first means it can greet an empty room.
 */
data class ChannelReservation(
    val channel: String,
    val localUid: Int,
    /** Null when the project has its App Certificate disabled (testing mode only). */
    val rtcToken: String?,
)

/** A reservation with a running agent in it. */
data class AgoraSession(
    val reservation: ChannelReservation,
    /** Agora's handle for the running agent. Needed to stop it. */
    val agentId: String,
    val mode: ConversationMode,
) {
    val channel: String get() = reservation.channel
}

/**
 * Talks to whatever starts and stops the Agora conversational agent.
 *
 * Two paths, chosen by what is configured — see [AgoraConfig] for why they both exist.
 *
 * ### Path 1 (production): your backend
 *
 * Set `agoraBackendUrl` and implement three endpoints. They are small; the whole contract is
 * below and the app does not care what they are written in.
 *
 * ```
 * GET  {base}/rtc-token?channel={channel}&uid={uid}
 *      -> 200 {"token": "006..."}
 *      Build with agora-token (Node), agora_token_builder (Python), or the Go/Java equivalents,
 *      using the project's App Certificate. Role: publisher. Expiry: an hour is plenty.
 *
 * POST {base}/agent/start
 *      {"channel": "...", "uid": 123456, "agent_uid": 1001, "mode": "COMPANION",
 *       "system_prompt": "...", "greeting": "...", "idle_timeout": 120}
 *      -> 200 {"agent_id": "1NT29X10YH..."}
 *      Your handler forwards this to Agora's join endpoint (the same body path 2 builds below),
 *      adding the Customer ID/Secret and your LLM and TTS vendor configuration.
 *
 * POST {base}/agent/stop
 *      {"agent_id": "..."}
 *      -> 200
 * ```
 *
 * The system prompt is built on the device and sent up rather than assembled server-side,
 * because it contains the person's medicine schedule — which lives on the phone
 * ([com.example.sentriai.care.CareScheduleStore]) and has no reason to be replicated anywhere
 * else.
 *
 * ### Path 2 (debug only): straight to Agora
 *
 * With `agoraCustomerId`/`agoraCustomerSecret` set in a debug build, this calls Agora's
 * Conversational AI Engine REST API itself:
 *
 * ```
 * POST https://api.agora.io/api/conversational-ai-agent/v2/projects/{appId}/join
 * POST https://api.agora.io/api/conversational-ai-agent/v2/projects/{appId}/agents/{agentId}/leave
 * ```
 *
 * It exists to make the flow runnable end to end before the backend is written. It cannot mint
 * an RTC token — that needs the App Certificate — so it only works against a project with the
 * certificate disabled, and it says so plainly when it fails.
 */
object AgoraSessionRepository {

    private const val TAG = "AgoraSessionRepo"

    private const val AGORA_API_BASE = "https://api.agora.io/api/conversational-ai-agent/v2/projects"

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    /**
     * Vendors for the inline fallback — used only when no Agent Studio pipeline is configured.
     *
     * With `agoraPipelineId` set (the normal case, see AGORA.md) none of these are sent: the
     * published Studio agent already names the ASR, LLM and TTS, and the console is a far better
     * place for that than a constant in an APK, because changing the voice or the model then
     * does not need an app release.
     *
     * TODO(agora-vendors): these only matter if you deliberately run without a Studio agent. They
     *   must then match what your Agora project is provisioned for — a vendor the account cannot
     *   use comes back as a 400 from `join` with the reason in the body, which [startAgent] logs
     *   verbatim.
     */
    private const val MANAGED_LLM_VENDOR = "openai"
    private const val MANAGED_LLM_MODEL = "gpt-4o-mini"
    private const val MANAGED_TTS_VENDOR = "microsoft"
    private const val MANAGED_ASR_LANGUAGE = "en-US"

    /** Turns the person's audio into text for the agent. Also what the client sees as transcripts. */
    private const val MAX_HISTORY = 32

    /**
     * Picks a channel and gets a token for it. No agent is started — that is [startAgent].
     *
     * @return a failure whose message is fit to show the user. The companion screen prints it
     *   directly, because "call failed" with the reason in logcat is useless to the person
     *   holding the phone.
     */
    suspend fun reserveChannel(): Result<ChannelReservation> = withContext(Dispatchers.IO) {
        AgoraConfig.missingRequirement()?.let {
            return@withContext Result.failure(IllegalStateException(it))
        }

        val channel = AgoraConfig.newChannelName()
        val uid = AgoraConfig.newLocalUid()
        Log.i(TAG, "reserved channel=$channel uid=$uid")

        fetchRtcToken(channel, uid).map { ChannelReservation(channel, uid, it) }
    }

    /**
     * Starts a conversational agent in an already-joined channel.
     *
     * Called after `onJoinChannelSuccess`, so the agent's greeting is spoken to someone who is
     * there to hear it.
     */
    suspend fun startAgent(
        reservation: ChannelReservation,
        mode: ConversationMode,
        schedule: CareSchedule,
        personName: String,
    ): Result<AgoraSession> = withContext(Dispatchers.IO) {
        Log.i(TAG, "starting $mode agent on ${reservation.channel}")

        startAgent(
            channel = reservation.channel,
            uid = reservation.localUid,
            mode = mode,
            systemPrompt = AgentPromptBuilder.systemPrompt(mode, schedule, personName),
            greeting = AgentPromptBuilder.greeting(mode, personName),
            token = reservation.rtcToken,
        ).map { agentId ->
            Log.i(TAG, "agent $agentId running on ${reservation.channel}")
            AgoraSession(reservation, agentId, mode)
        }
    }

    /**
     * Stops the agent. Best effort by design.
     *
     * A failure here is logged and swallowed: the call is already over from the person's point
     * of view, the client has left the channel, and Agora's `idle_timeout` reaps an
     * unreferenced agent on its own. Surfacing an error at this point would only be noise.
     */
    suspend fun stopSession(session: AgoraSession) = withContext(Dispatchers.IO) {
        val result = if (AgoraConfig.hasBackend) {
            post(
                url = "${AgoraConfig.BACKEND_URL}/agent/stop",
                body = JSONObject().put("agent_id", session.agentId),
                authHeader = null,
            )
        } else {
            post(
                url = "$AGORA_API_BASE/${AgoraConfig.APP_ID}/agents/${session.agentId}/leave",
                body = JSONObject(),
                authHeader = basicAuthHeader(),
            )
        }
        result
            .onSuccess { Log.i(TAG, "agent ${session.agentId} stopped") }
            .onFailure { Log.w(TAG, "stopping agent ${session.agentId} failed: ${it.message}") }
        Unit
    }

    // --- token ---------------------------------------------------------------------

    /**
     * @return the token, or null wrapped in success when the project runs without an App
     *   Certificate. Null is a legitimate value: `joinChannel` accepts it, and Agora projects in
     *   testing mode have no token to issue.
     */
    private fun fetchRtcToken(channel: String, uid: Int): Result<String?> {
        if (!AgoraConfig.hasBackend) {
            // TODO(agora-token): no backend configured, so there is nothing that holds the App
            //  Certificate and nothing that can sign a token. This path only joins successfully
            //  against a project with the certificate DISABLED (Console → Project → Security).
            //  With it enabled, joinChannel fails with ERR_INVALID_TOKEN (-2) / code 110 —
            //  AgoraConversationManager reports that as a token error rather than a mystery.
            Log.w(TAG, "no backend URL — joining without an RTC token (App Certificate must be off)")
            return Result.success(null)
        }

        val url = "${AgoraConfig.BACKEND_URL}/rtc-token?channel=$channel&uid=$uid"
        return get(url).mapCatching { body ->
            JSONObject(body).optString("token").takeIf { it.isNotBlank() }
                ?: throw IOException("Token service returned no `token` field")
        }.recoverCatching {
            throw IOException("Could not get an Agora token from your backend: ${it.message}")
        }
    }

    // --- starting the agent ----------------------------------------------------------

    private fun startAgent(
        channel: String,
        uid: Int,
        mode: ConversationMode,
        systemPrompt: String,
        greeting: String,
        token: String?,
    ): Result<String> {
        val (url, body, auth) = if (AgoraConfig.hasBackend) {
            Triple(
                "${AgoraConfig.BACKEND_URL}/agent/start",
                backendStartBody(channel, uid, mode, systemPrompt, greeting),
                null,
            )
        } else {
            Triple(
                "$AGORA_API_BASE/${AgoraConfig.APP_ID}/join",
                agoraJoinBody(channel, uid, mode, systemPrompt, greeting, token),
                basicAuthHeader(),
            )
        }

        return post(url, body, auth).mapCatching { response ->
            JSONObject(response).optString("agent_id").takeIf { it.isNotBlank() }
                ?: throw IOException("Agent start returned no `agent_id`: ${response.take(200)}")
        }
    }

    /** Path 1's body. Deliberately flat — your service reshapes it for Agora. */
    private fun backendStartBody(
        channel: String,
        uid: Int,
        mode: ConversationMode,
        systemPrompt: String,
        greeting: String,
    ): JSONObject = JSONObject().apply {
        put("channel", channel)
        put("uid", uid)
        put("agent_uid", AgoraConfig.AGENT_UID)
        put("mode", mode.name)
        put("system_prompt", systemPrompt)
        put("greeting", greeting)
        put("idle_timeout", AgentPromptBuilder.idleTimeoutSeconds(mode))
        // Forwarded so your service can pass it straight through as the top-level `pipeline_id`.
        // Omitted when unset, in which case your service supplies its own vendor configuration.
        AgoraConfig.PIPELINE_ID.takeIf { it.isNotBlank() }?.let { put("pipeline_id", it) }
        // So your service builds system_messages in the shape its configured LLM expects.
        put("llm_style", AgoraConfig.LLM_STYLE)
    }

    /**
     * Path 2's body: Agora's Conversational AI Engine `join` schema.
     *
     * `remote_rtc_uids` names this one uid rather than `"*"`. The agent then subscribes to
     * exactly the person on this phone — on a shared channel `"*"` would have it answering
     * anyone who joined.
     *
     * ### The prompt is always sent, even with a Studio agent
     *
     * `pipeline_id` names a published Agent Studio configuration, and Agora treats it as the
     * base: anything under `properties` overrides the corresponding saved setting. So the
     * console owns the ASR/LLM/TTS choice and the turn-detection tuning, and the app overrides
     * `system_messages` and `greeting_message` on every call.
     *
     * That split is not incidental. A prompt fixed in the console could not contain today's
     * medicine schedule, which lives on the phone and changes as doses are ticked off, and it
     * could not differ between [ConversationMode.COMPANION] and `EMERGENCY` —
     * one published agent would have to be three.
     */
    private fun agoraJoinBody(
        channel: String,
        uid: Int,
        mode: ConversationMode,
        systemPrompt: String,
        greeting: String,
        token: String?,
    ): JSONObject = JSONObject().apply {
        // Agora rejects a reused name while the previous agent is alive, so it carries the
        // channel — which is already unique per call.
        put("name", "sentri-$channel")
        AgoraConfig.PIPELINE_ID.takeIf { it.isNotBlank() }?.let { put("pipeline_id", it) }
        put(
            "properties",
            JSONObject().apply {
                put("channel", channel)
                token?.let { put("token", it) }
                put("agent_rtc_uid", AgoraConfig.AGENT_UID.toString())
                put("remote_rtc_uids", JSONArray().put(uid.toString()))
                put("enable_string_uid", false)
                put("idle_timeout", AgentPromptBuilder.idleTimeoutSeconds(mode))

                // Vendor selection is only sent when there is no published agent to take it
                // from. Sending it anyway would override the console's choice with a constant
                // compiled into the APK — the exact thing the Studio agent exists to avoid.
                if (!AgoraConfig.hasPipelineId) {
                    put(
                        "asr",
                        JSONObject()
                            .put("credential_mode", "managed")
                            .put("language", MANAGED_ASR_LANGUAGE),
                    )
                    put(
                        "tts",
                        JSONObject()
                            .put("credential_mode", "managed")
                            .put("vendor", MANAGED_TTS_VENDOR),
                    )
                }

                put(
                    "llm",
                    JSONObject().apply {
                        if (!AgoraConfig.hasPipelineId) {
                            put("credential_mode", "managed")
                            put("vendor", MANAGED_LLM_VENDOR)
                            put("params", JSONObject().put("model", MANAGED_LLM_MODEL))
                        }
                        // Always overridden — see the class docs above.
                        put("system_messages", systemMessages(systemPrompt))
                        // Gemini and Vertex AI need to be told which dialect this is; Agora
                        // reads `style` to decide how to talk to the vendor.
                        if (AgoraConfig.usesGeminiStyle) put("style", AgoraConfig.STYLE_GEMINI)
                        put("greeting_message", greeting)
                        put("failure_message", AgentPromptBuilder.failureMessage())
                        put("max_history", MAX_HISTORY)
                    },
                )

                put(
                    "advanced_features",
                    JSONObject()
                        // Lets the agent tell a pause from an ending, so it stops talking over
                        // someone who speaks slowly — which on this call is everyone.
                        .put("enable_aivad", true)
                        // Transcripts come back over the RTC data stream, which is what
                        // ConversationMessageParser reads. Turning RTM on would move them to the
                        // Signaling channel and require the Signaling SDK as well.
                        .put("enable_rtm", false),
                )
            },
        )
    }

    /**
     * The system prompt in whichever shape the configured LLM family expects.
     *
     * Gemini has no `system` role at all — the instruction goes in as a `user` turn whose
     * content is a `parts` array. Handing it an OpenAI-shaped message does not fail loudly; the
     * prompt is simply not applied, and the agent answers the call as a generic assistant with
     * no medicine schedule and none of the safety rules. See [AgoraConfig.LLM_STYLE].
     */
    private fun systemMessages(systemPrompt: String): JSONArray = JSONArray().put(
        if (AgoraConfig.usesGeminiStyle) {
            JSONObject()
                .put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
        } else {
            JSONObject().put("role", "system").put("content", systemPrompt)
        },
    )

    private fun basicAuthHeader(): String {
        val raw = "${AgoraConfig.CUSTOMER_ID}:${AgoraConfig.CUSTOMER_SECRET}"
        return "Basic " + Base64.encodeToString(raw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    // --- plain HTTP ------------------------------------------------------------------
    //
    // HttpURLConnection and org.json, as in ModelDownloader. The two calls here are a GET and a
    // POST of a few hundred bytes; adding OkHttp and a JSON mapper to the app for them would be
    // the largest dependency change in the project.

    private fun get(url: String): Result<String> = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
        }
        connection.readOrThrow()
    }

    private fun post(url: String, body: JSONObject, authHeader: String?): Result<String> =
        runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                authHeader?.let { setRequestProperty("Authorization", it) }
            }
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            connection.readOrThrow()
        }

    /**
     * Reads the body, or throws with the server's own explanation in the message.
     *
     * The error body matters more here than in most clients: Agora's `join` returns a 400 whose
     * body names the misconfigured vendor or the malformed field, and discarding it in favour of
     * "HTTP 400" turns a five-second fix into an afternoon.
     */
    private fun HttpURLConnection.readOrThrow(): String = try {
        val status = responseCode
        if (status in 200..299) {
            inputStream.bufferedReader().use { it.readText() }
        } else {
            val detail = errorStream?.bufferedReader()?.use { it.readText() }.orEmpty().take(500)
            Log.e(TAG, "HTTP $status from $url: $detail")
            throw IOException(
                buildString {
                    append("HTTP $status from ${url.host}")
                    if (detail.isNotBlank()) append(" — $detail")
                    if (status == 401 || status == 403) {
                        append(
                            "\n\nCheck the Agora Customer ID/Secret (Console → RESTful API), " +
                                "and that Conversational AI is enabled for this project.",
                        )
                    }
                },
            )
        }
    } finally {
        disconnect()
    }
}
