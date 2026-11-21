package dev.tmulator

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import dev.tmulator.ui.PrinterViewModel
import dev.tmulator.ui.TMulatorRoot
import dev.tmulator.ui.theme.TMulatorTheme

class MainActivity : ComponentActivity() {
    private val viewModel: PrinterViewModel by viewModels()
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TMulatorTheme {
                TMulatorRoot(viewModel)
            }
        }
        maybeAskForNotifications()
    }

    private fun maybeAskForNotifications() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val prefs = getSharedPreferences("tmulator", MODE_PRIVATE)
        if (prefs.getBoolean("notification_prompted", false)) return
        prefs.edit().putBoolean("notification_prompted", true).apply()
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
