package tv.anstelle.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A theme as stored in JSON (built-in themes in core/theme's `assets/themes`, the rest in the
 * `theme` table). Themes are data: adding one never needs a code change.
 */
@Serializable
data class ThemeSpec(
    val key: String,
    val name: String,
    val version: Int = 1,
    val colors: ThemeColors,
    val typography: ThemeTypography = ThemeTypography(),
    val guide: GuideStyle = GuideStyle(),
    val effects: ThemeEffects = ThemeEffects(),
    val progress: ProgressStyle = ProgressStyle(),
) {
    /** A light ground with dark text, which needs dark system bar icons. */
    val isLight: Boolean get() = ThemeContrast.luminance(colors.background) > 0.5

    companion object {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }

        fun parse(text: String): ThemeSpec = json.decodeFromString(serializer(), text)
    }

    fun toJson(): String = json.encodeToString(serializer(), this)

    /** Throws [IllegalArgumentException] naming the first value a theme can't have. */
    fun validate() {
        require(name.isNotBlank()) { "The theme has no name" }
        colors.all().forEach { (role, hex) -> require(ThemeContrast.isColor(hex)) { "$role is not a color: $hex" } }
        require(colors.guideRuns.size >= 2) { "guideRuns needs at least two colors" }
        require(typography.scale in MIN_SCALE..MAX_SCALE) { "Text size must be ${MIN_SCALE}–${MAX_SCALE}" }
        require(guide.cellRadiusDp in 0..MAX_RADIUS_DP) { "Guide corners must be 0–$MAX_RADIUS_DP" }
        require(effects.scanlineAlpha in 0f..MAX_SCANLINE_ALPHA) { "Scanline strength must be 0–$MAX_SCANLINE_ALPHA" }
        require(progress.segmentFrequency in MIN_PROGRESS_FREQUENCY..MAX_PROGRESS_FREQUENCY) { "Progress cut frequency must be $MIN_PROGRESS_FREQUENCY–$MAX_PROGRESS_FREQUENCY" }
    }
}

const val MIN_SCALE = 0.9f
const val MAX_SCALE = 1.3f
const val MAX_RADIUS_DP = 4
const val MAX_SCANLINE_ALPHA = 0.4f
const val MIN_PROGRESS_FREQUENCY = 4
const val MAX_PROGRESS_FREQUENCY = 64

/** Cuts per fixed reference length: every bar repeats the same block width and gap width. */
@Serializable
data class ProgressStyle(val segmentFrequency: Int = 20) {
    val blockWidthDp: Float get() = FREQUENCY_REFERENCE_DP / segmentFrequency - GAP_DP

    companion object {
        const val FREQUENCY_REFERENCE_DP = 320f
        const val GAP_DP = 3f
        val frequencies = (MIN_PROGRESS_FREQUENCY..MAX_PROGRESS_FREQUENCY step 4).toList()
    }
}

/**
 * Colors as `#RRGGBB` or `#AARRGGBB`. Focus is always [accent] filled with [onAccent] text,
 * so a theme sets one accent and every focused control, cell and tab follows it.
 */
@Serializable
data class ThemeColors(
    /** The ground every screen sits on. */
    val background: String,
    /** Rows, cells and panels one step up from the ground. */
    val surface: String,
    /** A second step: pressed rows, raised panels. */
    val surfaceHigh: String,
    /** Dividers, outlines and empty progress segments. */
    val line: String,
    val text: String,
    /** Secondary text, tinted from the palette rather than a neutral grey. */
    val textMuted: String,
    /** Focus fills, the now line, live markers and primary actions. */
    val accent: String,
    val onAccent: String,
    /** Selected (not focused) items, such as the open destination. */
    val accentContainer: String,
    val onAccentContainer: String,
    /** A guide cell on air, and later airings of the show on air in its row. */
    val guideCell: String,
    /** A guide cell that has finished airing. */
    val guideCellPast: String,
    /**
     * Upcoming guide cells of other shows, taken in turn: a show's back-to-back airings share
     * one, and the next show always takes another. At least two.
     */
    val guideRuns: List<String>,
    val nowLine: String,
    /** The player's banner and panels over video. */
    val banner: String,
    val bannerText: String,
) {
    /** Every single color by role name, the guide runs as `guideRuns[i]`. */
    fun all(): List<Pair<String, String>> = listOf(
        "background" to background,
        "surface" to surface,
        "surfaceHigh" to surfaceHigh,
        "line" to line,
        "text" to text,
        "textMuted" to textMuted,
        "accent" to accent,
        "onAccent" to onAccent,
        "accentContainer" to accentContainer,
        "onAccentContainer" to onAccentContainer,
        "guideCell" to guideCell,
        "guideCellPast" to guideCellPast,
        "nowLine" to nowLine,
        "banner" to banner,
        "bannerText" to bannerText,
    ) + guideRuns.mapIndexed { i, run -> "guideRuns[$i]" to run }
}

@Serializable
enum class FontChoice { SANS, SERIF, MONO, CONDENSED }

@Serializable
data class ThemeTypography(
    val display: FontChoice = FontChoice.SANS,
    val body: FontChoice = FontChoice.SANS,
    val scale: Float = 1f,
)

/**
 * Guide row heights. Touch rows stay tall enough to tap; TV rows are tighter, since the
 * remote moves the selection and the 10-foot screen should show more channels at once.
 */
@Serializable
enum class GuideDensity(val rowHeightDp: Int, val tvRowHeightDp: Int) {
    COMPACT(48, 40),
    COMFORTABLE(64, 52),
    SPACIOUS(80, 64),
}

@Serializable
data class GuideStyle(
    val density: GuideDensity = GuideDensity.COMFORTABLE,
    val cellRadiusDp: Int = 2,
)

/** A CRT's scanlines over the whole screen, video included, at [scanlineAlpha]. */
@Serializable
data class ThemeEffects(
    val scanlines: Boolean = false,
    val scanlineAlpha: Float = 0.14f,
)

/**
 * WCAG contrast for theme colors, and the floors every built-in theme meets. The theme
 * designer warns when a custom theme drops under one; it doesn't stop it.
 */
object ThemeContrast {
    /** A pair of roles that must read against each other, and the ratio it needs. */
    data class Floor(val foreground: String, val background: String, val minimum: Double, val label: String)

    val Floors = listOf(
        Floor("text", "background", 7.0, "Text on the ground"),
        Floor("text", "surface", 7.0, "Text on rows"),
        Floor("textMuted", "background", 4.5, "Quiet text on the ground"),
        Floor("textMuted", "surface", 4.5, "Quiet text on rows"),
        Floor("onAccent", "accent", 4.5, "Text on focus"),
        Floor("accent", "background", 3.0, "Focus on the ground"),
        Floor("onAccentContainer", "accentContainer", 4.5, "Text on selected"),
        Floor("bannerText", "banner", 7.0, "Banner text"),
        Floor("text", "guideCell", 7.0, "Text on guide cells"),
    )

    /** Text on each guide run needs the same as text on guide cells. */
    const val RUN_MINIMUM = 7.0

    /** The floors [colors] miss, each with its ratio. */
    fun problems(colors: ThemeColors): List<Pair<Floor, Double>> {
        val byRole = colors.all().toMap()
        val pairs = Floors.map { floor -> floor to ratio(byRole.getValue(floor.foreground), byRole.getValue(floor.background)) }
        val runs = colors.guideRuns.mapIndexed { i, run ->
            Floor("text", "guideRuns[$i]", RUN_MINIMUM, "Text on Guide: later ${i + 1}") to ratio(colors.text, run)
        }
        return (pairs + runs).filter { (floor, ratio) -> ratio < floor.minimum }
    }

    /** The WCAG contrast ratio of two opaque colors, 1 to 21. Alpha is ignored. */
    fun ratio(a: String, b: String): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** WCAG relative luminance of `#RRGGBB` or `#AARRGGBB`. */
    fun luminance(hex: String): Double {
        val rgb = rgb(hex)
        fun channel(shift: Int): Double {
            val c = ((rgb shr shift) and 0xFF) / 255.0
            return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    fun isColor(hex: String): Boolean {
        val digits = hex.removePrefix("#")
        return hex.startsWith("#") && (digits.length == 6 || digits.length == 8) && digits.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    }

    /** The color's 0xRRGGBB, without alpha. */
    fun rgb(hex: String): Int {
        require(isColor(hex)) { "Bad color: $hex" }
        return (hex.removePrefix("#").toLong(16) and 0xFFFFFF).toInt()
    }
}
