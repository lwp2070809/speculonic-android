package de.lwp2070809.speculonic.ui.screens.player.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import coil3.compose.AsyncImage
import de.lwp2070809.speculonic.data.PlayerBackgroundMode
import de.lwp2070809.speculonic.domain.repository.SubsonicRepository
import de.lwp2070809.speculonic.ui.composition.LocalSubsonicRepository
import kotlinx.coroutines.launch

@Composable
fun ArtworkView(
    artworkId: String?,
    artworkUri: android.net.Uri?,
    repository: SubsonicRepository
) {
    if (artworkUri != null || artworkId != null) {
        val context = LocalContext.current
        val coroutineScope = rememberCoroutineScope()

        var activeSlot by remember { mutableIntStateOf(0) }
        var model0 by remember { mutableStateOf<Any?>(null) }
        var model1 by remember { mutableStateOf<Any?>(null) }

        val alpha0 = remember { Animatable(1f) }
        val alpha1 = remember { Animatable(0f) }

        LaunchedEffect(artworkId, artworkUri) {
            if (artworkId == null && artworkUri == null) return@LaunchedEffect

            val newModel = repository.buildCoverArtRequest(
                id = artworkId,
                context = context,
                preferLocal = true,
                crossfade = false
            )

            if (model0 == newModel) {
                activeSlot = 0
                if (alpha0.value < 1f) {
                    coroutineScope.launch {
                        launch { alpha0.animateTo(1f, tween(500, easing = EaseInOut)) }
                        launch { alpha1.animateTo(0f, tween(500, easing = EaseInOut)) }
                    }
                }
                return@LaunchedEffect
            }
            if (model1 == newModel) {
                activeSlot = 1
                if (alpha1.value < 1f) {
                    coroutineScope.launch {
                        launch { alpha1.animateTo(1f, tween(500, easing = EaseInOut)) }
                        launch { alpha0.animateTo(0f, tween(500, easing = EaseInOut)) }
                    }
                }
                return@LaunchedEffect
            }

            if (model0 == null) {
                model0 = newModel
                coroutineScope.launch { alpha0.snapTo(1f) }
                activeSlot = 0
            } else if (model1 == null) {
                model1 = newModel
                coroutineScope.launch { alpha1.snapTo(0f) }
                activeSlot = 1
            } else {
                if (alpha0.value >= alpha1.value) {
                    model1 = newModel
                    coroutineScope.launch {
                        alpha1.snapTo(0f)
                        alpha0.animateTo(1f, tween(300))
                    }
                    activeSlot = 1
                } else {
                    model0 = newModel
                    coroutineScope.launch {
                        alpha0.snapTo(0f)
                        alpha1.animateTo(1f, tween(300))
                    }
                    activeSlot = 0
                }
            }
        }

        val startTransition = { targetSlot: Int ->
            coroutineScope.launch {
                if (targetSlot == 0 && activeSlot == 0) {
                    launch { alpha0.animateTo(1f, tween(500, easing = EaseInOut)) }
                    launch { alpha1.animateTo(0f, tween(500, easing = EaseInOut)) }
                } else if (targetSlot == 1 && activeSlot == 1) {
                    launch { alpha1.animateTo(1f, tween(500, easing = EaseInOut)) }
                    launch { alpha0.animateTo(0f, tween(500, easing = EaseInOut)) }
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {
            if (model0 != null) {
                AsyncImage(
                    model = model0,
                    contentDescription = null,
                    onSuccess = { if (activeSlot == 0 && alpha0.value < 1f) startTransition(0) },
                    onError = { if (activeSlot == 0 && alpha0.value < 1f) startTransition(0) },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = alpha0.value },
                    contentScale = ContentScale.Crop
                )
            }
            if (model1 != null) {
                AsyncImage(
                    model = model1,
                    contentDescription = null,
                    onSuccess = { if (activeSlot == 1 && alpha1.value < 1f) startTransition(1) },
                    onError = { if (activeSlot == 1 && alpha1.value < 1f) startTransition(1) },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = alpha1.value },
                    contentScale = ContentScale.Crop
                )
            }
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = androidx.compose.ui.res.painterResource(id = de.lwp2070809.speculonic.R.drawable.ic_symbol_music_note),
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

    val repository = LocalSubsonicRepository.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    var activeSlot by remember { mutableIntStateOf(0) }
    var model0 by remember { mutableStateOf<Any?>(null) }
    var model1 by remember { mutableStateOf<Any?>(null) }

    val alpha0 = remember { Animatable(1f) }
    val alpha1 = remember { Animatable(0f) }

    var debouncedArtworkId by remember { mutableStateOf(artworkId) }
    var debouncedArtworkUri by remember { mutableStateOf(artworkUri) }

    LaunchedEffect(artworkId, artworkUri) {
        kotlinx.coroutines.delay(150) 
        debouncedArtworkId = artworkId
        debouncedArtworkUri = artworkUri
    }

    LaunchedEffect(debouncedArtworkId, debouncedArtworkUri, lifecycleOwner) {
        if (debouncedArtworkId == null && debouncedArtworkUri == null) return@LaunchedEffect

        val isAppVisible = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

        val newModel = repository.buildCoverArtRequest(
            id = debouncedArtworkId,
            context = context,
            preferLocal = true,
            size = 300,
            crossfade = false
        )

        if (!isAppVisible) {
            if (alpha0.value >= alpha1.value) {
                model1 = newModel
                alpha1.snapTo(1f)
                alpha0.snapTo(0f)
                activeSlot = 1
            } else {
                model0 = newModel
                alpha0.snapTo(1f)
                alpha1.snapTo(0f)
                activeSlot = 0
            }
            return@LaunchedEffect
        }

        if (model0 == newModel) {
            activeSlot = 0
            if (alpha0.value < 1f) {
                coroutineScope.launch {
                    launch { alpha0.animateTo(1f, tween(800, easing = EaseInOut)) }
                    launch { alpha1.animateTo(0f, tween(800, easing = EaseInOut)) }
                }
            }
            return@LaunchedEffect
        }
        if (model1 == newModel) {
            activeSlot = 1
            if (alpha1.value < 1f) {
                coroutineScope.launch {
                    launch { alpha1.animateTo(1f, tween(800, easing = EaseInOut)) }
                    launch { alpha0.animateTo(0f, tween(800, easing = EaseInOut)) }
                }
            }
            return@LaunchedEffect
        }

        if (model0 == null) {
            model0 = newModel
            coroutineScope.launch { alpha0.snapTo(1f) }
            activeSlot = 0
        } else if (model1 == null) {
            model1 = newModel
            coroutineScope.launch { alpha1.snapTo(0f) }
            activeSlot = 1
        } else {
            if (alpha0.value >= alpha1.value) {
                model1 = newModel
                coroutineScope.launch {
                    alpha1.snapTo(0f)
                    alpha0.animateTo(1f, tween(300))
                }
                activeSlot = 1
            } else {
                model0 = newModel
                coroutineScope.launch {
                    alpha0.snapTo(0f)
                    alpha1.animateTo(1f, tween(300))
                }
                activeSlot = 0
            }
        }
    }

    val startTransition = { targetSlot: Int ->
        coroutineScope.launch {
            if (targetSlot == 0 && activeSlot == 0) {
                launch { alpha0.animateTo(1f, tween(800, easing = EaseInOut)) }
                launch { alpha1.animateTo(0f, tween(800, easing = EaseInOut)) }
            } else if (targetSlot == 1 && activeSlot == 1) {
                launch { alpha1.animateTo(1f, tween(800, easing = EaseInOut)) }
                launch { alpha0.animateTo(0f, tween(800, easing = EaseInOut)) }
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (model0 != null) {
            AsyncImage(
                model = model0,
                contentDescription = null,
                onSuccess = { if (activeSlot == 0 && alpha0.value < 1f) startTransition(0) },
                onError = { if (activeSlot == 0 && alpha0.value < 1f) startTransition(0) },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 0.5f * alpha0.value }
                    .blur(50.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded),
                contentScale = ContentScale.Crop
            )
        }
        if (model1 != null) {
            AsyncImage(
                model = model1,
                contentDescription = null,
                onSuccess = { if (activeSlot == 1 && alpha1.value < 1f) startTransition(1) },
                onError = { if (activeSlot == 1 && alpha1.value < 1f) startTransition(1) },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 0.5f * alpha1.value }
                    .blur(50.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded),
                contentScale = ContentScale.Crop
            )
        }
    }
}
