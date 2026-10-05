package tv.anstelle.core.scheduler

/** Order-sensitive 64-bit hash of schedule inputs, used for deck checks and `inputsHash`. */
internal class Fingerprint {
    var value: Long = 0x6A09E667F3BCC909L
        private set

    fun add(v: Long): Fingerprint {
        value = SplitMix64.mix64(value + v * SplitMix64.GOLDEN_GAMMA)
        return this
    }

    fun add(v: Int): Fingerprint = add(v.toLong())

    fun add(v: Boolean): Fingerprint = add(if (v) 1L else 0L)

    fun add(v: String?): Fingerprint {
        if (v == null) return add(-1L)
        add(v.length)
        for (c in v) add(c.code)
        return this
    }
}
