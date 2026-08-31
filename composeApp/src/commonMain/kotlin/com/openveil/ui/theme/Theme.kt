package com.openveil.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Access to the OpenVeil type scale. Material 3's own [MaterialTheme.typography] is left
 * populated for the stock components, but OpenVeil screens should read from here so the
 * named scale from the design tokens stays intact.
 */
val LocalOpenVeilType = staticCompositionLocalOf<OpenVeilTypeScale> {
    error("OpenVeilTypeScale requested outside of OpenVeilTheme")
}

/** Shorthand: `OpenVeilTheme.type.labelCaps`. */
object OpenVeilTheme {
    val type: OpenVeilTypeScale
        @Composable get() = LocalOpenVeilType.current
}

private val OpenVeilColorScheme = darkColorScheme(
    primary = OpenVeilColors.Primary,
    onPrimary = OpenVeilColors.OnPrimary,
    primaryContainer = OpenVeilColors.PrimaryContainer,
    onPrimaryContainer = OpenVeilColors.OnPrimaryContainer,
    inversePrimary = OpenVeilColors.InversePrimary,
    secondary = OpenVeilColors.Secondary,
    onSecondary = OpenVeilColors.OnSecondary,
    secondaryContainer = OpenVeilColors.SecondaryContainer,
    onSecondaryContainer = OpenVeilColors.OnSecondaryContainer,
    tertiary = OpenVeilColors.Tertiary,
    onTertiary = OpenVeilColors.OnTertiary,
    tertiaryContainer = OpenVeilColors.TertiaryContainer,
    onTertiaryContainer = OpenVeilColors.OnTertiaryContainer,
    background = OpenVeilColors.Background,
    onBackground = OpenVeilColors.OnBackground,
    surface = OpenVeilColors.Surface,
    onSurface = OpenVeilColors.OnSurface,
    surfaceVariant = OpenVeilColors.SurfaceVariant,
    onSurfaceVariant = OpenVeilColors.OnSurfaceVariant,
    surfaceTint = OpenVeilColors.SurfaceTint,
    inverseSurface = OpenVeilColors.InverseSurface,
    inverseOnSurface = OpenVeilColors.InverseOnSurface,
    error = OpenVeilColors.Error,
    onError = OpenVeilColors.OnError,
    errorContainer = OpenVeilColors.ErrorContainer,
    onErrorContainer = OpenVeilColors.OnErrorContainer,
    outline = OpenVeilColors.Outline,
    outlineVariant = OpenVeilColors.OutlineVariant,
    surfaceBright = OpenVeilColors.SurfaceBright,
    surfaceDim = OpenVeilColors.SurfaceDim,
    surfaceContainerLowest = OpenVeilColors.SurfaceContainerLowest,
    surfaceContainerLow = OpenVeilColors.SurfaceContainerLow,
    surfaceContainer = OpenVeilColors.SurfaceContainer,
    surfaceContainerHigh = OpenVeilColors.SurfaceContainerHigh,
    surfaceContainerHighest = OpenVeilColors.SurfaceContainerHighest,
)

/**
 * Dark-only by design -- see [OpenVeilColors]. There is no `darkTheme` parameter because
 * a light variant would fight the photography this app exists to present.
 */
@Composable
fun OpenVeilTheme(content: @Composable () -> Unit) {
    val typeScale = openVeilTypeScale()
    // MaterialTheme does not set LocalContentColor -- only Surface does, and these
    // screens paint their own backgrounds instead. Without this the default content
    // colour stays Material's black, which is invisible on a dark background.
    CompositionLocalProvider(
        LocalOpenVeilType provides typeScale,
        LocalContentColor provides OpenVeilColors.OnSurface,
    ) {
        MaterialTheme(
            colorScheme = OpenVeilColorScheme,
            typography = MaterialTheme.typography.copy(
                bodyLarge = typeScale.bodyMd,
                bodyMedium = typeScale.bodySm,
                titleLarge = typeScale.headlineSm,
                labelLarge = typeScale.bodyMd,
            ),
            content = content,
        )
    }
}
