package tv.anstelle.core.theme.components

import android.os.Build
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Blurs a title, logo or episode line of an age-restricted program when [enabled], keeping its
 * size so nothing around it moves (user decision: the one blur in the design). Blur needs API
 * 31; older devices (Fire OS) get a [bar] in its place instead. Screen readers hear "Age
 * restricted", not the title.
 */
fun Modifier.obscured(enabled: Boolean, bar: Color, radius: Dp = 8.dp): Modifier {
    if (!enabled) return this
    val hidden = clearAndSetSemantics { contentDescription = "Age restricted" }
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        hidden.blur(radius, BlurredEdgeTreatment.Unbounded)
    } else {
        hidden.drawWithContent { drawRoundRect(bar.copy(alpha = BAR_ALPHA), cornerRadius = CornerRadius(2.dp.toPx())) }
    }
}

private const val BAR_ALPHA = 0.35f
