package tv.anstelle.core.player

import android.graphics.Color
import android.graphics.Typeface
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import tv.anstelle.core.model.SubtitleEdge
import tv.anstelle.core.model.SubtitleFont
import tv.anstelle.core.model.SubtitleLook

/**
 * How subtitles that bring no style of their own look (SRT, plain WebVTT): the profile's
 * [SubtitleLook] in place of the system's caption style. A cue's own colors still apply, and
 * ASS keeps its authored look (libass draws it).
 */
@OptIn(UnstableApi::class)
object SubtitleStyle {
    /** Gives [view], a `PlayerView`'s subtitle view or a preview's, the [look]. */
    fun applyTo(view: SubtitleView, look: SubtitleLook) {
        view.setStyle(
            CaptionStyleCompat(
                look.color.argb,
                look.background.argb,
                // Nothing behind the cue's whole area, only behind its lines.
                Color.TRANSPARENT,
                edgeType(look.edge),
                Color.BLACK,
                typeface(look.font),
            ),
        )
        view.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * look.size.scale)
    }

    private fun edgeType(edge: SubtitleEdge) = when (edge) {
        SubtitleEdge.OUTLINE -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
        SubtitleEdge.DROP_SHADOW -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
        SubtitleEdge.RAISED -> CaptionStyleCompat.EDGE_TYPE_RAISED
        SubtitleEdge.SUNKEN -> CaptionStyleCompat.EDGE_TYPE_DEPRESSED
        SubtitleEdge.NONE -> CaptionStyleCompat.EDGE_TYPE_NONE
    }

    /** The system's faces; a device without the condensed one falls back to its sans. */
    private fun typeface(font: SubtitleFont): Typeface? = when (font) {
        SubtitleFont.SANS -> null
        SubtitleFont.CONDENSED -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
        SubtitleFont.SERIF -> Typeface.SERIF
        SubtitleFont.MONO -> Typeface.MONOSPACE
    }
}
