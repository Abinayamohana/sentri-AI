package com.example.sentriai.reminder

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Says the reminder out loud, once, and reports when it has finished.
 *
 * ### Why it is built around one callback
 *
 * The overlay leaves as soon as the sentence ends, so "has it finished" is the only question the
 * service asks — and every way this can go wrong has to answer it too. Engine missing, engine
 * fails to initialise, language unavailable, utterance errors, phone on silent: each of those
 * calls [onFinished] instead of leaving the character standing there indefinitely. [finish] is
 * latched so the service cannot be told twice.
 *
 * ### The voice
 *
 * A young-sounding voice, as far as the platform allows. There is no gender or age API on
 * [TextToSpeech] — the best available signal is that Google's engine names its voices with a
 * `#female`/`#male` suffix, so [selectVoice] looks for that and quietly does nothing when the
 * installed engine names things differently. [PITCH] does the rest.
 *
 * [RATE] is slightly under normal on purpose. This is heard by someone being reminded to take
 * medication, sometimes a dosage and a food rule in one sentence, and a default-rate engine is
 * faster than that sentence should be delivered.
 */
internal class ReminderVoice(
    private val context: Context,
    private val onFinished: () -> Unit,
) {

    private var tts: TextToSpeech? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    /** Held until the engine reports ready, since [speak] is normally called before that happens. */
    private var pendingText: String? = null

    private var finished = false
    private var ready = false

    /**
     * Whether speaking aloud is appropriate right now.
     *
     * A spoken reminder plays on the accessibility/media path, which the ringer switch does not
     * silence — so an app that ignored the ringer mode would talk out loud in a meeting from a
     * phone that had been explicitly silenced. Vibrate counts as silenced too: both settings mean
     * the same thing to the person who chose them.
     *
     * The reminder is not lost when this is false. The card is still on screen and a notification
     * still records it; only the voice is withheld.
     */
    fun canSpeakAloud(): Boolean {
        val manager = manager() ?: return false
        return when (manager.ringerMode) {
            AudioManager.RINGER_MODE_SILENT, AudioManager.RINGER_MODE_VIBRATE -> false
            else -> true
        }
    }

    /**
     * Starts the engine and speaks [text] as soon as it is ready.
     *
     * Nothing is spoken twice: the engine is created on the first call and later calls replace the
     * pending text, which matters because a second batch can arrive while one is being read out.
     */
    fun speak(text: String) {
        if (text.isBlank()) {
            Log.w(TAG, "nothing to say")
            finish()
            return
        }
        pendingText = text

        if (ready) {
            speakNow(text)
            return
        }
        if (tts != null) return // already initialising; onInit will pick up pendingText

        tts = runCatching {
            TextToSpeech(context) { status -> onInit(status) }
        }.onFailure {
            Log.e(TAG, "no text-to-speech engine available", it)
            finish()
        }.getOrNull()
    }

    /**
     * Stops any speech and releases the engine.
     *
     * Both halves matter: an engine left open holds a connection to another process, and an
     * unstopped utterance carries on talking after the character has walked off screen.
     */
    fun shutdown() {
        abandonAudioFocus()
        val engine = tts ?: return
        tts = null
        ready = false
        runCatching {
            engine.stop()
            engine.shutdown()
        }.onFailure { Log.w(TAG, "error shutting the engine down: ${it.message}") }
    }

    // ---- engine setup -------------------------------------------------------------

    private fun onInit(status: Int) {
        val engine = tts
        if (status != TextToSpeech.SUCCESS || engine == null) {
            Log.e(TAG, "text-to-speech failed to initialise (status $status)")
            finish()
            return
        }

        // A missing language is not recoverable by trying anyway — the engine would either
        // refuse or read the sentence with the wrong phonetics.
        val locale = Locale.getDefault()
        val available = runCatching { engine.setLanguage(locale) }.getOrDefault(TextToSpeech.LANG_MISSING_DATA)
        if (available == TextToSpeech.LANG_MISSING_DATA || available == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "$locale unavailable, falling back to US English")
            val fallback = runCatching { engine.setLanguage(Locale.US) }
                .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            if (fallback == TextToSpeech.LANG_MISSING_DATA || fallback == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "no usable language, staying quiet")
                finish()
                return
            }
        }

        engine.setPitch(PITCH)
        engine.setSpeechRate(RATE)
        selectVoice(engine)

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                abandonAudioFocus()
                finish()
            }

            @Deprecated("Superseded by the two-argument overload, still required by the base class.")
            override fun onError(utteranceId: String?) {
                Log.w(TAG, "utterance failed")
                abandonAudioFocus()
                finish()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.w(TAG, "utterance failed with code $errorCode")
                abandonAudioFocus()
                finish()
            }
        })

        ready = true
        pendingText?.let { speakNow(it) }
    }

    /** Best effort; an engine that names its voices differently simply keeps its default. */
    private fun selectVoice(engine: TextToSpeech) {
        runCatching {
            val language = Locale.getDefault().language
            val candidate = engine.voices
                ?.filter { it.locale.language == language && !it.isNetworkConnectionRequired }
                ?.firstOrNull { it.name.contains("female", ignoreCase = true) }
                ?: return
            engine.voice = candidate
            Log.i(TAG, "using voice ${candidate.name}")
        }.onFailure { Log.w(TAG, "could not choose a voice: ${it.message}") }
    }

    private fun speakNow(text: String) {
        val engine = tts ?: return
        pendingText = null
        requestAudioFocus()

        // QUEUE_FLUSH, not QUEUE_ADD: if a second reminder replaced the first mid-sentence, the
        // card on screen is the new one and the old sentence is now wrong.
        val result = runCatching {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        }.getOrDefault(TextToSpeech.ERROR)

        if (result == TextToSpeech.ERROR) {
            Log.e(TAG, "the engine refused to speak")
            abandonAudioFocus()
            finish()
        }
    }

    // ---- audio focus --------------------------------------------------------------

    /**
     * `MAY_DUCK` transient focus: a reminder spoken over whatever is already playing is a reminder
     * nobody can make out, and taking focus outright would stop the radio for one sentence.
     */
    private fun requestAudioFocus() {
        val manager = manager() ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(audioAttributes())
                    .build()
                focusRequest = request
                manager.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")
                manager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
                )
            }
        }.onFailure { Log.w(TAG, "could not take audio focus: ${it.message}") }
    }

    private fun abandonAudioFocus() {
        val manager = manager() ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { manager.abandonAudioFocusRequest(it) }
                focusRequest = null
            } else {
                @Suppress("DEPRECATION")
                manager.abandonAudioFocus(null)
            }
        }.onFailure { Log.w(TAG, "could not release audio focus: ${it.message}") }
    }

    /**
     * `USAGE_ASSISTANCE_ACCESSIBILITY` rather than a notification usage: this is a spoken sentence
     * meant to be understood, and it should play at the volume spoken content plays at rather than
     * the one chosen for notification blips.
     */
    private fun audioAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private fun manager(): AudioManager? {
        audioManager = audioManager ?: context.getSystemService(AudioManager::class.java)
        return audioManager
    }

    /** Latched, so every failure path can call it without the service being told twice. */
    private fun finish() {
        if (finished) return
        finished = true
        onFinished()
    }

    private companion object {
        const val TAG = "ReminderVoice"
        const val UTTERANCE_ID = "sentriai_reminder"

        /**
         * Above normal, so the character sounds like the young companion the card depicts. Kept
         * well short of the range where an engine starts to sound shrill — this has to be
         * understood by someone who may be hard of hearing, and clarity beats characterisation.
         */
        const val PITCH = 1.22f

        /** Slightly under normal: a dosage and a food rule in one sentence deserve the extra beat. */
        const val RATE = 0.94f
    }
}
