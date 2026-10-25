package tv.anstelle.core.theme.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import tv.anstelle.core.theme.ControlShape
import tv.anstelle.core.theme.LocalAnstelleTheme
import kotlin.math.hypot

/**
 * An image from the server, or the title's first letter on the surface step when there is none.
 * [shaded] applies the watched shade. [content] supplies overlays such as the watch strip.
 */
@Composable
fun Poster(
    imageUrl: String?,
    title: String,
    modifier: Modifier = Modifier,
    shaded: Boolean = false,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val theme = LocalAnstelleTheme.current
    Box(modifier.clip(ControlShape).background(theme.surfaceHigh), contentAlignment = Alignment.Center) {
        androidx.compose.material3.Text(
            title.take(1).uppercase(),
            style = androidx.compose.material3.MaterialTheme.typography.displayMedium,
            color = theme.line,
        )
        if (imageUrl != null) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
        if (shaded) Box(Modifier.matchParentSize().background(PastShade))
        content()
    }
}

/**
 * How much of the ground lies over a backdrop: one flat wash, never a gradient, so bone text
 * keeps its contrast even over a white sky. The guide's past shade is the only other
 * translucent layer.
 */
const val BACKDROP_WASH = 0.8f

/** How dark the shades over a picture get where they are deepest: the player banner's foot and a corner. */
const val SHADE_DEEPEST = 0.88f

/**
 * The corner shade over a picture: [color] deepest in the element's top-end corner (the
 * screen's, where the player's channel ident sits) and gone at [CORNER_REACH] times the
 * element's diagonal, a quarter ellipse a little wider than tall, whatever the element's own
 * shape, so a short row of typed digits gets the same corner vignette as a tall logo. A
 * broadcaster's corner bug sits on a vignette like this, never on a plate. [atStart] sets it in
 * the top-start corner instead, behind a wide detail hero's way back on touch screens.
 */
fun Modifier.cornerShade(color: Color, atStart: Boolean = false) = drawBehind {
    val rx = hypot(size.width, size.height) * CORNER_REACH
    val ry = rx * 0.8f
    // The screen's left corner: the end in a right-to-left layout, or the start in a left-to-right one.
    val left = (layoutDirection == LayoutDirection.Rtl) != atStart
    val corner = if (left) Offset.Zero else Offset(size.width, 0f)
    val brush = Brush.radialGradient(
        0f to color.copy(alpha = SHADE_DEEPEST),
        0.5f to color.copy(alpha = 0.72f),
        0.78f to color.copy(alpha = 0.32f),
        1f to color.copy(alpha = 0f),
        center = corner,
        radius = ry,
    )
    // A circle of radius ry around the corner, stretched sideways to rx.
    scale(scaleX = rx / ry, scaleY = 1f, pivot = corner) {
        drawRect(brush, topLeft = Offset(if (left) 0f else corner.x - ry, 0f), size = Size(ry, ry))
    }
}

/** How far the corner shade reaches, in multiples of its element's diagonal. */
private const val CORNER_REACH = 1.25f

/**
 * A show's or movie's backdrop under the flat wash, cropped to fill the box, with [content]
 * on top. Without one the box is the plain ground.
 */
@Composable
fun Backdrop(
    url: String?,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.TopCenter,
    content: @Composable BoxScope.() -> Unit,
) {
    val theme = LocalAnstelleTheme.current
    Box(modifier.background(theme.background)) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = alignment,
                modifier = Modifier.matchParentSize(),
            )
            Box(Modifier.matchParentSize().background(theme.background.copy(alpha = BACKDROP_WASH)))
        }
        content()
    }
}

/** A channel logo's box, width over height: the 5:3 set-top picons use. */
const val CHANNEL_LOGO_ASPECT = 5f / 3f

/** Shared logo heights for players and compact guide details. */
object LogoSize {
    val Standard = 56.dp
    val Compact = 40.dp
}

/**
 * A channel's logo in its standard box, [height] tall and [CHANNEL_LOGO_ASPECT] as wide, the
 * same box everywhere so every channel's mark carries the same weight. The logo keeps its
 * proportions inside it, placed by [alignment]. Stored logos are already trimmed and fitted
 * to this box (`ChannelLogos`), so it decodes small and shows little margin. The channel's
 * name always sits beside it, so the logo is silent to screen readers.
 */
@Composable
fun ChannelLogo(uri: String, height: Dp = LogoSize.Standard, modifier: Modifier = Modifier, alignment: Alignment = Alignment.Center) {
    val context = LocalPlatformContext.current
    // No crossfade: rows rebind as the guide scrolls, and a logo should never flicker in.
    val request = remember(uri, context) { ImageRequest.Builder(context).data(uri).crossfade(false).build() }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        alignment = alignment,
        modifier = modifier.size(width = height * CHANNEL_LOGO_ASPECT, height = height),
    )
}

/**
 * A title as its logo when the server has one, else as text in [style]. The slot is at least
 * [height] tall either way (the logo exactly that), so swapping one for the other never moves
 * what sits under it. If the logo fails to load, the text comes back, in [color]. Light themes
 * always set the text: title logos are drawn for dark pictures and fade into a light ground.
 */
@Composable
fun TitleLogo(
    logoUrl: String?,
    title: String,
    height: Dp = LogoSize.Standard,
    maxLogoWidth: Dp = 360.dp,
    style: TextStyle = MaterialTheme.typography.headlineMedium,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    color: Color = LocalAnstelleTheme.current.text,
) {
    var failed by remember(logoUrl) { mutableStateOf(false) }
    val logo = logoUrl.takeUnless { LocalAnstelleTheme.current.spec.isLight }
    Box(modifier.heightIn(min = height), contentAlignment = Alignment.BottomStart) {
        if (logo != null && !failed) {
            AsyncImage(
                model = logo,
                contentDescription = title,
                contentScale = ContentScale.Fit,
                alignment = Alignment.BottomStart,
                onError = { failed = true },
                modifier = Modifier.height(height).widthIn(max = maxLogoWidth),
            )
        } else {
            androidx.compose.material3.Text(
                title,
                style = style,
                color = color,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
