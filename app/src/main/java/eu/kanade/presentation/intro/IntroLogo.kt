package eu.kanade.presentation.intro

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Solo se muestra una vez por arranque de la app. */
private var introShown = false

/**
 * Animación de entrada: el logo aparece girando ligeramente y creciendo, se mantiene un instante
 * y luego se desvanece junto con el fondo.
 */
@Composable
fun IntroLogoOverlay() {
    var visible by remember { mutableStateOf(!introShown) }
    if (!visible) return

    val scale = remember { Animatable(0.6f) }
    val rotation = remember { Animatable(-12f) }
    val logoAlpha = remember { Animatable(0f) }
    val overlayAlpha = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        coroutineScope {
            launch { logoAlpha.animateTo(1f, tween(350)) }
            launch { rotation.animateTo(0f, tween(700, easing = FastOutSlowInEasing)) }
            scale.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
        }
        delay(350)
        coroutineScope {
            launch { scale.animateTo(1.15f, tween(300)) }
            overlayAlpha.animateTo(0f, tween(300))
        }
        introShown = true
        visible = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = overlayAlpha.value }
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_logo_color),
            contentDescription = null,
            modifier = Modifier
                .size(160.dp)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    rotationZ = rotation.value
                    alpha = logoAlpha.value
                }
                .clip(CircleShape),
        )
    }
}
