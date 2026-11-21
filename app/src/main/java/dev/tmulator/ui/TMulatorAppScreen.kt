package dev.tmulator.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tmulator.util.ShareUtils
import kotlinx.coroutines.launch

private enum class AppTab(val label: String) {
    PRINTER("Printer"),
    JOBS("Jobs"),
    DEBUG("Debug"),
    SETTINGS("Settings"),
}

@Composable
fun TMulatorRoot(viewModel: PrinterViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val runtime by viewModel.runtime.collectAsStateWithLifecycle()
    val jobs by viewModel.jobs.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(AppTab.PRINTER) }
    var selectedJobId by rememberSaveable { mutableStateOf<Long?>(null) }

    fun notify(message: String) {
        scope.launch { snackbar.showSnackbar(message, withDismissAction = true) }
    }

    LaunchedEffect(Unit) { viewModel.ensureServiceRunning() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.ensureServiceRunning() }

    BackHandler(enabled = selectedJobId != null || tab != AppTab.PRINTER) {
        if (selectedJobId != null) selectedJobId = null else tab = AppTab.PRINTER
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Column(Modifier.statusBarsPadding()) {
                    AppHeader(
                        modelName = runtime.profile.displayName,
                        network = runtime.network,
                        bluetooth = runtime.bluetooth,
                    )
                }
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.background,
                tonalElevation = 0.dp,
            ) {
                AppTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = {
                            tab = item
                            selectedJobId = null
                        },
                        icon = {
                            Icon(item.icon(), contentDescription = item.label, modifier = Modifier.size(20.dp))
                        },
                        label = { Text(item.label) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = Color.Transparent,
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val test: () -> Unit = {
            if (!settings.networkEnabled) {
                notify("Enable network emulation first")
            } else {
                viewModel.testReceipt()
                tab = AppTab.PRINTER
                selectedJobId = null
                notify("Test receipt requested")
            }
        }
        val shareCurrent: () -> Unit = {
            val snapshot = viewModel.graph.runtime.latestSnapshot
            if (snapshot == null) {
                notify("No receipt to share")
            } else {
                scope.launch {
                    ShareUtils.shareSnapshot(context, snapshot).onFailure {
                        notify("Could not prepare receipt: ${it.message ?: "unknown error"}")
                    }
                }
            }
        }

        AnimatedContent(
            targetState = tab to selectedJobId,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
            label = "screen",
            modifier = Modifier.fillMaxSize().padding(padding),
        ) { (targetTab, jobId) ->
            when (targetTab) {
                AppTab.PRINTER -> HomeScreen(
                    settings = settings,
                    runtime = runtime,
                    jobCount = jobs.size,
                    onClear = {
                        viewModel.clearReceipt()
                        notify("Receipt cleared")
                    },
                    onTest = test,
                    onShare = shareCurrent,
                    onOpenJobs = { tab = AppTab.JOBS },
                    onOpenSettings = { tab = AppTab.SETTINGS },
                    onRestartNetwork = {
                        viewModel.restartNetwork()
                        notify("Restarting network listener")
                    },
                    onSnackbar = ::notify,
                )
                AppTab.JOBS -> {
                    val job = jobs.firstOrNull { it.id == jobId }
                    if (job != null) {
                        JobDetailScreen(
                            job = job,
                            onBack = { selectedJobId = null },
                            onDelete = { viewModel.deleteJob(it) },
                            onSnackbar = ::notify,
                        )
                    } else {
                        JobsScreen(
                            jobs = jobs,
                            canTest = settings.networkEnabled,
                            onOpenJob = { selectedJobId = it.id },
                            onDeleteJob = { viewModel.deleteJob(it) },
                            onClearHistory = { viewModel.clearHistory() },
                            onTest = test,
                            onSnackbar = ::notify,
                        )
                    }
                }
                AppTab.DEBUG -> DebugScreen(
                    runtime = runtime,
                    onTest = test,
                    onSnackbar = ::notify,
                    clearLogs = { viewModel.clearLogs() },
                )
                AppTab.SETTINGS -> SettingsScreen(
                    settings = settings,
                    runtime = runtime,
                    viewModel = viewModel,
                    onSnackbar = ::notify,
                )
            }
        }
    }
}

private fun AppTab.icon() = when (this) {
    AppTab.PRINTER -> Icons.Rounded.Print
    AppTab.JOBS -> Icons.Rounded.ReceiptLong
    AppTab.DEBUG -> Icons.Rounded.Terminal
    AppTab.SETTINGS -> Icons.Rounded.Settings
}
