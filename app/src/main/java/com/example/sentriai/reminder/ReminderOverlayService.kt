package com.example.sentriai.reminder

import android.app.KeyguardManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.sentriai.data.ProfileStore

/**
 * Owns the overlay for as long as it is on screen.
 *
 * A foreground service rather than the receiver that started it, for two reasons that both bite:
 * a [BroadcastReceiver]'s process is killable the instant `onReceive` returns, and the overlay has
 * to survive for minutes; and a window added from a dying process is torn down with it.
 *
 * ```
 *   ReminderAlarmReceiver ──▶ ReminderOverlayService ──┬──▶ ReminderOverlayWindow   (she appears)
 *                                       │              └──▶ ReminderVoice           (she speaks)
 *                                       │
 *                                       └──▶ ReminderNotifications  (when the window cannot show)
 * ```
 *
 * ### The shape of a reminder
 *
 * A visit, not a dialog. The character arrives, says what is due out loud, waits a few seconds in
 * case Snooze is wanted, and leaves on her own — see [speakThenLeave]. Nothing has to be dismissed
 * for the phone to go back to what it was doing.
 *
 * ### It always ends up somewhere
 *
 * Nothing here may silently swallow a dose reminder, so every path that is not an explicit tap
 * leaves a notification behind:
 *
 *  - **No overlay grant.** Revoked, or never given.
 *  - **A locked screen.** A `TYPE_APPLICATION_OVERLAY` window does not draw over the keyguard —
 *    only an activity with `FLAG_SHOW_WHEN_LOCKED` does, and this deliberately does not become
 *    one. Waking a phone into a full-screen card is the behaviour of an alarm clock, and it would
 *    show whatever the person takes to anyone holding the phone. So the reminder is posted, and
 *    the window is held back until the device is actually unlocked ([LOCK_WAIT_MS]).
 *  - **She left unanswered.** [leaveOnOwn], the normal ending. The sentence may have been spoken
 *    to an empty room.
 *  - **A voice that never reports back.** [VISIBLE_TIMEOUT_MS] is the backstop for an engine that
 *    neither finishes nor errors.
 *
 * Stop and Snooze are the two exits that leave nothing behind, because both are someone answering.
 */
class ReminderOverlayService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    private var overlay: ReminderOverlayWindow? = null
    private var batch: ReminderBatch? = null

    /** Non-null from the moment a visit starts speaking until the service stops. */
    private var voice: ReminderVoice? = null

    /**
     * True when this is the "see what it looks like" run from the setup card.
     *
     * A preview has to look exactly like the real thing and change nothing: no notification left
     * behind, and Snooze does not schedule anything. Otherwise checking that reminders work would
     * itself create a reminder.
     */
    private var preview = false

    /** Non-null only while waiting for the screen to be unlocked. */
    private var unlockReceiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Before anything else: the platform allows about five seconds between here and
        // startForeground before it throws.
        if (!enterForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_SHOW -> {
                val incoming = ReminderBatch.fromJsonStringOrNull(intent.getStringExtra(EXTRA_BATCH))
                preview = intent.getBooleanExtra(EXTRA_PREVIEW, false)
                if (incoming == null || incoming.items.isEmpty()) {
                    Log.w(TAG, "show without a usable batch, nothing to do")
                    finish()
                } else {
                    present(incoming)
                }
            }

            else -> {
                Log.w(TAG, "unknown action ${intent?.action}, stopping")
                finish()
            }
        }
        // Not sticky: a restart by the OS would arrive with a null intent and no idea what was
        // due. The alarm chain is the source of truth for that, and it is still armed.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopWaitingForUnlock()
        // Before the window goes: an engine left running would carry on talking about a card that
        // is no longer on screen.
        voice?.shutdown()
        voice = null
        overlay?.remove()
        overlay = null
        super.onDestroy()
    }

    // ---- presenting ---------------------------------------------------------------

    /**
     * A second batch arriving while one is up replaces it rather than queueing behind it. The
     * only way that happens is a snooze coming due during another reminder, and the older card is
     * then the one already snoozed or ignored.
     */
    private fun present(incoming: ReminderBatch) {
        handler.removeCallbacksAndMessages(null)
        batch = incoming

        if (!ReminderPermissions.canDrawOverlay(this)) {
            Log.w(TAG, "no overlay grant, falling back to a notification")
            ReminderNotifications.postAlert(this, incoming)
            finish()
            return
        }

        if (isKeyguardLocked()) {
            Log.i(TAG, "screen locked, posting alert with full-screen intent")
            ReminderNotifications.postAlert(this, incoming)
            waitForUnlock()
            return
        }

        showOverlay(incoming)
    }

    private fun showOverlay(target: ReminderBatch) {
        // The notification and the window are never both the reminder; whichever appears second
        // takes over.
        ReminderNotifications.cancelAlert(this)

        val window = overlay ?: ReminderOverlayWindow(
            context = this,
            onStop = ::onStop,
            onSnooze = ::onSnooze,
        ).also { overlay = it }

        if (!window.show(target)) {
            Log.w(TAG, "overlay window refused, falling back to a notification")
            ReminderNotifications.postAlert(this, target)
            finish()
            return
        }

        speakThenLeave(target)

        // A backstop, not the normal exit. Everything below is meant to have taken the character
        // away long before this, but an engine that never calls back either way would otherwise
        // leave her on screen for the rest of the day.
        handler.postDelayed(::leaveOnOwn, VISIBLE_TIMEOUT_MS)
    }

    // ---- the visit ----------------------------------------------------------------

    /**
     * The whole point of the character: she arrives, says what is due, and goes.
     *
     * Speech starts after the entrance has landed rather than with it — a sentence that begins
     * while she is still sliding in reads as a notification that happens to have a voice, instead
     * of somebody who walked over to say something.
     *
     * The leaving is what makes this a visit and not a dialog. [LINGER_AFTER_SPEECH_MS] is the one
     * concession: a few seconds after the last word, so Snooze is still reachable for anyone who
     * wants it, and then she is gone without needing to be dismissed.
     */
    private fun speakThenLeave(target: ReminderBatch) {
        val line = ReminderSpeech.line(target, personName())
        // A replaced batch must not leave the previous sentence still being read out under a card
        // that no longer says it.
        voice?.shutdown()
        val voice = ReminderVoice(this, onFinished = ::onSpeechFinished).also { this.voice = it }

        if (line.isBlank() || !voice.canSpeakAloud()) {
            // Silenced phone, or nothing sensible to say. She still came, so she is still read —
            // just for long enough to be read rather than long enough to be listened to.
            Log.i(TAG, "not speaking aloud; leaving after a readable pause")
            handler.postDelayed(::leaveOnOwn, SILENT_DWELL_MS)
            return
        }

        handler.postDelayed({ voice.speak(line) }, ReminderOverlayWindow.ENTRANCE_MS)
    }

    /** Called for every outcome the engine can have, including its failures. */
    private fun onSpeechFinished() {
        Log.i(TAG, "finished speaking")
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed(::leaveOnOwn, LINGER_AFTER_SPEECH_MS)
    }

    /**
     * She leaves of her own accord, having said her piece and not been answered.
     *
     * A notification is left behind, and this is the one exit that leaves one. Nobody tapped
     * anything, so nothing is known about whether the reminder landed — the person may have been
     * in another room while it was spoken. For a dose, a record in the shade is the difference
     * between a reminder that was missed and one that never existed.
     */
    private fun leaveOnOwn() {
        if (preview) {
            Log.i(TAG, "preview over")
        } else {
            Log.i(TAG, "reminder unanswered; leaving a notification behind")
            batch?.let { ReminderNotifications.postAlert(this, it) }
        }
        dismissThenFinish()
    }

    // ---- the two buttons ----------------------------------------------------------

    /**
     * Stop. Ends this occurrence and nothing else: the daily alarm for tomorrow is untouched, and
     * any pending snooze is dropped so a dismissed reminder cannot reappear ten minutes later.
     *
     * Note what it does *not* do — it does not mark a dose taken. Dismissing a reminder and
     * swallowing a tablet are different events, and recording the second one from the first would
     * put adherence the check-in call reads back on a tap that means "not now".
     */
    private fun onStop() {
        Log.i(TAG, "reminder stopped")
        ReminderScheduler.cancelSnooze(this)
        ReminderNotifications.cancelAlert(this)
        dismissThenFinish()
    }

    /** Snooze. Adds a one-off alarm and replaces any earlier one; the daily schedule is untouched. */
    private fun onSnooze() {
        val target = batch
        if (target == null) {
            Log.w(TAG, "snooze with nothing to snooze")
            finish()
            return
        }
        if (preview) {
            Log.i(TAG, "preview snooze; not scheduling anything")
            dismissThenFinish()
            return
        }
        Log.i(TAG, "reminder snoozed for ${ReminderSettings.SNOOZE_MINUTES} min")
        ReminderScheduler.snooze(this, target)
        ReminderNotifications.cancelAlert(this)
        dismissThenFinish()
    }

    // ---- the locked-screen wait ---------------------------------------------------

    /**
     * `ACTION_USER_PRESENT` is the unlock, not the screen coming on — that distinction is the
     * whole point, since a screen-on that is still on the keyguard cannot host the window either.
     */
    private fun waitForUnlock() {
        if (unlockReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_USER_PRESENT) return
                Log.i(TAG, "device unlocked, showing the held-back reminder")
                stopWaitingForUnlock()
                handler.removeCallbacksAndMessages(null)
                batch?.let { showOverlay(it) } ?: finish()
            }
        }
        unlockReceiver = receiver
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(Intent.ACTION_USER_PRESENT),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // A phone left locked all afternoon must not leave this service running all afternoon.
        // The notification is already posted, so giving up costs nothing but the animation.
        handler.postDelayed(
            {
                Log.i(TAG, "still locked after ${LOCK_WAIT_MS / 60_000} min, leaving it in the shade")
                finish()
            },
            LOCK_WAIT_MS,
        )
    }

    private fun stopWaitingForUnlock() {
        val receiver = unlockReceiver ?: return
        unlockReceiver = null
        runCatching { unregisterReceiver(receiver) }
            .onFailure { Log.w(TAG, "unlock receiver already gone: ${it.message}") }
    }

    // ---- lifecycle plumbing -------------------------------------------------------

    private fun enterForeground(): Boolean {
        ReminderNotifications.ensureChannels(this)
        return runCatching {
            ServiceCompat.startForeground(
                this,
                ReminderNotifications.SERVICE_NOTIFICATION_ID,
                ReminderNotifications.buildOngoing(this),
                // No foreground service type describes "draws a reminder over other apps", and
                // from API 34 one is required. specialUse is the honest answer; the manifest
                // carries the matching <property> subtype.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                },
            )
        }.onFailure {
            // ForegroundServiceStartNotAllowedException, if the exact-alarm allowlist that
            // permits this start was not in effect. Nothing to recover with here — the receiver
            // has already returned — so the reminder is lost and the log says why.
            Log.e(TAG, "startForeground refused; reminder cannot be shown", it)
        }.isSuccess
    }

    private fun dismissThenFinish() {
        val window = overlay
        if (window == null) {
            finish()
            return
        }
        window.dismiss(onRemoved = ::finish)
    }

    private fun finish() {
        handler.removeCallbacksAndMessages(null)
        stopWaitingForUnlock()
        voice?.shutdown()
        voice = null
        overlay?.remove()
        overlay = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun isKeyguardLocked(): Boolean =
        getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    /**
     * The name to greet by, or empty when the profile has none.
     *
     * Read from the same place the check-in call reads it, so the character and the agent address
     * the person identically rather than one using a name the other does not know.
     */
    private fun personName(): String =
        ProfileStore.preferences(this)
            .getString(ProfileStore.KEY_FULL_NAME, "")
            ?.trim()
            .orEmpty()

    companion object {
        private const val TAG = "ReminderOverlayService"

        private const val ACTION_SHOW = "com.example.sentriai.action.SHOW_REMINDER"
        private const val EXTRA_BATCH = "batch"
        private const val EXTRA_PREVIEW = "preview"

        /**
         * How long after the last word she stays before leaving.
         *
         * Long enough that Snooze is a real option for someone who has just heard the sentence and
         * has to reach for the phone; short enough that the normal outcome is her leaving rather
         * than the person having to dismiss her. Under about three seconds this becomes a race.
         */
        private const val LINGER_AFTER_SPEECH_MS = 5_000L

        /**
         * The visit length when nothing is spoken — a silenced phone, or no usable voice.
         *
         * Longer than [LINGER_AFTER_SPEECH_MS], because the card now has to be read rather than
         * heard, and it has to be noticed first.
         */
        private const val SILENT_DWELL_MS = 9_000L

        /** Backstop for a voice engine that neither completes nor reports an error. */
        private const val VISIBLE_TIMEOUT_MS = 2 * 60_000L

        /** How long to hold a reminder back for an unlock before giving up on the window. */
        private const val LOCK_WAIT_MS = 10 * 60_000L

        /**
         * Must be called from a context that is allowed to start a foreground service — in
         * practice [ReminderAlarmReceiver], inside the temporary allowlist that delivering an
         * exact alarm grants.
         */
        fun show(context: Context, batch: ReminderBatch) {
            start(context, batch, preview = false)
        }

        /**
         * Shows a reminder right now, exactly as one would arrive, and changes nothing.
         *
         * Started from the app while it is on screen, which is the easiest foreground-service start
         * there is — so if the character appears here but not at her scheduled time, the problem is
         * the alarm end of the chain, and if she does not appear here either, it is the overlay
         * grant or the window itself. That split is most of the diagnosis.
         */
        fun preview(context: Context, batch: ReminderBatch) {
            start(context, batch, preview = true)
        }

        private fun start(context: Context, batch: ReminderBatch, preview: Boolean) {
            val intent = Intent(context, ReminderOverlayService::class.java)
                .setAction(ACTION_SHOW)
                .putExtra(EXTRA_BATCH, batch.toJsonString())
                .putExtra(EXTRA_PREVIEW, preview)
            // ContextCompat, not the platform call: startForegroundService only exists from API
            // 26, and this app supports 24.
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.e(TAG, "could not start the overlay service", it) }
        }
    }
}
