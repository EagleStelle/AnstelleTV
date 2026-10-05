package tv.anstelle.core.common

/** Wall-clock source. Injected so scheduling and tuning code can be tested with a fixed clock. */
fun interface TimeSource {
    fun nowUtcMs(): Long

    companion object {
        val System: TimeSource = TimeSource { java.lang.System.currentTimeMillis() }
    }
}
