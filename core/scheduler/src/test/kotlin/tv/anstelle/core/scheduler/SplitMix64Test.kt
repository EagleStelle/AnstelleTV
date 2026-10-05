package tv.anstelle.core.scheduler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SplitMix64Test {
    @Test
    fun matchesReferenceOutputForSeedZero() {
        val rng = SplitMix64(0L)
        assertEquals(0xE220A8397B1DCDAFuL.toLong(), rng.nextLong())
        assertEquals(0x6E789E6AA1B965F4uL.toLong(), rng.nextLong())
        assertEquals(0x06C45D188009454FuL.toLong(), rng.nextLong())
    }

    @Test
    fun sameSeedGivesSameSequence() {
        val a = SplitMix64(42L)
        val b = SplitMix64(42L)
        repeat(1000) { assertEquals(a.nextLong(), b.nextLong()) }
    }

    @Test
    fun differentSeedsDiverge() {
        assertNotEquals(SplitMix64(1L).nextLong(), SplitMix64(2L).nextLong())
    }

    @Test
    fun resumingFromSavedStateContinuesTheSequence() {
        val full = SplitMix64(7L)
        repeat(100) { full.nextLong() }
        val saved = full.state
        val expected = List(50) { full.nextLong() }

        val resumed = SplitMix64(saved)
        assertEquals(expected, List(50) { resumed.nextLong() })
    }

    @Test
    fun nextIntStaysInBoundsAndCoversRange() {
        val rng = SplitMix64(99L)
        val seen = BooleanArray(7)
        repeat(10_000) {
            val v = rng.nextInt(7)
            assertTrue(v in 0 until 7)
            seen[v] = true
        }
        assertTrue(seen.all { it })
    }

    @Test
    fun nextDoubleIsInUnitInterval() {
        val rng = SplitMix64(5L)
        repeat(10_000) {
            val d = rng.nextDouble()
            assertTrue(d >= 0.0 && d < 1.0)
        }
    }
}
