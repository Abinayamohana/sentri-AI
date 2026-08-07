package com.example.sentriai.reminder

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.sentriai.R
import com.example.sentriai.care.CareBriefing
import com.example.sentriai.data.ProfileStore
import java.util.Calendar

class ReminderActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var batch: ReminderBatch? = null
    private var voice: ReminderVoice? = null
    private var idle: ObjectAnimator? = null
    private var dismissing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Setup flags to show when locked and turn screen on
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        
        // Keep screen on while showing
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Read batch from intent
        val jsonStr = intent.getStringExtra(EXTRA_BATCH_JSON)
        val incoming = ReminderBatch.fromJsonStringOrNull(jsonStr)
        if (incoming == null || incoming.items.isEmpty()) {
            Log.w(TAG, "invalid or empty batch, finishing activity")
            finish()
            return
        }
        batch = incoming

        setContentView(R.layout.overlay_reminder)

        // Center the reminder card and character on the screen
        findViewById<LinearLayout>(R.id.reminder_root).apply {
            layoutParams.height = WindowManager.LayoutParams.MATCH_PARENT
            gravity = android.view.Gravity.CENTER
        }

        bind(incoming)
        
        // Backstop timeout: close activity if left untouched
        handler.postDelayed({ leaveOnOwn() }, VISIBLE_TIMEOUT_MS)
        
        animateIn(incoming)
    }

    private fun bind(batch: ReminderBatch) {
        val kinds = batch.items.map { it.kind }.toSet()
        val kindLabel = getString(
            when {
                kinds == setOf(ReminderKind.MEDICINE) -> R.string.reminder_heading_medicine
                kinds == setOf(ReminderKind.MEAL) -> R.string.reminder_heading_meal
                else -> R.string.reminder_heading_mixed
            },
        )
        findViewById<TextView>(R.id.reminder_heading).text =
            getString(R.string.reminder_heading_format, kindLabel, CareBriefing.clock(batch.minutes))

        val container = findViewById<LinearLayout>(R.id.reminder_items)
        container.removeAllViews()
        val inflater = layoutInflater
        batch.items.forEach { item ->
            val row = inflater.inflate(R.layout.overlay_reminder_item, container, false)
            row.findViewById<TextView>(R.id.item_title).text = item.title
            row.findViewById<TextView>(R.id.item_detail).apply {
                text = item.detail
                visibility = if (item.detail.isBlank()) View.GONE else View.VISIBLE
            }
            container.addView(row)
        }

        findViewById<TextView>(R.id.reminder_snooze).apply {
            text = getString(R.string.reminder_snooze, ReminderSettings.SNOOZE_MINUTES)
            setOnClickListener { if (!dismissing) handleSnooze() }
        }
        findViewById<TextView>(R.id.reminder_stop).setOnClickListener {
            if (!dismissing) handleStop()
        }

        bindCharacter(batch)
    }

    private fun bindCharacter(batch: ReminderBatch) {
        val character = findViewById<ImageView>(R.id.reminder_character)
        val params = character.layoutParams
        val drawable = ReminderCharacter.load(
            context = this,
            kind = dominantKind(batch),
            targetWidthPx = params.width.takeIf { it > 0 } ?: dp(120f).toInt(),
            targetHeightPx = params.height.takeIf { it > 0 } ?: dp(160f).toInt(),
        )

        if (drawable == null) {
            character.visibility = View.GONE
            return
        }
        character.visibility = View.VISIBLE
        character.setImageDrawable(drawable)
    }

    private fun dominantKind(batch: ReminderBatch): ReminderKind =
        if (batch.items.any { it.kind == ReminderKind.MEDICINE }) {
            ReminderKind.MEDICINE
        } else {
            ReminderKind.MEAL
        }

    private fun motionFor(batch: ReminderBatch): CharacterMotion =
        when (dominantKind(batch)) {
            ReminderKind.MEAL -> CharacterMotion(wobbleDegrees = 13f, bobMillis = 1_150L, bobDp = 4f)
            ReminderKind.MEDICINE -> CharacterMotion(wobbleDegrees = 8f, bobMillis = 1_650L, bobDp = 3f)
        }

    private data class CharacterMotion(
        val wobbleDegrees: Float,
        val bobMillis: Long,
        val bobDp: Float,
    )

    private fun animateIn(batch: ReminderBatch) {
        val character = findViewById<ImageView>(R.id.reminder_character)
        val card = findViewById<View>(R.id.reminder_card)
        val motion = motionFor(batch)

        character.alpha = 0f
        card.alpha = 0f
        card.translationY = dp(22f)

        window.decorView.post {
            val (startX, startY) = offscreenStart(character)
            character.translationX = startX
            character.translationY = startY
            character.alpha = 1f

            ObjectAnimator.ofFloat(
                character,
                View.TRANSLATION_X,
                View.TRANSLATION_Y,
                entrancePath(startX, startY),
            ).apply {
                duration = ENTRANCE_MS
                interpolator = DecelerateInterpolator(1.7f)
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (!dismissing) {
                            startIdle(character, motion)
                            speak()
                        }
                    }
                })
            }.start()

            val wobble = motion.wobbleDegrees
            ObjectAnimator.ofFloat(
                character,
                View.ROTATION,
                -wobble,
                wobble * 0.5f,
                -wobble * 0.2f,
                0f,
            ).apply {
                duration = ENTRANCE_MS
                interpolator = DecelerateInterpolator()
            }.start()

            card.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(CARD_DELAY_MS)
                .setDuration(CARD_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun startIdle(character: View, motion: CharacterMotion) {
        idle?.cancel()
        idle = ObjectAnimator.ofFloat(character, View.TRANSLATION_Y, 0f, -dp(motion.bobDp)).apply {
            duration = motion.bobMillis
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun offscreenStart(character: View): Pair<Float, Float> {
        val width = character.width.takeIf { it > 0 }?.toFloat() ?: dp(120f)
        val height = character.height.takeIf { it > 0 }?.toFloat() ?: dp(160f)
        return (width + dp(28f)) to (height * 0.75f)
    }

    private fun entrancePath(startX: Float, startY: Float): Path = Path().apply {
        moveTo(startX, startY)
        quadTo(startX * 0.4f, -dp(16f), 0f, 0f)
    }

    private fun exitPath(startX: Float, startY: Float): Path = Path().apply {
        moveTo(0f, 0f)
        quadTo(startX * 0.4f, -dp(10f), startX, startY)
    }

    private fun speak() {
        val target = batch ?: return
        val line = ReminderSpeech.line(target, personName())
        
        voice?.shutdown()
        val voiceInstance = ReminderVoice(this, onFinished = ::onSpeechFinished).also { this.voice = it }

        if (line.isBlank() || !voiceInstance.canSpeakAloud()) {
            handler.postDelayed({ leaveOnOwn() }, SILENT_DWELL_MS)
            return
        }
        
        voiceInstance.speak(line)
    }

    private fun onSpeechFinished() {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ leaveOnOwn() }, LINGER_AFTER_SPEECH_MS)
    }

    private fun leaveOnOwn() {
        Log.i(TAG, "reminder unanswered; leaving a notification behind")
        batch?.let { ReminderNotifications.postAlert(this, it) }
        dismissThenFinish()
    }

    private fun handleStop() {
        Log.i(TAG, "reminder stopped")
        ReminderScheduler.cancelSnooze(this)
        ReminderNotifications.cancelAlert(this)
        dismissThenFinish()
    }

    private fun handleSnooze() {
        val target = batch ?: return
        Log.i(TAG, "reminder snoozed")
        ReminderScheduler.snooze(this, target)
        ReminderNotifications.cancelAlert(this)
        dismissThenFinish()
    }

    private fun dismissThenFinish() {
        if (dismissing) return
        dismissing = true

        idle?.cancel()
        idle = null
        
        // Stop background service
        stopService(Intent(this, ReminderOverlayService::class.java))

        val character = findViewById<ImageView>(R.id.reminder_character)
        val card = findViewById<View>(R.id.reminder_card)
        character.translationY = 0f

        val (startX, startY) = offscreenStart(character)

        card.animate()
            .alpha(0f)
            .translationY(dp(12f))
            .setStartDelay(0)
            .setDuration(EXIT_MS / 2)
            .start()

        ObjectAnimator.ofFloat(
            character,
            View.TRANSLATION_X,
            View.TRANSLATION_Y,
            exitPath(startX, startY),
        ).apply {
            duration = EXIT_MS
            interpolator = AccelerateInterpolator(1.4f)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    finish()
                }
            })
        }.start()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        voice?.shutdown()
        voice = null
        super.onDestroy()
    }

    private fun personName(): String =
        ProfileStore.preferences(this)
            .getString(ProfileStore.KEY_FULL_NAME, "")
            ?.trim()
            .orEmpty()

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    companion object {
        private const val TAG = "ReminderActivity"
        const val EXTRA_BATCH_JSON = "batch_json"
        
        private const val ENTRANCE_MS = 520L
        private const val EXIT_MS = 300L
        private const val CARD_DELAY_MS = 130L
        private const val CARD_MS = 300L
        
        private const val LINGER_AFTER_SPEECH_MS = 5_000L
        private const val SILENT_DWELL_MS = 9_000L
        private const val VISIBLE_TIMEOUT_MS = 2 * 60_000L
    }
}
