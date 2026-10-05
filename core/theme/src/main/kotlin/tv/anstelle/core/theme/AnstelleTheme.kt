package tv.anstelle.core.theme

import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** True on TV devices (Android TV, Google TV, Fire TV): D-pad first, 10-foot layouts. */
val LocalIsTv = staticCompositionLocalOf { false }

/** The active guide theme. */
val LocalAnstelleTheme = staticCompositionLocalOf { DefaultTheme.resolved }

/**
 * Applies a guide theme to both Material 3 (phone layouts) and Compose for TV
 * (TV layouts), so components from either library pick up the same palette.
 */
@Composable
fun AnstelleTheme(
    isTv: Boolean,
    theme: ResolvedTheme = DefaultTheme.resolved,
    content: @Composable () -> Unit,
) {
    val phoneColors = remember(theme) {
        darkColorScheme(
            primary = theme.primary,
            onPrimary = theme.onPrimary,
            secondary = theme.secondary,
            background = theme.background,
            onBackground = theme.text,
            surface = theme.surface,
            onSurface = theme.text,
            surfaceVariant = theme.surfaceVariant,
            onSurfaceVariant = theme.textMuted,
            surfaceContainer = theme.surface,
            surfaceContainerHigh = theme.surfaceVariant,
        )
    }
    val tvColors = remember(theme) {
        androidx.tv.material3.darkColorScheme(
            primary = theme.primary,
            onPrimary = theme.onPrimary,
            secondary = theme.secondary,
            background = theme.background,
            onBackground = theme.text,
            surface = theme.surface,
            onSurface = theme.text,
            surfaceVariant = theme.surfaceVariant,
            onSurfaceVariant = theme.textMuted,
            border = theme.focus,
        )
    }
    val typography = remember(theme) { typography(theme) }

    CompositionLocalProvider(LocalIsTv provides isTv, LocalAnstelleTheme provides theme) {
        androidx.compose.material3.MaterialTheme(colorScheme = phoneColors, typography = typography) {
            androidx.tv.material3.MaterialTheme(colorScheme = tvColors, typography = tvTypography(typography)) {
                // Text outside a Surface would otherwise default to black on the dark background.
                CompositionLocalProvider(
                    androidx.compose.material3.LocalContentColor provides theme.text,
                    androidx.tv.material3.LocalContentColor provides theme.text,
                    content = content,
                )
            }
        }
    }
}

private fun typography(theme: ResolvedTheme): Typography {
    val scale = theme.spec.typography.scale
    val display = TextStyle(fontFamily = theme.displayFont, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontFamily = theme.bodyFont)
    return Typography(
        displaySmall = display.copy(fontSize = (36 * scale).sp),
        headlineMedium = display.copy(fontSize = (28 * scale).sp),
        headlineSmall = display.copy(fontSize = (24 * scale).sp),
        titleLarge = display.copy(fontSize = (22 * scale).sp),
        titleMedium = body.copy(fontSize = (18 * scale).sp, fontWeight = FontWeight.Medium),
        bodyLarge = body.copy(fontSize = (16 * scale).sp),
        bodyMedium = body.copy(fontSize = (14 * scale).sp),
        labelLarge = body.copy(fontSize = (14 * scale).sp, fontWeight = FontWeight.Medium),
        labelMedium = body.copy(fontSize = (12 * scale).sp, fontWeight = FontWeight.Medium),
    )
}

private fun tvTypography(m3: Typography) = androidx.tv.material3.Typography(
    displaySmall = m3.displaySmall,
    headlineMedium = m3.headlineMedium,
    headlineSmall = m3.headlineSmall,
    titleLarge = m3.titleLarge,
    titleMedium = m3.titleMedium,
    bodyLarge = m3.bodyLarge,
    bodyMedium = m3.bodyMedium,
    labelLarge = m3.labelLarge,
    labelMedium = m3.labelMedium,
)
