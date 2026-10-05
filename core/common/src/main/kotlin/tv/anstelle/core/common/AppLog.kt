package tv.anstelle.core.common

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

data class LogEntry(
    val sequence: Long,
    val timeUtcMs: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val throwable: String?,
)

/** Receives every log entry, e.g. Android logcat. Registered by the app at startup. */
fun interface LogSink {
    fun write(entry: LogEntry)
}

/**
 * Small app-wide logger. Keeps the latest [CAPACITY] entries in a ring buffer for the
 * dashboard and the web UI log page, and forwards each entry to the registered sinks.
 * Logging is for events, never for per-frame code paths.
 */
object AppLog {
    const val CAPACITY = 1000

    private val lock = Any()
    private val ring = arrayOfNulls<LogEntry>(CAPACITY)
    private var next = 0L
    private val sinks = mutableListOf<LogSink>()
    private val _lastSequence = MutableStateFlow(-1L)

    /** Sequence number of the newest entry; observe it to refresh log views. */
    val lastSequence: StateFlow<Long> = _lastSequence.asStateFlow()

    @Volatile
    var minLevel: LogLevel = LogLevel.DEBUG

    fun addSink(sink: LogSink) = synchronized(lock) { sinks += sink }

    fun d(tag: String, message: String) = log(LogLevel.DEBUG, tag, message, null)
    fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message, null)
    fun w(tag: String, message: String, error: Throwable? = null) = log(LogLevel.WARN, tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = log(LogLevel.ERROR, tag, message, error)

    fun log(level: LogLevel, tag: String, message: String, error: Throwable?) {
        if (level < minLevel) return
        val entry: LogEntry
        val targets: List<LogSink>
        synchronized(lock) {
            entry = LogEntry(next, System.currentTimeMillis(), level, tag, message, error?.stackTraceToString())
            ring[(next % CAPACITY).toInt()] = entry
            next++
            targets = sinks.toList()
        }
        _lastSequence.value = entry.sequence
        for (sink in targets) sink.write(entry)
    }

    /** Entries with a sequence number greater than [afterSequence], oldest first. */
    fun snapshot(afterSequence: Long = -1L, limit: Int = CAPACITY): List<LogEntry> = synchronized(lock) {
        val oldest = maxOf(0L, next - CAPACITY, afterSequence + 1)
        val start = maxOf(oldest, next - limit)
        (start until next).map { ring[(it % CAPACITY).toInt()]!! }
    }
}
