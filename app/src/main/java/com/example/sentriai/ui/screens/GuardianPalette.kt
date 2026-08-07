package com.example.sentriai.ui.screens

import androidx.compose.ui.graphics.Color

/**
 * Palette shared by the Guardian AI surfaces (activation + voice transcript) —
 * intentionally light-only, as designed.
 */
internal val PageBackground = Color(0xFFF2F6FD)
internal val TopBarBackground = Color(0xFFFFFFFF)
internal val CardBackground = Color(0xFFFFFFFF)
internal val NavyInk = Color(0xFF111A32)
internal val AccentBlue = Color(0xFF2563EB)
internal val SoftBlueContainer = Color(0xFFE9F0FE)
internal val MutedText = Color(0xFF64748B)
internal val HaloRing = Color(0xFFE4EBF8)
internal val CardBorder = Color(0xFFEDF1F9)

/** Power button states: slate grey while off, green once armed. */
internal val PowerOffGrey = Color(0xFF64748B)
internal val PowerOnGreen = Color(0xFF15803D)

/** Destructive/attention accents: the stop pill and error copy. */
internal val StopRed = Color(0xFFDC2626)
internal val ErrorRed = Color(0xFFB91C1C)

/**
 * Emergency screen. Held apart from the Guardian blues on purpose: the SOS surface should not
 * look like the rest of the app, so a frightened person can find it without reading anything.
 */
internal val SosRed = Color(0xFFD32029)
internal val SosRedPressed = Color(0xFF9F1218)
internal val SosHalo = Color(0xFFFBE3E4)
internal val EmergencyBackground = Color(0xFFFFF7F7)

/** Daily Companion accents: taken doses, meal rows, food-relation chips. */
internal val CalmGreen = Color(0xFF15803D)
internal val CalmGreenContainer = Color(0xFFE7F6EC)
internal val WarmAmber = Color(0xFFB45309)
internal val WarmAmberContainer = Color(0xFFFEF3C7)
