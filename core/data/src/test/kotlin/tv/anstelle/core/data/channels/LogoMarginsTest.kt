package tv.anstelle.core.data.channels

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

class LogoMarginsTest {
    private val ink = 0xFFE0306A.toInt()
    private val clear = 0x00FFFFFF

    /** A [width] × [height] image of [ground] with [paint] set at each (x, y). */
    private fun image(width: Int, height: Int, ground: Int, vararg paint: Pair<Int, Int>): IntArray =
        IntArray(width * height) { ground }.also { pixels -> paint.forEach { (x, y) -> pixels[y * width + x] = ink } }

    @Test
    fun trimsClearMarginsOnEverySide() {
        val pixels = image(8, 6, clear, 2 to 1, 5 to 1, 3 to 4)
        assertContentEquals(intArrayOf(2, 1, 5, 4), LogoMargins.bounds(pixels, 8, 6))
    }

    @Test
    fun faintExportNoiseCountsAsClear() {
        val pixels = image(6, 6, clear, 2 to 2, 3 to 3)
        pixels[0] = 0x08FFFFFF
        pixels[35] = 0x05000000
        assertContentEquals(intArrayOf(2, 2, 3, 3), LogoMargins.bounds(pixels, 6, 6))
    }

    @Test
    fun keepsAnOpaquePlateWhole() {
        // A colored plate is part of the logo: only transparent margins are trimmed.
        val plate = 0xFF202020.toInt()
        assertNull(LogoMargins.bounds(image(6, 4, plate, 2 to 2), 6, 4))
    }

    @Test
    fun leavesALogoThatReachesEveryEdge() {
        assertNull(LogoMargins.bounds(image(4, 3, clear, 0 to 0, 3 to 2), 4, 3))
    }

    @Test
    fun leavesAnImageWithNoInk() {
        assertNull(LogoMargins.bounds(image(5, 5, clear), 5, 5))
    }

    @Test
    fun keepsASinglePixelLogo() {
        assertContentEquals(intArrayOf(4, 0, 4, 0), LogoMargins.bounds(image(5, 3, clear, 4 to 0), 5, 3))
    }
}
