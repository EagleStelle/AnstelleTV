package tv.anstelle.core.model

/** Saved playback progress, independent of the server's watched flag. */
data class MediaProgress(val durationMs: Long, val positionMs: Long) {
    val hasProgress: Boolean get() = positionMs > 0 && durationMs > 0
    val fraction: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val remainingMs: Long get() = (durationMs.coerceAtLeast(0) - positionMs.coerceAtLeast(0)).coerceAtLeast(0)
    val remainingLabel: String? get() = if (hasProgress) remainingTime(remainingMs) else null

    companion object {
        /** Shared time-left readout for saved progress and scheduled airings. */
        fun remainingTime(ms: Long): String = if (ms <= 0) "0 MIN" else span(ms, roundUp = true).uppercase()

        /** A duration, with started minutes rounded up only for remaining time. */
        fun span(ms: Long, roundUp: Boolean = false): String {
            val minutes = if (roundUp) ((ms + 59_999) / 60_000).coerceAtLeast(1) else (ms / 60_000).coerceAtLeast(0)
            return if (minutes < 60) "$minutes min" else if (minutes % 60 == 0L) "${minutes / 60} h" else "${minutes / 60} h ${minutes % 60} min"
        }
    }
}
