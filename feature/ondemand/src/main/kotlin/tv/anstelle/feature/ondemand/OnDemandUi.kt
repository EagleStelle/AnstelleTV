package tv.anstelle.feature.ondemand

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv

private val PosterShape = RoundedCornerShape(10.dp)

/** A poster with its title: a TV surface that grows on focus, or a touch surface on phones. */
@Composable
internal fun PosterCard(
    title: String,
    subtitle: String?,
    imageUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalAnstelleTheme.current
    val content: @Composable () -> Unit = {
        Column {
            Poster(imageUrl, title, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
            Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                Text(title, size = 14, weight = FontWeight.Medium, maxLines = 1)
                subtitle?.let { Text(it, size = 12, color = theme.textMuted, maxLines = 1) }
            }
        }
    }
    if (LocalIsTv.current) {
        androidx.tv.material3.Surface(
            onClick = onClick,
            modifier = modifier,
            shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(PosterShape),
            colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                containerColor = theme.surface,
                contentColor = theme.text,
                focusedContainerColor = theme.guideCellFocused,
                focusedContentColor = theme.text,
            ),
            border = androidx.tv.material3.ClickableSurfaceDefaults.border(
                focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(2.dp, theme.focus)),
            ),
            scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        ) { content() }
    } else {
        androidx.compose.material3.Surface(onClick = onClick, modifier = modifier, shape = PosterShape, color = theme.surface) { content() }
    }
}

/** An image from the server, or the title's first letter when there is none. */
@Composable
internal fun Poster(imageUrl: String?, title: String, modifier: Modifier) {
    val theme = LocalAnstelleTheme.current
    Box(modifier.clip(PosterShape).background(theme.surfaceVariant), contentAlignment = Alignment.Center) {
        Text(title.take(1).uppercase(), size = 36, weight = FontWeight.Bold, color = theme.textMuted)
        if (imageUrl != null) {
            AsyncImage(model = imageUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/** A scrolling page with a title, as the show and item pages use. */
@Composable
internal fun Page(modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    val theme = LocalAnstelleTheme.current
    val isTv = LocalIsTv.current
    LazyColumn(
        modifier = modifier.fillMaxSize().background(theme.background),
        contentPadding = PaddingValues(horizontal = if (isTv) 48.dp else 16.dp, vertical = if (isTv) 32.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** On TV, focuses the requester's element once it is laid out. */
@Composable
internal fun rememberInitialFocus(key: Any? = Unit): FocusRequester {
    val requester = remember { FocusRequester() }
    if (LocalIsTv.current) LaunchedEffect(key) { runCatching { requester.requestFocus() } }
    return requester
}

@Composable
internal fun Badge(text: String) {
    val theme = LocalAnstelleTheme.current
    Text(
        text,
        size = 12,
        weight = FontWeight.Bold,
        modifier = Modifier
            .border(1.dp, theme.textMuted, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
internal fun Text(
    text: String,
    size: Int,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Normal,
    color: Color = LocalAnstelleTheme.current.text,
    maxLines: Int = Int.MAX_VALUE,
) {
    androidx.compose.material3.Text(
        text,
        modifier = modifier,
        color = color,
        fontSize = size.sp,
        fontWeight = weight,
        fontFamily = LocalAnstelleTheme.current.bodyFont,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** "12:34" or "1:02:03". */
internal fun clock(ms: Long): String {
    val seconds = ms / 1000
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
