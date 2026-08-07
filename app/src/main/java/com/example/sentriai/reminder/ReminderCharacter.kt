package com.example.sentriai.reminder

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.annotation.SuppressLint
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.example.sentriai.R

/**
 * Loads the character, per scenario, and animates her if the asset can be animated.
 *
 * ### Why this is not just `android:src`
 *
 * Two reasons, and the first is a live hazard. [R.drawable.ai_assistant] is a 1024×1024 PNG in
 * `res/drawable/` with no density qualifier, which the platform reads as mdpi and *upscales* to
 * the device density — on a 3× screen that is a 3072×3072 bitmap, roughly 38 MB, decoded inside a
 * foreground service for a view 96dp wide. Loading it here instead means it is sampled down to
 * something near its display size on the way in.
 *
 * The second is that the scenario matters: a reminder about a tablet and a reminder about lunch are
 * different moments, and the character should not be the same frozen picture in both.
 *
 * ### Dropping in animated art
 *
 * Assets are resolved **by name at runtime**, so no code changes when they arrive. Drop either of
 * these into `res/drawable/` and it is used automatically:
 *
 * ```
 *   res/drawable/reminder_girl_medicine.webp   (or .gif, or .png)
 *   res/drawable/reminder_girl_meal.webp       (or .gif, or .png)
 * ```
 *
 * An **animated** WebP or GIF plays and loops on its own, decoded through [ImageDecoder] into an
 * [AnimatedImageDrawable]. That path needs API 28+; below it, and for a still image at any API
 * level, the frame is static and the motion in [ReminderOverlayWindow] carries the animation
 * instead. Animated WebP is worth preferring over GIF — smaller, and it has real alpha, which
 * matters for a character with no background sitting over another app.
 *
 * Until those files exist, everything falls back to the existing illustration, so this works today
 * and improves the moment art lands.
 */
internal object ReminderCharacter {

    private const val TAG = "ReminderCharacter"

    /** Resource names looked for, per scenario. Any image extension the platform can decode. */
    private const val MEDICINE_ASSET = "reminder_girl_medicine"
    private const val MEAL_ASSET = "reminder_girl_meal"

    /**
     * The character for this reminder, sized for a [targetWidthPx] × [targetHeightPx] view.
     *
     * @return null when nothing could be decoded at all, which leaves the card to carry the
     *   reminder on its own rather than failing the whole overlay for a missing picture.
     */
    fun load(
        context: Context,
        kind: ReminderKind,
        targetWidthPx: Int,
        targetHeightPx: Int,
    ): Drawable? {
        val resId = resolve(context, kind)
        if (resId == 0) return null

        val drawable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            decodeWithImageDecoder(context, resId, targetWidthPx, targetHeightPx)
        } else {
            null
        } ?: decodeSampledBitmap(context, resId, targetWidthPx, targetHeightPx)

        // An animated asset does nothing until told to run, and it should keep running for as long
        // as she is on screen rather than playing once and freezing.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && drawable is AnimatedImageDrawable) {
            drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
            drawable.start()
            Log.i(TAG, "playing animated character for $kind")
        }
        return drawable
    }

    /**
     * The scenario's asset if it has been added, otherwise the shared illustration.
     *
     * `getIdentifier` is normally worth avoiding — it is a string lookup the compiler cannot check,
     * and resource shrinking can remove what it would have found. It is the right tool here because
     * the whole point is that the asset is optional: referencing `R.drawable.reminder_girl_meal`
     * directly would not compile until somebody adds the file. Resource shrinking is off for this
     * project; if it is ever switched on, these two names need a `tools:keep`.
     */
    @SuppressLint("DiscouragedApi") // Intentional; see the KDoc above.
    private fun resolve(context: Context, kind: ReminderKind): Int {
        val name = when (kind) {
            ReminderKind.MEDICINE -> MEDICINE_ASSET
            ReminderKind.MEAL -> MEAL_ASSET
        }
        val scoped = context.resources.getIdentifier(name, "drawable", context.packageName)
        if (scoped != 0) return scoped
        return R.drawable.ai_assistant
    }

    /**
     * The modern path. Returns an [AnimatedImageDrawable] for animated WebP/GIF and a plain
     * bitmap drawable otherwise — the caller does not have to know which the asset was.
     */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun decodeWithImageDecoder(
        context: Context,
        resId: Int,
        targetWidthPx: Int,
        targetHeightPx: Int,
    ): Drawable? = runCatching {
        val source = ImageDecoder.createSource(context.resources, resId)
        ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
            decoder.setTargetSampleSize(
                sampleSize(info.size.width, info.size.height, targetWidthPx, targetHeightPx),
            )
            // Force software allocation so that the decoded image can be rendered on both software
            // and hardware canvases (solving the "Software rendering doesn't support hardware bitmaps" crash).
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            // Nothing draws into this bitmap, and an immutable one can be shared and hardware-backed.
            decoder.isMutableRequired = false
        }
    }.onFailure { Log.w(TAG, "ImageDecoder could not read the character: ${it.message}") }
        .getOrNull()

    /** The API 24-27 path, and the fallback whenever [decodeWithImageDecoder] declines. */
    private fun decodeSampledBitmap(
        context: Context,
        resId: Int,
        targetWidthPx: Int,
        targetHeightPx: Int,
    ): Drawable? = runCatching {
        // Bounds first, so the sample size is chosen before anything is allocated.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(context.resources, resId, bounds)

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetWidthPx, targetHeightPx)
            // Without this the platform pre-scales an unqualified drawable up to device density,
            // which is the 38 MB problem this class exists to avoid.
            inScaled = false
        }
        val bitmap = BitmapFactory.decodeResource(context.resources, resId, options)
            ?: return@runCatching null
        BitmapDrawable(context.resources, bitmap)
    }.onFailure { Log.e(TAG, "could not decode the character at all: ${it.message}") }
        .getOrNull()

    /**
     * The largest power-of-two reduction that still covers the target box.
     *
     * Powers of two because that is the only thing `inSampleSize` and `setTargetSampleSize`
     * honour exactly; anything else is rounded down to one anyway. Never returns less than 1, and
     * deliberately stops at "still at least as large as the view" so she is downscaled rather than
     * blurry.
     */
    private fun sampleSize(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): Int {
        if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) return 1
        var size = 1
        while (sourceWidth / (size * 2) >= targetWidth && sourceHeight / (size * 2) >= targetHeight) {
            size *= 2
        }
        return size
    }
}
