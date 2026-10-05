package tv.anstelle.app

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.provider.Settings
import androidx.core.content.getSystemService

/** True on Android TV, Google TV and Fire TV. Decides D-pad-first TV layouts versus phone layouts. */
fun Context.isTvDevice(): Boolean {
    val uiMode = getSystemService<UiModeManager>()?.currentModeType
    return uiMode == Configuration.UI_MODE_TYPE_TELEVISION ||
        packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
        packageManager.hasSystemFeature(FIRE_TV_FEATURE)
}

/** Name shown to browsers while pairing, e.g. "Living Room TV". */
fun Context.deviceDisplayName(): String =
    runCatching { Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: Build.MODEL

private const val FIRE_TV_FEATURE = "amazon.hardware.fire_tv"
