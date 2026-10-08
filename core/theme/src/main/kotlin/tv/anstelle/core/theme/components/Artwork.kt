package tv.anstelle.core.theme.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage
import tv.anstelle.core.theme.LocalAnstelleTheme

/**
 * How much of the ground lies over a backdrop: one flat wash, never a gradient, so bone text
 * keeps its contrast even over a white sky. The guide's past shade is the only other
 * translucent layer.
 */
const val BACKDROP_WASH = 0.8f

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

/**
 * A title as its logo when the server has one, else as text in [style]. The slot is at least
 * [height] tall either way (the logo exactly that), so swapping one for the other never moves
 * what sits under it. If the logo fails to load, the text comes back. Light themes always set
 * the text: title logos are drawn for dark pictures and fade into a light ground.
 */
@Composable
fun TitleLogo(
    logoUrl: String?,
    title: String,
    height: Dp,
    maxLogoWidth: Dp,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
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
                color = LocalAnstelleTheme.current.text,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
