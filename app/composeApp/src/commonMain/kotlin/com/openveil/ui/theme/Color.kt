package com.openveil.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The OpenVeil palette, ported verbatim from the reference design's token set.
 *
 * This is a dark-only scheme by design: the product is a photography surface and a light
 * mode would fight the imagery. There is deliberately no light variant.
 *
 * Never hard-code a hex value in a composable -- add it here first.
 */
object OpenVeilColors {

    // Primary
    val Primary = Color(0xFFC4C0FF)
    val OnPrimary = Color(0xFF2000A4)
    val PrimaryContainer = Color(0xFF8781FF)
    val OnPrimaryContainer = Color(0xFF1B0091)
    val PrimaryFixed = Color(0xFFE3DFFF)
    val PrimaryFixedDim = Color(0xFFC4C0FF)
    val OnPrimaryFixed = Color(0xFF100069)
    val OnPrimaryFixedVariant = Color(0xFF3622CA)
    val InversePrimary = Color(0xFF4F44E2)

    // Secondary
    val Secondary = Color(0xFFBEC7D4)
    val OnSecondary = Color(0xFF28313B)
    val SecondaryContainer = Color(0xFF414A54)
    val OnSecondaryContainer = Color(0xFFB0B9C5)
    val SecondaryFixed = Color(0xFFDAE3F0)
    val SecondaryFixedDim = Color(0xFFBEC7D4)
    val OnSecondaryFixed = Color(0xFF141C25)
    val OnSecondaryFixedVariant = Color(0xFF3F4852)

    // Tertiary
    val Tertiary = Color(0xFFFFB785)
    val OnTertiary = Color(0xFF502500)
    val TertiaryContainer = Color(0xFFDB761F)
    val OnTertiaryContainer = Color(0xFF461F00)
    val TertiaryFixed = Color(0xFFFFDCC6)
    val TertiaryFixedDim = Color(0xFFFFB785)
    val OnTertiaryFixed = Color(0xFF301400)
    val OnTertiaryFixedVariant = Color(0xFF713700)

    // Surfaces
    val Background = Color(0xFF13121B)
    val OnBackground = Color(0xFFE4E1EE)
    val Surface = Color(0xFF13121B)
    val OnSurface = Color(0xFFE4E1EE)
    val SurfaceVariant = Color(0xFF35343E)
    val OnSurfaceVariant = Color(0xFFC7C4D8)
    val SurfaceDim = Color(0xFF13121B)
    val SurfaceBright = Color(0xFF393842)
    val SurfaceContainerLowest = Color(0xFF0E0D16)
    val SurfaceContainerLow = Color(0xFF1B1B24)
    val SurfaceContainer = Color(0xFF1F1F28)
    val SurfaceContainerHigh = Color(0xFF2A2933)
    val SurfaceContainerHighest = Color(0xFF35343E)
    val SurfaceTint = Color(0xFFC4C0FF)
    val InverseSurface = Color(0xFFE4E1EE)
    val InverseOnSurface = Color(0xFF302F39)

    // Outlines
    val Outline = Color(0xFF918FA1)
    val OutlineVariant = Color(0xFF464555)

    // Error
    val Error = Color(0xFFFFB4AB)
    val OnError = Color(0xFF690005)
    val ErrorContainer = Color(0xFF93000A)
    val OnErrorContainer = Color(0xFFFFDAD6)

    /**
     * Verification / success accent. The reference used two different greens across
     * screens (#4ADE80 and #4CAF50); standardised here on the brighter one, which clears
     * WCAG AA against [Background]. Never the sole carrier of meaning -- always pair it
     * with an icon and a text label (spec 57).
     */
    val Success = Color(0xFF4ADE80)
    val SuccessSubtle = Color(0x1A4ADE80)

    /** Hairline border used on the reference's glass cards: rgba(255,255,255,0.06). */
    val GlassBorder = Color(0x0FFFFFFF)

    /** Scrim over the camera preview so white controls stay legible on bright scenes. */
    val PreviewScrim = Color(0x33000000)
}
