package com.openveil.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.openveil.ui.resources.Res
import com.openveil.ui.resources.hankengrotesk_bold
import com.openveil.ui.resources.hankengrotesk_regular
import com.openveil.ui.resources.hankengrotesk_semibold
import com.openveil.ui.resources.jetbrainsmono_medium
import com.openveil.ui.resources.jetbrainsmono_regular
import org.jetbrains.compose.resources.Font

/**
 * The OpenVeil type scale, ported from the reference design tokens.
 *
 * Two families carry the whole product: Hanken Grotesk for prose and headings, and
 * JetBrains Mono for anything machine-generated -- hashes, event ids, EXIF values,
 * section labels. That split is deliberate: monospace signals "this is data the device
 * recorded", which is the entire point of a provenance app.
 */
data class OpenVeilTypeScale(
    val displayLg: TextStyle,
    val headlineMd: TextStyle,
    val headlineSm: TextStyle,
    val bodyLg: TextStyle,
    val bodyMd: TextStyle,
    val bodySm: TextStyle,
    /** Small uppercase mono, used for section headers and status chips. */
    val labelCaps: TextStyle,
    /** Smallest mono. Hashes, ids, EXIF pairs. */
    val metadata: TextStyle,
    /**
     * Button labels. Deliberately smaller than [headlineSm]: at 20sp a label like
     * "Retake" plus its icon overflows a one-third-width button and wraps mid-word.
     */
    val button: TextStyle,
)

@Composable
internal fun hankenGrotesk(): FontFamily = FontFamily(
    Font(Res.font.hankengrotesk_regular, FontWeight.Normal),
    Font(Res.font.hankengrotesk_semibold, FontWeight.SemiBold),
    Font(Res.font.hankengrotesk_bold, FontWeight.Bold),
)

@Composable
internal fun jetBrainsMono(): FontFamily = FontFamily(
    Font(Res.font.jetbrainsmono_regular, FontWeight.Normal),
    Font(Res.font.jetbrainsmono_medium, FontWeight.Medium),
)

@Composable
internal fun openVeilTypeScale(): OpenVeilTypeScale {
    val sans = hankenGrotesk()
    val mono = jetBrainsMono()
    return OpenVeilTypeScale(
        displayLg = TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.Bold,
            fontSize = 32.sp,
            lineHeight = 40.sp,
            letterSpacing = (-0.02).em,
        ),
        headlineMd = TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 24.sp,
            lineHeight = 32.sp,
            letterSpacing = (-0.01).em,
        ),
        headlineSm = TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 20.sp,
            lineHeight = 28.sp,
        ),
        bodyLg = TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.Normal,
            fontSize = 18.sp,
            lineHeight = 28.sp,
        ),
        bodyMd = TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.Normal,
            fontSize = 16.sp,
            lineHeight = 24.sp,
        ),
        bodySm = TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        ),
        labelCaps = TextStyle(
            fontFamily = mono,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.05.em,
        ),
        metadata = TextStyle(
            fontFamily = mono,
            fontWeight = FontWeight.Normal,
            fontSize = 11.sp,
            lineHeight = 14.sp,
        ),
        button = TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            lineHeight = 20.sp,
        ),
    )
}
