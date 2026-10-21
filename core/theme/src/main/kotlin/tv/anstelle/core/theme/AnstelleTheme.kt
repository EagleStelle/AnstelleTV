package tv.anstelle.core.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import tv.anstelle.core.model.SubtitleLook

/** True on TV devices (Android TV, Google TV, Fire TV): D-pad first, 10-foot layouts. */
val LocalIsTv = staticCompositionLocalOf { false }

/** The active guide theme. */
val LocalAnstelleTheme = staticCompositionLocalOf { DefaultTheme.resolved }

/** How the watching profile's plain subtitles look. Only the players read it, so a change recomposes just them. */
val LocalSubtitleLook = compositionLocalOf { SubtitleLook() }

/** Near-square corners: controls, cells and posters. */
val ControlShape = RoundedCornerShape(2.dp)

/** Panels over video and dialogs. */
val PanelShape = RoundedCornerShape(4.dp)

/**
 * The color of a destructive action (delete, remove, hand over): one red outside every theme, so
 * a dangerous control looks the same whatever the profile wears (user decision). It fills the
 * control, with [OnDangerColor] on it, so it reads on dark and light grounds alike.
 */
val DangerColor = Color(0xFFD32F2F)

/** Text and icons on [DangerColor]. */
val OnDangerColor = Color.White

/**
 * Applies a guide theme to both Material 3 (phone layouts) and Compose for TV (TV layouts),
 * so components from either library pick up the same palette. Everything is flat: no tonal
 * tint on surfaces, no shadows; depth comes from the surface steps alone. [subtitleLook] goes
 * to the players inside; a theme nested in another keeps the outer one's.
 */
@Composable
fun AnstelleTheme(
    isTv: Boolean,
    theme: ResolvedTheme = DefaultTheme.resolved,
    subtitleLook: SubtitleLook = LocalSubtitleLook.current,
    content: @Composable () -> Unit,
) {
    val phoneColors = remember(theme) {
        darkColorScheme(
            primary = theme.accent,
            onPrimary = theme.onAccent,
            primaryContainer = theme.accentContainer,
            onPrimaryContainer = theme.onAccentContainer,
            inversePrimary = theme.accentContainer,
            secondary = theme.accent,
            onSecondary = theme.onAccent,
            secondaryContainer = theme.accentContainer,
            onSecondaryContainer = theme.onAccentContainer,
            tertiary = theme.accent,
            onTertiary = theme.onAccent,
            tertiaryContainer = theme.accentContainer,
            onTertiaryContainer = theme.onAccentContainer,
            background = theme.background,
            onBackground = theme.text,
            surface = theme.background,
            onSurface = theme.text,
            surfaceVariant = theme.surfaceHigh,
            onSurfaceVariant = theme.textMuted,
            surfaceTint = Color.Transparent,
            inverseSurface = theme.text,
            inverseOnSurface = theme.background,
            error = theme.accent,
            onError = theme.onAccent,
            errorContainer = theme.accentContainer,
            onErrorContainer = theme.onAccentContainer,
            outline = theme.textMuted,
            outlineVariant = theme.line,
            scrim = Color.Black,
            surfaceBright = theme.surfaceHigh,
            surfaceDim = theme.background,
            surfaceContainerLowest = theme.background,
            surfaceContainerLow = theme.surface,
            surfaceContainer = theme.surface,
            surfaceContainerHigh = theme.surfaceHigh,
            surfaceContainerHighest = theme.line,
        )
    }
    val tvColors = remember(theme) {
        androidx.tv.material3.darkColorScheme(
            primary = theme.accent,
            onPrimary = theme.onAccent,
            primaryContainer = theme.accentContainer,
            onPrimaryContainer = theme.onAccentContainer,
            inversePrimary = theme.accentContainer,
            secondary = theme.accent,
            onSecondary = theme.onAccent,
            secondaryContainer = theme.accentContainer,
            onSecondaryContainer = theme.onAccentContainer,
            tertiary = theme.accent,
            onTertiary = theme.onAccent,
            tertiaryContainer = theme.accentContainer,
            onTertiaryContainer = theme.onAccentContainer,
            background = theme.background,
            onBackground = theme.text,
            surface = theme.background,
            onSurface = theme.text,
            surfaceVariant = theme.surfaceHigh,
            onSurfaceVariant = theme.textMuted,
            surfaceTint = Color.Transparent,
            inverseSurface = theme.text,
            inverseOnSurface = theme.background,
            error = theme.accent,
            onError = theme.onAccent,
            errorContainer = theme.accentContainer,
            onErrorContainer = theme.onAccentContainer,
            border = theme.accent,
            borderVariant = theme.line,
            scrim = Color.Black,
        )
    }
    val typography = remember(theme) { typography(theme) }
    val shapes = remember {
        Shapes(
            extraSmall = ControlShape,
            small = ControlShape,
            medium = ControlShape,
            large = PanelShape,
            extraLarge = PanelShape,
        )
    }

    CompositionLocalProvider(LocalIsTv provides isTv, LocalAnstelleTheme provides theme, LocalSubtitleLook provides subtitleLook) {
        androidx.compose.material3.MaterialTheme(colorScheme = phoneColors, typography = typography, shapes = shapes) {
            androidx.tv.material3.MaterialTheme(
                colorScheme = tvColors,
                typography = tvTypography(typography),
                shapes = androidx.tv.material3.Shapes(
                    extraSmall = ControlShape,
                    small = ControlShape,
                    medium = ControlShape,
                    large = PanelShape,
                    extraLarge = PanelShape,
                ),
            ) {
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

/**
 * The type scale. Display and headline roles use the condensed cut with tabular figures
 * (channel numbers, times, screen titles); title, body and label roles the regular width.
 */
private fun typography(theme: ResolvedTheme): Typography {
    val display = TextStyle(fontFamily = theme.displayFont, fontFeatureSettings = AnstelleFonts.TABULAR)
    val body = TextStyle(fontFamily = theme.bodyFont)
    fun TextStyle.at(size: Float, line: Float, weight: FontWeight, tracking: Float = 0f) = copy(
        fontSize = theme.sp(size),
        lineHeight = theme.sp(line),
        fontWeight = weight,
        letterSpacing = tracking.em,
    )
    return Typography(
        displayLarge = display.at(64f, 68f, FontWeight.Bold, -0.01f),
        displayMedium = display.at(48f, 52f, FontWeight.Bold, -0.01f),
        displaySmall = display.at(36f, 40f, FontWeight.SemiBold),
        headlineLarge = display.at(34f, 38f, FontWeight.SemiBold),
        headlineMedium = display.at(30f, 34f, FontWeight.SemiBold),
        headlineSmall = display.at(24f, 28f, FontWeight.SemiBold),
        titleLarge = body.at(20f, 26f, FontWeight.SemiBold),
        titleMedium = body.at(16f, 22f, FontWeight.SemiBold),
        titleSmall = body.at(14f, 20f, FontWeight.Medium),
        bodyLarge = body.at(16f, 24f, FontWeight.Normal),
        bodyMedium = body.at(14f, 20f, FontWeight.Normal),
        bodySmall = body.at(12f, 16f, FontWeight.Normal),
        labelLarge = body.at(14f, 18f, FontWeight.SemiBold, 0.01f),
        labelMedium = display.at(13f, 16f, FontWeight.SemiBold, 0.06f),
        labelSmall = display.at(11f, 14f, FontWeight.SemiBold, 0.06f),
    )
}

private fun tvTypography(m3: Typography) = androidx.tv.material3.Typography(
    displayLarge = m3.displayLarge,
    displayMedium = m3.displayMedium,
    displaySmall = m3.displaySmall,
    headlineLarge = m3.headlineLarge,
    headlineMedium = m3.headlineMedium,
    headlineSmall = m3.headlineSmall,
    titleLarge = m3.titleLarge,
    titleMedium = m3.titleMedium,
    titleSmall = m3.titleSmall,
    bodyLarge = m3.bodyLarge,
    bodyMedium = m3.bodyMedium,
    bodySmall = m3.bodySmall,
    labelLarge = m3.labelLarge,
    labelMedium = m3.labelMedium,
    labelSmall = m3.labelSmall,
)
