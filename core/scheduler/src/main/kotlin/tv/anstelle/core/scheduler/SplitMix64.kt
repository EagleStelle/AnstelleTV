package tv.anstelle.core.scheduler

/**
 * SplitMix64 pseudo-random generator.
 *
 * The scheduler uses its own generator instead of `java.util.Random` so the whole
 * state is one `Long` that can be persisted and resumed: extending a schedule from a
 * saved [state] produces exactly the slots a single longer run would have produced.
 */
class SplitMix64(seed: Long) {
    var state: Long = seed
        private set

    fun nextLong(): Long {
        state += GOLDEN_GAMMA
        var z = state
        z = (z xor (z ushr 30)) * MIX_1
        z = (z xor (z ushr 27)) * MIX_2
        return z xor (z ushr 31)
    }

    /** Uniform value in `[0, bound)`, without modulo bias. */
    fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound must be positive: $bound" }
        val b = bound.toLong()
        while (true) {
            val r = nextLong() ushr 1
            val value = r % b
            // Reject the top partial range so every value is equally likely.
            if (r - value + (b - 1) >= 0) return value.toInt()
        }
    }

    /** Uniform value in `[0, 1)` with 53 bits of precision. */
    fun nextDouble(): Double = (nextLong() ushr 11) * DOUBLE_UNIT

    private companion object {
        const val GOLDEN_GAMMA = -0x61c8864680b583ebL // 0x9E3779B97F4A7C15
        const val MIX_1 = -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9
        const val MIX_2 = -0x6b2fb644ecceee15L // 0x94D049BB133111EB
        const val DOUBLE_UNIT = 1.0 / (1L shl 53)
    }
}
