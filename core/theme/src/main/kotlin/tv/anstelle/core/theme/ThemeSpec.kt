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

/** Colors as `#RRGGBB` or `#AARRGGBB`. */
@Serializable
data class ThemeColors(
    val background: String,
    val surface: String,
    val surfaceVariant: String,
    val primary: String,
    val onPrimary: String,
    val secondary: String,
    val text: String,
    val textMuted: String,
    val focus: String,
    val guideCell: String,
    val guideCellAlt: String,
    val guideCellFocused: String,
    val nowLine: String,
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

@Serializable
enum class GuideDensity(val rowHeightDp: Int) {
    COMPACT(48),
    COMFORTABLE(64),
    SPACIOUS(80),
}

@Serializable
data class GuideStyle(
    val density: GuideDensity = GuideDensity.COMFORTABLE,
    val cellRadiusDp: Int = 8,
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
