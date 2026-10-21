package tv.anstelle.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.LocalIsTv
import tv.anstelle.core.theme.PanelShape

/*
 * The parts a settings readout is built from: the set's system-information panel, the way the
 * server's page reads. A hairline frame with no fill (so it never reads as something to press),
 * headings in the OSD's field voice behind the icon that names them, figures in tabular digits
 * over their names, facts behind their icons, and checks that read without color.
 */

/** A readout's frame: a hairline with no fill, its parts a fixed rhythm apart. */
@Composable
internal fun ReadoutFrame(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val isTv = LocalIsTv.current
    Column(
        modifier
            .fillMaxWidth()
            .border(1.dp, LocalAnstelleTheme.current.line, PanelShape)
            .padding(if (isTv) 24.dp else 18.dp),
        verticalArrangement = Arrangement.spacedBy(if (isTv) 18.dp else 14.dp),
        content = content,
    )
}

/** A hairline across the frame, between a readout's parts. */
@Composable
internal fun ReadoutRule() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(LocalAnstelleTheme.current.line))
}

/**
 * A part's heading: the icon that names it and its name in the field voice, with its setting at
 * the row's end in bone, so the eye runs from what to how.
 */
@Composable
internal fun ReadoutHeading(icon: ImageVector, name: String, value: String? = null) {
    val theme = LocalAnstelleTheme.current
    val typography = androidx.compose.material3.MaterialTheme.typography
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Icon(icon, contentDescription = null, tint = theme.textMuted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(10.dp))
        androidx.compose.material3.Text(name.uppercase(), style = typography.labelMedium, color = theme.textMuted, maxLines = 1, modifier = Modifier.weight(1f))
        if (value != null) {
            androidx.compose.material3.Text(value.uppercase(), style = typography.labelMedium, color = theme.text, maxLines = 1)
        }
    }
}

/** Figures side by side, wrapping when the column runs out. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReadoutFigures(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(if (LocalIsTv.current) 36.dp else 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** One figure: the number in tabular digits over its name; [lead] sets the readout's main figure a size up. */
@Composable
internal fun ReadoutFigure(value: Int, name: String, lead: Boolean = false) {
    val theme = LocalAnstelleTheme.current
    val typography = androidx.compose.material3.MaterialTheme.typography
    val isTv = LocalIsTv.current
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        androidx.compose.material3.Text(
            "%,d".format(value),
            style = when {
                lead -> typography.displaySmall
                isTv -> typography.headlineMedium
                else -> typography.headlineSmall
            },
            color = if (value == 0) theme.textMuted else theme.text,
            maxLines = 1,
        )
        androidx.compose.material3.Text(name.uppercase(), style = typography.labelSmall, color = theme.textMuted, maxLines = 1)
    }
}

/**
 * One fact: the icon names it, so the text stands without a label; [detail] follows it quietly.
 * With no [icon] the text lines up under the fact above it.
 */
@Composable
internal fun ReadoutFact(icon: ImageVector?, text: String, detail: String? = null, quiet: Boolean = false, modifier: Modifier = Modifier) {
    val theme = LocalAnstelleTheme.current
    val typography = androidx.compose.material3.MaterialTheme.typography
    Row(modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (icon != null) androidx.compose.material3.Icon(icon, contentDescription = null, tint = theme.textMuted, modifier = Modifier.size(18.dp)) else Spacer(Modifier.size(18.dp))
        androidx.compose.material3.Text(text, style = typography.bodyMedium, color = if (quiet) theme.textMuted else theme.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (detail != null) {
            androidx.compose.material3.Text(detail, style = typography.bodyMedium, color = theme.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Facts side by side, wrapping when the column runs out. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReadoutFacts(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(if (LocalIsTv.current) 28.dp else 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** Whether something is kept or sent: a check or a dash before its name, so it reads without color. */
@Composable
internal fun ReadoutCheck(on: Boolean, name: String) {
    val theme = LocalAnstelleTheme.current
    Row(
        Modifier.semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        androidx.compose.material3.Icon(
            if (on) Icons.Filled.Check else Icons.Filled.Remove,
            contentDescription = if (on) "Yes" else "No",
            tint = if (on) theme.accent else theme.textMuted,
            modifier = Modifier.size(18.dp),
        )
        androidx.compose.material3.Text(
            name,
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            color = if (on) theme.text else theme.textMuted,
            maxLines = 1,
        )
    }
}

/** An ordered list's entry: its place in tabular digits, then its name. */
@Composable
internal fun ReadoutRank(rank: Int, name: String, modifier: Modifier = Modifier) {
    val theme = LocalAnstelleTheme.current
    val typography = androidx.compose.material3.MaterialTheme.typography
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.material3.Text(
            "$rank",
            style = typography.bodyMedium.merge(TextStyle(fontFamily = theme.displayFont, fontFeatureSettings = "tnum")),
            color = theme.textMuted,
            modifier = Modifier.widthIn(min = 12.dp),
        )
        androidx.compose.material3.Text(name, style = typography.bodyMedium, color = theme.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
