package com.example.sentriai.agora

import android.content.Context
import android.util.Log
import com.example.sentriai.care.CareSchedule
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.Constants
import io.agora.rtc2.IRtcEngineEventHandler
// Nested classifiers of a Java supertype are not pulled into scope by subclassing in Kotlin the
// way they are in Java, so the callback parameter types are imported by name.
import io.agora.rtc2.IRtcEngineEventHandler.AudioVolumeInfo
import io.agora.rtc2.IRtcEngineEventHandler.ErrorCode
import io.agora.rtc2.IRtcEngineEventHandler.RtcStats
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Where a call is in its lifecycle. Drives the whole call UI. */
sealed interface CallState {
    data object Idle : CallState

    /** Joining the channel and starting the agent. [detail] is shown while it happens. */
    data class Connecting(val mode: ConversationMode, val detail: String) : CallState

    /**
     * The person is in the channel.
     *
     * @param agentPresent false in the gap between joining and the agent's own join landing.
     *   The call is real either way — the person can already hear the channel — so this is a
     *   badge, not a separate state.
     */
    data class Live(val mode: ConversationMode, val agentPresent: Boolean) : CallState

    data object Ending : CallState

    /** [message] is written to be shown to the user verbatim. */
    data class Failed(val message: String) : CallState
}

/**
 * Owns the Agora RTC engine for one conversational call: joins the channel, asks
 * [AgoraSessionRepository] to put an agent in it, and republishes the agent's data-stream
 * transcripts as [TranscriptEvent]s.
 *
 * ### What this deliberately does not do
 *
 * It does not decide anything about emergencies. It emits transcript events;
 * [com.example.sentriai.engine.ConversationEscalationMonitor] judges them and
 * `EmergencyPipeline` acts. Keeping the judgement out of here is what stops the RTC layer from
 * growing a second, subtly different alerting path next to the on-device one.
 *
 * It also does not touch [com.example.sentriai.service.ListeningService]. Both want the
 * microphone and only one can have it; resolving that is the caller's job (see
 * `CompanionCallViewModel`), because the caller is the only thing that knows whether the user
 * intended to arm passive listening in the first place.
 *
 * ### Lifetime
 *
 * One instance per ViewModel, not per call — [RtcEngine.create] and [RtcEngine.destroy] are
 * process-global and expensive, so the engine is created on the first call and released in
 * [release]. Calls are joins and leaves on that engine.
 */
class AgoraConversationManager(
    context: Context,
    private val scope: CoroutineScope,
) {

    private val appContext = context.applicationContext

    private val _state = MutableStateFlow<CallState>(CallState.Idle)
    val state: StateFlow<CallState> = _state.asStateFlow()

    /**
     * Transcript updates, interim and final.
     *
     * `extraBufferCapacity` rather than a replay buffer: a subscriber that attaches mid-call
     * should see what is said from then on, not a replay of the whole conversation. The buffer
     * exists so the RTC callback thread never suspends — emitting from it is
     * [MutableSharedFlow.tryEmit], which drops rather than blocks if a collector stalls.
     */
    private val _transcript = MutableSharedFlow<TranscriptEvent>(extraBufferCapacity = 64)
    val transcript: SharedFlow<TranscriptEvent> = _transcript.asSharedFlow()

    /** True while the agent's audio is above the silence threshold. Drives the speaking dot. */
    private val _agentSpeaking = MutableStateFlow(false)
    val agentSpeaking: StateFlow<Boolean> = _agentSpeaking.asStateFlow()

    @Volatile
    private var engine: RtcEngine? = null

    @Volatile
    private var session: AgoraSession? = null

    private val parser = ConversationMessageParser()

    /** Completed by `onJoinChannelSuccess`, or by an error callback with the reason. */
    @Volatile
    private var joinResult: CompletableDeferred<Result<Unit>>? = null

    /**
     * Whether the agent is in the channel, kept outside [state] because it is learned before
     * there is a [CallState.Live] to record it on.
     *
     * The agent joins the channel about a third of a second *before* the REST call that started
     * it returns, so `onUserJoined` regularly fires while the state is still
     * [CallState.Connecting]. Reading it off the state there would drop the only notification
     * that ever arrives, and the call would spend its whole life claiming the companion was
     * still joining.
     */
    @Volatile
    private var agentJoined = false

    val isLive: Boolean get() = _state.value is CallState.Live

    // --- lifecycle ---------------------------------------------------------------

    /**
     * Places one call, start to finish: reserve, join, start the agent.
     *
     * Suspends until the person is in the channel and the agent has been started. Failures at
     * any step leave [state] as [CallState.Failed] with a message meant for the screen, and
     * unwind whatever succeeded before them — a channel joined but agentless is not a call, and
     * leaving the person in one would bill for RTC minutes of silence.
     */
    suspend fun startCall(
        mode: ConversationMode,
        schedule: CareSchedule,
        personName: String,
    ): Result<AgoraSession> {
        if (session != null) {
            return Result.failure(IllegalStateException("A call is already in progress"))
        }

        agentJoined = false
        _state.value = CallState.Connecting(mode, "Getting things ready…")

        val reservation = AgoraSessionRepository.reserveChannel().getOrElse {
            return fail(it)
        }

        val rtc = ensureEngine().getOrElse { return fail(it) }

        _state.value = CallState.Connecting(mode, "Connecting…")
        joinChannel(rtc, reservation).getOrElse {
            runCatching { rtc.leaveChannel() }
            return fail(it)
        }

        _state.value = CallState.Connecting(mode, "Waking your companion…")
        val started = AgoraSessionRepository.startAgent(reservation, mode, schedule, personName)
            .getOrElse {
                Log.e(TAG, "agent failed to start; leaving the channel again")
                runCatching { rtc.leaveChannel() }
                return fail(it)
            }

        session = started
        // agentJoined, not false: the join callback has usually already fired by now.
        _state.value = CallState.Live(mode, agentPresent = agentJoined)
        Log.i(TAG, "call live: mode=$mode channel=${started.channel} agent=${started.agentId}")
        return Result.success(started)
    }

    /** Ends the call. Safe to call when there is none — that is the common teardown path. */
    suspend fun endCall() {
        val current = session
        if (current == null && _state.value is CallState.Idle) return

        _state.value = CallState.Ending
        Log.i(TAG, "ending call on ${current?.channel}")

        // Leave first. The person stops hearing the agent immediately, rather than after a
        // round trip to Agora's REST API that may take seconds or fail outright.
        runCatching { engine?.leaveChannel() }
            .onFailure { Log.w(TAG, "leaveChannel failed: ${it.message}") }

        current?.let { AgoraSessionRepository.stopSession(it) }

        session = null
        agentJoined = false
        parser.reset()
        _agentSpeaking.value = false
        _state.value = CallState.Idle
    }

    fun setMuted(muted: Boolean) {
        engine?.muteLocalAudioStream(muted)
        Log.d(TAG, "microphone ${if (muted) "muted" else "unmuted"}")
    }

    fun setSpeakerphone(on: Boolean) {
        engine?.setEnableSpeakerphone(on)
    }

    /**
     * Destroys the RTC engine. Call from `onCleared`.
     *
     * [RtcEngine.destroy] blocks until the SDK's internal threads have joined, which is why it
     * runs off the main thread.
     */
    fun release() {
        Log.i(TAG, "releasing RTC engine")
        engine = null
        session = null
        agentJoined = false
        parser.reset()
        _state.value = CallState.Idle
        // Static: it tears down whichever engine instance exists, and there is only ever one.
        runCatching { RtcEngine.destroy() }
            .onFailure { Log.w(TAG, "RtcEngine.destroy failed: ${it.message}") }
    }

    // --- engine and channel -------------------------------------------------------

    private fun ensureEngine(): Result<RtcEngine> {
        engine?.let { return Result.success(it) }

        return runCatching {
            val config = RtcEngineConfig().apply {
                mContext = appContext
                mAppId = AgoraConfig.APP_ID
                mEventHandler = handler
                // LIVE_BROADCASTING with both sides as broadcasters is what the Conversational
                // AI Engine expects; COMMUNICATION profile does not carry the agent's audio.
                mChannelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
                // Added in RTC 4.5.1 specifically for talking to a TTS agent: it tunes AEC and
                // noise suppression for a synthetic remote voice and keeps latency down. The
                // default profile treats the agent as a human speaker and echo-cancels it badly.
                mAudioScenario = Constants.AUDIO_SCENARIO_AI_CLIENT
            }
            RtcEngine.create(config).also { rtc ->
                rtc.enableAudio()
                rtc.setClientRole(Constants.CLIENT_ROLE_BROADCASTER)
                // A phone on a table or in a lap, not held to an ear. Earpiece routing would
                // make the whole feature unusable for the people it is for.
                rtc.setDefaultAudioRoutetoSpeakerphone(true)
                // Feeds the "companion is speaking" indicator. 300 ms is a compromise: fast
                // enough to look live, slow enough not to flicker on every syllable.
                rtc.enableAudioVolumeIndication(300, 3, false)
                engine = rtc
                Log.i(TAG, "RTC engine created, SDK ${RtcEngine.getSdkVersion()}")
            }
        }.recoverCatching {
            throw IllegalStateException(
                "Could not start the Agora voice engine: ${it.message}. Check that agoraAppId " +
                    "is a valid App ID.",
            )
        }
    }

    /** Joins and waits for the callback, so the caller knows the person is actually in. */
    private suspend fun joinChannel(
        rtc: RtcEngine,
        reservation: ChannelReservation,
    ): Result<Unit> {
        val awaiting = CompletableDeferred<Result<Unit>>()
        joinResult = awaiting

        val options = ChannelMediaOptions().apply {
            clientRoleType = Constants.CLIENT_ROLE_BROADCASTER
            publishMicrophoneTrack = true
            autoSubscribeAudio = true
        }

        val code = rtc.joinChannel(
            reservation.rtcToken,
            reservation.channel,
            reservation.localUid,
            options,
        )
        if (code != 0) {
            joinResult = null
            return Result.failure(IllegalStateException(joinErrorMessage(code)))
        }

        val outcome = withTimeoutOrNull(JOIN_TIMEOUT_MS) { awaiting.await() }
        joinResult = null
        return outcome ?: Result.failure(
            IllegalStateException("Could not connect to the call. Check the network and try again."),
        )
    }

    private fun fail(cause: Throwable): Result<AgoraSession> {
        val message = cause.message ?: "The call could not be started."
        Log.e(TAG, "call setup failed: $message", cause)
        _state.value = CallState.Failed(message)
        session = null
        return Result.failure(cause)
    }

    private fun joinErrorMessage(code: Int): String = when (code) {
        -2 -> "Invalid channel or token. If the Agora project has an App Certificate enabled, " +
            "set agoraBackendUrl so a token can be issued."
        -7 -> "The Agora engine was not ready. Try again."
        -17 -> "Already joining a call."
        else -> "Could not join the call (Agora error $code)."
    }

    // --- RTC callbacks -------------------------------------------------------------
    //
    // Every one of these runs on an Agora thread, never the main thread. Nothing here touches
    // the UI directly — state goes through StateFlow and transcripts through tryEmit, both of
    // which are safe to call from anywhere.

    private val handler = object : IRtcEngineEventHandler() {

        override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
            Log.i(TAG, "joined $channel as $uid in ${elapsed}ms")
            joinResult?.complete(Result.success(Unit))
        }

        override fun onUserJoined(uid: Int, elapsed: Int) {
            Log.i(TAG, "remote user $uid joined")
            if (uid != AgoraConfig.AGENT_UID) return
            agentJoined = true
            (_state.value as? CallState.Live)?.let {
                _state.value = it.copy(agentPresent = true)
            }
        }

        override fun onUserOffline(uid: Int, reason: Int) {
            Log.w(TAG, "remote user $uid left (reason $reason)")
            if (uid != AgoraConfig.AGENT_UID) return
            agentJoined = false
            _agentSpeaking.value = false
            (_state.value as? CallState.Live)?.let {
                _state.value = it.copy(agentPresent = false)
            }
            // The agent dropping out mid-call is not made into a failure state. On an emergency
            // call the person still has an open channel and an alert already sent; blanking the
            // screen to an error would take away the one thing telling them help is coming.
        }

        /**
         * Transcripts arrive here — see [ConversationMessageParser] for the wire format.
         */
        override fun onStreamMessage(uid: Int, streamId: Int, data: ByteArray?) {
            val payload = data ?: return
            val event = parser.parse(payload) ?: return
            if (!_transcript.tryEmit(event)) {
                // Only possible if a collector has stalled for 64 events. Worth a line, because
                // a dropped final event is a distress phrase the monitor never sees.
                Log.w(TAG, "transcript buffer full; dropped a ${event.speaker} event")
            }
        }

        override fun onAudioVolumeIndication(
            speakers: Array<out AudioVolumeInfo>?,
            totalVolume: Int,
        ) {
            // uid 0 in this callback means the local user, so the agent is matched by its own
            // uid. Volume is 0..255.
            val agent = speakers?.firstOrNull { it.uid == AgoraConfig.AGENT_UID }
            _agentSpeaking.value = (agent?.volume ?: 0) > SPEAKING_VOLUME_THRESHOLD
        }

        override fun onError(err: Int) {
            Log.e(TAG, "RTC error $err")
            // A rejected token is the failure a misconfigured project hits first, and the one
            // whose bare numeric code explains least.
            if (err == ErrorCode.ERR_TOKEN_EXPIRED || err == ErrorCode.ERR_INVALID_TOKEN) {
                val message = "The call token was rejected. If the Agora project has an App " +
                    "Certificate enabled, agoraBackendUrl must point at a token service."
                joinResult?.complete(Result.failure(IllegalStateException(message)))
                if (_state.value is CallState.Live) _state.value = CallState.Failed(message)
            }
        }

        override fun onTokenPrivilegeWillExpire(token: String?) {
            // TODO(agora-token-renewal): re-fetch from {backend}/rtc-token and call
            //  engine.renewToken(newToken). Only bites on calls longer than the token's TTL, so
            //  issuing tokens with an hour of life keeps every realistic companion call inside
            //  one token. Logged rather than silently ignored so a dropped long call has a trail.
            Log.w(TAG, "RTC token is about to expire; renewal is not implemented")
        }

        override fun onConnectionStateChanged(state: Int, reason: Int) {
            Log.d(TAG, "connection state=$state reason=$reason")
        }

        override fun onLeaveChannel(stats: RtcStats?) {
            Log.i(TAG, "left channel after ${stats?.totalDuration ?: 0}s")
            _agentSpeaking.value = false
        }
    }

    /**
     * Runs [block] for every transcript event, on [scope]. A convenience so callers do not each
     * write the same collector.
     */
    fun onTranscript(block: suspend (TranscriptEvent) -> Unit) {
        scope.launch(Dispatchers.Default) {
            transcript.collect { runCatching { block(it) }.onFailure { e ->
                Log.e(TAG, "transcript handler threw: ${e.message}", e)
            } }
        }
    }

    private companion object {
        const val TAG = "AgoraConversation"

        /** Joining takes well under a second on a good network; this is a stuck-call backstop. */
        const val JOIN_TIMEOUT_MS = 15_000L

        const val SPEAKING_VOLUME_THRESHOLD = 20
    }
}
