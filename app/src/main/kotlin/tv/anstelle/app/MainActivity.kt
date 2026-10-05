package tv.anstelle.app

import android.Manifest
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
import androidx.work.WorkManager
import dagger.hilt.android.AndroidEntryPoint
import tv.anstelle.app.ui.AnstelleApp
import tv.anstelle.core.common.AppLog
import tv.anstelle.core.data.WebServerController
import tv.anstelle.core.data.work.ScheduleCleanupWorker
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var webServer: WebServerController

    private var deferredWorkStarted = false

    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        results.filterValues { !it }.keys.forEach { AppLog.w(TAG, "Permission denied: $it") }
        webServer.startIfEnabled()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val isTv = isTvDevice()
        // The app is always dark, so system bar icons must be light whatever the system theme is.
        if (!isTv) {
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
                navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            )
        }
        super.onCreate(savedInstanceState)
        setContent { AnstelleApp(isTv = isTv) }

        // Everything that is not needed for the first frame starts right after it is drawn.
        window.decorView.post { startDeferredWork(isTv) }
    }

    private fun startDeferredWork(isTv: Boolean) {
        if (deferredWorkStarted) return
        deferredWorkStarted = true
        ScheduleCleanupWorker.enqueue(WorkManager.getInstance(applicationContext))

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
