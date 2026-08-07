package com.example.sentriai.reminder

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.sentriai.R
import com.example.sentriai.care.CareBriefing

/**
 * The overlay window itself: adds it, animates the character in, and takes it away again.
 *
 * ### Why the window is a bottom strip
 *
 * `match_parent` × `wrap_content` at [Gravity.BOTTOM], not full screen. A full-screen overlay
 * covers the app underneath even when most of it is transparent, and every touch that lands on
 * the empty part is swallowed. A strip only occupies the space the card actually needs, so the
 * person can keep using whatever they were doing while the reminder waits.
 *
 * [WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE] is what keeps the phone behaving normally:
 * without it this window takes key input, and back and the home gesture start going somewhere
 * unexpected. It does not affect touch, so the two buttons still work.
 *
 * ### The entrance
 *
 * The character starts translated past the bottom-right corner of the strip — outside the window,
 * so it is clipped and invisible — and animates to its resting position along a curve rather than
 * a straight line. One [ObjectAnimator] over a [Path] drives `translationX` and `translationY`
 * together; a control point above the line makes it rise and settle instead of sliding, which is
 * what reads as a character arriving rather than a bitmap moving. A small rotation wobble runs
 * alongside it for the same reason.
 *
 * The asset is a single static illustration, so this is the translation-only version of that
 * idea. If walk frames or a Lottie file ever exist, the frame animation goes on the same
 * [ImageView] and the translation below is unchanged — the two are independent.
 */
internal class ReminderOverlayWindow(
    private val context: Context,
    private val onStop: () -> Unit,
    private val onSnooze: () -> Unit,
) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

    private var root: View? = null

    /** The infinite idle bob. Held so it can be cancelled — see [startIdle]. */
    private var idle: ObjectAnimator? = null

    /** Guards against Stop and Snooze both landing during the exit animation. */
    private var dismissing = false

    val isShowing: Boolean get() = root != null

    /**
     * Adds the window and plays the entrance.
     *
     * @return false when the window could not be added at all — the overlay grant is the usual
     *   reason, and the caller falls back to a notification.
     */
    @SuppressLint("InflateParams")
    fun show(batch: ReminderBatch): Boolean {
        val manager = windowManager ?: return false
        if (root != null) remove()

        // Inflating and binding are inside the guard along with addView. Both touch resources and
        // decode a bitmap, so both can throw — and an exception escaping here would propagate out
        // of Service.onStartCommand and take the process down, turning a missing picture into a
        // crash with no reminder at all.
        val view = runCatching {
            // A null root is correct here and not the usual mistake: this view's parent is the
            // WindowManager, so its layout params come from layoutParams() below rather than from
            // any ViewGroup.
            LayoutInflater.from(context).inflate(R.layout.overlay_reminder, null)
                .also { bind(it, batch) }
        }.onFailure { Log.e(TAG, "could not build the overlay", it) }.getOrNull() ?: return false

        val added = runCatching { manager.addView(view, layoutParams()) }
            .onFailure { Log.e(TAG, "could not add the overlay window", it) }
            .isSuccess
        if (!added) return false

        root = view
        dismissing = false
        animateIn(view, motionFor(batch))
        return true
    }

    /**
     * Plays the exit and then removes the window, calling [onRemoved] once it is gone.
     *
     * The reverse of the entrance rather than a fade: the character leaves the way it came, which
     * makes a dismissed reminder feel finished instead of interrupted.
     */
    fun dismiss(onRemoved: () -> Unit) {
        val view = root
        if (view == null || dismissing) {
            onRemoved()
            return
        }
        dismissing = true

        val character = view.findViewById<ImageView>(R.id.reminder_character)
        val card = view.findViewById<View>(R.id.reminder_card)

        // The idle bob also owns translationY, so it has to stop before the exit path takes it
        // over — and be zeroed, or she leaves from wherever mid-bob happened to be.
        idle?.cancel()
        idle = null
        character.translationY = 0f

        val (startX, startY) = offscreenStart(character)

        // setStartDelay(0) is not redundant: this is the same ViewPropertyAnimator the entrance
        // used, and it would otherwise still be carrying CARD_DELAY_MS.
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
                    remove()
                    onRemoved()
                }
            })
        }.start()
    }

    /** Takes the window away immediately, with no animation. Safe to call more than once. */
    fun remove() {
        idle?.cancel()
        idle = null
        val view = root ?: return
        root = null
        runCatching { windowManager?.removeView(view) }
            .onFailure { Log.w(TAG, "overlay already gone: ${it.message}") }
    }

    // ---- binding ------------------------------------------------------------------

    private fun bind(view: View, batch: ReminderBatch) {
        val kinds = batch.items.map { it.kind }.toSet()
        val kindLabel = context.getString(
            when {
                kinds == setOf(ReminderKind.MEDICINE) -> R.string.reminder_heading_medicine
                kinds == setOf(ReminderKind.MEAL) -> R.string.reminder_heading_meal
                // A dose due at a meal time is one moment with two instructions in it; naming
                // either kind alone would describe half the card.
                else -> R.string.reminder_heading_mixed
            },
        )
        view.findViewById<TextView>(R.id.reminder_heading).text =
            context.getString(R.string.reminder_heading_format, kindLabel, CareBriefing.clock(batch.minutes))

        val container = view.findViewById<LinearLayout>(R.id.reminder_items)
        container.removeAllViews()
        val inflater = LayoutInflater.from(context)
        batch.items.forEach { item ->
            val row = inflater.inflate(R.layout.overlay_reminder_item, container, false)
            row.findViewById<TextView>(R.id.item_title).text = item.title
            row.findViewById<TextView>(R.id.item_detail).apply {
                text = item.detail
                visibility = if (item.detail.isBlank()) View.GONE else View.VISIBLE
            }
            container.addView(row)
        }

        // Both handlers fire once and then go dead: the buttons stay on screen through the exit
        // animation, and a second tap during it would snooze a reminder that was already stopped.
        view.findViewById<TextView>(R.id.reminder_snooze).apply {
            text = context.getString(R.string.reminder_snooze, ReminderSettings.SNOOZE_MINUTES)
            setOnClickListener { if (!dismissing) onSnooze() }
        }
        view.findViewById<TextView>(R.id.reminder_stop).setOnClickListener {
            if (!dismissing) onStop()
        }

        bindCharacter(view, batch)
    }

    /**
     * Loads the scenario's character into the [ImageView], sized to the slot it will occupy.
     *
     * The target size comes from the view's own layout params rather than a constant, because they
     * are already populated from the XML at this point — one place to change the size instead of a
     * dimension in the layout and a matching number over here that drifts from it.
     *
     * A character that will not load hides its view rather than leaving an empty gap, and the card
     * takes the full width. The reminder is the words; she is how they arrive.
     */
    private fun bindCharacter(view: View, batch: ReminderBatch) {
        val character = view.findViewById<ImageView>(R.id.reminder_character)
        val params = character.layoutParams
        val drawable = ReminderCharacter.load(
            context = context,
            kind = dominantKind(batch),
            targetWidthPx = params.width.takeIf { it > 0 } ?: dp(120f).toInt(),
            targetHeightPx = params.height.takeIf { it > 0 } ?: dp(160f).toInt(),
        )

        if (drawable == null) {
            Log.w(TAG, "no character to show; the card carries the reminder alone")
            character.visibility = View.GONE
            return
        }
        character.visibility = View.VISIBLE
        character.setImageDrawable(drawable)
    }

    /**
     * Which character a mixed batch gets.
     *
     * Medicine wins. A dose due at a meal time is the more consequential half of that moment, and
     * one of the two has to choose the picture.
     */
    private fun dominantKind(batch: ReminderBatch): ReminderKind =
        if (batch.items.any { it.kind == ReminderKind.MEDICINE }) {
            ReminderKind.MEDICINE
        } else {
            ReminderKind.MEAL
        }

    /**
     * How lively she is, by scenario.
     *
     * Same illustration, different bearing — a meal is a cheerful thing to be called to and a dose
     * is not, and the difference costs nothing but two numbers. When per-scenario animated art
     * lands (see [ReminderCharacter]) this keeps working underneath it: the asset plays its own
     * frames while these move the whole figure.
     */
    private fun motionFor(batch: ReminderBatch): CharacterMotion =
        when (dominantKind(batch)) {
            // Bouncier arrival, quicker bob: being called to the table.
            ReminderKind.MEAL -> CharacterMotion(wobbleDegrees = 13f, bobMillis = 1_150L, bobDp = 4f)
            // Calmer, slower. This one is asking something of the person.
            ReminderKind.MEDICINE -> CharacterMotion(wobbleDegrees = 8f, bobMillis = 1_650L, bobDp = 3f)
        }

    /** @see motionFor */
    private data class CharacterMotion(
        val wobbleDegrees: Float,
        val bobMillis: Long,
        val bobDp: Float,
    )

    // ---- animation ----------------------------------------------------------------

    private fun animateIn(view: View, motion: CharacterMotion) {
        val character = view.findViewById<ImageView>(R.id.reminder_character)
        val card = view.findViewById<View>(R.id.reminder_card)

        // Hidden until the first layout pass, because the off-screen start position is derived
        // from the character's measured size. Without this it renders once at its resting spot.
        character.alpha = 0f
        card.alpha = 0f
        card.translationY = dp(22f)

        view.post {
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
                // The idle only starts once she has actually arrived — the two animate the same
                // property, and overlapping them would fight over translationY.
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (!dismissing) startIdle(character, motion)
                    }
                })
            }.start()

            // The wobble. Small on purpose — enough to suggest weight landing, not enough to
            // read as the image being crooked.
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

            // Staggered behind the character, so the eye follows the arrival to the card rather
            // than having both land at once.
            card.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(CARD_DELAY_MS)
                .setDuration(CARD_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    /**
     * The breathing bob she keeps up for the whole visit.
     *
     * Without it she is a photograph pasted over the screen — which is especially wrong while she
     * is talking, since a still image with a voice coming out of it reads as broken rather than
     * quiet. A couple of dp is enough; this sits next to text that must stay readable.
     *
     * Runs until [dismiss] or [remove] stops it. An infinite animator on a window that has gone
     * away is a leak, so both of those cancel it.
     */
    private fun startIdle(character: View, motion: CharacterMotion) {
        idle?.cancel()
        idle = ObjectAnimator.ofFloat(character, View.TRANSLATION_Y, 0f, -dp(motion.bobDp)).apply {
            duration = motion.bobMillis
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            // Ease at both ends, so it reads as breathing rather than as a mechanical shuttle.
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    /**
     * Where the character sits when it is off screen: one full width to the right of its resting
     * spot, and most of its height below it. Both are outside the strip, which clips them.
     */
    private fun offscreenStart(character: View): Pair<Float, Float> {
        val width = character.width.takeIf { it > 0 }?.toFloat() ?: dp(120f)
        val height = character.height.takeIf { it > 0 }?.toFloat() ?: dp(160f)
        return (width + dp(28f)) to (height * 0.75f)
    }

    private fun entrancePath(startX: Float, startY: Float): Path = Path().apply {
        moveTo(startX, startY)
        // The control point is above the straight line between the two ends, so the path bows
        // upward: the character comes up over the corner and drops onto its resting position.
        quadTo(startX * 0.4f, -dp(16f), 0f, 0f)
    }

    private fun exitPath(startX: Float, startY: Float): Path = Path().apply {
        moveTo(0f, 0f)
        quadTo(startX * 0.4f, -dp(10f), startX, startY)
    }

    // ---- window plumbing ----------------------------------------------------------

    private fun layoutParams(): WindowManager.LayoutParams = WindowManager.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        overlayType(),
        // NOT_FOCUSABLE keeps key input — back, home, the IME — with whatever is underneath.
        // Touch is unaffected, so the buttons still receive taps.
        // HARDWARE_ACCELERATED is not optional here: an overlay window is not accelerated by
        // default, and the entrance animation visibly stutters without it.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.START
    }

    @Suppress("DEPRECATION")
    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            // TYPE_PHONE is the pre-Oreo equivalent and the only option on API 24-25.
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun dp(value: Float): Float = value * context.resources.displayMetrics.density

    companion object {
        private const val TAG = "ReminderOverlay"

        /**
         * Long enough to register as an entrance, short enough not to feel like a wait. Below
         * ~350 ms the arrival reads as a flicker; past ~700 ms the person is watching an
         * animation instead of reading a reminder.
         *
         * Public because the service waits this out before speaking — she finishes arriving, then
         * talks.
         */
        const val ENTRANCE_MS = 520L

        private const val EXIT_MS = 300L
        private const val CARD_DELAY_MS = 130L
        private const val CARD_MS = 300L
    }
}
