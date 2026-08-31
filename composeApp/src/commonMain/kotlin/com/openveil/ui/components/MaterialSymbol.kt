package com.openveil.ui.components

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.openveil.ui.resources.Res
import com.openveil.ui.resources.symbols_filled
import com.openveil.ui.resources.symbols_outlined
import org.jetbrains.compose.resources.Font

/**
 * Renders one glyph from the subset Material Symbols font.
 *
 * Icons are addressed by codepoint (see the generated [OpenVeilIcon]) rather than by
 * ligature, which is why the bundled font is ~6 KB instead of 10.6 MB.
 *
 * [contentDescription] is required rather than defaulted so that every call site has to
 * make a decision. Pass null only for icons that are genuinely decorative -- one sitting
 * next to a text label that already says the same thing. Anything that carries meaning on
 * its own, especially verification status, must describe itself (spec 57).
 *
 * @param size the rendered glyph box. Expressed in Dp and converted through the current
 *   density so icons keep their layout size when the user scales system text; the text
 *   beside them still scales.
 * @param tint leave unspecified to inherit [LocalContentColor], which is what makes an
 *   icon inside a button automatically take that button's content colour.
 */
@Composable
fun MaterialSymbol(
    icon: OpenVeilIcon,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = Color.Unspecified,
    filled: Boolean = false,
) {
    val family = FontFamily(Font(if (filled) Res.font.symbols_filled else Res.font.symbols_outlined))
    val fontSize = with(LocalDensity.current) { size.toSp() }

    // BasicText, unlike Material's Text, does not fall back to LocalContentColor -- an
    // unspecified colour renders BLACK, which is invisible on this dark theme. Resolving
    // it here is what lets every call site omit the tint and still be legible, and lets an
    // icon inside a Button pick up that button's content colour automatically.
    val resolvedTint = if (tint.isSpecified) tint else LocalContentColor.current

    val semanticsModifier = if (contentDescription == null) {
        Modifier.clearAndSetSemantics { }
    } else {
        Modifier.semantics { this.contentDescription = contentDescription }
    }

    BasicText(
        text = icon.code.toString(),
        modifier = modifier.then(semanticsModifier),
        style = TextStyle(
            fontFamily = family,
            fontSize = fontSize,
            lineHeight = fontSize,
            color = resolvedTint,
            textAlign = TextAlign.Center,
        ),
    )
}
