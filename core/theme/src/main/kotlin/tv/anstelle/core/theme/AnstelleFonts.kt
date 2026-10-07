package tv.anstelle.core.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/**
 * Barlow and Barlow Condensed (SIL OFL 1.1, `core/theme/licenses/barlow-OFL.txt`), bundled as
 * static files: Fire TV has no downloadable fonts, and Fire OS 6 (API 25) can't set variable
 * font axes. One family carries everything; the condensed cut sets numbers, labels and titles.
 */
object AnstelleFonts {
    val Barlow = FontFamily(
        Font(R.font.barlow_regular, FontWeight.Normal),
        Font(R.font.barlow_medium, FontWeight.Medium),
        Font(R.font.barlow_semibold, FontWeight.SemiBold),
        Font(R.font.barlow_bold, FontWeight.Bold),
    )

    val BarlowCondensed = FontFamily(
        Font(R.font.barlow_condensed_medium, FontWeight.Medium),
        Font(R.font.barlow_condensed_semibold, FontWeight.SemiBold),
        Font(R.font.barlow_condensed_bold, FontWeight.Bold),
    )

    /** Tabular figures, so times and channel numbers don't shift as they change. */
    const val TABULAR = "tnum"
}
