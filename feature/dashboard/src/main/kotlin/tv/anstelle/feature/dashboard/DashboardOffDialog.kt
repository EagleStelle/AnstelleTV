package tv.anstelle.feature.dashboard

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.runtime.Composable
import tv.anstelle.core.theme.LocalAnstelleTheme
import tv.anstelle.core.theme.components.ActionDialog
import tv.anstelle.core.theme.components.DialogAction

/**
 * Asked every time a profile turns its dashboard off: keep what was kept so far (it returns when
 * the dashboard is on again) or delete it. Back or a tap outside leaves the dashboard on.
 */
@Composable
fun DashboardOffDialog(onKeep: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val theme = LocalAnstelleTheme.current
    val typography = androidx.compose.material3.MaterialTheme.typography
    ActionDialog(
        actions = listOf(
            DialogAction("Keep history", "Comes back when the dashboard is on again.", Icons.Filled.Archive, onKeep),
            DialogAction("Delete history", "Removes everything kept for this profile.", Icons.Filled.DeleteOutline, onDelete),
        ),
        onDismiss = onDismiss,
    ) {
        androidx.compose.material3.Text("Turn off the dashboard?", style = typography.headlineSmall, color = theme.text)
        androidx.compose.material3.Text("Nothing more is kept for this profile.", style = typography.bodyMedium, color = theme.textMuted)
    }
}
