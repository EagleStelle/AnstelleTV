package tv.anstelle.core.player

import android.graphics.Color
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView

/**
 * How subtitles that bring no style of their own look (SRT, plain WebVTT): white letters in a
 * black outline with no box behind them, readable over a bright picture or a dark one. A cue's
 * own colors still apply, and ASS keeps its authored look (libass draws it). It is the one look
 * for now; a viewer's choice can take its place later.
 */
@OptIn(UnstableApi::class)
object SubtitleStyle {
    private val default = CaptionStyleCompat(
        Color.WHITE,
        // No box behind the text, nor behind the cue's area.
        Color.TRANSPARENT,
        Color.TRANSPARENT,
        CaptionStyleCompat.EDGE_TYPE_OUTLINE,
        Color.BLACK,
        null,
    )

    /** Gives [view], a `PlayerView`'s subtitle view, the default look in place of the system's. */
    fun applyTo(view: SubtitleView) = view.setStyle(default)
}
