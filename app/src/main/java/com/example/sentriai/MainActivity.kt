package com.example.sentriai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.example.sentriai.models.ModelRepository
import com.example.sentriai.reminder.ReminderScheduler
import com.example.sentriai.ui.navigation.SentriAiNavHost
import com.example.sentriai.ui.screens.ModelSetupScreen
import com.example.sentriai.ui.screens.SmsPermissionRequiredScreen
import com.example.sentriai.ui.theme.SentriAITheme

class MainActivity : ComponentActivity() {

    private var hasSmsPermission by mutableStateOf(false)

    // Seeded synchronously so a returning user never sees the setup screen flash before the
    // repository has finished looking at the filesystem.
    private var modelsReady by mutableStateOf(false)

    private val requestSmsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasSmsPermission = isGranted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        super.onCreate(savedInstanceState)

        modelsReady = ModelRepository.requiredModelsPresent(this)

        // Belt and braces on top of ReminderBootReceiver. A reboot re-arms reminders on its own,
        // but an alarm can also be lost to a force-stop or a "clear data", neither of which
        // broadcasts anything — and opening the app is the one moment that is certain to happen
        // afterwards. Idempotent: syncing replaces the pending alarm rather than adding one.
        ReminderScheduler.sync(this)
        ReminderScheduler.restoreSnoozeIfPending(this)

        setContent {
            SentriAITheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (!hasSmsPermission) {
                        SmsPermissionRequiredScreen(
                            onRequestPermission = {
                                requestSmsPermissionLauncher.launch(Manifest.permission.SEND_SMS)
                            },
                            onOpenSettings = {
                                openAppSettings()
                            }
                        )
                    } else if (!modelsReady) {
                        // Nothing downstream works without Whisper — the assistant would arm
                        // itself and then fail on the first utterance — so the setup screen
                        // stands in front of the whole nav graph rather than inside it.
                        ModelSetupScreen(onReady = { modelsReady = true })
                    } else {
                        SentriAiNavHost()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hasSmsPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)
            )
        )
    }
}
