package com.openveil.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.openveil.ui.theme.OpenVeilColors
import com.openveil.ui.theme.OpenVeilTheme
import com.openveil.ui.theme.Spacing

/**
 * Visual state of one stage in the publishing timeline.
 *
 * [Failed] is distinct from [Pending] so a failure keeps the stages that already succeeded
 * marked complete. The user needs to see that the upload survived even though the relay
 * publish did not, because that is exactly what a retry will resume from.
 */
enum class TimelineStepState { Complete, InProgress, Pending, Failed }

/** One labelled stage rendered by the publishing timeline. */
data class TimelineStep(
    val title: String,
    val description: String,
    val state: TimelineStepState,
)

/**
 * Vertical publishing timeline.
 *
 * Steps that have completed stay visibly complete when a later step fails -- that is the
 * whole point. A user whose Nostr publish failed needs to see that their photo is already
 * signed and already uploaded, so "Retry" reads as resuming rather than starting over.
 */
@Composable
fun ProgressTimeline(
    steps: List<TimelineStep>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        steps.forEachIndexed { index, step ->
            TimelineRow(
                step = step,
                showConnector = index != steps.lastIndex,
                connectorActive = step.state == TimelineStepState.Complete,
            )
        }
    }
}

@Composable
private fun TimelineRow(
    step: TimelineStep,
    showConnector: Boolean,
    connectorActive: Boolean,
) {
    val stateWord = when (step.state) {
        TimelineStepState.Complete -> "completed"
        TimelineStepState.InProgress -> "in progress"
        TimelineStepState.Pending -> "waiting"
        TimelineStepState.Failed -> "failed"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "${step.title}, $stateWord. ${step.description}"
            },
    ) {
        // Marker column: indicator plus the connector running to the next step.
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(24.dp)) {
            StepIndicator(step.state)
            if (showConnector) {
                Box(
                    Modifier
                        .width(1.dp)
                        .height(40.dp)
                        .background(
                            if (connectorActive) OpenVeilColors.Primary
                            else OpenVeilColors.OutlineVariant
                        )
                )
            }
        }

        Spacer(Modifier.width(Spacing.md))

        Column(modifier = Modifier.padding(bottom = if (showConnector) Spacing.md else 0.dp)) {
            Text(
                step.title.uppercase(),
                style = OpenVeilTheme.type.labelCaps,
                color = when (step.state) {
                    TimelineStepState.Complete -> OpenVeilColors.Primary
                    TimelineStepState.InProgress -> OpenVeilColors.OnSurface
                    TimelineStepState.Failed -> OpenVeilColors.Error
                    TimelineStepState.Pending -> OpenVeilColors.OnSurfaceVariant
                },
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                step.description,
                style = OpenVeilTheme.type.bodySm,
                color = OpenVeilColors.OnSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StepIndicator(state: TimelineStepState) {
    when (state) {
        TimelineStepState.Complete -> Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(Color(0x33C4C0FF))
                .border(1.dp, OpenVeilColors.Primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            MaterialSymbol(OpenVeilIcon.Check, null, size = 14.dp, tint = OpenVeilColors.Primary, filled = true)
        }

        TimelineStepState.InProgress -> {
            val transition = rememberInfiniteTransition(label = "timelineSpinner")
            val angle by transition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 2400, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "angle",
            )
            Box(
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(OpenVeilColors.SurfaceContainer)
                    .border(1.dp, OpenVeilColors.Primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                MaterialSymbol(
                    OpenVeilIcon.Sync,
                    null,
                    size = 14.dp,
                    tint = OpenVeilColors.Primary,
                    modifier = Modifier.rotate(angle),
                )
            }
        }

        TimelineStepState.Failed -> Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(Color(0x1FFFB4AB))
                .border(1.dp, OpenVeilColors.Error, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            MaterialSymbol(OpenVeilIcon.Error, null, size = 14.dp, tint = OpenVeilColors.Error, filled = true)
        }

        TimelineStepState.Pending -> Box(
            Modifier.size(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(OpenVeilColors.OutlineVariant)
            )
        }
    }
}
