package tv.anstelle.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.metrics.performance.JankStats
import androidx.work.WorkManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tv.anstelle.app.ui.AnstelleApp
import tv.anstelle.core.common.AppLog
import tv.anstelle.core.data.WebServerController
import tv.anstelle.core.data.backup.RestoreSignal
import tv.anstelle.core.data.work.LibrarySyncWorker
import tv.anstelle.core.data.work.ScheduleCleanupWorker
import tv.anstelle.core.data.work.ScheduleWorker
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var webServer: WebServerController
    @Inject lateinit var restores: RestoreSignal

    private var deferredWorkStarted = false

    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        results.filterValues { !it }.keys.forEach { AppLog.w(TAG, "Permission denied: $it") }
        webServer.startIfEnabled()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val isTv = isTvDevice()
        // System bar icons follow the theme's ground, whatever the system theme is: dark themes
        // come first, so they start light.
        if (!isTv) systemBars(light = false)
        super.onCreate(savedInstanceState)
        setContent { AnstelleApp(isTv = isTv, onLightTheme = { light -> if (!isTv) systemBars(light) }) }
        if (BuildConfig.DEBUG) JankStats.createAndTrack(window, JankLogger())

        // Everything that is not needed for the first frame starts right after it is drawn.
        window.decorView.post { startDeferredWork(isTv) }

        // A restored backup replaced every profile and channel: the app starts over on it, every
        // screen and cache built anew, and the launch picker asks who's watching.
        lifecycleScope.launch {
            restores.restored.first { it }
            startActivity(Intent(this@MainActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            Runtime.getRuntime().exit(0)
        }
    }

    private fun systemBars(light: Boolean) {
        val style = if (light) SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT) else SystemBarStyle.dark(Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    private fun startDeferredWork(isTv: Boolean) {
        if (deferredWorkStarted) return
        deferredWorkStarted = true
        val workManager = WorkManager.getInstance(applicationContext)
        ScheduleCleanupWorker.enqueue(workManager)
        LibrarySyncWorker.schedulePeriodic(workManager)
        ScheduleWorker.schedulePeriodic(workManager)
        // Schedules may have run short while the app was closed.
        ScheduleWorker.runNow(workManager)

        val missing = buildList {
            // Android 17+: reaching Jellyfin on the LAN and serving the web UI need this.
            if (Build.VERSION.SDK_INT >= 37) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
            // Phones show the web-server PIN in a notification while the app is in the background.
            if (Build.VERSION.SDK_INT >= 33 && !isTv) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

        if (missing.isEmpty()) {
            webServer.startIfEnabled()
        } else {
            permissionRequest.launch(missing.toTypedArray())
        }
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
