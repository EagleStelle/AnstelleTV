package tv.anstelle.core.common

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

data class LogEntry(
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
 * Small app-wide logger: forwards each entry to the registered sinks (logcat in the app).
 * Logging is for events, never for per-frame code paths.
 */
object AppLog {
    private val lock = Any()
    private val sinks = mutableListOf<LogSink>()

    @Volatile
    var minLevel: LogLevel = LogLevel.DEBUG

    fun addSink(sink: LogSink) = synchronized(lock) { sinks += sink }

    fun d(tag: String, message: String) = log(LogLevel.DEBUG, tag, message, null)
    fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message, null)
    fun w(tag: String, message: String, error: Throwable? = null) = log(LogLevel.WARN, tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = log(LogLevel.ERROR, tag, message, error)

    fun log(level: LogLevel, tag: String, message: String, error: Throwable?) {
        if (level < minLevel) return
        val targets = synchronized(lock) { sinks.toList() }
        if (targets.isEmpty()) return
        val entry = LogEntry(level, tag, message, error?.stackTraceToString())
        for (sink in targets) sink.write(entry)
    }
}
