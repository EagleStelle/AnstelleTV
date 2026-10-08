package tv.anstelle.core.model

import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A color as OKLCH: [lightness] 0–1, [chroma] 0 to about 0.37, [hue] in degrees. Steps in
 * lightness look even to the eye, so the theme designer edits colors this way, the way
 * `tools/themes/generate_themes.py` builds the built-in themes.
 */
data class Oklch(val lightness: Double, val chroma: Double, val hue: Double) {
    /** As `#RRGGBB`, chroma reduced until the color fits sRGB. */
    fun toHex(): String {
        var c = chroma.coerceAtLeast(0.0)
        var rgb = linear(lightness, c, hue)
        while (c > 0 && rgb.any { it < -EPSILON || it > 1 + EPSILON }) {
            c = (c - 0.002).coerceAtLeast(0.0)
            rgb = linear(lightness, c, hue)
        }
        return "#" + rgb.joinToString("") { "%02X".format(encode(it)) }
    }

    companion object {
        const val MAX_CHROMA = 0.37
        private const val EPSILON = 1e-6

        fun fromHex(hex: String): Oklch {
            val rgb = ThemeContrast.rgb(hex)
            val r = decode((rgb shr 16) and 0xFF)
            val g = decode((rgb shr 8) and 0xFF)
            val b = decode(rgb and 0xFF)
            val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
            val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
            val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
            val lightness = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s
            val a = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s
            val bb = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
            val chroma = hypot(a, bb)
            val hue = if (chroma < 1e-4) 0.0 else (Math.toDegrees(atan2(bb, a)) + 360) % 360
            return Oklch(lightness, chroma, hue)
        }

        private fun linear(l: Double, c: Double, h: Double): DoubleArray {
            val a = c * cos(Math.toRadians(h))
            val b = c * sin(Math.toRadians(h))
            val l3 = (l + 0.3963377774 * a + 0.2158037573 * b).pow(3)
            val m3 = (l - 0.1055613458 * a - 0.0638541728 * b).pow(3)
            val s3 = (l - 0.0894841775 * a - 1.2914855480 * b).pow(3)
            return doubleArrayOf(
                4.0767416621 * l3 - 3.3077115913 * m3 + 0.2309699292 * s3,
                -1.2684380046 * l3 + 2.6097574011 * m3 - 0.3413193965 * s3,
                -0.0041960863 * l3 - 0.7034186147 * m3 + 1.7076147010 * s3,
            )
        }

        private fun decode(channel: Int): Double {
            val c = channel / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }

        private fun encode(linear: Double): Int {
            val x = linear.coerceIn(0.0, 1.0)
            val v = if (x <= 0.0031308) 12.92 * x else 1.055 * x.pow(1 / 2.4) - 0.055
            return (v * 255).roundToInt()
        }
    }
}
