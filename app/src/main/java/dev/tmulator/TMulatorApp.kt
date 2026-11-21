package dev.tmulator

import android.app.Application
import dev.tmulator.data.JobStore
import dev.tmulator.data.PrinterRuntime
import dev.tmulator.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class TMulatorApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

class AppGraph(application: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val settings = SettingsRepository(application, scope)
    val runtime = PrinterRuntime()
    val jobs = JobStore(application, scope)
}
