package tv.anstelle.app

import androidx.metrics.performance.FrameData
import androidx.metrics.performance.JankStats
import tv.anstelle.core.common.AppLog

/**
 * Debug builds: counts JankStats frames and logs, every 5 seconds with drawing going on, how
 * many missed their deadline and the slowest one (`adb logcat -s Anstelle/Jank`).
 */
class JankLogger : JankStats.OnFrameListener {
    private var frames = 0
    private var janky = 0
    private var worstNs = 0L
    private var windowStartNs = 0L

    override fun onFrame(volatileFrameData: FrameData) {
        val start = volatileFrameData.frameStartNanos
        if (windowStartNs == 0L) windowStartNs = start
        frames++
        if (volatileFrameData.isJank) janky++
        worstNs = maxOf(worstNs, volatileFrameData.frameDurationUiNanos)
        if (start - windowStartNs >= REPORT_NS) {
            AppLog.i(TAG, "$janky of $frames frames janky, slowest ${worstNs / 1_000_000} ms")
            frames = 0
            janky = 0
            worstNs = 0
            windowStartNs = start
        }
    }

    private companion object {
        const val TAG = "Jank"
        const val REPORT_NS = 5_000_000_000L
    }
}
