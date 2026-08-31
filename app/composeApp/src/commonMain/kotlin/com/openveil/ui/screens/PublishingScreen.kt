package com.openveil.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.openveil.domain.model.PublishError
import com.openveil.domain.model.PublishStatus
import com.openveil.ui.components.GlassCard
import com.openveil.ui.components.OpenVeilButton
import com.openveil.ui.components.OpenVeilSecondaryButton
import com.openveil.ui.components.ProgressTimeline
import com.openveil.ui.components.TimelineStep
import com.openveil.ui.components.TimelineStepState
import com.openveil.ui.components.decodeImageForDisplay
import com.openveil.ui.theme.OpenVeilColors
import com.openveil.ui.theme.OpenVeilShapes
import com.openveil.ui.theme.OpenVeilTheme
import com.openveil.ui.theme.Spacing

/** Live pipeline state driving the publishing timeline. */
data class PublishingUiState(
    val imageBytes: ByteArray,
    val status: PublishStatus,
    val error: PublishError? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is PublishingUiState &&
            imageBytes.contentEquals(other.imageBytes) &&
            status == other.status && error == other.error)

    override fun hashCode(): Int = imageBytes.contentHashCode() * 31 + status.hashCode()
}

/**
 * The publishing progress view, and -- when a stage fails -- the recovery view.
 *
 * Failure is a state of this screen rather than a separate destination, so completed
 * stages stay visibly complete. That is what makes "Retry" legible as resuming: a user
 * whose Nostr publish failed can see their photo is already signed and already stored,
 * and the retry will not redo either.
 */
@Composable
fun PublishingScreen(
    state: PublishingUiState,
    onRetry: () -> Unit,
    onSaveForLater: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val failed = state.status == PublishStatus.FAILED && state.error != null

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OpenVeilColors.Background)
            .systemBarsPadding()
            .padding(Spacing.containerMargin),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val bitmap = remember(state.imageBytes) { decodeImageForDisplay(state.imageBytes) }
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.size(96.dp).clip(OpenVeilShapes.medium),
                contentScale = ContentScale.Crop,
            )
        }

        Spacer(Modifier.height(Spacing.lg))

        Text(
            if (failed) failureTitle(state.error!!) else "Publishing your photo",
            style = OpenVeilTheme.type.headlineMd,
            color = OpenVeilColors.OnSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            if (failed) failureBody(state.error!!) else "Keep the app open while we secure your photo.",
            style = OpenVeilTheme.type.bodySm,
            color = OpenVeilColors.OnSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(Spacing.xl))

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            ProgressTimeline(steps = timelineFor(state.status, state.error))
        }

        if (failed) {
            Spacer(Modifier.height(Spacing.lg))
            OpenVeilButton(text = retryLabel(state.error!!), onClick = onRetry)
            Spacer(Modifier.height(Spacing.sm))
            OpenVeilSecondaryButton(text = "Save for later", onClick = onSaveForLater)
        }
    }
}

/**
 * Maps pipeline status onto the three visible stages.
 *
 * Signing normally completes while the user is still on the Review screen, so step one
 * usually arrives here already green. It is still shown -- seeing that provenance was
 * created is the reassurance the screen exists to give.
 */
private val PUBLISH_STAGES = listOf(
    "Creating Content Credentials" to "Adding verifiable provenance to your photo.",
    "Uploading photo" to "Storing your signed original on Blossom.",
    "Publishing to Nostr" to "Announcing the photo and its fingerprint to relays.",
)

/** Which visible stage a failure belongs to, derived from where a retry would resume. */
private fun stageIndexOf(resumeAt: PublishStatus): Int = when (resumeAt) {
    PublishStatus.CAPTURED -> 0
    PublishStatus.C2PA_SIGNED -> 1
    PublishStatus.BLOSSOM_UPLOADED -> 2
    else -> 0
}

internal fun timelineFor(status: PublishStatus, error: PublishError?): List<TimelineStep> {
    val failedIndex = if (status == PublishStatus.FAILED && error != null) {
        stageIndexOf(error.resumeAt)
    } else {
        -1
    }

    // completed = stages fully behind us; active = the one currently running (-1 if none).
    val completed: Int
    val active: Int
    when (status) {
        PublishStatus.CAPTURED -> { completed = 0; active = -1 }
        PublishStatus.C2PA_SIGNING -> { completed = 0; active = 0 }
        PublishStatus.C2PA_SIGNED -> { completed = 1; active = -1 }
        PublishStatus.UPLOADING_BLOSSOM -> { completed = 1; active = 1 }
        PublishStatus.BLOSSOM_UPLOADED -> { completed = 2; active = -1 }
        PublishStatus.PUBLISHING_NOSTR -> { completed = 2; active = 2 }
        PublishStatus.PUBLISHED -> { completed = 3; active = -1 }
        // On failure every stage before the failing one genuinely did succeed, and the
        // timeline must keep showing that so retry reads as resuming, not restarting.
        PublishStatus.FAILED -> { completed = failedIndex.coerceAtLeast(0); active = -1 }
    }

    return PUBLISH_STAGES.mapIndexed { index, (title, description) ->
        TimelineStep(
            title = title,
            description = description,
            state = when {
                index == failedIndex -> TimelineStepState.Failed
                index < completed -> TimelineStepState.Complete
                index == active -> TimelineStepState.InProgress
                else -> TimelineStepState.Pending
            },
        )
    }
}

internal fun failureTitle(error: PublishError): String = when (error) {
    PublishError.C2PA_FAILED, PublishError.HASH_FAILED -> "Signing couldn't be completed"
    PublishError.BLOSSOM_AUTH_FAILED, PublishError.BLOSSOM_UPLOAD_FAILED -> "Upload couldn't be completed"
    PublishError.NOSTR_SIGNING_FAILED, PublishError.NOSTR_PUBLISH_FAILED -> "Publication didn't complete"
    PublishError.NO_NETWORK -> "You're offline"
    PublishError.CAMERA_FAILED -> "Capture failed"
}

internal fun failureBody(error: PublishError): String = when (error) {
    PublishError.C2PA_FAILED, PublishError.HASH_FAILED ->
        "We couldn't create Content Credentials for this photo. It is still safe on this device."
    PublishError.BLOSSOM_AUTH_FAILED, PublishError.BLOSSOM_UPLOAD_FAILED ->
        "We couldn't securely upload your photo. Your original is still safe on this device."
    PublishError.NOSTR_SIGNING_FAILED, PublishError.NOSTR_PUBLISH_FAILED ->
        "Your photo was uploaded successfully, but publishing didn't finish. Retrying won't upload it again."
    PublishError.NO_NETWORK ->
        "Your signed photo is saved on this device. We'll finish publishing when you're back online."
    PublishError.CAMERA_FAILED -> "The camera didn't return a photo."
}

internal fun retryLabel(error: PublishError): String = when (error) {
    PublishError.NOSTR_SIGNING_FAILED, PublishError.NOSTR_PUBLISH_FAILED -> "Retry publication"
    else -> "Try again"
}
