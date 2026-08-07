package com.example.sentriai.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.sentriai.data.ProfileStore
import com.example.sentriai.model_inference.speech_to_text.TranscriptionViewModel
import com.example.sentriai.ui.screens.AiAssistantActivateScreen
import com.example.sentriai.ui.screens.AlertHistoryScreen
import com.example.sentriai.ui.screens.CompanionCallScreen
import com.example.sentriai.ui.screens.CompanionCallViewModel
import com.example.sentriai.ui.screens.DailyCompanionScreen
import com.example.sentriai.ui.screens.EmergencyScreen
import com.example.sentriai.ui.screens.ProfileSettingsScreen
import com.example.sentriai.ui.screens.TriggerLogViewModel
import com.example.sentriai.ui.screens.VoiceTranscriptScreen

/** Destinations reachable from the nav host. */
object Routes {
    const val PROFILE = "profile"
    const val AI_ASSISTANT = "ai_assistant"
    const val VOICE_TRANSCRIPT = "voice_transcript"
    const val ALERT_HISTORY = "alert_history"

    /** SOS surface. Kept as its own destination, with nothing else on it. */
    const val EMERGENCY = "emergency"

    /** Schedule + call launcher. */
    const val DAILY_COMPANION = "daily_companion"

    /** The live Agora conversation, pushed on top of whichever screen started it. */
    const val COMPANION_CALL = "companion_call"
}

/**
 * App-level navigation graph. First-run users start on the profile form and are moved
 * to the Guardian AI activation screen once they save; anyone with a completed profile
 * already stored lands on the activation screen directly. The profile chip in that
 * screen's top bar reopens the form for edits.
 */
@Composable
fun SentriAiNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val context = LocalContext.current
    // Hoisted above the NavHost so it is scoped to the activity rather than to a single
    // back stack entry: the activation and transcript screens drive the same stream, and
    // the loaded Whisper model (~200 MB) survives navigating between them.
    val transcriptionViewModel: TranscriptionViewModel = viewModel()
    // Hoisted so the home badge, transcript screen inline log, and alert history page
    // all share the same log state.
    val triggerLogViewModel: TriggerLogViewModel = viewModel()
    // Hoisted for a stronger reason than the others: this owns the Agora RTC engine, the live
    // call and — critically — the escalation latch. Scoped to a back stack entry it would be
    // destroyed on navigation, taking a call and an in-flight escalation with it, and the three
    // care screens would each see a different call.
    val companionCallViewModel: CompanionCallViewModel = viewModel()
    // Resolved once per host: NavHost only reads startDestination when it builds the
    // graph, and re-reading prefs on recomposition would have no effect anyway.
    val startDestination = remember {
        if (ProfileStore.isProfileComplete(context)) Routes.AI_ASSISTANT else Routes.PROFILE
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        composable(Routes.PROFILE) {
            ProfileSettingsScreen(
                onProfileSaved = {
                    navController.navigate(Routes.AI_ASSISTANT) {
                        // The form is a one-time setup step — don't leave it on the back stack.
                        popUpTo(Routes.PROFILE) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }

        composable(Routes.AI_ASSISTANT) {
            AiAssistantActivateScreen(
                onProfileClick = { navController.navigate(Routes.PROFILE) },
                onTranscriptionStarted = {
                    navController.navigate(Routes.VOICE_TRANSCRIPT) { launchSingleTop = true }
                },
                onAlertHistoryClick = {
                    navController.navigate(Routes.ALERT_HISTORY) { launchSingleTop = true }
                },
                onEmergencyClick = {
                    navController.navigate(Routes.EMERGENCY) { launchSingleTop = true }
                },
                onDailyCompanionClick = {
                    navController.navigate(Routes.DAILY_COMPANION) { launchSingleTop = true }
                },
                viewModel = transcriptionViewModel,
                triggerLogViewModel = triggerLogViewModel,
            )
        }

        composable(Routes.VOICE_TRANSCRIPT) {
            VoiceTranscriptScreen(
                // Back leaves the stream running — the assistant stays armed and the
                // activation screen keeps showing it as active.
                onBack = { navController.popBackStack() },
                onProfileClick = { navController.navigate(Routes.PROFILE) },
                viewModel = transcriptionViewModel,
                triggerLogViewModel = triggerLogViewModel,
            )
        }

        composable(Routes.ALERT_HISTORY) {
            AlertHistoryScreen(
                onBack = { navController.popBackStack() },
                onProfileClick = { navController.navigate(Routes.PROFILE) },
                triggerLogViewModel = triggerLogViewModel,
            )
        }

        composable(Routes.EMERGENCY) {
            EmergencyScreen(
                onBack = { navController.popBackStack() },
                onViewAlertHistory = {
                    // Refreshed first so an alert raised seconds ago by the SOS button is
                    // already in the list when the page opens.
                    triggerLogViewModel.refresh()
                    navController.navigate(Routes.ALERT_HISTORY) { launchSingleTop = true }
                },
                onCallStarted = {
                    navController.navigate(Routes.COMPANION_CALL) { launchSingleTop = true }
                },
                viewModel = companionCallViewModel,
            )
        }

        composable(Routes.DAILY_COMPANION) {
            DailyCompanionScreen(
                onBack = { navController.popBackStack() },
                onCallStarted = {
                    navController.navigate(Routes.COMPANION_CALL) { launchSingleTop = true }
                },
                viewModel = companionCallViewModel,
            )
        }

        composable(Routes.COMPANION_CALL) {
            CompanionCallScreen(
                // Popping rather than navigating: the call screen sits on top of whichever
                // screen started the call, so ending one returns there.
                onCallFinished = {
                    if (navController.currentDestination?.route == Routes.COMPANION_CALL) {
                        navController.popBackStack()
                    }
                    // An emergency raised during the call is now in the log.
                    triggerLogViewModel.refresh()
                },
                viewModel = companionCallViewModel,
            )
        }
    }
}
