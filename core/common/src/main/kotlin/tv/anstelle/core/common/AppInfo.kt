package tv.anstelle.core.common

/** Facts about the running app and device, provided by the app module. */
data class AppInfo(
    val appName: String,
    val versionName: String,
    val versionCode: Long,
    /** Human-readable device name shown to browsers while pairing. */
    val deviceName: String,
    val isTv: Boolean,
    val isDebug: Boolean,
)
