package eu.kanade.presentation.intro

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.repeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
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
 * Animación de entrada: el personaje aparece leyendo su manga con un zoom suave, se balancea un
 * poco mientras lee, de repente salta una chispa, levanta la cabeza y reacciona dando un brinco;
 * después todo se acerca y se desvanece. Tocar la pantalla la salta.
 */
@Composable
fun IntroLogoOverlay() {
    var visible by remember { mutableStateOf(!introShown) }
    if (!visible) return

    var reacting by remember { mutableStateOf(false) }
    val zoom = remember { Animatable(0.7f) }
    val alpha = remember { Animatable(0f) }
    val sway = remember { Animatable(0f) }
    val squash = remember { Animatable(1f) }
    val jump = remember { Animatable(0f) }
    val spark = remember { Animatable(0f) }
    val overlayAlpha = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        // Aparece leyendo
        coroutineScope {
            launch { alpha.animateTo(1f, tween(400)) }
            zoom.animateTo(1f, tween(650, easing = FastOutSlowInEasing))
        }
        // Lee, balanceando la cabeza
        sway.animateTo(
            targetValue = 1f,
            animationSpec = repeatable(iterations = 3, animation = tween(260, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
        )
        sway.animateTo(0f, tween(120))
        // Algo la sorprende: se encoge un instante, salta la chispa y levanta la cabeza
        squash.animateTo(0.9f, tween(110))
        reacting = true
        coroutineScope {
            launch { squash.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = 500f)) }
            launch { spark.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 600f)) }
            jump.animateTo(-28f, tween(140, easing = FastOutSlowInEasing))
            jump.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = 700f))
        }
        delay(650)
        // Entra en la app
        coroutineScope {
            launch { zoom.animateTo(1.35f, tween(320)) }
            overlayAlpha.animateTo(0f, tween(320))
        }
        introShown = true
        visible = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = overlayAlpha.value }
            .background(MaterialTheme.colorScheme.surface)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                introShown = true
                visible = false
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            contentAlignment = Alignment.BottomCenter,
            modifier = Modifier
                .size(width = 260.dp, height = 340.dp)
                .graphicsLayer {
                    this.alpha = alpha.value
                    scaleX = zoom.value * (2f - squash.value)
                    scaleY = zoom.value * squash.value
                    transformOrigin = TransformOrigin(0.5f, 1f)
                    translationY = jump.value * density
                    // leyendo: la cabeza se mece de lado a lado
                    rotationZ = if (reacting) 0f else (sway.value - 0.5f) * 5f
                },
        ) {
            Image(
                painter = painterResource(if (reacting) R.drawable.intro_react else R.drawable.intro_reading),
                contentDescription = null,
                modifier = Modifier.height(if (reacting) 330.dp else 300.dp),
            )
            if (reacting) {
                Image(
                    painter = painterResource(R.drawable.intro_spark),
                    contentDescription = null,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(x = 2.dp, y = 58.dp)
                        .size(40.dp)
                        .graphicsLayer {
                            scaleX = spark.value
                            scaleY = spark.value
                            rotationZ = (1f - spark.value) * -40f
                        },
                )
            }
        }
    }
}
