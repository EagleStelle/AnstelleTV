package tv.anstelle.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import tv.anstelle.core.theme.LocalAnstelleTheme

/**
 * Full-screen playback. The player pool, surfing, info banner and Skip Intro arrive in
 * Phase 5; the D-pad and channel keys already change the channel number here.
 */
@Composable
fun PlayerScreen(
    channelNumber: Int,
    modifier: Modifier = Modifier,
) {
    var number by remember { mutableIntStateOf(channelNumber) }
    val focus = remember { FocusRequester() }
    val theme = LocalAnstelleTheme.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionUp, Key.ChannelUp -> { number += 1; true }
                    Key.DirectionDown, Key.ChannelDown -> { number = maxOf(1, number - 1); true }
                    else -> false
                }
            }
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            androidx.compose.material3.Text(
                "Channel $number",
                style = androidx.compose.material3.MaterialTheme.typography.displaySmall,
                color = theme.text,
            )
            androidx.compose.material3.Text(
                "Playback arrives in a later phase. Up/Down changes channel.",
                style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                color = theme.textMuted,
            )
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
