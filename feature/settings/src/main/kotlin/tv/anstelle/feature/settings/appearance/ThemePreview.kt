package tv.anstelle.feature.settings.appearance

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tv.anstelle.core.model.ThemeSpec
import tv.anstelle.core.theme.AnstelleTheme
import tv.anstelle.core.theme.ControlShape
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv
import tv.anstelle.core.theme.PanelShape
import tv.anstelle.core.theme.ResolvedTheme
import tv.anstelle.core.theme.components.AirMarker
import tv.anstelle.core.theme.components.NumberBlock
import tv.anstelle.core.theme.components.SegmentLadder
import tv.anstelle.core.theme.components.StatusLine
import tv.anstelle.core.theme.scanlines

/**
 * [spec] drawn as the app would wear it: a banner, a strip of guide cells, a focused and a
 * plain menu row and the two buttons. Nothing in it takes focus; it only shows.
 */
@Composable
fun ThemePreview(spec: ThemeSpec, modifier: Modifier = Modifier) {
    val resolved = remember(spec) { ResolvedTheme.from(spec) }
    AnstelleTheme(isTv = LocalIsTv.current, theme = resolved) {
        val theme = LocalAnstelleTheme.current
        val typography = androidx.compose.material3.MaterialTheme.typography
        Column(
            modifier
                .clearAndSetSemantics { }
                .background(theme.background, PanelShape)
                .border(1.dp, theme.line, PanelShape)
                .scanlines(if (spec.effects.scanlines) spec.effects.scanlineAlpha else 0f)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            androidx.compose.material3.Text(spec.name, style = typography.headlineSmall, color = theme.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // The banner.
            Row(
                Modifier.fillMaxWidth().height(64.dp).background(theme.banner, PanelShape).border(1.dp, theme.line, PanelShape),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NumberBlock("101", style = typography.headlineSmall, modifier = Modifier.fillMaxHeight().width(64.dp))
                Column(Modifier.weight(1f).padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    StatusLine(onAir = true, label = "Now", text = "8:00 – 8:30")
                    androidx.compose.material3.Text("Feature Presentation", style = typography.titleMedium, color = theme.bannerText, maxLines = 1)
                    SegmentLadder(0.55f, segments = 20)
                }
            }
            // A row of the guide: aired, on now under the now line, then later shows.
            Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                val radius = androidx.compose.foundation.shape.RoundedCornerShape(spec.guide.cellRadiusDp.dp)
                Cell("Aired", theme.guideCellPast, theme.textMuted, radius, Modifier.weight(0.8f))
                Box(Modifier.weight(1.2f).fillMaxHeight()) {
                    Cell("On now", theme.guideCell, theme.text, radius, Modifier.fillMaxWidth(), marker = true)
                    Box(Modifier.fillMaxHeight().width(2.dp).align(Alignment.CenterStart).padding(start = 0.dp).background(theme.nowLine))
                }
                theme.guideRuns.take(2).forEachIndexed { i, run -> Cell(if (i == 0) "Later" else "Then", run, theme.text, radius, Modifier.weight(1f)) }
            }
            // Menu rows: one focused, one at rest.
            MenuRowSample("Focused row", theme.accent, theme.onAccent, focused = true)
            MenuRowSample("A row at rest", theme.surface, theme.text, focused = false)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ButtonSample("Watch", theme.accent, theme.onAccent, Modifier.weight(1f))
                ButtonSample("Later", theme.surface, theme.text, Modifier.weight(1f))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                androidx.compose.material3.Text(
                    "SELECTED",
                    style = typography.labelSmall,
                    color = theme.onAccentContainer,
                    modifier = Modifier.background(theme.accentContainer, ControlShape).padding(horizontal = 6.dp, vertical = 2.dp),
                )
                androidx.compose.material3.Text("Quiet text sits here.", style = typography.bodySmall, color = theme.textMuted)
            }
        }
    }
}

@Composable
private fun Cell(text: String, fill: Color, ink: Color, shape: androidx.compose.ui.graphics.Shape, modifier: Modifier, marker: Boolean = false) {
    Row(
        modifier.fillMaxHeight().background(fill, shape).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (marker) AirMarker(onAir = true)
        androidx.compose.material3.Text(text, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = ink, maxLines = 1, overflow = TextOverflow.Clip)
    }
}

@Composable
private fun MenuRowSample(text: String, fill: Color, ink: Color, focused: Boolean) {
    Row(
        Modifier.fillMaxWidth().height(44.dp).background(fill, ControlShape).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        androidx.compose.material3.Icon(Icons.Filled.LiveTv, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
        androidx.compose.material3.Text(text, style = androidx.compose.material3.MaterialTheme.typography.titleSmall, color = ink, modifier = Modifier.weight(1f))
        androidx.compose.material3.Text(if (focused) "On" else "Off", style = androidx.compose.material3.MaterialTheme.typography.labelLarge, color = ink)
    }
}

@Composable
private fun ButtonSample(text: String, fill: Color, ink: Color, modifier: Modifier) {
    Row(
        modifier.height(40.dp).background(fill, ControlShape).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        androidx.compose.material3.Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
        androidx.compose.material3.Text(text, style = androidx.compose.material3.MaterialTheme.typography.labelLarge, color = ink)
    }
}

/** A theme at a glance: its ground, row, focus, text and a guide run, side by side. */
@Composable
fun ThemeSwatches(spec: ThemeSpec, modifier: Modifier = Modifier) {
    val c = spec.colors
    val colors = remember(spec) { listOf(c.background, c.surface, c.accent, c.text, c.guideRuns.first()).map(ResolvedTheme::parseColor) }
    Row(modifier.border(1.dp, LocalAnstelleTheme.current.line, ControlShape).padding(1.dp)) {
        for (color in colors) Box(Modifier.size(width = 12.dp, height = 28.dp).background(color))
    }
}
