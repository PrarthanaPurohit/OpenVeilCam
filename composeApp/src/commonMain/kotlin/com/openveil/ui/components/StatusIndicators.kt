package com.openveil.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.openveil.ui.theme.OpenVeilColors
import com.openveil.ui.theme.OpenVeilShapes
import com.openveil.ui.theme.OpenVeilTheme
import com.openveil.ui.theme.Spacing

/**
 * How a verifiable claim currently stands.
 *
 * Each state carries its own icon and label, never colour alone. A user who cannot
 * distinguish the green from the amber still reads "Verified" versus "Pending", which is
 * the difference between trusting a photo and not (spec 57).
 */
enum class VerificationState(
    val label: String,
    val icon: OpenVeilIcon,
    val tint: Color,
    val container: Color,
) {
    Verified("Verified", OpenVeilIcon.Check, OpenVeilColors.Success, OpenVeilColors.SuccessSubtle),
    Stored("Stored", OpenVeilIcon.Check, OpenVeilColors.Success, OpenVeilColors.SuccessSubtle),
    Published("Published", OpenVeilIcon.Check, OpenVeilColors.Success, OpenVeilColors.SuccessSubtle),
    Ready("Ready", OpenVeilIcon.Verified, OpenVeilColors.Success, OpenVeilColors.SuccessSubtle),
    Pending("Pending", OpenVeilIcon.HourglassEmpty, OpenVeilColors.Outline, Color(0x14FFFFFF)),
    InProgress("Working", OpenVeilIcon.Sync, OpenVeilColors.Primary, Color(0x1FC4C0FF)),
    Failed("Failed", OpenVeilIcon.Error, OpenVeilColors.Error, Color(0x1FFFB4AB)),
    Unavailable("Unavailable", OpenVeilIcon.CloudOff, OpenVeilColors.Outline, Color(0x14FFFFFF)),
}

/**
 * Small pill stating a verification outcome. Announced as a single phrase
 * ("Content Credentials, Verified") rather than as two loose fragments.
 */
@Composable
fun VerificationBadge(
    state: VerificationState,
    modifier: Modifier = Modifier,
    label: String = state.label,
    subject: String? = null,
) {
    val announcement = if (subject != null) "$subject, $label" else label
    Row(
        modifier = modifier
            .clip(OpenVeilShapes.full)
            .background(state.container)
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
            .semantics(mergeDescendants = true) { contentDescription = announcement },
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MaterialSymbol(state.icon, contentDescription = null, size = 14.dp, tint = state.tint, filled = true)
        Text(label, style = OpenVeilTheme.type.labelCaps, color = state.tint)
    }
}

/**
 * One line of a status list: leading icon, name, trailing badge. Used for the Home
 * identity card and the Success screen's three guarantees.
 */
@Composable
fun StatusRow(
    icon: OpenVeilIcon,
    title: String,
    state: VerificationState,
    modifier: Modifier = Modifier,
    supporting: String? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f, fill = false),
        ) {
            MaterialSymbol(icon, contentDescription = null, tint = OpenVeilColors.OnSurfaceVariant)
            Column {
                Text(title, style = OpenVeilTheme.type.bodyMd, color = OpenVeilColors.OnSurface)
                if (supporting != null) {
                    Text(
                        supporting,
                        style = OpenVeilTheme.type.metadata,
                        color = OpenVeilColors.OnSurfaceVariant,
                    )
                }
            }
        }
        VerificationBadge(state, subject = title)
    }
}

/**
 * Checklist line used on the Review screen. [done] is real state, not decoration -- it
 * flips when the underlying stage actually completes.
 */
@Composable
fun ChecklistRow(
    text: String,
    done: Boolean,
    modifier: Modifier = Modifier,
    inProgress: Boolean = false,
) {
    val stateWord = when {
        done -> "done"
        inProgress -> "in progress"
        else -> "pending"
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = "$text, $stateWord" },
        horizontalArrangement = Arrangement.spacedBy(Spacing.gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(
                    if (done) Color(0x33C4C0FF) else OpenVeilColors.SurfaceContainerHighest
                ),
            contentAlignment = Alignment.Center,
        ) {
            MaterialSymbol(
                icon = if (done) OpenVeilIcon.Check else OpenVeilIcon.HourglassEmpty,
                contentDescription = null,
                size = 16.dp,
                tint = if (done) OpenVeilColors.Primary else OpenVeilColors.Outline,
            )
        }
        Text(
            text,
            style = OpenVeilTheme.type.bodyMd,
            color = if (done) OpenVeilColors.OnSurface else OpenVeilColors.OnSurfaceVariant,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}
