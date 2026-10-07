package tv.anstelle.core.theme

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A guide theme as stored in JSON (built-in themes in `assets/themes`, user themes in the
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
    val banner: BannerStyle = BannerStyle.BAR,
) {
    companion object {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }

        fun parse(text: String): ThemeSpec = json.decodeFromString(serializer(), text)
    }

    fun toJson(): String = json.encodeToString(serializer(), this)
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
)

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
    val showChannelLogos: Boolean = true,
)

@Serializable
data class ThemeEffects(
    val scanlines: Boolean = false,
    val scanlineAlpha: Float = 0.12f,
    val vignette: Boolean = false,
)

@Serializable
enum class BannerStyle { BAR, BOX, TICKER }
