package tv.anstelle.core.data.channels

/**
 * Where a logo's own pixels sit inside its image, without the clear margins around them.
 * Only transparent margins are trimmed: an opaque logo is often a colored plate, and cutting
 * into the plate would change the logo. Pure, over ARGB pixels, so it runs on the JVM.
 */
internal object LogoMargins {
    /** At or under this alpha (of 255) a pixel counts as clear: export noise, not ink. */
    const val CLEAR_ALPHA = 8

    /**
     * The logo's bounds in [pixels] ([width] × [height], row by row) as left, top, right and
     * bottom, inclusive. Null when there is nothing to trim: no clear margin, or nothing but
     * clear pixels.
     */
    fun bounds(pixels: IntArray, width: Int, height: Int): IntArray? {
        require(width > 0 && height > 0 && pixels.size >= width * height)
        fun clear(p: Int) = p ushr 24 <= CLEAR_ALPHA
        fun rowIsClear(y: Int): Boolean {
            val start = y * width
            for (x in 0 until width) if (!clear(pixels[start + x])) return false
            return true
        }
        fun columnIsClear(x: Int, top: Int, bottom: Int): Boolean {
            for (y in top..bottom) if (!clear(pixels[y * width + x])) return false
            return true
        }

        var top = 0
        while (top < height && rowIsClear(top)) top++
        if (top == height) return null
        var bottom = height - 1
        while (bottom > top && rowIsClear(bottom)) bottom--
        // Row `top` holds ink, so some column between the edges does too.
        var left = 0
        while (left < width - 1 && columnIsClear(left, top, bottom)) left++
        var right = width - 1
        while (right > left && columnIsClear(right, top, bottom)) right--
        if (left == 0 && top == 0 && right == width - 1 && bottom == height - 1) return null
        return intArrayOf(left, top, right, bottom)
    }
}
