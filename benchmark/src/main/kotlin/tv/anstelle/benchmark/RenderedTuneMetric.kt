package tv.anstelle.benchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.TraceMetric
import androidx.benchmark.traceprocessor.TraceProcessor
import tv.anstelle.core.common.TuneTraceNames

/** A closed attempt is not necessarily a rendered channel: canceled tunes never count as fast successes. */
@OptIn(ExperimentalMetricApi::class)
internal class RenderedTuneMetric(private val expectedFrames: Int) : TraceMetric() {
    override fun getMeasurements(captureInfo: CaptureInfo, traceSession: TraceProcessor.Session): List<Measurement> {
        val times = traceSession.query(
            """
            SELECT attempt.dur / 1000000.0 AS ms
            FROM slice attempt
            JOIN process_track track ON track.id = attempt.track_id
            JOIN process p ON p.upid = track.upid
            WHERE attempt.name = '${TuneTraceNames.SECTION}' AND attempt.dur > 0
                AND p.name = '${captureInfo.targetPackageName}'
                AND EXISTS (
                    SELECT 1 FROM slice frame
                    JOIN thread_track ft ON ft.id = frame.track_id
                    JOIN thread t ON t.utid = ft.utid
                    WHERE frame.name = '${TuneTraceNames.RENDERED}' AND t.upid = p.upid
                        AND frame.ts >= attempt.ts AND frame.ts < attempt.ts + attempt.dur
                )
            ORDER BY attempt.ts
            """.trimIndent(),
        ).map { it.double("ms") }.toList()
        check(times.size == expectedFrames) { "Expected $expectedFrames rendered tunes, found ${times.size}" }
        return listOf(
            Measurement("TuneMs", times),
            Measurement("TuneAverageMs", times.average()),
            Measurement("TuneMaxMs", times.max()),
            Measurement("TuneCount", times.size.toDouble()),
        )
    }
}
