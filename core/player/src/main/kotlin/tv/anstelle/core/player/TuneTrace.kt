package tv.anstelle.core.player

import android.os.Build
import android.os.SystemClock
import android.os.Trace
import tv.anstelle.core.common.TuneTraceNames
import java.util.concurrent.atomic.AtomicInteger

/**
 * The "Tune" trace section, from the channel change to the first rendered frame. It is an
 * async section because the frame arrives in a later callback; macrobenchmarks select its
 * successful attempts with `RenderedTuneMetric`.
 * Aborted attempts are closed too; successful attempts contain a [RENDERED] marker so
 * benchmarks can exclude cancellation, errors, restricted programs and off-air channels.
 */
class TuneTrace private constructor(private val cookie: Int, private val section: String) {
    private val startedMs = SystemClock.elapsedRealtime()
    private var open = true

    /** Ends the section and returns the time since [begin], or -1 when it already ended. */
    fun end(): Long {
        if (!open) return -1
        open = false
        if (Build.VERSION.SDK_INT >= 29) Trace.endAsyncSection(section, cookie)
        return SystemClock.elapsedRealtime() - startedMs
    }

    /** Marks a real first frame before closing its attempt. Never marks an already closed trace. */
    fun rendered(): Long {
        if (!open) return -1
        Trace.beginSection(RENDERED)
        Trace.endSection()
        return end()
    }

    companion object {
        const val SECTION = TuneTraceNames.SECTION
        const val RENDERED = TuneTraceNames.RENDERED
        private val nextCookie = AtomicInteger()

        fun begin(section: String = SECTION): TuneTrace {
            val trace = TuneTrace(nextCookie.incrementAndGet(), section)
            if (Build.VERSION.SDK_INT >= 29) Trace.beginAsyncSection(section, trace.cookie)
            return trace
        }
    }
}
