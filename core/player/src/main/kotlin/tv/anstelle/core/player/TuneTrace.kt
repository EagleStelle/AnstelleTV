package tv.anstelle.core.player

import android.os.Build
import android.os.SystemClock
import android.os.Trace

/**
 * The "Tune" trace section, from the channel change to the first rendered frame. It is an
 * async section because the frame arrives in a later callback; macrobenchmarks measure it
 * with `TraceSectionMetric("Tune")`.
 */
class TuneTrace private constructor(private val cookie: Int) {
    private val startedMs = SystemClock.elapsedRealtime()
    private var open = true

    /** Ends the section and returns the time since [begin], or -1 when it already ended. */
    fun end(): Long {
        if (!open) return -1
        open = false
        if (Build.VERSION.SDK_INT >= 29) Trace.endAsyncSection(SECTION, cookie)
        return SystemClock.elapsedRealtime() - startedMs
    }

    companion object {
        const val SECTION = "Tune"
        private var nextCookie = 0

        fun begin(): TuneTrace {
            val trace = TuneTrace(++nextCookie)
            if (Build.VERSION.SDK_INT >= 29) Trace.beginAsyncSection(SECTION, trace.cookie)
            return trace
        }
    }
}
