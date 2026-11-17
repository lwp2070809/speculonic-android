package de.lwp2070809.speculonic.ui.screens.player.components

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import coil3.compose.AsyncImage
import de.lwp2070809.speculonic.R
import de.lwp2070809.speculonic.data.PlayerBackgroundMode
import de.lwp2070809.speculonic.ui.composition.CoverArtRequester
import de.lwp2070809.speculonic.ui.composition.LocalCoverArtRequester
import kotlinx.coroutines.delay

private const val BLUR_BACKGROUND_DEBOUNCE_MS = 150L

@Composable
fun ArtworkView(
    artworkId: String?,
    artworkUri: android.net.Uri?,
    coverRequester: CoverArtRequester = LocalCoverArtRequester.current
) {
    if (artworkUri != null || artworkId != null) {
        val context = LocalContext.current
        val requestModel = remember(artworkId, artworkUri, coverRequester, context) {
            coverRequester.buildCoverArtRequest(
                id = artworkId,
                context = context,
                preferLocal = true,
                crossfade = false,
                fallbackUri = artworkUri
            )
        }

        DualSlotCrossfadeImage(
            requestModel = requestModel,
            durationMillis = 500,
            maxAlpha = 1f,
            animateTransitions = true,
            contentScale = ContentScale.Fit,
            containerModifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = androidx.compose.ui.res.painterResource(id = R.drawable.ic_symbol_music_note),
                contentDescription = null,
                modifier = Modifier.size(120.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
fun PlayerBlurBackground(
    artworkId: String?,
    artworkUri: android.net.Uri?,
    playerBackgroundMode: PlayerBackgroundMode,
    modifier: Modifier = Modifier
) {
    if (playerBackgroundMode != PlayerBackgroundMode.GAUSSIAN_BLUR) return

    val coverRequester = LocalCoverArtRequester.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var debouncedArtworkId by remember { mutableStateOf(artworkId) }
    var debouncedArtworkUri by remember { mutableStateOf(artworkUri) }

    LaunchedEffect(artworkId, artworkUri) {
        delay(BLUR_BACKGROUND_DEBOUNCE_MS)
        debouncedArtworkId = artworkId
        debouncedArtworkUri = artworkUri
    }

    val requestModel = remember(debouncedArtworkId, debouncedArtworkUri, coverRequester, context) {
        if (debouncedArtworkId == null && debouncedArtworkUri == null) {
            null
        } else {
            coverRequester.buildCoverArtRequest(
                id = debouncedArtworkId,
                context = context,
                preferLocal = true,
                size = 300,
                crossfade = false,
                fallbackUri = debouncedArtworkUri
            )
        }
    }

    val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()
    val isAppVisible = lifecycleState.isAtLeast(Lifecycle.State.STARTED)

    DualSlotCrossfadeImage(
        requestModel = requestModel,
        durationMillis = 800,
        maxAlpha = 0.5f,
        animateTransitions = isAppVisible,
        contentScale = ContentScale.Crop,
        imageModifier = Modifier.blur(50.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded),
        containerModifier = modifier.fillMaxSize()
    )
}

private data class ArtworkCrossfadeState(
    val slot0Model: Any? = null,
    val slot1Model: Any? = null,
    val slot0Ready: Boolean = false,
    val slot1Ready: Boolean = false,
    val slot0Failed: Boolean = false,
    val slot1Failed: Boolean = false,
    val slot0Attempt: Int = 0,
    val slot1Attempt: Int = 0,
    val displayedSlot: Int = 0,
    val targetSlot: Int = 0,
    val hasDisplayedInitialArtwork: Boolean = false
) {
    val isReadyToDisplay: Boolean
        get() = targetSlot != -1 && ((displayedSlot == 0 && slot0Ready) || (displayedSlot == 1 && slot1Ready))

    fun onRequestChanged(requestModel: Any?): ArtworkCrossfadeState {
        if (requestModel == null) {
            return copy(targetSlot = -1)
        }
        return when {
            slot0Model == requestModel -> copy(
                targetSlot = 0,
                displayedSlot = if (slot0Ready) 0 else displayedSlot,
                slot0Failed = if (slot0Failed) false else slot0Failed,
                slot0Attempt = if (slot0Failed) slot0Attempt + 1 else slot0Attempt
            )
            slot1Model == requestModel -> copy(
                targetSlot = 1,
                displayedSlot = if (slot1Ready) 1 else displayedSlot,
                slot1Failed = if (slot1Failed) false else slot1Failed,
                slot1Attempt = if (slot1Failed) slot1Attempt + 1 else slot1Attempt
            )
            displayedSlot == 0 && slot0Model != null -> copy(
                slot1Model = requestModel,
                slot1Ready = false,
                slot1Failed = false,
                targetSlot = 1
            )
            else -> copy(
                slot0Model = requestModel,
                slot0Ready = false,
                slot0Failed = false,
                targetSlot = 0
            )
        }
    }

    fun onSlotResult(slotIndex: Int, model: Any?, success: Boolean): ArtworkCrossfadeState {
        val currentSlotModel = if (slotIndex == 0) slot0Model else slot1Model
        if (currentSlotModel != model) return this
        val nextDisplayed = if (targetSlot == slotIndex) slotIndex else displayedSlot
        return if (slotIndex == 0) {
            copy(slot0Ready = success, slot0Failed = !success, displayedSlot = nextDisplayed)
        } else {
            copy(slot1Ready = success, slot1Failed = !success, displayedSlot = nextDisplayed)
        }
    }

    fun onInitialArtworkCommitted(): ArtworkCrossfadeState =
        if (!hasDisplayedInitialArtwork && isReadyToDisplay) {
            copy(hasDisplayedInitialArtwork = true)
        } else {
            this
        }
}

/**
 * 共享双槽位封面交叉淡入淡出组件（收敛 P1-09 与 P3-02）：
 * 先将候选槽位的就绪状态置 false 再换模型，完全依托 displayedSlot 与 animateFloatAsState 驱动淡入淡出，
 * 并通过 slotXAttempt 在失败后切回同一模型时强制触发重载、首帧就绪时跳过淡入延迟。
 */
@Composable
private fun DualSlotCrossfadeImage(
    requestModel: Any?,
    durationMillis: Int,
    maxAlpha: Float,
    animateTransitions: Boolean,
    contentScale: ContentScale,
    containerModifier: Modifier = Modifier,
    imageModifier: Modifier = Modifier
) {
    var state by remember { mutableStateOf(ArtworkCrossfadeState()) }

    LaunchedEffect(requestModel) {
        state = state.onRequestChanged(requestModel)
    }

    LaunchedEffect(state.isReadyToDisplay) {
        if (state.isReadyToDisplay) {
            state = state.onInitialArtworkCommitted()
        }
    }

    val shouldAnimate = animateTransitions && state.hasDisplayedInitialArtwork
    val animSpec = if (shouldAnimate) {
        tween<Float>(durationMillis = durationMillis, easing = EaseInOut)
    } else {
        snap()
    }
    val alpha0 by animateFloatAsState(
        targetValue = if (state.targetSlot != -1 && state.displayedSlot == 0 && state.slot0Ready) maxAlpha else 0f,
        animationSpec = animSpec,
        label = "crossfadeAlpha0"
    )
    val alpha1 by animateFloatAsState(
        targetValue = if (state.targetSlot != -1 && state.displayedSlot == 1 && state.slot1Ready) maxAlpha else 0f,
        animationSpec = animSpec,
        label = "crossfadeAlpha1"
    )

    Box(modifier = containerModifier) {
        ArtworkSlotImage(
            model = state.slot0Model,
            attempt = state.slot0Attempt,
            alpha = alpha0,
            contentScale = contentScale,
            imageModifier = imageModifier,
            onResult = { model, success -> state = state.onSlotResult(0, model, success) }
        )
        ArtworkSlotImage(
            model = state.slot1Model,
            attempt = state.slot1Attempt,
            alpha = alpha1,
            contentScale = contentScale,
            imageModifier = imageModifier,
            onResult = { model, success -> state = state.onSlotResult(1, model, success) }
        )
    }
}

@Composable
private fun ArtworkSlotImage(
    model: Any?,
    attempt: Int,
    alpha: Float,
    contentScale: ContentScale,
    imageModifier: Modifier,
    onResult: (model: Any, success: Boolean) -> Unit
) {
    if (model == null) return
    key(attempt) {
        AsyncImage(
            model = model,
            contentDescription = null,
            onSuccess = { onResult(model, true) },
            onError = { onResult(model, false) },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { this.alpha = alpha }
                .then(imageModifier),
            contentScale = contentScale
        )
    }
}
